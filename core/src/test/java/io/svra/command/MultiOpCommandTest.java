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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import io.svra.line.LinePushClient;
import io.svra.llm.LlmRateLimiter;
import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteItemRepository;
import io.svra.note.NoteRepository;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一句話多個動作時，動手的順序會不會咬到自己。
 *
 * <p>解析交給 LLM，這裡把 parser 換掉直接餵指令——要驗的是執行階段的行為，
 * 不是模型看不看得懂。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiOpCommandTest {

    private static final String USER_ID = "U4af4980629";

    @Mock NoteRepository noteRepository;
    @Mock NoteExtractionRepository extractionRepository;
    @Mock NoteItemRepository itemRepository;
    @Mock CommandExecutionRepository executionRepository;
    @Mock NoteCommandParser parser;
    @Mock LinePushClient pushClient;
    @Mock OutboxEventRepository outboxRepository;
    @Mock ObjectMapper objectMapper;
    @Mock LlmRateLimiter rateLimiter;
    @Mock PlatformTransactionManager transactionManager;

    private NoteCommandService service;
    private NoteExtraction extraction;

    @BeforeEach
    void setUp() {
        service = new NoteCommandService(noteRepository, extractionRepository, itemRepository,
                executionRepository,
                Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneId.of("Asia/Taipei")),
                parser, pushClient, outboxRepository, objectMapper, rateLimiter,
                transactionManager);

        extraction = NoteExtraction.of(1L, "raw", "v-test");
        extraction.addItem(item(1L, "第一筆"));
        extraction.addItem(item(2L, "第二筆"));
        extraction.addItem(item(3L, "第三筆"));

        when(itemRepository.findUpcoming(anyString(), any()))
                .thenReturn(List.copyOf(extraction.getItems()));
        // 第三段用 id 重新載入目標，回傳的就是同一批物件。
        when(itemRepository.findAllById(any()))
                .thenReturn(List.copyOf(extraction.getItems()));
        // 沒執行過，而且這次搶到了。
        when(executionRepository.existsById(anyString())).thenReturn(false);
        when(executionRepository.insertIfAbsent(anyString())).thenReturn(1);
    }

    /**
     * id 平常由資料庫給。這裡要自己填：指令的目標解析走的就是 id
     * （編號 → 快照 id → 資料庫那一筆），少了它測到的會是另一條路。
     */
    private static NoteItem item(Long id, String title) {
        NoteItem item = new NoteItem(NoteCategory.TODO, title, null, null, List.of());
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private void execute(NoteCommand.Op... ops) {
        when(parser.parse(anyString(), any())).thenReturn(new NoteCommand(List.of(ops), null, null));
        service.applyCommand(new NoteCommandService.CommandPayload(USER_ID, "m1", "指令", null));
    }

    private static NoteCommand.Op delete(int index) {
        return new NoteCommand.Op(NoteCommand.Action.DELETE, index, null, null, null);
    }

    @Test
    @DisplayName("刪掉第一筆跟第三筆——不會因為前面刪掉了就刪錯後面那筆")
    void deletesByOriginalNumbering() {
        execute(delete(1), delete(3));

        assertThat(extraction.getItems())
                .extracting(NoteItem::getTitle)
                .containsExactly("第二筆");
    }

    @Test
    @DisplayName("刪除與新增混在一句話裡，兩件事都要做到")
    void appliesDeleteAndAddTogether() {
        when(noteRepository.findTopByLineUserIdOrderByIdDesc(USER_ID)).thenReturn(Optional.empty());

        execute(delete(2),
                new NoteCommand.Op(NoteCommand.Action.ADD, null, "新的一筆", null, "TODO"));

        assertThat(extraction.getItems())
                .extracting(NoteItem::getTitle)
                .containsExactly("第一筆", "第三筆");
    }

    @Test
    @DisplayName("解析期間那一筆被刪掉了 → 說出來，不要假裝做到了")
    void reportsTargetThatDisappearedDuringParsing() {
        // 第一段看到三筆，第三段重新載入時第二筆已經不在了
        when(itemRepository.findAllById(any())).thenReturn(
                List.of(extraction.getItems().get(0), extraction.getItems().get(2)));

        execute(delete(2));

        assertThat(extraction.getItems()).hasSize(3);
        verify(pushClient).pushText(anyString(), org.mockito.ArgumentMatchers.contains("已經不在清單上"));
    }

    @Test
    @DisplayName("第三段搶輸了（別人已經執行過這則指令）→ 一個字都不動")
    void skipsEverythingWhenAnotherRunAlreadyExecuted() {
        when(executionRepository.insertIfAbsent(anyString())).thenReturn(0);

        execute(delete(1));

        assertThat(extraction.getItems())
                .as("重跑不可以再刪一次——那會刪到別筆")
                .hasSize(3);
        verify(pushClient, never()).pushText(anyString(), anyString());
    }
}
