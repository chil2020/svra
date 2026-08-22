package io.svra.calendar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import tools.jackson.databind.ObjectMapper;

import io.svra.IntegrationTest;
import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteRepository;
import io.svra.note.NoteService;
import io.svra.notify.MessageAnchors;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 寫進行事曆這件事是冪等的，<b>但回覆不是</b>——而那是兩件事。
 *
 * <p>outbox 是 at-least-once（決策 3）：處理器整段跑完、交易也提交了，
 * poller 卻在 {@code markSent()} 之前掛掉。重跑時 Google 那端沒事
 * （決定性 event id 撞 409 轉更新），但少了冪等鍵的話，
 * <b>回覆會再寫一筆</b>——使用者收到兩張「已加入行事曆」，各吃一次免費推播額度。
 *
 * <p>這個縫在把外部寫入做成冪等的時候被漏掉了。指令那條路一直有
 * {@code command_executions} 擋著同一件事。
 *
 * <p>Google client 換成 mock：這裡要驗的是<b>重跑時我們自己寫了幾筆 outbox</b>，
 * 不是 Google 的行為。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "svra.calendar.oauth-user-ids=U4af4980629",
        "svra.calendar.client-id=test-client",
        "svra.calendar.client-secret=test-secret",
        "svra.calendar.refresh-token=test-refresh-token",
        "svra.calendar.calendar-id=test@group.calendar.google.com",
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class CalendarReplyIdempotencyIntegrationTest {

    private static final String USER_ID = "U4af4980629";

    @Autowired
    private CalendarSyncHandler handler;

    @Autowired
    private MessageAnchors anchors;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteExtractionRepository extractionRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 這裡不打真的 Google——要驗的是我們自己寫了幾筆 outbox。 */
    @MockitoBean
    private GoogleCalendarClient client;

    @BeforeEach
    void clearOutbox() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("🔴 同一筆同步事件被重跑 → Google 照樣寫，但回覆只推一次")
    void aRetriedSyncDoesNotReplyTwice() {
        Long itemId = seedItem();
        String cardId = seedCard(List.of(itemId));
        String requestId = "wh-" + UUID.randomUUID();

        String payload = serialize(new CalendarSyncPayload(USER_ID, requestId, null, cardId,
                List.of(CalendarSyncPayload.Target.upsert(itemId))));

        // poller 在 markSent() 之前掛掉，事件留在 PENDING，下一輪再撿一次。
        // 同一個 eventId 代表「同一筆 outbox 事件被重跑」——那正是要測的情況。
        handler.handle(1L, payload);
        handler.handle(1L, payload);

        // 對 Google 重送是安全的：決定性 event id 讓第二次撞 409 轉成更新。
        verify(client, atLeastOnce()).upsert(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any());

        assertThat(replies())
                .as("回覆沒有冪等鍵的話，使用者會收到兩張一模一樣的卡片")
                .hasSize(1);
    }

    @Test
    @DisplayName("同一張卡先按單筆、再按「全部加入」→ 兩次都要回覆")
    void twoDistinctRequestsOnTheSameCardBothReply() {
        Long itemId = seedItem();
        String cardId = seedCard(List.of(itemId));

        // 冪等鍵刻意用 requestId 而不是 cardId：這是兩次合法的請求，
        // 共用一個鍵會讓第二次沒有回覆，而使用者按了卻沒反應。
        handler.handle(11L, serialize(new CalendarSyncPayload(USER_ID, "wh-single", null, cardId,
                List.of(CalendarSyncPayload.Target.upsert(itemId)))));
        handler.handle(12L, serialize(new CalendarSyncPayload(USER_ID, "wh-bulk", null, cardId,
                List.of(CalendarSyncPayload.Target.upsert(itemId)))));

        assertThat(replies()).hasSize(2);
    }

    @Test
    @DisplayName("指令引發的連動不回覆——使用者剛收到一份調整後的清單了")
    void commandDrivenSyncStaysSilent() {
        Long itemId = seedItem();

        handler.handle(13L, serialize(new CalendarSyncPayload(USER_ID, "cmd-1", null, null,
                List.of(CalendarSyncPayload.Target.upsert(itemId)))));

        assertThat(replies()).isEmpty();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private Long seedItem() {
        return inTransaction(() -> {
            String sourceMessageId = "audio-" + UUID.randomUUID();
            noteRepository.insertPendingIfAbsent(USER_ID, sourceMessageId);
            Long noteId = noteRepository.findBySourceMessageId(sourceMessageId).orElseThrow().getId();
            NoteExtraction extraction = NoteExtraction.of(noteId, "test", "v-test");
            extraction.addItem(new NoteItem(NoteCategory.SCHEDULE, "開會",
                    Instant.parse("2026-08-25T07:00:00Z"), true, null, List.of()));
            return extractionRepository.save(extraction).getItems().get(0).getId();
        });
    }

    private String seedCard(List<Long> itemIds) {
        String cardId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        anchors.record("msg-" + UUID.randomUUID(), cardId, USER_ID, itemIds);
        return cardId;
    }

    private List<OutboxEvent> replies() {
        return outboxRepository.findAll().stream()
                .filter(e -> NoteService.EVENT_PUSH_TEXT_REQUESTED.equals(e.getEventType()))
                .toList();
    }

    private String serialize(CalendarSyncPayload payload) {
        return objectMapper.writeValueAsString(payload);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
