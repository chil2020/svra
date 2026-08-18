package io.svra.outbox;

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
    /** 處理器跑在自己的交易裡——理由見 {@link #runIsolated}。 */
    private final TransactionTemplate handlerTx;

    public OutboxPoller(OutboxEventRepository outboxRepository,
            List<OutboxEventHandler> handlers,
            PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
        this.handlerTx = new TransactionTemplate(transactionManager);
        this.handlerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${svra.outbox.poll-interval-ms:2000}")
    @Transactional
    public void dispatch() {
        List<OutboxEvent> batch = outboxRepository.lockNextBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            OutboxEventHandler handler = handlers.get(event.getEventType());
            try {
                if (handler == null) {
                    throw new IllegalStateException("沒有處理器對應事件型別：" + event.getEventType());
                }
                runIsolated(() -> handler.handle(event.getPayload()));

                event.markSent();
            } catch (Exception e) {
                log.warn("outbox 發送失敗：id={} type={} attempts={}",
                        event.getId(), event.getEventType(), event.getAttempts(), e);
                event.markFailed(e.toString());

                // 重試耗盡就不會再有人處理這個事件了，讓對應的處理器自己收尾——
                // 該怎麼善後只有它知道（例如把 note 標成 FAILED）。
                if (event.getStatus() == OutboxStatus.FAILED && handler != null) {
                    try {
                        runIsolated(() -> handler.onGiveUp(event.getPayload()));
                    } catch (Exception cleanupFailure) {
                        log.error("放棄後的收尾也失敗：id={}", event.getId(), cleanupFailure);
                    }
                }
                // 不 throw —— 這一批的其他筆要能繼續
            }
        }
    }

    /**
     * 把處理器跑在獨立的交易裡。
     *
     * <p>🔴 少了這一層，重試機制是假的：處理器的 {@code @Transactional} 在例外往外
     * 傳時會把交易標成 rollback-only，就算這裡把例外接住，外層 commit 時仍會拋
     * {@code UnexpectedRollbackException}——連 {@code markFailed()} 累加的次數
     * 都一起被回滾。實測就是 {@code attempts} 永遠停在 0、事件無限重試，
     * 而且同一批的其他事件也跟著陪葬。
     *
     * <p>代價是處理器的副作用會先於狀態更新提交：處理器成功、外層卻失敗時，
     * 事件仍是 PENDING 而會再跑一次。這是 outbox 本來就有的 at-least-once 性質
     * （見 README 決策 3），消費端要冪等。
     */
    private void runIsolated(ThrowingRunnable action) throws Exception {
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
