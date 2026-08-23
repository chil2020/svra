package io.svra.calendar;

import org.springframework.stereotype.Component;

import io.svra.note.NoteItem;
import io.svra.notify.CalendarCapability;
import io.svra.user.Credentials;

/**
 * 告訴排版層「這個使用者的匯入按鈕該長什麼樣」。
 *
 * <p>兩種使用者，兩種按鈕：
 * <ul>
 * <li><b>授權過的</b>——postback 按鈕。一鍵匯入整批，之後在 LINE 改時間或刪除，
 * 行事曆會跟著動（決策 26）</li>
 * <li><b>其他人</b>——一條開 Google 預填頁的連結。一次一筆、要多按一次儲存、
 * 而且之後改了不會連動，但<b>不需要授權、不受 Google 的人數上限與審核限制</b>（決策 27）</li>
 * </ul>
 *
 * <p>🔴 <b>這個類別的 javadoc 曾經寫著：「白名單是暫時的形狀，不是最終設計。
 * 真正做多租戶 OAuth 時，{@code canImportDirectly} 會從『查設定檔』變成
 * 『查這個人有沒有 refresh token』——而呼叫端一行都不用改。」</b>
 *
 * <p>就是這一次，而且那句話兌現了：改的只有這個方法的一行，
 * {@code CardRenderer} 與 {@code CalendarSync} 都沒有動。
 */
@Component
class CalendarCapabilityAdapter implements CalendarCapability {

    private final CalendarProperties properties;
    private final Credentials credentials;

    CalendarCapabilityAdapter(CalendarProperties properties, Credentials credentials) {
        this.properties = properties;
        this.credentials = credentials;
    }

    @Override
    public boolean canImportDirectly(String lineUserId) {
        return credentials.hasActive(lineUserId);
    }

    @Override
    public String importLinkFor(NoteItem item) {
        return GoogleCalendarLinks.forItem(item, properties.defaultDurationMinutes());
    }
}
