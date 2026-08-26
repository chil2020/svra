package io.svra.command;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

import io.svra.calendar.CalendarSync;
import io.svra.line.LinePushClient;
import io.svra.llm.LlmRateLimiter;
import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteItemRepository;
import io.svra.note.NoteRepository;
import io.svra.notify.CalendarCapability;
import io.svra.notify.CardRenderer;
import io.svra.notify.MessageAnchors;
import io.svra.notify.NoteNotifier;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;
import io.svra.user.Users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 圖文選單的按鈕送出的是<b>永遠一模一樣的字串</b>，所以那條路不必經過 LLM。
 *
 * <p>這一組守的是三件事，而它們的失敗方式都<b>不會報錯</b>：
 * <ul>
 * <li>相符時真的沒去打模型——打了的話只是慢，功能照樣對，沒有人會發現</li>
 * <li>相符時<b>沒有消耗 LLM 的限流額度</b>——這是最容易寫錯的一行，
 * 而症狀是「傳了幾則語音之後，按鈕就按不動了」</li>
 * <li>差一個字要原封不動落回 LLM——快速路徑只認完全相符</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuickCommandTest {

    private static final String USER_ID = "U4af4980629";

    @Mock NoteRepository noteRepository;
    @Mock NoteExtractionRepository extractionRepository;
    @Mock NoteItemRepository itemRepository;
    @Mock CommandExecutionRepository executionRepository;
    @Mock NoteCommandParser parser;
    @Mock OutboxEventRepository outboxRepository;
    @Mock LlmRateLimiter rateLimiter;
    @Mock MessageAnchors anchors;
    @Mock CalendarSync calendarSync;
    @Mock LinePushClient pushClient;
    @Mock CalendarCapability calendarCapability;
    @Mock Users users;
    @Mock PlatformTransactionManager transactionManager;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private NoteCommandService service;

    @BeforeEach
    void setUp() {
        NoteNotifier notifier = new NoteNotifier(noteRepository, extractionRepository,
                itemRepository, pushClient, anchors,
                new CardRenderer(objectMapper, calendarCapability), users);

        service = new NoteCommandService(noteRepository, extractionRepository, itemRepository,
                executionRepository,
                Clock.fixed(Instant.parse("2026-08-26T09:00:00Z"), ZoneId.of("Asia/Taipei")),
                parser, outboxRepository, objectMapper, rateLimiter, anchors, notifier,
                calendarSync, transactionManager);

        NoteExtraction extraction = NoteExtraction.of(1L, "raw", "v-test");
        ReflectionTestUtils.setField(extraction, "id", 100L);
        extraction.addItem(item(1L, "開會"));

        when(itemRepository.findUpcoming(anyString(), any()))
                .thenAnswer(inv -> List.copyOf(extraction.getItems()));
        when(itemRepository.findAllByIdAndUser(anyString(), any()))
                .thenAnswer(inv -> List.copyOf(extraction.getItems()));
        when(noteRepository.findTopByLineUserIdOrderByIdDesc(USER_ID))
                .thenReturn(Optional.of(extraction).map(e -> null));
        when(executionRepository.existsById(anyString())).thenReturn(false);
        when(executionRepository.insertIfAbsent(anyString(), any())).thenReturn(1);
        when(calendarCapability.canImportDirectly(anyString())).thenReturn(false);
    }

    // ── 解析本身 ──────────────────────────────────────────────────

    @Test
    @DisplayName("完全相符才算，前後空白不算差異")
    void resolvesOnlyExactMatches() {
        assertThat(QuickCommand.resolve("列出行程")).isEqualTo(QuickCommand.LIST);
        assertThat(QuickCommand.resolve("  列出行程  ")).isEqualTo(QuickCommand.LIST);
        assertThat(QuickCommand.resolve("使用說明")).isEqualTo(QuickCommand.HELP);

        assertThat(QuickCommand.resolve("列出行程！")).isNull();
        assertThat(QuickCommand.resolve("幫我列出行程")).isNull();
        assertThat(QuickCommand.resolve("列出")).isNull();
        assertThat(QuickCommand.resolve("")).isNull();
        assertThat(QuickCommand.resolve(null)).isNull();
    }

    // ── 列出行程 ──────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 「列出行程」不呼叫 LLM")
    void listSkipsTheModel() {
        execute("列出行程");

        verify(parser, never()).parse(any(), any());
    }

    @Test
    @DisplayName("🔴 「列出行程」不消耗 LLM 的限流額度")
    void listDoesNotSpendTheLlmBudget() {
        execute("列出行程");

        // 🔴 這一行是整個功能最容易寫錯的地方。限流保護的是「只有一份」的 Ollama，
        // 而這條路根本沒碰它——照樣扣額度的話，使用者傳了幾則語音撞到上限之後，
        // **連按鈕都按不動了**，而按鈕做的事跟 Ollama 一點關係都沒有。
        verify(rateLimiter, never()).consume(anyString());
    }

    @Test
    @DisplayName("「列出行程」照樣回一份清單")
    void listStillReplies() {
        execute("列出行程");

        assertThat(savedPayload()).contains("開會");
    }

    @Test
    @DisplayName("差一個字就整句落回 LLM，額度也照扣")
    void aNearMissFallsBackToTheModel() {
        when(parser.parse(any(), any())).thenReturn(NoteCommand.listOnly());

        execute("列出行程！");

        verify(parser).parse(any(), any());
        verify(rateLimiter).consume(USER_ID);
    }

    // ── 使用說明 ──────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 「使用說明」回的是說明本文，而且完全不碰 LLM")
    void helpRepliesWithTheSharedText() {
        execute("使用說明");

        assertThat(savedPayload()).contains("整理成行程、待辦和想法");
        verify(parser, never()).parse(any(), any());
        verify(rateLimiter, never()).consume(anyString());
    }

    @Test
    @DisplayName("說明重投一次不會回兩份——不改資料不等於可以重複做")
    void helpIsIdempotent() {
        when(executionRepository.insertIfAbsent(anyString(), any())).thenReturn(0);

        execute("使用說明");

        verify(outboxRepository, never()).save(any());
    }

    // ── 工具 ──────────────────────────────────────────────────────

    private void execute(String text) {
        service.applyCommand(new NoteCommandService.CommandPayload(
                USER_ID, "m-" + text.hashCode(), text, null, "reply-token"));
    }

    private String savedPayload() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue().getPayload();
    }

    private static NoteItem item(Long id, String title) {
        NoteItem noteItem = new NoteItem(NoteCategory.SCHEDULE, title,
                Instant.parse("2026-08-27T02:00:00Z"), true, null, List.of());
        ReflectionTestUtils.setField(noteItem, "id", id);
        return noteItem;
    }
}
