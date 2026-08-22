package io.svra.calendar;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

import io.svra.note.NoteItem;

/**
 * 組一條「開 Google 行事曆的新增活動頁、而且欄位已經填好」的連結。
 *
 * <p>這是給<b>沒有授權</b>的使用者用的路：不需要 OAuth、不需要 Google 審核、
 * 沒有人數上限、後端不必保管任何 token。代價寫在 README 決策 27。
 */
final class GoogleCalendarLinks {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarLinks.class);

    private static final String RENDER_URL = "https://calendar.google.com/calendar/render";
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    /** Google 的格式。定時事件不帶 Z，時區另外用 ctz 指定，才不必自己換算成 UTC。 */
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 來源標記。放在 details 而不是標題裡——
     * 標題是使用者在行事曆上一眼要掃到的東西，加前綴是污染；
     * 而 details 裡的這一行足以讓他事後用搜尋把機器產生的事件全部找出來。
     *
     * <p>這是 A 案唯一剩下的「可辨識來源」機制：TEMPLATE 連結的 {@code src} 參數
     * 雖然可以指定寫進哪一本行事曆，但要填的是行事曆 id，而沒有 OAuth 就查不到——
     * 要使用者自己去複製一串 id，正是這條路想避免的事。所以事件會落在他的預設行事曆，
     * 跟手建的行程混在一起（決策 26 的子行事曆隔離在這裡不成立）。
     */
    private static final String SOURCE_MARK = "— 由 SVRA 從語音筆記匯入";

    /**
     * LINE 的 URI action 上限 1000 字元。超過整則訊息會被拒絕，
     * 而被拒絕的結果是使用者<b>什麼都沒收到</b>——所以寧可把 details 截短。
     */
    private static final int MAX_URI_LENGTH = 1000;

    private GoogleCalendarLinks() {
    }

    /**
     * @param durationMinutes 使用者講了幾點但沒講多久時的事件長度。
     *                        <b>跟 API 那條路用同一個設定值</b>——同一筆行程不該因為
     *                        使用者有沒有授權，就變成不同長度的事件
     * @return 預填好的連結；沒有時間的項目匯不進行事曆，回 null
     */
    static String forItem(NoteItem item, int durationMinutes) {
        if (item.getOccursAt() == null) {
            return null;
        }
        String url = build(item, detailsOf(item), durationMinutes);
        if (url.length() <= MAX_URI_LENGTH) {
            return url;
        }

        // 太長就把 details 收成只剩來源標記再試一次。
        // 標題與時間不能砍——那是這條連結存在的理由。
        String trimmed = build(item, SOURCE_MARK, durationMinutes);
        log.warn("匯入連結超過 {} 字元，已捨棄 details：原長度={}", MAX_URI_LENGTH, url.length());
        return trimmed.length() <= MAX_URI_LENGTH ? trimmed : null;
    }

    private static String build(NoteItem item, String details, int durationMinutes) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(RENDER_URL)
                .queryParam("action", "TEMPLATE")
                .queryParam("text", item.getTitle())
                .queryParam("dates", dates(item, durationMinutes))
                .queryParam("details", details)
                // 🔴 沒有這個參數，這條路對一部分使用者是完全壞的。
                // Google 自 2021 起封鎖內嵌 webview 的登入（403 disallowed_useragent），
                // 而 LINE 的內建瀏覽器就是內嵌 webview——使用者若沒在那裡登入過 Google，
                // 點下去只會看到一片錯誤畫面，而且**沒有任何 fallback**：
                // 重導向也還在同一個被擋的 webview 裡。
                // openExternalBrowser 是 LINE 的參數，Google 會當成不認識的查詢字串忽略。
                .queryParam("openExternalBrowser", "1");

        if (Boolean.TRUE.equals(item.getTimeSpecified())) {
            // 定時事件才需要 ctz：全天事件沒有時刻，帶時區只會讓 Google 多算一次。
            builder.queryParam("ctz", ZONE.getId());
        }
        return builder.build().encode().toUriString();
    }

    /**
     * 事件時間。
     *
     * <p>定時與全天是兩種<b>格式</b>而不是同一種的兩個值，跟 API 那條路
     * （{@code start.dateTime} vs {@code start.date}）是同一個區分，
     * 依據也同一個：{@code timeSpecified}（見決策 26）。
     */
    private static String dates(NoteItem item, int durationMinutes) {
        var at = item.getOccursAt().atZone(ZONE);
        if (Boolean.TRUE.equals(item.getTimeSpecified())) {
            // 起訖都要給。只給起點的話 Google 會建出一個零長度的事件，
            // 而不是套用什麼預設長度——那在行事曆上是一條看不見的線。
            return DATE_TIME.format(at) + "/"
                    + DATE_TIME.format(at.plusMinutes(durationMinutes));
        }
        var day = at.toLocalDate();
        // 全天事件的結束日是「不含」的隔天——Google 的規格，不是我們的選擇。
        return DATE.format(day) + "/" + DATE.format(day.plusDays(1));
    }

    private static String detailsOf(NoteItem item) {
        String detail = item.getDetail();
        return detail == null || detail.isBlank()
                ? SOURCE_MARK
                : detail + "\n\n" + SOURCE_MARK;
    }
}
