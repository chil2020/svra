package io.svra.command;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.svra.IntegrationTest;
import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteRepository;
import io.svra.note.NoteService;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 指令重跑會不會做第二次——在真的資料庫上。
 *
 * <p>單元測試那邊是把 repository 換成 mock，證明的是「回 0 就不動手」。
 * 這裡問的是更難的兩件事：<b>主鍵真的擋得住兩個 poller 同時處理同一筆事件嗎</b>，
 * 以及<b>擋下來之後那個交易還提交得了嗎</b>（決策 2 踩過的坑）。
 *
 * <p>為什麼指令需要這道防線，而語音不用：語音重跑是重複<b>插入</b>，唯一鍵擋著；
 * 指令重跑是重複<b>執行</b>，而「刪掉第一筆」是位置性的——第二次跑的時候
 * 清單已經少了一筆，同樣的第一筆指向的是另一個項目。它會成功，會回覆「已刪除」，
 * 而刪掉的是別筆。
 *
 * <p>解析被換成 mock：要驗的是執行階段，不是模型看不看得懂，
 * 而且真的呼叫 LLM 會讓這個測試又慢又不具決定性。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 行事曆的設定跟 LINE 的一樣是 @NotBlank，少了就起不來（決策 8 的一貫做法）。
        // 這裡填假的：整合測試不會真的打 Google。
        "svra.calendar.client-id=test-client",
        "svra.calendar.client-secret=test-secret",
        "svra.calendar.refresh-token=test-refresh-token",
        "svra.calendar.calendar-id=test@group.calendar.google.com",
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class CommandIdempotencyIntegrationTest {

    @MockitoBean
    private NoteCommandParser parser;

    @Autowired private NoteCommandService commandService;
    @Autowired private NoteRepository noteRepository;
    @Autowired private NoteExtractionRepository extractionRepository;
    @Autowired private OutboxEventRepository outboxRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("同一筆指令事件跑兩次 → 只刪一筆，而且只回覆一次")
    void rerunningTheSameCommandDeletesOnlyOnce() {
        Fixture fixture = seedThreeItems();
        deleteFirstItem();

        commandService.applyCommand(fixture.payload());
        commandService.applyCommand(fixture.payload());

        assertThat(titlesOf(fixture.extractionId()))
                .as("第二次跑的時候「第一筆」已經是原本的第二筆了——重跑會刪掉它")
                .containsExactly("第二筆", "第三筆");
        assertThat(countReplies(fixture.commandMessageId()))
                .as("回覆也只能有一次，否則使用者會收到兩則「已刪除」")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("兩個 poller 同時處理同一筆指令事件 → 一樣只刪一筆，且沒有例外外洩")
    void concurrentPollersExecuteTheCommandOnce() throws Exception {
        Fixture fixture = seedThreeItems();
        deleteFirstItem();

        List<Throwable> failures = race(4, () -> commandService.applyCommand(fixture.payload()));

        // 這一條是重點：並行時兩個執行緒都會通過第一段的「查過沒執行」，
        // 真正分出勝負的是第三段那次 INSERT。
        assertThat(failures)
                .as("輸的那一邊要安靜地放棄，不能把例外丟回 poller——那會讓事件無限重試")
                .isEmpty();
        assertThat(titlesOf(fixture.extractionId())).containsExactly("第二筆", "第三筆");
        assertThat(countReplies(fixture.commandMessageId())).isEqualTo(1);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /**
     * 每個測試自己一個使用者。
     *
     * <p>「目前的清單」是跨語音查詢（決策：LIST 要看的是現況，不是最後一則），
     * 所以兩個測試共用一個 userId 的話，後跑的那個「第一筆」會指到前一個留下的項目。
     * 那不是產品的問題，是測試把彼此的資料當成自己的。
     */
    private record Fixture(String userId, Long extractionId, String commandMessageId) {
        NoteCommandService.CommandPayload payload() {
            return new NoteCommandService.CommandPayload(
                    userId, commandMessageId, "刪掉第一筆", null, null);
        }
    }

    /** 讓 mock 的 parser 一律回「刪掉第一筆」——位置性正是這裡要測的東西。 */
    private void deleteFirstItem() {
        when(parser.parse(any(), any())).thenReturn(new NoteCommand(
                List.of(new NoteCommand.Op(NoteCommand.Action.DELETE, 1, null, null, null, null)),
                null, null));
    }

    private Fixture seedThreeItems() {
        String sourceMessageId = "audio-" + UUID.randomUUID();
        String userId = "U" + UUID.randomUUID().toString().replace("-", "");
        Long extractionId = inTransaction(() -> {
            noteRepository.insertPendingIfAbsent(userId, sourceMessageId);
            Long noteId = noteRepository.findBySourceMessageId(sourceMessageId)
                    .orElseThrow().getId();
            NoteExtraction extraction = NoteExtraction.of(noteId, "test", "v-test");
            extraction.addItem(item("第一筆"));
            extraction.addItem(item("第二筆"));
            extraction.addItem(item("第三筆"));
            return extractionRepository.save(extraction).getId();
        });
        return new Fixture(userId, extractionId, "cmd-" + UUID.randomUUID());
    }

    private static NoteItem item(String title) {
        return new NoteItem(NoteCategory.TODO, title, null, null, null, List.of());
    }

    private List<String> titlesOf(Long extractionId) {
        return inTransaction(() -> extractionRepository.findById(extractionId).orElseThrow()
                .getOrderedItems().stream().map(NoteItem::getTitle).toList());
    }

    @Test
    @DisplayName("執行紀錄要帶著使用者——不然「他下過哪些指令」永遠查不到")
    void theExecutionRecordCarriesTheUser() {
        Fixture fixture = seedThreeItems();
        deleteFirstItem();

        commandService.applyCommand(fixture.payload());

        // 這張表原本只有一個 message id，而 message id 對不回 notes（指令不建 note）。
        // 欄位加了卻沒有人填的話，症狀是「查詢永遠回空」而不是報錯。
        assertThat(executedBy(fixture.commandMessageId())).isEqualTo(fixture.userId());
    }

    private String executedBy(String commandMessageId) {
        return jdbcTemplate.queryForObject(
                "SELECT line_user_id FROM command_executions WHERE command_message_id = ?",
                String.class, commandMessageId);
    }

    private long countReplies(String commandMessageId) {
        return outboxRepository.findAll().stream()
                .filter(e -> commandMessageId.equals(e.getAggregateId()))
                .filter(e -> NoteService.EVENT_PUSH_TEXT_REQUESTED.equals(e.getEventType()))
                .count();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    /** 讓 n 個執行緒在同一瞬間做同一件事，盡量逼出 race。 */
    private List<Throwable> race(int threads, Runnable action) throws Exception {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        action.run();
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
        return failures;
    }
}
