package io.svra.outbox;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.svra.LogContext;

/**
 * 把 outbox 裡的待送事件真的送出去。
 *
 * <p>🔴 承重點③：這支是 outbox 模式能不能成立的關鍵。
 *
 * <p>它不認識任何一個業務功能——各模組自己實作 {@link OutboxEventHandler} 並註冊。
 * 加新的事件型別不用改這裡，基礎設施也就不會反過來依賴業務。
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    /** 一批的上限。處理事件會佔住資料庫的列鎖，批次太大會讓交易拖很久。 */
    private static final int BATCH_SIZE = 5;

    private final OutboxEventRepository outboxRepository;
    /** 事件型別 → 處理器。Spring 把所有實作注入進來，這裡不用認識任何一個。 */
    private final Map<String, OutboxEventHandler> handlers;
    /** 跑處理器時把 poller 自己的交易讓開——理由見 {@link #runIsolated}。 */
    private final TransactionTemplate handlerTx;
    /** 到期判斷用這個時鐘，跟 {@code OutboxEvent} 寫入時間戳用的是同一個。 */
    private final Clock clock;

    public OutboxPoller(OutboxEventRepository outboxRepository,
            List<OutboxEventHandler> handlers,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
        this.handlerTx = new TransactionTemplate(transactionManager);
        this.handlerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${svra.outbox.poll-interval-ms:2000}")
    @Transactional
    public void dispatch() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> batch = outboxRepository.lockNextBatch(BATCH_SIZE, now);
        if (batch.isEmpty()) {
            // 空轉每 2 秒一次，記了會把 log 洗掉。撈到東西才出聲。
            return;
        }
        log.debug("撈到 {} 筆待送事件", batch.size());

        for (OutboxEvent event : batch) {
            // MDC 設在這裡最划算：所有 handler 都跑在這個執行緒上，
            // 下載音檔、抽取、推播、指令套用全部自動繼承，不用逐個改。
            // aggregate_id 就是 LINE 的 message id（見 LogContext）。
            try (var ignored = LogContext.messageId(event.getAggregateId())) {
                dispatchOne(event, now);
            }
        }
    }

    private void dispatchOne(OutboxEvent event, Instant now) {
        OutboxEventHandler handler = handlers.get(event.getEventType());
        long startedNanos = System.nanoTime();
        try {
            if (handler == null) {
                throw new IllegalStateException("沒有處理器對應事件型別：" + event.getEventType());
            }
            runOutsideOwnTransaction(() -> handler.handle(event.getPayload()));

            event.markSent(now);
            log.info("outbox 送出：{} id={} 耗時={}ms",
                    event.getEventType(), event.getId(), elapsedMillis(startedNanos));

        } catch (Exception e) {
            event.markFailed(e.toString(), now);

            if (event.getStatus() == OutboxStatus.FAILED) {
                // 🔴 「放棄」與「第 3 次重試」必須長得不一樣。
                // 這兩件事以前共用同一行 warn，唯一的差別是 attempts 剛好等於上限——
                // 而「這筆永遠不會再送了」是這個系統最需要被看見的事件之一。
                log.error("outbox 放棄：{} id={} 已重試 {} 次，不會再送",
                        event.getEventType(), event.getId(), event.getAttempts(), e);
                giveUp(event, handler);
            } else {
                log.warn("outbox 重試：{} id={} 第 {}/{} 次，{}後再試：{}",
                        event.getEventType(), event.getId(), event.getAttempts(),
                        OutboxEvent.MAX_ATTEMPTS,
                        humanizeUntil(now, event.getNextAttemptAt()), e.toString());
            }
            // 不 throw —— 這一批的其他筆要能繼續
        }
    }

    /**
     * 重試耗盡就不會再有人處理這個事件了，讓對應的處理器自己收尾——
     * 該怎麼善後只有它知道（例如把 note 標成 FAILED 並通知使用者）。
     */
    private void giveUp(OutboxEvent event, OutboxEventHandler handler) {
        if (handler == null) {
            log.error("沒有處理器可以收尾，這筆事件沒有終局：id={}", event.getId());
            return;
        }
        try {
            runOutsideOwnTransaction(() -> handler.onGiveUp(event.getPayload()));
            log.info("放棄後的收尾已完成：id={}", event.getId());
        } catch (Exception cleanupFailure) {
            // 收尾也失敗＝使用者不會知道這件事被放棄了，比放棄本身更嚴重
            log.error("放棄後的收尾也失敗，使用者不會收到任何通知：id={}",
                    event.getId(), cleanupFailure);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** 「30 秒」比一個絕對時間戳好讀——看 log 的人想知道的是還要等多久。 */
    private static String humanizeUntil(Instant now, Instant next) {
        long seconds = Math.max(0, next.getEpochSecond() - now.getEpochSecond());
        return seconds + " 秒";  // 呼叫端接「後再試」
    }

    /**
     * 跑處理器時，把 poller 自己的交易<b>暫時讓開</b>（{@code NOT_SUPPORTED}）。
     *
     * <p>🔴 少了這一層，重試機制是假的：處理器底下的 {@code @Transactional} 在例外
     * 往外傳時會把交易標成 rollback-only，就算這裡把例外接住，外層 commit 時仍會拋
     * {@code UnexpectedRollbackException}——連 {@code markFailed()} 累加的次數
     * 都一起被回滾。症狀是 {@code attempts} 永遠停在 0、事件無限重試，
     * 而且同一批的其他事件也跟著陪葬。{@code OutboxPollerIntegrationTest} 守著這件事。
     *
     * <p><b>為什麼是「讓開」而不是「另外開一個」。</b>
     * 這裡原本用 {@code REQUIRES_NEW}，隔離的效果一樣，但它順手<b>替處理器決定了
     * 交易語意</b>——而處理器在做的是下載音檔、呼叫 LLM 這類外部 I/O。
     * 一次抽取 12 秒起跳，那 12 秒全程佔著一條資料庫連線，什麼事也沒做。
     * <b>基礎設施不該替業務決定要不要交易</b>，跟決策 7 把 switch 換成介面是同一件事。
     *
     * <p>讓開之後：需要交易的處理器靠自己的 {@code @Transactional} 開（此時沒有
     * 外層交易，{@code REQUIRED} 就是開一個新的，隔離效果不變）；不需要的
     * ——下載、發訊息、呼叫模型——就真的不在交易裡跑。
     *
     * <p>代價不變：處理器的副作用會先於狀態更新提交，處理器成功而外層失敗時
     * 事件仍是 PENDING 而會再跑一次。這是 outbox 本來就有的 at-least-once 性質
     * （見 README 決策 3），消費端要冪等。
     */
    private void runOutsideOwnTransaction(ThrowingRunnable action) throws Exception {
        try {
            handlerTx.executeWithoutResult(status -> {
                try {
                    action.run();
                } catch (Exception e) {
                    throw new HandlerFailure(e);
                }
            });
        } catch (HandlerFailure wrapper) {
            throw (Exception) wrapper.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 只為了讓受檢例外穿過 TransactionTemplate，不對外。 */
    private static class HandlerFailure extends RuntimeException {
        HandlerFailure(Exception cause) {
            super(cause);
        }
    }
}
