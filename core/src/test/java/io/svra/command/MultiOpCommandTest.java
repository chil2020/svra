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
import io.svra.calendar.CalendarSyncPayload;
import io.svra.line.LinePushClient;
import io.svra.llm.LlmRateLimiter;
import io.svra.notify.Blocklist;
import io.svra.notify.CalendarCapability;
import io.svra.notify.CardRenderer;
import io.svra.notify.MessageAnchors;
import io.svra.notify.NoteNotifier;
import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteItemRepository;
import io.svra.note.NoteRepository;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @Mock OutboxEventRepository outboxRepository;
    /** 用真的 mapper：回覆現在寫進 outbox 的 payload，要驗內容就得序列化得出來。 */
    private final JsonMapper objectMapper = JsonMapper.builder().build();
    @Mock LlmRateLimiter rateLimiter;
    @Mock MessageAnchors anchors;
    /** 行事曆連動另外測——這裡要驗的是指令本身的行為。 */
    @Mock CalendarSync calendarSync;
    @Mock LinePushClient pushClient;
    /** 這裡的使用者當成授權過的——行事曆連動正是這些測試在驗的東西。 */
    @Mock CalendarCapability calendarCapability;
    @Mock Blocklist blocklist;
    @Mock PlatformTransactionManager transactionManager;

    private NoteCommandService service;
    private NoteExtraction extraction;

    @BeforeEach
    void setUp() {
        // notifier 用真的：回覆的內容（「已更新」vs「目前還有這些」、列了哪幾筆）
        // 正是這些測試在驗的東西，換成 mock 就等於驗一個 stub。
        NoteNotifier notifier = new NoteNotifier(noteRepository, extractionRepository,
                itemRepository, pushClient, anchors,
                new CardRenderer(objectMapper, calendarCapability), blocklist);

        service = new NoteCommandService(noteRepository, extractionRepository, itemRepository,
                executionRepository,
                Clock.fixed(Instant.parse("2026-08-18T09:00:00Z"), ZoneId.of("Asia/Taipei")),
                parser, outboxRepository, objectMapper, rateLimiter, anchors, notifier,
                calendarSync, transactionManager);

        extraction = NoteExtraction.of(1L, "raw", "v-test");
        // id 平常由資料庫給。少了它，指令會以為「引用沒對到」而退回整體清單。
        ReflectionTestUtils.setField(extraction, "id", 100L);
        extraction.addItem(item(1L, "第一筆"));
        extraction.addItem(item(2L, "第二筆"));
        extraction.addItem(item(3L, "第三筆"));

        // 用 thenAnswer 而不是凍結一份快照：真實的查詢會反映這個交易裡剛改完的樣子，
        // 回傳固定清單的話，驗「回覆內容」時看到的永遠是改動前的狀態。
        when(itemRepository.findUpcoming(anyString(), any()))
                .thenAnswer(inv -> List.copyOf(extraction.getItems()));
        // 第三段用 id 重新載入目標，回傳的就是同一批物件。
        when(itemRepository.findAllById(any()))
                .thenAnswer(inv -> List.copyOf(extraction.getItems()));
        // 沒執行過，而且這次搶到了。
        when(executionRepository.existsById(anyString())).thenReturn(false);
        when(executionRepository.insertIfAbsent(anyString())).thenReturn(1);
        when(calendarCapability.canImportDirectly(anyString())).thenReturn(true);
    }

    /**
     * id 平常由資料庫給。這裡要自己填：指令的目標解析走的就是 id
     * （編號 → 快照 id → 資料庫那一筆），少了它測到的會是另一條路。
     */
    private static NoteItem item(Long id, String title) {
        NoteItem item = new NoteItem(NoteCategory.TODO, title, null, null, null, List.of());
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private void execute(NoteCommand.Op... ops) {
        when(parser.parse(anyString(), any())).thenReturn(new NoteCommand(List.of(ops), null, null));
        service.applyCommand(new NoteCommandService.CommandPayload(USER_ID, "m1", "指令", null, null));
    }

    private void executeQuoting(String quotedMessageId, NoteCommand.Op... ops) {
        when(parser.parse(anyString(), any())).thenReturn(new NoteCommand(List.of(ops), null, null));
        service.applyCommand(new NoteCommandService.CommandPayload(
                USER_ID, "m1", "指令", quotedMessageId, null));
    }

    private static NoteCommand.Op delete(int index) {
        return new NoteCommand.Op(NoteCommand.Action.DELETE, index, null, null, null, null);
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
                new NoteCommand.Op(NoteCommand.Action.ADD, null, "新的一筆", null, null, "TODO"));

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
        assertThat(replyPayload()).contains("已經不在清單上");
    }

    @Test
    @DisplayName("第三段搶輸了（別人已經執行過這則指令）→ 一個字都不動")
    void skipsEverythingWhenAnotherRunAlreadyExecuted() {
        when(executionRepository.insertIfAbsent(anyString())).thenReturn(0);

        execute(delete(1));

        assertThat(extraction.getItems())
                .as("重跑不可以再刪一次——那會刪到別筆")
                .hasSize(3);
        verify(outboxRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("引用的訊息對不回任何一批 → 指名項目的動作不執行，並說出來")
    void refusesTargetedOpsWhenQuoteCannotBeResolved() {
        when(anchors.itemIdsFor(anyString())).thenReturn(Optional.empty());

        executeQuoting("unknown-message", delete(1));

        assertThat(extraction.getItems())
                .as("編號指的是整份清單，不是使用者眼前那則訊息——照做會確實地做錯事")
                .hasSize(3);
        assertThat(replyPayload()).contains("對不上");
    }

    @Test
    @DisplayName("引用的訊息還在，但那一筆已經被刪了 → 精確說是哪一筆，不要對看不見的資料動手")
    void reportsExactlyWhichQuotedItemIsGone() {
        // 錨點還在（那則訊息推播過），但項目本身已經不在了
        when(anchors.itemIdsFor(anyString())).thenReturn(Optional.of(List.of(1L, 2L, 3L)));
        when(itemRepository.findAllById(any())).thenReturn(List.of());

        executeQuoting("stale-message", delete(1));

        assertThat(extraction.getItems())
                .as("看不見的資料不能動——回「已刪除」而使用者什麼也看不到是最糟的")
                .hasSize(3);
        assertThat(replyPayload())
                .as("錨點知道使用者指的是哪一筆，就該精確說那一筆怎麼了，"
                        + "而不是籠統地說「你引用的我對不上」")
                .contains("第 1 筆已經不在清單上了");
    }

    @Test
    @DisplayName("引用對不上，但只是要看清單 → 照做。LIST 不指涉編號")
    void stillAnswersListWhenQuoteCannotBeResolved() {
        when(anchors.itemIdsFor(anyString())).thenReturn(Optional.empty());

        executeQuoting("unknown-message",
                new NoteCommand.Op(NoteCommand.Action.LIST, null, null, null, null, null));

        assertThat(replyPayload()).contains("目前還有這些");
    }

    @Test
    @DisplayName("回覆是調整後的清單，不是「改了什麼」的變更說明")
    void replyIsTheUpdatedListNotAChangeLog() {
        execute(delete(1));

        String reply = replyPayload();
        assertThat(reply)
                .as("使用者要的是那則訊息的新版本，清單本身就是確認")
                .contains("已更新")
                .contains("第二筆").contains("第三筆");
        assertThat(reply)
                .as("刪掉的那筆不該還出現在回覆裡")
                .doesNotContain("第一筆");
        assertThat(reply)
                .as("不要逐條交代做了什麼——那是要使用者照著說明反推結果")
                .doesNotContain("已刪除");
    }

    @Test
    @DisplayName("引用某一批時，回覆的是那一批的新版本，不混進其他語音的項目")
    void quotedCommandRepliesWithThatBatchOnly() {
        NoteItem other = item(9L, "別則語音的項目");
        when(itemRepository.findUpcoming(anyString(), any()))
                .thenReturn(List.of(other));   // 整體清單裡有別的東西
        when(anchors.itemIdsFor("push-1")).thenReturn(Optional.of(List.of(1L, 2L, 3L)));

        executeQuoting("push-1", delete(1));

        assertThat(replyPayload())
                .as("使用者盯著的是那則訊息，回的就該是它的新版本")
                .contains("第二筆")
                .doesNotContain("別則語音的項目");
    }

    @Test
    @DisplayName("只是要看清單 → 說「目前還有這些」，不能說「已更新」")
    void listOnlyDoesNotClaimAnUpdate() {
        execute(new NoteCommand.Op(NoteCommand.Action.LIST, null, null, null, null, null));

        assertThat(replyPayload())
                .as("什麼都沒改卻說已更新，就是宣稱一件沒發生的事")
                .contains("目前還有這些")
                .doesNotContain("已更新");
    }

    // ── 行事曆連動（決策 26）──────────────────────────────────────────

    @Test
    @DisplayName("🔴 刪掉已匯入的項目 → 同步意圖要<b>自帶</b> event id")
    void deletingAnImportedItemCarriesTheEventIdInTheIntent() {
        NoteItem imported = importedItem(4L, "已匯入的行程", "svra0000000000004");
        extraction.addItem(imported);

        // 🔴 編號 1 而不是 4：三筆基底是 TODO，而 NoteCategory.itemOrder()
        // 把 SCHEDULE 排在最前面。編號是「顯示順序」不是「加入順序」——
        // 這件事有 ItemNumberingConsistencyTest 專門守著。
        execute(delete(1));

        // orphanRemoval 一提交那一列就沒了，poller 兩秒後撿起事件時已經無從回查。
        // 意圖必須自足，這正是決策 3「先寫意圖」的字面意思。
        assertThat(calendarTargets())
                .containsExactly(CalendarSyncPayload.Target.delete("svra0000000000004"));
    }

    @Test
    @DisplayName("刪掉沒匯入過的項目 → 完全不寫同步意圖")
    void deletingANeverImportedItemQueuesNothing() {
        execute(delete(1));

        // 行事曆上本來就沒有它。寫下去只是製造註定 404 的請求，
        // 而 404 會被判成永久失敗、推一則假的失敗通知給使用者。
        verify(calendarSync).syncAfterCommand(anyString(), anyString(), eq(List.of()));
    }

    @Test
    @DisplayName("改已匯入項目的時間 → 寫一筆 UPSERT，讓行事曆跟著動")
    void reschedulingAnImportedItemQueuesAnUpsert() {
        NoteItem imported = importedItem(4L, "已匯入的行程", "svra0000000000004");
        extraction.addItem(imported);

        // 編號 1：SCHEDULE 排在三筆 TODO 前面（見上面那則測試的說明）
        execute(new NoteCommand.Op(NoteCommand.Action.UPDATE_TIME, 1, null,
                "2026-08-25T15:00:00+08:00", true, null));

        assertThat(calendarTargets())
                .containsExactly(CalendarSyncPayload.Target.upsert(4L));
        assertThat(imported.getOccursAt()).isEqualTo(Instant.parse("2026-08-25T07:00:00Z"));
    }

    @Test
    @DisplayName("改標題也要同步——行事曆上的 summary 就是標題")
    void renamingAnImportedItemQueuesAnUpsert() {
        NoteItem imported = importedItem(4L, "舊標題", "svra0000000000004");
        extraction.addItem(imported);

        execute(new NoteCommand.Op(NoteCommand.Action.UPDATE_TITLE, 1, "新標題",
                null, null, null));

        assertThat(calendarTargets()).containsExactly(CalendarSyncPayload.Target.upsert(4L));
    }

    @Test
    @DisplayName("新增的項目不會自動進行事曆——使用者沒按過那顆按鈕")
    void addedItemsAreNotSilentlyPushedToTheCalendar() {
        execute(new NoteCommand.Op(NoteCommand.Action.ADD, null, "新的一筆",
                "2026-08-25T15:00:00+08:00", true, "SCHEDULE"));

        verify(calendarSync).syncAfterCommand(anyString(), anyString(), eq(List.of()));
    }

    @Test
    @DisplayName("🔴 只講日期的改期，不該把定時事件變成全天事件")
    void reschedulingWithoutAClockKeepsTheOriginalPrecision() {
        NoteItem imported = importedItem(4L, "三點那筆", "svra0000000000004");
        extraction.addItem(imported);

        // 「改到下週三」——沒講幾點，所以模型回 timeSpecified=false，
        // 而 prompt 要它沿用原本的時刻。時刻既然是沿用的，精度也該沿用。
        execute(new NoteCommand.Op(NoteCommand.Action.UPDATE_TIME, 1, null,
                "2026-08-26T15:00:00+08:00", false, null));

        assertThat(imported.getTimeSpecified())
                .as("一律填 false 的話，使用者從沒要求過的「變成全天事件」就發生了")
                .isTrue();
    }

    /** 一筆有時間、而且已經匯進行事曆的項目。 */
    private static NoteItem importedItem(Long id, String title, String googleEventId) {
        NoteItem item = new NoteItem(NoteCategory.SCHEDULE, title,
                Instant.parse("2026-08-20T01:00:00Z"), true, null, List.of());
        ReflectionTestUtils.setField(item, "id", id);
        item.markCalendarEvent(googleEventId);
        return item;
    }

    /** 這一輪交給 calendar 模組的同步意圖。 */
    @SuppressWarnings("unchecked")
    private List<CalendarSyncPayload.Target> calendarTargets() {
        ArgumentCaptor<List<CalendarSyncPayload.Target>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(calendarSync).syncAfterCommand(anyString(), anyString(), captor.capture());
        return captor.getValue();
    }

    /** 這一輪寫進 outbox 的回覆內容。回覆改由 outbox 送出之後，驗的是它而不是 push。 */
    private String replyPayload() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PUSH_TEXT_REQUESTED");
        return captor.getValue().getPayload();
    }
}
