package io.svra.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.svra.note.NoteItem;
import io.svra.notify.CalendarCapability;

/**
 * 告訴排版層「這個使用者的匯入按鈕該長什麼樣」。
 *
 * <p>兩種使用者，兩種按鈕：
 * <ul>
 * <li><b>授權過的</b>（目前是白名單）——postback 按鈕。一鍵匯入整批，
 * 之後在 LINE 改時間或刪除，行事曆會跟著動（決策 26）</li>
 * <li><b>其他人</b>——一條開 Google 預填頁的連結。一次一筆、要多按一次儲存、
 * 而且之後改了不會連動，但<b>不需要授權、不受 Google 的人數上限與審核限制</b>（決策 27）</li>
 * </ul>
 *
 * <p>🔴 <b>白名單是暫時的形狀，不是最終設計。</b>真正做多租戶 OAuth 時，
 * {@link #canImportDirectly} 會從「查設定檔」變成「查這個人有沒有 refresh token」——
 * 而<b>呼叫端一行都不用改</b>，這正是把它做成介面的理由。
 */
@Component
class CalendarCapabilityAdapter implements CalendarCapability {

    private static final Logger log = LoggerFactory.getLogger(CalendarCapabilityAdapter.class);

    private final CalendarProperties properties;

    CalendarCapabilityAdapter(CalendarProperties properties) {
        this.properties = properties;
        log.info("行事曆直接匯入的白名單人數：{}（其餘使用者走連結）",
                properties.oauthUserIds().size());
    }

    @Override
    public boolean canImportDirectly(String lineUserId) {
        return lineUserId != null && properties.oauthUsers().contains(lineUserId);
    }

    @Override
    public String importLinkFor(NoteItem item) {
        return GoogleCalendarLinks.forItem(item, properties.defaultDurationMinutes());
    }
}
