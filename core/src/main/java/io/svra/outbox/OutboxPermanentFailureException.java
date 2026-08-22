package io.svra.outbox;

/**
 * 「重試一萬次也不會好」的失敗。
 *
 * <p>Outbox 的退避重試預設所有失敗都是暫時的——網路抖一下、下游正在重啟、
 * 資料庫忙不過來。那個假設對絕大多數失敗成立，<b>但不是全部</b>：
 * Google 的 refresh token 被撤銷了、行事曆被刪了、權限範圍不夠。
 * 這幾種再試五次只是把「使用者知道出事」這件事往後推幾分鐘，
 * 而且中間每一次重試都是註定失敗的請求。
 *
 * <p>處理器丟出這個例外，就是在說「別退避了，直接判死並收尾」——
 * poller 會立刻標成 FAILED 並呼叫 {@link OutboxEventHandler#onGiveUp}，
 * 由處理器自己決定要怎麼讓使用者知道。
 *
 * <p>🔴 <b>分類錯的代價不對稱</b>：把暫時性失敗誤判成永久，會讓一次網路抖動
 * 變成一則「授權失效」的假警報；把永久誤判成暫時，只是晚幾分鐘知道。
 * 所以判斷要保守——<b>只有明確指出「這個請求本身不對」的回應才算永久</b>，
 * 例如帶著 {@code invalid_grant} 的 401。5xx、429、逾時一律當暫時。
 */
public class OutboxPermanentFailureException extends RuntimeException {

    public OutboxPermanentFailureException(String message) {
        super(message);
    }

    public OutboxPermanentFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
