package io.svra.outbox;

/**
 * 一種 outbox 事件的處理方式。
 *
 * <p>由各功能模組自己實作並註冊，poller 只認識這個介面——
 * 基礎設施不需要知道有哪些業務功能，加新功能也不用改 poller。
 */
public interface OutboxEventHandler {

    /** 這個處理器負責的事件型別，對應 {@code outbox_events.event_type}。 */
    String eventType();

    /**
     * 允許丟出受檢例外：處理器都在做 I/O（下載、發訊息、呼叫 LLM），
     * 而 poller 的契約本來就是「攔下所有失敗，記錄後退避重試」——
     * 逼每個實作自己包一層 try/catch 只會讓失敗被吞掉。
     *
     * <p>呼叫時<b>沒有</b>外層交易（poller 會把自己的讓開），需要交易的實作
     * 自己標 {@code @Transactional}。
     *
     * @param payload 事件的 JSON 內容，由實作者自己決定怎麼解讀——
     *                不同事件的 payload 形狀本來就不同
     */
    void handle(String payload) throws Exception;

    /**
     * 重試耗盡、徹底放棄時呼叫。預設什麼都不做——
     * 但如果放棄會讓使用者的東西卡在中間狀態（例如 note 永遠停在 PENDING），
     * 就必須在這裡收尾，否則沒有人知道它被放棄了。
     */
    default void onGiveUp(String payload) throws Exception {
    }
}
