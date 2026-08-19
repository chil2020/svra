package io.svra.note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import io.svra.IntegrationTest;
import io.svra.command.NoteCommandService;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 決策 2「冪等靠 DB 唯一約束，不是先查再插」在真的資料庫上成不成立。
 *
 * <p>單元測試那邊是 mock 出來的例外，證明的是呼叫端會 catch。這裡問的是更難的兩件事：
 * <b>約束真的擋得住並行嗎</b>，以及<b>擋下來之後那個交易還提交得了嗎</b>。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        // 這些測試自己呼叫 poller，不要讓排程在背景插進來
        "svra.outbox.poll-interval-ms=3600000",
        // 沒有 RabbitMQ 容器，listener 不用啟動
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // LineProperties 現在是 @NotBlank，少了會啟動失敗（決策 22）。
        // 這幾個測試不打 LINE，給值只是為了讓 context 起得來。
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class IdempotencyIntegrationTest {

    private static final String USER_ID = "U4af4980629";

    @Autowired
    private NoteService noteService;

    @Autowired
    private NoteCommandService commandService;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Test
    @DisplayName("同一則語音被兩個執行緒同時收到 → 只留一筆 note、一筆 outbox，且沒有例外外洩")
    void concurrentDeliveryOfSameVoiceMessageCreatesExactlyOneNote() throws Exception {
        String messageId = "audio-" + UUID.randomUUID();

        Outcome outcome = raceOnSameMessage(8,
                () -> noteService.recordIncoming(USER_ID, messageId));

        // 這一條是重點：webhook 只要把例外往外拋就會回 500，LINE 就再重送一次。
        // 「先查再插」在這裡不會過——兩個執行緒會同時查到不存在。
        assertThat(outcome.failures())
                .as("重複投遞不可以有任何例外外洩，否則 webhook 回 500 會讓 LINE 一直重送")
                .isEmpty();
        assertThat(outcome.trueCount())
                .as("只有一個執行緒可以認為自己是第一次收到")
                .isEqualTo(1);
        assertThat(noteRepository.findBySourceMessageId(messageId)).isPresent();
        assertThat(countOutbox(messageId))
                .as("outbox 也只能有一筆，否則會重複發轉錄任務")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("同一則文字指令被兩個執行緒同時收到 → 只留一筆 outbox（V5 的冪等鍵）")
    void concurrentDeliveryOfSameCommandRecordsExactlyOneEvent() throws Exception {
        String messageId = "cmd-" + UUID.randomUUID();

        Outcome outcome = raceOnSameMessage(8, () -> {
            commandService.recordCommand(USER_ID, messageId, "刪掉第一筆", null);
            return true;
        });

        assertThat(outcome.failures())
                .as("指令重送一樣不可以拋例外——這裡沒有 note，唯一的防線就是 outbox 的冪等鍵")
                .isEmpty();
        assertThat(countOutbox(messageId))
                .as("沒有 dedupe_key 的話這裡會是 8，而「刪掉第一筆」就會執行 8 次")
                .isEqualTo(1);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private long countOutbox(String aggregateId) {
        return outboxRepository.findAll().stream()
                .filter(e -> aggregateId.equals(e.getAggregateId()))
                .count();
    }

    private record Outcome(List<Boolean> results, List<Throwable> failures) {
        long trueCount() {
            return results.stream().filter(Boolean::booleanValue).count();
        }
    }

    /** 讓 n 個執行緒在同一瞬間對同一個 messageId 動手，盡量逼出 race。 */
    private Outcome raceOnSameMessage(int threads, ThrowingSupplier action) throws Exception {
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        results.add(action.get());
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("所有執行緒都要在時限內結束").isTrue();
        }
        return new Outcome(results, failures);
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        boolean get() throws Exception;
    }
}
