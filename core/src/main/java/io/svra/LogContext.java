package io.svra;

import org.slf4j.MDC;

/**
 * 把「這一行 log 屬於哪一則訊息」放進 MDC，讓輸出格式自動帶上。
 *
 * <p><b>為什麼需要它</b>：一則語音會經過 webhook → outbox → RabbitMQ → whisper →
 * 抽取 → 推播，中間跨了 HTTP 執行緒、排程執行緒、AMQP listener 執行緒三種。
 * 沒有共同的鍵，log 就只是一堆各自獨立的句子，看不出哪幾行是同一件事。
 *
 * <p>用的是 <b>LINE 的 message id</b>，因為它天生貫穿全程：
 * {@code notes.source_message_id}、{@code outbox_events.aggregate_id}、
 * RabbitMQ 訊息的 {@code job_id}、whisper worker 的 {@code job=} 都是同一個值。
 * 不必另外發一個 trace id——<b>已經有一個貫穿的識別碼了，再造一個只會多一層對照</b>。
 *
 * <p>（它曾經在不同地方叫不同名字：listener 那邊叫 {@code jobId}、其餘叫
 * {@code messageId}。同一個值兩個名字，讀 log 的人看不出那是同一件事。）
 *
 * <h2>要在哪裡設</h2>
 *
 * 只有<b>四個入口</b>需要手動設，因為它們是執行緒的起點：
 *
 * <ul>
 * <li>{@code LineWebhookController}——逐則事件設，<b>不是逐個請求</b>：
 *     一個 webhook 請求可能帶多則訊息，各自是不同的 id
 * <li>{@code TranscribeResultListener}——從結果訊息的 job_id
 * <li>{@code TranscribeDlqListener}——兩個死信方法各一
 * <li>{@code OutboxPoller}——從 {@code aggregate_id}
 * </ul>
 *
 * poller 那一處特別划算：所有 {@code OutboxEventHandler} 都在它的執行緒上跑，
 * 所以下載音檔、抽取、推播、指令套用<b>全部自動繼承</b>，不用逐個 handler 改。
 *
 * <h2>一定要用 try-with-resources</h2>
 *
 * 這些執行緒都來自共用的執行緒池，<b>會被重複使用</b>。忘記清掉的話，
 * 下一個任務會頂著上一則訊息的 id 印 log——那比沒有 id 更糟，
 * 因為它看起來是對的。{@link MDC.MDCCloseable} 會在離開區塊時還原。
 */
public final class LogContext {

    /** 輸出格式用的鍵名，見 {@code application.yml} 的 logging.pattern。 */
    public static final String MESSAGE_ID = "messageId";

    private LogContext() {
    }

    /**
     * <pre>
     * try (var ignored = LogContext.messageId(id)) {
     *     ...   // 這個區塊裡的每一行 log 都會帶上 id
     * }
     * </pre>
     *
     * @param messageId LINE 的 message id；null 時等同不設（不會印出殘留的舊值）
     */
    public static MDC.MDCCloseable messageId(String messageId) {
        return MDC.putCloseable(MESSAGE_ID, messageId == null ? "" : messageId);
    }
}
