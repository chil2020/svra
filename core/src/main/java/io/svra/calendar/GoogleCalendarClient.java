package io.svra.calendar;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import io.svra.outbox.OutboxPermanentFailureException;

/**
 * 對 Google Calendar API 的最小呼叫面：寫一筆、改一筆、刪一筆。
 *
 * <p>不用 {@code google-api-client}，直接走 {@code RestClient}——跟
 * {@code LinePushClient} 同一個做法。要用到的只有三個端點，
 * 而那個 SDK 會拖進一整套自己的 HTTP 堆疊與認證抽象。
 *
 * <p>🔴 <b>這個類別最重要的職責不是發請求，是把失敗分成兩類。</b>
 * 見 {@link #classify}：分錯的代價不對稱，所以判斷保守——
 * 只有明確指出「這個請求本身不對」的回應才算永久。
 */
@Component
class GoogleCalendarClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarClient.class);

    private static final String BASE = "https://www.googleapis.com/calendar/v3";
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * 403 底下這幾個 reason 是「現在不行」，不是「不准」。
     *
     * <p>Google 把限流也放進 403，跟「權限不足」共用同一個狀態碼。
     * 不分開的話，一次超量就會被判死並推一則「授權失效」的假警報給使用者。
     */
    private static final Set<String> TRANSIENT_403 = Set.of(
            "rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "backendError");

    private final RestClient restClient;
    private final GoogleTokenProvider tokenProvider;
    private final CalendarProperties properties;

    GoogleCalendarClient(RestClient.Builder builder, GoogleTokenProvider tokenProvider,
            CalendarProperties properties) {
        this.restClient = builder.build();
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    /**
     * 寫入或更新一筆事件。
     *
     * <p>先 {@code insert}（帶著我們自己算的 event id），撞 409 就轉 {@code update}。
     *
     * <p>🔴 <b>409 不是錯誤，是冪等機制在生效。</b>它代表「這個 id 已經在了」——
     * 可能是使用者手滑點兩次，也可能是 poller 送出後、標記 SENT 前掛掉又重跑。
     * 轉成 update 之後這條路同時解掉三件事：重複匯入不會變成兩筆、
     * 「已加入・重新同步」那顆按鈕有東西可做、以及<b>使用者在 Google 端手動刪掉之後
     * 還能重新匯入</b>——被刪的事件在 Google 那邊是 {@code status=cancelled} 而非消失，
     * insert 照樣撞 409，只有 update 帶著 {@code status=confirmed} 才叫得回來。
     *
     * @param timeSpecified 使用者有沒有講出幾點。{@code TRUE} 是定時事件，
     *                      其餘（{@code FALSE} 或 v8 之前的舊資料）是全天事件
     */
    void upsert(String eventId, String summary, String detail,
            Instant occursAt, Boolean timeSpecified) {
        Map<String, Object> body = eventBody(eventId, summary, detail, occursAt, timeSpecified);

        String path = "/calendars/" + encode(properties.calendarId()) + "/events";
        boolean inserted = withRetryOnUnauthorized(token -> restClient.post()
                .uri(BASE + path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == 409) {
                        return false;
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        // 這裡的 404 是「行事曆不見了」，不是「事件不見了」
                        throw classify("寫入事件", false, response.getStatusCode(),
                                response.bodyTo(Map.class));
                    }
                    return true;
                }));

        if (inserted) {
            log.info("行事曆已新增事件：eventId={}", eventId);
            return;
        }

        withRetryOnUnauthorized(token -> restClient.put()
                .uri(BASE + path + "/" + encode(eventId))
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw classify("更新事件", true, response.getStatusCode(),
                                response.bodyTo(Map.class));
                    }
                    return true;
                }));
        log.info("行事曆已更新既有事件：eventId={}", eventId);
    }

    /**
     * 刪掉一筆事件。
     *
     * <p>404 與 410 當成功：<b>目的是「那筆不該在行事曆上」，而它確實不在了。</b>
     * 當成失敗的話，使用者在 Google 端先手動刪掉的那些，會讓同步事件一路重試到放棄，
     * 然後推一則沒有意義的失敗通知給他。
     */
    void delete(String eventId) {
        withRetryOnUnauthorized(token -> restClient.delete()
                .uri(BASE + "/calendars/" + encode(properties.calendarId())
                        + "/events/" + encode(eventId))
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) -> {
                    int code = response.getStatusCode().value();
                    if (code == 404 || code == 410) {
                        log.info("要刪的事件已經不在行事曆上，視為已達成：eventId={}", eventId);
                        return true;
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        // 刪除的 404/410 在上面就當成功了，走到這裡的 404 不可能是事件不見
                        throw classify("刪除事件", false, response.getStatusCode(),
                                response.bodyTo(Map.class));
                    }
                    log.info("行事曆已刪除事件：eventId={}", eventId);
                    return true;
                }));
    }

    /**
     * 把失敗分成「重試會好」與「重試不會好」。
     *
     * <p>永久的只有三種，而且都要有明確證據：
     * <ul>
     * <li><b>401</b>——access token 換過一次仍被拒，代表授權本身沒了</li>
     * <li><b>403 且 reason 不是限流</b>——scope 不夠、API 沒啟用、對這個行事曆沒有寫入權</li>
     * <li><b>404</b>——行事曆 id 指向的東西不存在了（刪除那條路另外處理，見 {@link #delete}）</li>
     * </ul>
     *
     * <p>其餘一律暫時：5xx、429、逾時、連不上。<b>不確定的時候當暫時</b>——
     * 誤判成永久會推一則假的「授權失效」給使用者，誤判成暫時只是晚幾分鐘知道。
     */
    // package-private 而不是 private：這個類別最重要的職責就是這個判斷
    // （見類別的 javadoc），而分錯的症狀是「使用者收到一則假的授權失效通知」
    // 或「一個一次重試就好的狀況被判死」——兩種都不會有例外浮上來。
    static RuntimeException classify(String what, boolean notFoundMeansEventGone,
            HttpStatusCode status, Map<?, ?> body) {
        String reason = reasonOf(body);
        String detail = what + " 失敗：" + status + (reason == null ? "" : "（" + reason + "）");

        int code = status.value();
        if (code == 401) {
            return new CalendarAuthorizationException(detail + "——授權已失效，要重新授權");
        }
        if (code == 403 && !TRANSIENT_403.contains(reason)) {
            return new OutboxPermanentFailureException(detail + "——權限不足或 API 未啟用");
        }
        if (code == 404) {
            // 🔴 同一個狀態碼，兩種完全不同的意思，而它們的正確處置也相反。
            //
            // 寫入時的 404 ＝ 行事曆本身不見了（id 錯了、或那本被刪了）。
            // 那不會自己好，判死。
            //
            // 更新時的 404 ＝ **那個事件**不見了。這種要當暫時性失敗，
            // 因為 upsert 會整段重跑，而重跑的第一步 insert 這次會成功
            // ——事件真的不在了，就不會再撞 409。**它會自己修好。**
            // 判死的話，反而是把一個一次重試就解決的狀況變成永久失敗，
            // 還會推一則「行事曆不存在，請確認 calendarId」給使用者，
            // 而 calendarId 根本沒有問題。
            return notFoundMeansEventGone
                    ? new IllegalStateException(detail + "——事件已經不在了，重試會改走新增")
                    : new OutboxPermanentFailureException(
                            detail + "——行事曆不存在，請確認 calendarId");
        }
        return new IllegalStateException(detail);
    }

    /** Google 的錯誤 JSON：{@code {"error":{"errors":[{"reason":"..."}],"status":"..."}}}。 */
    private static String reasonOf(Map<?, ?> body) {
        if (body == null || !(body.get("error") instanceof Map<?, ?> error)) {
            return null;
        }
        if (error.get("errors") instanceof List<?> errors && !errors.isEmpty()
                && errors.get(0) instanceof Map<?, ?> first) {
            return String.valueOf(first.get("reason"));
        }
        return error.get("status") == null ? null : String.valueOf(error.get("status"));
    }

    /**
     * 401 就換一顆 token 再試一次，還是 401 才判死。
     *
     * <p>{@code GoogleTokenProvider} 已經提早五分鐘讓 token 過期，所以正常情況
     * 走不到這裡。走到了代表<b>我們對到期時刻的認知是錯的</b>——token 被提前撤銷，
     * 或機器的時鐘跟 Google 對不上。這種時候相信對方的 401，不要相信自己的快取。
     */
    private <T> T withRetryOnUnauthorized(java.util.function.Function<String, T> call) {
        try {
            return call.apply(tokenProvider.accessToken());
        } catch (CalendarAuthorizationException expired) {
            log.warn("token 還沒到期卻被拒，換一顆再試一次");
            tokenProvider.invalidate();
            // 換完還是被拒就讓它往外拋——那代表 refresh token 本身沒了，不是這顆過期。
            // 這一次不再接：接住只會變成無限換發。
            return call.apply(tokenProvider.accessToken());
        }
    }

    /** 事件本體。定時與全天是兩種形狀，而不是同一種的兩個值。 */
    private Map<String, Object> eventBody(String eventId, String summary, String detail,
            Instant occursAt, Boolean timeSpecified) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", eventId);
        body.put("summary", summary);
        if (detail != null && !detail.isBlank()) {
            body.put("description", detail);
        }
        // 🔴 update 走的是 PUT（整筆取代），被刪掉的事件在 Google 那邊是 cancelled，
        // 不明寫 confirmed 就叫不回來——「Google 端手刪後重新匯入」靠這一行。
        body.put("status", "confirmed");

        if (Boolean.TRUE.equals(timeSpecified)) {
            var start = occursAt.atZone(ZONE);
            var end = start.plus(Duration.ofMinutes(properties.defaultDurationMinutes()));
            body.put("start", Map.of("dateTime", DATE_TIME.format(start), "timeZone", ZONE.getId()));
            body.put("end", Map.of("dateTime", DATE_TIME.format(end), "timeZone", ZONE.getId()));
        } else {
            // 全天事件的 end.date 是「不含」的隔天——Google 的規格，不是我們的選擇。
            var day = occursAt.atZone(ZONE).toLocalDate();
            body.put("start", Map.of("date", DATE.format(day)));
            body.put("end", Map.of("date", DATE.format(day.plusDays(1))));
        }
        return body;
    }

    /** calendarId 是一個 email，裡面有 {@code @}；event id 是我們自己算的，但一併處理。 */
    private static String encode(String segment) {
        return UriUtils.encodePathSegment(segment, java.nio.charset.StandardCharsets.UTF_8);
    }
}
