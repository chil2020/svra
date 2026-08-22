package io.svra.calendar;

import java.util.List;

/**
 * 「把這幾筆的狀態同步到 Google 行事曆」。
 *
 * @param lineUserId      同步完成或失敗時要通知誰
 * @param requestId       是什麼引發了這次同步：postback 的 {@code webhookEventId}，
 *                        或指令的訊息 id。
 *                        <p>🔴 <b>它的用途是讓「回覆」也有冪等鍵。</b>
 *                        寫進行事曆這件事已經冪等了（決定性 event id 撞 409），
 *                        但<b>回覆的推播沒有</b>——poller 在標記 SENT 之前掛掉重跑，
 *                        Google 那端沒事，使用者卻會收到<b>兩張</b>「已加入行事曆」，
 *                        而且各吃一次免費額度。
 *                        <p>不能改用 {@code cardId} 當鍵：同一張卡上先按單筆、
 *                        再按「全部加入」是兩次合法的請求，共用一個鍵會讓第二次
 *                        沒有回覆。
 * @param replyToken      匯入完成的回覆用它送，<b>不計入月額度</b>。實測從按下按鈕
 *                        到回覆約 2 秒，token 撐得住。指令引發的連動不回覆，為 null
 * @param cardId          使用者按的是<b>哪一張卡片</b>上的按鈕。
 *                        非 null＝這是他主動要求的，做完要用同一張卡的內容
 *                        回一份新版本給他；null＝這是指令改動引發的連動，
 *                        安靜地做完就好（失敗仍然會說，見
 *                        {@code CalendarSyncHandler.onGiveUp}）
 * @param targets         這次要處理的項目
 */
public record CalendarSyncPayload(
        String lineUserId,
        String requestId,
        String replyToken,
        String cardId,
        List<Target> targets) {

    /**
     * 一筆要同步的東西。
     *
     * <p>🔴 <b>{@code googleEventId} 在 DELETE 時是必填的，而那不是冗餘。</b>
     * 指令刪除走的是 {@code orphanRemoval}，交易一提交那一列就不在了——
     * poller 兩秒後撿起這個事件時，已經<b>沒有東西可以回查</b>。
     * 事件必須自帶做完這件事所需的全部資訊，這正是決策 3「先寫意圖」的字面意思：
     * 意圖要自足，不能依賴一份可能已經消失的資料。
     *
     * <p>UPSERT 反過來只帶 {@code itemId}：標題與時間要用<b>執行當下</b>的值，
     * 而不是寫下意圖那一刻的快照。中間如果又改了一次，最後一次同步才是對的。
     */
    public record Target(Op op, Long itemId, String googleEventId) {

        public static Target upsert(Long itemId) {
            return new Target(Op.UPSERT, itemId, null);
        }

        public static Target delete(String googleEventId) {
            return new Target(Op.DELETE, null, googleEventId);
        }
    }

    public enum Op {
        /** 寫進去；已經在了就更新。 */
        UPSERT,
        /** 從行事曆上拿掉。 */
        DELETE
    }
}
