package io.svra.command;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.when;

/**
 * 一句話多個動作時，動手的順序會不會咬到自己。
 *
 * <p>解析交給 LLM，這裡把 parser 換掉直接餵指令——要驗的是執行階段的行為，
 * 不是模型看不看得懂。
 */
@ExtendWith(MockitoExtension.class)
class MultiOpCommandTest {

    private static final String USER_ID = "U4af4980629";

    @Mock NoteRepository noteRepository;
    @Mock NoteExtractionRepository extractionRepository;
    @Mock NoteItemRepository itemRepository;
    @Mock NoteCommandParser parser;
    @Mock LinePushClient pushClient;
    @Mock OutboxEventRepository outboxRepository;
    @Mock ObjectMapper objectMapper;
    @Mock LlmRateLimiter rateLimiter;

    private NoteCommandService service;
    private NoteExtraction extraction;

    @BeforeEach
    void setUp() {
        service = new NoteCommandService(noteRepository, extractionRepository, itemRepository,
                Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneId.of("Asia/Taipei")),
                parser, pushClient, outboxRepository, objectMapper, rateLimiter);

        extraction = NoteExtraction.of(1L, "raw", "v-test");
        extraction.addItem(item("第一筆"));
        extraction.addItem(item("第二筆"));
        extraction.addItem(item("第三筆"));
        when(itemRepository.findUpcoming(anyString(), any()))
                .thenReturn(List.copyOf(extraction.getItems()));
    }

    private static NoteItem item(String title) {
        return new NoteItem(NoteCategory.TODO, title, null, null, List.of());
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
        when(noteRepository.findTopByLineUserIdOrderByIdDesc(USER_ID)).thenReturn(java.util.Optional.empty());

        execute(delete(2),
                new NoteCommand.Op(NoteCommand.Action.ADD, null, "新的一筆", null, "TODO"));

        assertThat(extraction.getItems())
                .extracting(NoteItem::getTitle)
                .containsExactly("第一筆", "第三筆");
    }
}
