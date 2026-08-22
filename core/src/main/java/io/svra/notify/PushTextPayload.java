package io.svra.notify;

import java.util.List;

/**
 * 「把這則訊息推給這個人」。
 *
 * <p>不帶產生它的模組的領域資訊（note id、指令內容、抽取版本）：這個事件表達的
 * 就只是一次推播，否則每加一種要回覆的情境，這裡就要多認識一個模組。
 *
 * <p>{@code anchoredItemIds} 是例外，而它不是破例：<b>notify 本來就認識 NoteItem</b>
 * （它負責排版）。這個欄位說的是「這則訊息列了哪幾筆、依什麼順序」，
 * 而那是<b>訊息本身的性質</b>，不是誰要求推播的性質。訊息送出去之後，
 * 只有這裡拿得到 LINE 給的 messageId，錨點也只能在這裡記下。
 *
 * <p>🔴 <b>排版在寫下意圖的時候就做完，不是推出去的時候。</b>
 * 這則訊息該長什麼樣，取決於<b>那個交易提交當下</b>的資料——指令改完之後的清單、
 * 抽取剛寫進去的項目。留到 poller 兩秒後才渲染，中間又插進一則語音的話，
 * 使用者收到的回覆就不是他那則指令的結果了。代價是 outbox 的 payload 變大
 * （一張卡片幾 KB），而那一欄本來就是 {@code text}。
 *
 * @param anchoredItemIds 這則訊息列出的項目，順序即編號；不是清單訊息時給空清單
 * @param cardId          Flex 卡片上按鈕帶的 id，推播成功後跟錨點存在一起。
 *                        純文字訊息沒有按鈕，為 null（見 V10）
 * @param flexJson        Flex 訊息的 {@code contents}；為 null 時就當純文字送，
 *                        此時 {@code text} 就是訊息本身
 * @param replyToken      有帶就先試 reply（<b>不計入月額度</b>），失效才退回推播。
 *                        <p>只有「使用者剛做了什麼」才拿得到，而且短效——
 *                        語音抽取那條路要跑數十秒，token 早就死了，所以它一律是 null。
 *                        指令（約 7 秒）與匯入（約 2 秒）來得及。
 */
public record PushTextPayload(
        String lineUserId,
        String text,
        List<Long> anchoredItemIds,
        String cardId,
        String flexJson,
        String replyToken) {

    /** 不是清單的訊息（錯誤說明、轉錄失敗通知、同步失敗通知），沒有編號可以指涉。 */
    public static PushTextPayload plain(String lineUserId, String text) {
        return new PushTextPayload(lineUserId, text, List.of(), null, null, null);
    }

    /** 一張帶按鈕的清單卡片。{@code text} 在這裡的角色是 altText——被引用時顯示的就是它。 */
    public static PushTextPayload card(String lineUserId, String altText,
            List<Long> itemIds, String cardId, String flexJson) {
        return new PushTextPayload(lineUserId, altText, itemIds, cardId, flexJson, null);
    }

    /**
     * 這則訊息可以用 reply 送。
     *
     * <p>做成 wither 而不是多開一組建構參數：<b>「訊息長什麼樣」與「怎麼送出去」
     * 是兩個不同的決定</b>，由不同的地方負責。排版那邊不必知道有沒有 token，
     * 收到 webhook 的那邊也不必知道卡片怎麼排。
     */
    public PushTextPayload repliedWith(String replyToken) {
        return new PushTextPayload(lineUserId, text, anchoredItemIds, cardId, flexJson, replyToken);
    }

    public boolean isCard() {
        return flexJson != null;
    }
}
