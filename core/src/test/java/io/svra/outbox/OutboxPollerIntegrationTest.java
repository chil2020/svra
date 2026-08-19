package io.svra.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.svra.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 承重點③在真的資料庫上的兩個宣稱：{@code SKIP LOCKED} 的多實例安全，
 * 以及處理器跑在獨立交易。
 *
 * <p>第二個測試守的是 README 裡「重試上限一度是假的」那個坑。
 * 在寫這個測試之前，把 {@code runIsolated()} 整個拿掉，{@code mvn test} 依然全綠——
 * 那個 bug 可以原封不動地回來而沒有任何東西會叫。
 */
@Tag("integration")
@SpringBootTest
@Import({ IntegrationTest.class, OutboxPollerIntegrationTest.FailingHandlerConfig.class })
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // LineProperties 現在是 @NotBlank，少了會啟動失敗（決策 22）。
        // 這幾個測試不打 LINE，給值只是為了讓 context 起得來。
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class OutboxPollerIntegrationTest {

    private static final String BOOM = "TEST_ALWAYS_FAILS";
    private static final String FINE = "TEST_ALWAYS_SUCCEEDS";

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SucceedingHandler succeedingHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOutbox() {
        outboxRepository.deleteAll();
        succeedingHandler.reset();
    }

    @Test
    @DisplayName("處理器失敗 → attempts 真的累加，而且同一批的其他事件不會陪葬")
    void handlerFailureIncrementsAttemptsAndDoesNotPoisonTheBatch() {
        // 順序很重要：失敗的排在前面，才測得到「後面的會不會被拖下水」
        OutboxEvent doomed = save(BOOM);
        OutboxEvent innocent = save(FINE);

        poller.dispatch();

        // 少了 runIsolated：處理器的 @Transactional 在例外往外傳時把交易標成
        // rollback-only，外層 commit 時拋 UnexpectedRollbackException，
        // 連 markFailed() 累加的次數一起被回滾。症狀就是這裡永遠是 0。
        assertThat(reload(doomed).getAttempts())
                .as("重試次數沒有累加的話，這個事件會無限重試而永遠不會放棄")
                .isEqualTo(1);
        assertThat(reload(doomed).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reload(doomed).getLastError()).isNotBlank();

        assertThat(succeedingHandler.calls())
                .as("同一批裡的其他事件不該因為前面那筆失敗就跟著回滾")
                .isEqualTo(1);
        assertThat(reload(innocent).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("重試耗盡 → 標成 FAILED，不再被撈出來")
    void retriesEventuallyGiveUp() {
        OutboxEvent doomed = save(BOOM);

        for (int i = 0; i < OutboxEvent.MAX_ATTEMPTS; i++) {
            // 退避會把 next_attempt_at 排到未來，直接改回現在，免得測試真的在等
            makeDueNow(doomed.getId());
            poller.dispatch();
        }

        assertThat(reload(doomed).getAttempts()).isEqualTo(OutboxEvent.MAX_ATTEMPTS);
        assertThat(reload(doomed).getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @DisplayName("兩個 poller 同時撈 → SKIP LOCKED 讓它們拿到不重疊的批次")
    void concurrentPollersGetDisjointBatches() throws Exception {
        for (int i = 0; i < 10; i++) {
            save(FINE);
        }

        AtomicReference<List<Long>> first = new AtomicReference<>();
        AtomicReference<List<Long>> second = new AtomicReference<>();
        CountDownLatch firstHasLocked = new CountDownLatch(1);
        CountDownLatch secondIsDone = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> inNewTransaction(() -> {
                first.set(ids(outboxRepository.lockNextBatch(5, Instant.now())));
                firstHasLocked.countDown();
                // 抓著鎖不放，逼第二個 poller 在鎖還在的時候去撈
                await(secondIsDone);
            }));

            pool.submit(() -> inNewTransaction(() -> {
                await(firstHasLocked);
                second.set(ids(outboxRepository.lockNextBatch(5, Instant.now())));
                secondIsDone.countDown();
            }));

            assertThat(secondIsDone.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(first.get()).hasSize(5);
        assertThat(second.get())
                .as("少了 SKIP LOCKED，第二個 poller 會卡住等鎖；少了 FOR UPDATE，它會拿到同一批")
                .hasSize(5)
                .doesNotContainAnyElementsOf(first.get());
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private OutboxEvent save(String eventType) {
        return outboxRepository.save(OutboxEvent.pending(
                UUID.randomUUID().toString(), eventType, "{}"));
    }

    private OutboxEvent reload(OutboxEvent event) {
        return outboxRepository.findById(event.getId()).orElseThrow();
    }

    /**
     * 把退避排的下次重試時間拉回現在。
     *
     * <p>直接下 SQL 而不是加一個 repository 方法：那個方法只有測試會用，
     * 放進正式程式碼就是為了測試而開的後門。
     */
    private void makeDueNow(Long id) {
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), id);
    }

    private static List<Long> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getId).toList();
    }

    private void inNewTransaction(Runnable action) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> action.run());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待逾時");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ── 測試用的處理器 ──────────────────────────────────────────────

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingHandlerConfig {

        @Bean
        FailingHandler failingHandler() {
            return new FailingHandler();
        }

        @Bean
        SucceedingHandler succeedingHandler() {
            return new SucceedingHandler();
        }
    }

    /**
     * {@code @Transactional} 是重點——沒有它就重現不出那個坑。
     * 例外從一個有交易的方法往外傳時，交易才會被標成 rollback-only。
     */
    static class FailingHandler implements OutboxEventHandler {

        @Override
        public String eventType() {
            return BOOM;
        }

        @Override
        @Transactional
        public void handle(String payload) {
            throw new IllegalStateException("處理器爆了");
        }
    }

    /**
     * 計數走方法而不是公開欄位：這個 bean 因為 {@code @Transactional} 會被 CGLIB
     * 代理，直接讀代理物件的欄位讀到的是代理自己那份（null），不是目標物件的。
     */
    static class SucceedingHandler implements OutboxEventHandler {

        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public String eventType() {
            return FINE;
        }

        @Override
        @Transactional
        public void handle(String payload) {
            callCount.incrementAndGet();
        }

        int calls() {
            return callCount.get();
        }

        void reset() {
            callCount.set(0);
        }
    }
}
