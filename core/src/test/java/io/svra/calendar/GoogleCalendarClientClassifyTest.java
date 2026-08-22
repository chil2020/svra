package io.svra.calendar;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import io.svra.outbox.OutboxPermanentFailureException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把失敗分成「重試會好」與「重試不會好」——這個類別最重要的職責。
 *
 * <p>分錯不會有例外浮上來：把暫時判成永久，使用者收到一則假的「授權失效」；
 * 把永久判成暫時，只是晚幾分鐘知道。<b>代價不對稱，所以判斷要保守。</b>
 */
class GoogleCalendarClientClassifyTest {

    /** Google 的錯誤 JSON 形狀。 */
    private static Map<String, Object> error(String reason) {
        return Map.of("error", Map.of("errors", List.of(Map.of("reason", reason))));
    }

    private static RuntimeException classify(HttpStatus status, String reason) {
        return GoogleCalendarClient.classify("寫入事件", false, status, error(reason));
    }

    @Test
    @DisplayName("401 → 授權沒了，而且要跟其他永久性失敗分得開")
    void unauthorizedIsAnAuthorizationProblem() {
        // 用型別而不是比對訊息字串：收尾要說「去重新授權」還是「這幾筆沒同步到」，
        // 靠的就是這個區分（見 CalendarSyncHandler.onGiveUp）。
        assertThat(classify(HttpStatus.UNAUTHORIZED, "authError"))
                .isInstanceOf(CalendarAuthorizationException.class);
    }

    @Test
    @DisplayName("403 權限不足 → 判死，重試一萬次也不會變成有權限")
    void forbiddenIsPermanent() {
        assertThat(classify(HttpStatus.FORBIDDEN, "insufficientPermissions"))
                .isInstanceOf(OutboxPermanentFailureException.class)
                .isNotInstanceOf(CalendarAuthorizationException.class);
    }

    @Test
    @DisplayName("🔴 403 限流 → 暫時。Google 把限流跟權限不足塞在同一個狀態碼裡")
    void rateLimitingIsTransientEvenThoughItIs403() {
        // 不分開的話，一次超量就會被判死並推一則「授權失效」的假警報。
        for (String reason : List.of("rateLimitExceeded", "userRateLimitExceeded",
                "quotaExceeded", "backendError")) {
            assertThat(classify(HttpStatus.FORBIDDEN, reason))
                    .as("reason=%s", reason)
                    .isNotInstanceOf(OutboxPermanentFailureException.class);
        }
    }

    @Test
    @DisplayName("🔴 404 有兩種意思，而它們的正確處置相反")
    void notFoundMeansDifferentThingsPerOperation() {
        // 寫入時 ＝ 行事曆本身不見了。不會自己好。
        assertThat(GoogleCalendarClient.classify("寫入事件", false,
                HttpStatus.NOT_FOUND, error("notFound")))
                .isInstanceOf(OutboxPermanentFailureException.class);

        // 更新時 ＝ 那個事件不見了。整段 upsert 重跑時，第一步 insert 這次會成功
        // ——它會自己修好。判死反而是把一次重試就解決的狀況變成永久失敗，
        // 還會推一則「請確認 calendarId」，而 calendarId 根本沒問題。
        assertThat(GoogleCalendarClient.classify("更新事件", true,
                HttpStatus.NOT_FOUND, error("notFound")))
                .isNotInstanceOf(OutboxPermanentFailureException.class);
    }

    @Test
    @DisplayName("5xx、429、看不懂的錯誤 → 一律暫時。不確定就當暫時")
    void everythingElseIsTransient() {
        assertThat(classify(HttpStatus.INTERNAL_SERVER_ERROR, "backendError"))
                .isNotInstanceOf(OutboxPermanentFailureException.class);
        // 429 常常不帶 reason，所以這裡刻意給一個空的錯誤內容
        assertThat(GoogleCalendarClient.classify("寫入事件", false,
                HttpStatus.TOO_MANY_REQUESTS, Map.of()))
                .isNotInstanceOf(OutboxPermanentFailureException.class);
        assertThat(GoogleCalendarClient.classify("寫入事件", false,
                HttpStatus.BAD_GATEWAY, Map.of()))
                .isNotInstanceOf(OutboxPermanentFailureException.class);
    }

    @Test
    @DisplayName("錯誤 JSON 缺東缺西也不能自己爆掉——那會蓋掉真正的失敗原因")
    void survivesMalformedErrorBodies() {
        assertThat(GoogleCalendarClient.classify("寫入事件", false, HttpStatus.BAD_GATEWAY, null))
                .isNotNull();
        assertThat(GoogleCalendarClient.classify("寫入事件", false,
                HttpStatus.BAD_GATEWAY, Map.of("error", "just a string")))
                .isNotNull();
    }
}
