package io.svra.calendar;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.svra.note.NoteCategory;
import io.svra.note.NoteItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 預填連結（決策 27）。
 *
 * <p>這條路上<b>後端沒有任何回饋</b>——使用者點了沒、存了沒，我們永遠不知道。
 * 也就是說連結組錯了不會有任何錯誤浮上來，只有使用者看到一個時間不對的預填頁。
 * <b>唯一能守住它的就是這裡。</b>
 *
 * <p>斷言用解碼後的參數值而不是比對整條 URL 字串：要驗的是「帶了什麼」，
 * 不是「UriComponentsBuilder 怎麼跳脫」。
 */
class GoogleCalendarLinksTest {

    private static final int DURATION = 60;

    private static NoteItem item(String title, String occursAt, Boolean timeSpecified,
            String detail) {
        NoteItem item = new NoteItem(NoteCategory.SCHEDULE, title,
                occursAt == null ? null : Instant.parse(occursAt),
                timeSpecified, detail, List.of());
        ReflectionTestUtils.setField(item, "id", 1L);
        return item;
    }

    private static Map<String, String> paramsOf(String url) {
        Map<String, String> params = new HashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    @Test
    @DisplayName("講了幾點 → 起訖都給，並用 ctz 指定台北時間")
    void timedEventCarriesStartEndAndTimezone() {
        var params = paramsOf(GoogleCalendarLinks.forItem(
                item("跟客戶開會", "2026-08-15T01:00:00Z", true, null), DURATION));

        // 01:00 UTC = 09:00 台北。不自己換算成 UTC，交給 ctz——
        // 少一次換算就少一個會算錯的地方。
        assertThat(params.get("dates")).isEqualTo("20260815T090000/20260815T100000");
        assertThat(params.get("ctz")).isEqualTo("Asia/Taipei");
        assertThat(params.get("text")).isEqualTo("跟客戶開會");
        assertThat(params.get("action")).isEqualTo("TEMPLATE");
    }

    @Test
    @DisplayName("🔴 只講了日期 → 全天事件的格式，而且不帶 ctz")
    void dateOnlyEventUsesTheAllDayFormat() {
        var params = paramsOf(GoogleCalendarLinks.forItem(
                item("那天要交報告", "2026-08-15T01:00:00Z", false, null), DURATION));

        // 結束日是「不含」的隔天——Google 的規格。
        // 少了這一天，行事曆上會是一個零長度的全天事件。
        assertThat(params.get("dates")).isEqualTo("20260815/20260816");
        assertThat(params).doesNotContainKey("ctz");
    }

    @Test
    @DisplayName("v8 之前的舊資料（時間精度未知）當成全天，跟卡片上的顯示一致")
    void legacyItemsAreTreatedAsAllDay() {
        var params = paramsOf(GoogleCalendarLinks.forItem(
                item("舊資料", "2026-08-15T01:00:00Z", null, null), DURATION));

        assertThat(params.get("dates")).isEqualTo("20260815/20260816");
    }

    @Test
    @DisplayName("🔴 一定要帶 openExternalBrowser=1，否則一部分使用者根本點不開")
    void alwaysForcesTheExternalBrowser() {
        var params = paramsOf(GoogleCalendarLinks.forItem(
                item("開會", "2026-08-15T01:00:00Z", true, null), DURATION));

        // Google 自 2021 起封鎖內嵌 webview 的登入（403 disallowed_useragent），
        // 而 LINE 的內建瀏覽器就是內嵌 webview。沒登入過的使用者會看到一片錯誤畫面，
        // 而且沒有任何 fallback——重導向也還在同一個被擋的 webview 裡。
        assertThat(params.get("openExternalBrowser")).isEqualTo("1");
    }

    @Test
    @DisplayName("detail 帶進去，並在末尾附來源標記")
    void detailIsCarriedWithASourceMark() {
        var params = paramsOf(GoogleCalendarLinks.forItem(
                item("開會", "2026-08-15T01:00:00Z", true, "記得帶合約"), DURATION));

        assertThat(params.get("details")).contains("記得帶合約").contains("SVRA");
    }

    @Test
    @DisplayName("沒有 detail 時，details 仍然只放來源標記——那是唯一能辨識來源的東西")
    void sourceMarkSurvivesWhenThereIsNoDetail() {
        var params = paramsOf(GoogleCalendarLinks.forItem(
                item("開會", "2026-08-15T01:00:00Z", true, null), DURATION));

        // A 案寫進使用者的預設行事曆（沒有 OAuth 就查不到子行事曆的 id），
        // 所以事件會跟他手建的行程混在一起。這一行讓他事後搜尋得到。
        assertThat(params.get("details")).contains("SVRA");
    }

    @Test
    @DisplayName("沒有時間的項目匯不進行事曆 → 回 null，讓卡片不要長按鈕")
    void itemsWithoutATimeHaveNoLink() {
        assertThat(GoogleCalendarLinks.forItem(item("想法", null, null, null), DURATION))
                .isNull();
    }

    @Test
    @DisplayName("🔴 detail 太長 → 捨棄 detail 保住連結，而不是送出一則會被拒收的訊息")
    void oversizedDetailIsDroppedRatherThanBreakingTheMessage() {
        String url = GoogleCalendarLinks.forItem(
                item("開會", "2026-08-15T01:00:00Z", true, "長".repeat(2000)), DURATION);

        // LINE 的 URI action 上限 1000 字元，超過整則訊息會被拒絕——
        // 而被拒絕的結果是使用者什麼都沒收到。標題與時間不能砍，那是連結存在的理由。
        assertThat(url).hasSizeLessThanOrEqualTo(1000);
        var params = paramsOf(url);
        assertThat(params.get("text")).isEqualTo("開會");
        assertThat(params.get("dates")).isEqualTo("20260815T090000/20260815T100000");
        assertThat(params.get("details")).doesNotContain("長長長");
    }

    @Test
    @DisplayName("連標題都塞不下 → 回 null，寧可少一顆按鈕")
    void givesUpWhenEvenTheTitleIsTooLong() {
        assertThat(GoogleCalendarLinks.forItem(
                item("長".repeat(2000), "2026-08-15T01:00:00Z", true, null), DURATION))
                .isNull();
    }

    @Test
    @DisplayName("標題裡的特殊字元要跳脫，不能讓它把 URL 切斷")
    void specialCharactersAreEncoded() {
        var params = paramsOf(GoogleCalendarLinks.forItem(
                item("A&B 開會 #1 100%", "2026-08-15T01:00:00Z", true, null), DURATION));

        // 沒跳脫的話，& 之後的東西會被當成另一個查詢參數，標題就斷在「A」
        assertThat(params.get("text")).isEqualTo("A&B 開會 #1 100%");
    }
}
