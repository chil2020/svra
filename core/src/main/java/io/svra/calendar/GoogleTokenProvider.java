package io.svra.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.svra.user.GoogleAuthorization;

/**
 * 拿使用者的 refresh token 換 access token，並在有效期內重複使用同一顆。
 *
 * <p>🔴 <b>快取從一個欄位變成一張表，那不是重構。</b>在這之前這個類別持有
 * {@code cachedToken} 一顆 token——因為系統假設「只有一組憑證」。那個假設
 * 正是為什麼白名單放兩個人時，第二個人的行程會寫進第一個人的行事曆：
 * 兩個人的請求拿到的是<b>同一顆 access token</b>。
 *
 * <p><b>為什麼快取在記憶體，而不是已經有的 Redis。</b>
 * access token 等同於那個 Google 帳號的存取權，有效期一小時。放進 Redis 的好處
 * 只有「多個實例共用」，而那個好處在單實例下等於零；壞處是把一份祕密複製到一個
 * <b>沒有設密碼</b>的 Redis 裡（見 docker-compose）。多實例時各自去換就好，
 * Google 允許，而祕密不會離開 JVM。
 *
 * <p>這跟決策 14「Redis 只做快取與限流」不衝突，是同一個判斷的延伸：
 * <b>放不放進 Redis 要看那份資料是什麼，不是看 Redis 在不在。</b>
 */
@Component
class GoogleTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenProvider.class);

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    /**
     * 提早這麼久就當作過期。
     *
     * <p>不留緩衝的話，會發生「檢查時還有兩秒、送出去時已經過期」——
     * 那是一個只在高負載或網路慢的時候才出現、而且看起來像隨機的 401。
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    private final RestClient restClient;
    private final CalendarProperties properties;
    private final Clock clock;

    /**
     * 每個使用者手上那顆 token。
     *
     * <p>整個方法 {@code synchronized} 而不是做 per-key 鎖：換 token 的呼叫端只有
     * outbox poller，而<b>它是循序處理事件的</b>——這裡實際上不存在併發。
     * 真的要並行處理事件那天，這裡要換成 per-user 鎖（不能用
     * {@code ConcurrentHashMap.compute}，它的 mapping function 裡不該做網路 I/O）。
     */
    private final Map<String, CachedToken> cache = new HashMap<>();

    GoogleTokenProvider(RestClient.Builder builder, CalendarProperties properties, Clock clock) {
        this.restClient = builder.build();
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws CalendarAuthorizationException refresh token 已經失效——被撤銷、
     *         Google 帳號改過密碼，或 consent screen 還停在 Testing 而 Google
     *         在七天後把它收走了。重試不會好，要那個使用者重新授權。
     */
    synchronized String accessToken(String lineUserId, GoogleAuthorization authorization) {
        Instant now = Instant.now(clock);
        CachedToken cached = cache.get(lineUserId);
        if (cached != null && now.isBefore(cached.validUntil())) {
            return cached.token();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        // client id/secret 是**應用程式**的身分，一份；refresh token 是**這個使用者**的。
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("refresh_token", authorization.refreshToken());
        form.add("grant_type", "refresh_token");

        TokenResponse token = restClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return response.bodyTo(TokenResponse.class);
                    }
                    // 🔴 400/401 帶 invalid_grant 才是「授權真的沒了」。
                    // 其他狀態（5xx、429、逾時）是 Google 那邊的事，往外拋當暫時性失敗，
                    // 讓 outbox 退避重試——分類錯的代價不對稱，見 OutboxPermanentFailureException。
                    Map<?, ?> error = response.bodyTo(Map.class);
                    String reason = error == null ? null : String.valueOf(error.get("error"));
                    if ("invalid_grant".equals(reason) || "invalid_client".equals(reason)) {
                        throw new CalendarAuthorizationException(
                                "Google 授權失效（" + reason + "），要重新授權才會好");
                    }
                    throw new IllegalStateException(
                            "換 access token 失敗：" + response.getStatusCode() + " " + reason);
                });

        if (token == null || token.accessToken() == null) {
            throw new IllegalStateException("Google 回了 200 但沒有 access_token");
        }

        Instant validUntil = now.plusSeconds(token.expiresIn()).minus(EXPIRY_MARGIN);
        cache.put(lineUserId, new CachedToken(token.accessToken(), validUntil));
        // 不記 token 本身，也不記是誰——lineUserId 已經在 MDC 裡（見 LogContext）。
        log.info("已換發 Google access token：有效至 {}", validUntil);
        return token.accessToken();
    }

    /**
     * 丟掉這個使用者手上那顆。
     *
     * <p>用在「明明還沒到期卻收到 401」——token 可能被提前撤銷，也可能是機器的時鐘
     * 跟 Google 對不上。與其相信自己的到期時刻，不如讓下一次呼叫重新去換一顆。
     */
    synchronized void invalidate(String lineUserId) {
        cache.remove(lineUserId);
    }

    private record CachedToken(String token, Instant validUntil) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
