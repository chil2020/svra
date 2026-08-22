package io.svra.notify;

import io.svra.note.NoteItem;

/**
 * 卡片上那顆「加入行事曆」的按鈕，對<b>這個</b>使用者該長什麼樣。
 *
 * <p>🔴 <b>介面定義在 notify，實作在 calendar，而那個方向是刻意的。</b>
 *
 * <p>排版需要知道「這個人能不能讓後端直接寫進他的行事曆」，但那個知識屬於
 * calendar 模組——而 <b>calendar 已經依賴 notify</b>（同步完要回一張新卡片）。
 * 讓 notify 反過來直接依賴 calendar 就是環狀依賴，{@code ModularityTest} 會當場擋下。
 *
 * <p>所以反轉依賴：notify 定義自己需要的介面，calendar 來實作。
 * 這跟 {@code OutboxEventHandler} 完全同構——基礎設施定義契約、業務模組實作，
 * 依賴方向永遠是業務指向基礎設施。
 *
 * <p>順帶一個好處：<b>notify 完全不需要認識 Google</b>。它只問「這個人能不能直接匯入」
 * 與「這一筆的連結是什麼」，換成 Apple 行事曆或 Outlook 時，排版一行都不用改。
 */
public interface CalendarCapability {

    /**
     * 這個使用者能不能讓後端直接寫進他的行事曆。
     *
     * <p>{@code true} 代表他授權過（按鈕走 postback，一鍵整批、之後改時間會連動）；
     * {@code false} 代表沒有（按鈕是一條開 Google 預填頁的連結，一次一筆）。
     */
    boolean canImportDirectly(String lineUserId);

    /**
     * 沒有授權的人用的匯入連結。
     *
     * @return 開行事曆預填頁的網址；這一筆匯不進去（沒有時間）時為 null
     */
    String importLinkFor(NoteItem item);
}
