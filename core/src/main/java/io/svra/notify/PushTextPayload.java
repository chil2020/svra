package io.svra.notify;

import java.util.List;

/**
 * 「把這段文字推給這個人」。
 *
 * <p>不帶產生它的模組的領域資訊（note id、指令內容、抽取版本）：這個事件表達的
 * 就只是一次推播，否則每加一種要回覆的情境，這裡就要多認識一個模組。
 *
 * <p>{@code anchoredItemIds} 是例外，而它不是破例：<b>notify 本來就認識 NoteItem</b>
 * （它負責排版）。這個欄位說的是「這則訊息列了哪幾筆、依什麼順序」，
 * 而那是<b>訊息本身的性質</b>，不是誰要求推播的性質。訊息送出去之後，
 * 只有這裡拿得到 LINE 給的 messageId，錨點也只能在這裡記下。
 *
 * @param anchoredItemIds 這則訊息列出的項目，順序即編號；不是清單訊息時給空清單
 */
public record PushTextPayload(String lineUserId, String text, List<Long> anchoredItemIds) {

    /** 不是清單的訊息（錯誤說明、轉錄失敗通知），沒有編號可以指涉。 */
    public static PushTextPayload plain(String lineUserId, String text) {
        return new PushTextPayload(lineUserId, text, List.of());
    }
}
