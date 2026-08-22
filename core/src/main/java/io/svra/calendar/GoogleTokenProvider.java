package io.svra.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

import io.svra.outbox.OutboxPermanentFailureException;

/**
 * 拿 refresh token 換 access token，並在有效期內重複使用同一個。
 *
 * <p><b>為什麼快取在記憶體，而不是已經有的 Redis。</b>
 * access token 是<b>整個應用共用</b>的憑證（不是 per-user），有效期一小時。
 * 放進 Redis 的好處只有「多個實例共用同一顆」，而那個好處在單實例下等於零；
 * 壞處是把一個等同於帳號存取權的祕密，複製到一個<b>沒有設密碼</b>的 Redis 裡
 * （見 docker-compose）。多實例時各自去換一顆就好，Google 允許，
 * 而祕密就不會離開 JVM。
 *
 * <p>這跟決策 14「Redis 只做快取與限流」不衝突，反而是同一個判斷的延伸：
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

    /** 目前這顆 token 與它的到期時刻。單一實例、低頻率，同步取用就夠。 */
    private String cachedToken;
    private Instant cachedUntil = Instant.EPOCH;

    GoogleTokenProvider(RestClient.Builder builder, CalendarProperties properties, Clock clock) {
        this.restClient = builder.build();
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws OutboxPermanentFailureException refresh token 已經失效——
     *         被撤銷、Google 帳號改過密碼，或 consent screen 還停在 Testing
     *         而 Google 在七天後把它收走了。重試不會好，要人去重跑授權腳本。
     */
    synchronized String accessToken() {
        Instant now = Instant.now(clock);
        if (cachedToken != null && now.isBefore(cachedUntil)) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("refresh_token", properties.refreshToken());
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

        cachedToken = token.accessToken();
        cachedUntil = now.plusSeconds(token.expiresIn()).minus(EXPIRY_MARGIN);
        // 不記 token 本身。記到期時刻就足以判斷「是不是又去換了一顆」。
        log.info("已換發 Google access token：有效至 {}", cachedUntil);
        return cachedToken;
    }

    /**
     * 丟掉手上這顆。
     *
     * <p>用在「明明還沒到期卻收到 401」——token 可能被提前撤銷，也可能是機器的時鐘
     * 跟 Google 對不上。與其相信自己的到期時刻，不如讓下一次呼叫重新去換一顆。
     */
    synchronized void invalidate() {
        cachedToken = null;
        cachedUntil = Instant.EPOCH;
    }

    /**
     * 啟動時驗一次。
     *
     * <p>{@code @NotBlank} 只擋得住「沒填」，擋不住「填了但是錯的」——
     * 而錯的 refresh token 的症狀是「按鈕按下去，幾分鐘後收到一則失敗通知」，
     * 那時你已經在用了。跟 {@code ddl-auto=validate} 在啟動時擋下 schema 不一致
     * 是同一個判斷（決策 8）。
     *
     * <p>不在這裡丟例外中斷啟動：refresh token 也可能在<b>運行中</b>失效，
     * 那條路本來就得靠推播通知處理。啟動就炸只會讓一個已經在跑的系統
     * 因為外部服務的狀態而起不來——記一行 error 讓人看得見，比較合乎比例。
     */
    void verifyOnStartup() {
        try {
            accessToken();
        } catch (Exception e) {
            log.error("Google 行事曆的授權目前是壞的，匯入功能不會動——"
                    + "請重跑 deploy/google-calendar-auth.py 取得新的 refresh token", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
