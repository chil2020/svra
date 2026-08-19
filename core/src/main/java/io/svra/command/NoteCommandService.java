package io.svra.command;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.line.LinePushClient;
import io.svra.llm.LlmRateLimiter;
import io.svra.note.NoteRepository;
import io.svra.note.NoteService;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteCategory;
import io.svra.note.NoteItemRepository;
import io.svra.notify.NoteNotifier;

/** 處理使用者用文字下的指令（刪除、改標題、改時間、列出清單）。 */
@Service
public class NoteCommandService {

    private static final Logger log = LoggerFactory.getLogger(NoteCommandService.class);

    private final NoteRepository noteRepository;
    private final NoteExtractionRepository extractionRepository;
    private final NoteCommandParser parser;
    private final LinePushClient pushClient;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final NoteItemRepository itemRepository;
    private final LlmRateLimiter rateLimiter;
    private final Clock clock;

    public NoteCommandService(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            NoteItemRepository itemRepository,
            Clock clock,
            NoteCommandParser parser,
            LinePushClient pushClient,
            OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper,
            LlmRateLimiter rateLimiter) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.parser = parser;
        this.pushClient = pushClient;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.itemRepository = itemRepository;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * 記下使用者的指令。解析要呼叫 LLM，不能拖慢 webhook——這裡只寫意圖，
     * 跟語音訊息一樣交給 outbox 非同步處理。
     */
    @Transactional
    public void recordCommand(String lineUserId, String commandMessageId,
            String text, String quotedMessageId) {
        // 🔴 指令是這個系統裡唯一沒有其他去重機制的入口。
        // 語音有 notes.source_message_id 的唯一約束擋著重送，文字訊息不建 note，
        // 只寫一筆 outbox——沒有冪等鍵的話 LINE 逾時重送就是執行兩次「刪掉第一筆」。
        // 這裡曾經寫著 catch (DataIntegrityViolationException)，但表上根本沒有
        // 約束可以違反，那是一段永遠不會執行的死碼。
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new CommandPayload(
                    lineUserId, commandMessageId, text, quotedMessageId));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化指令 payload 失敗", e);
        }

        if (outboxRepository.insertIfAbsent(
                commandMessageId,
                NoteService.EVENT_COMMAND_REQUESTED,
                payload,
                OutboxEvent.dedupeKeyFor(
                        NoteService.EVENT_COMMAND_REQUESTED, commandMessageId)) == 0) {
            log.debug("重複的指令訊息，已忽略：messageId={}", commandMessageId);
        }
    }

    /** 存進 outbox 的指令內容。 */
    public record CommandPayload(
            String lineUserId,
            String commandMessageId,
            String text,
            String quotedMessageId) {
    }

    @Transactional
    public void applyCommand(CommandPayload payload) {
        // 有引用就鎖定那一批——編號跟那則推播上的一致。
        // 沒有引用時要看的是「目前還有什麼」，那會跨越多則語音，不是最後一則而已。
        NoteExtraction quoted = resolveQuoted(payload);
        List<NoteItem> items = quoted != null ? quoted.getOrderedItems() : upcoming(payload.lineUserId());

        // 指令解析也是一次 LLM 呼叫，算同一份額度。
        rateLimiter.consume(payload.lineUserId());

        NoteCommand command = parser.parse(payload.text(), items);
        if (command.isUnknown()) {
            pushClient.pushText(payload.lineUserId(), "🤔 " + command.reason());
            return;
        }

        // 🔴 先把編號解析成項目，再開始動手。
        // 邊刪邊用編號取值的話，「刪掉第一筆跟第三筆」會刪錯第二筆——
        // 刪掉第一筆之後，原本的第三筆已經變成第二筆了。
        // 只有指名項目的動作要解析目標。ADD 與 LIST 不指涉任何一筆，
        // 但模型仍可能在那些動作上填 itemIndex——不能因為它填了就當真。
        List<NoteItem> targets = command.ops().stream()
                .map(op -> needsTarget(op.action()) ? items.get(op.itemIndex() - 1) : null)
                .toList();

        StringBuilder reply = new StringBuilder();
        boolean listRequested = false;

        for (int i = 0; i < command.ops().size(); i++) {
            NoteCommand.Op op = command.ops().get(i);
            NoteItem target = targets.get(i);
            switch (op.action()) {
                case DELETE -> {
                    target.getExtraction().removeItem(target);
                    reply.append("🗑 已刪除：").append(target.getTitle()).append('\n');
                }
                case UPDATE_TITLE -> {
                    String before = target.getTitle();
                    target.rename(op.title());
                    reply.append("✏️ 已改標題：").append(before)
                            .append(" → ").append(op.title()).append('\n');
                }
                case UPDATE_TIME -> {
                    target.reschedule(NoteCommandParser.parseOccursAt(op.occursAt()));
                    reply.append("🕘 已改時間：").append(target.getTitle()).append('\n');
                }
                case ADD -> {
                    NoteExtraction owner = quoted != null ? quoted : latestExtraction(payload.lineUserId());
                    if (owner == null) {
                        reply.append("⚠️ 還沒有任何筆記可以加進去，先傳一則語音吧。\n");
                        break;
                    }
                    NoteItem added = new NoteItem(
                            NoteCommandParser.parseCategory(op.category()), op.title(),
                            NoteCommandParser.parseOccursAt(op.occursAt()), null, List.of());
                    owner.addItem(added);
                    reply.append("➕ 已新增：").append(op.title()).append('\n');
                }
                case LIST -> listRequested = true;
            }
        }

        if (listRequested) {
            // 重新查一次而不是沿用上面那份——這一輪可能剛改過東西，
            // 要秀的是改完之後的樣子。
            if (!reply.isEmpty()) {
                reply.append('\n');
            }
            reply.append(NoteNotifier.renderCurrent(upcoming(payload.lineUserId())));
        }

        // 只做了一半就要說——不然使用者會以為都交代了。
        if (command.unhandled() != null && !command.unhandled().isBlank()) {
            reply.append("\n⚠️ 這部分我還不會處理：").append(command.unhandled());
        }

        pushClient.pushText(payload.lineUserId(), reply.toString().strip());
        log.info("指令已處理：{} 個動作 messageId={}", command.ops().size(), payload.commandMessageId());
    }

    private static boolean needsTarget(NoteCommand.Action action) {
        return action == NoteCommand.Action.DELETE
                || action == NoteCommand.Action.UPDATE_TITLE
                || action == NoteCommand.Action.UPDATE_TIME;
    }

    /** 使用者引用了某則推播時，精準定位到那一批；沒引用回 null。 */
    private NoteExtraction resolveQuoted(CommandPayload payload) {
        if (payload.quotedMessageId() == null) {
            return null;
        }
        NoteExtraction quoted = extractionRepository
                .findByNotifyMessageId(payload.quotedMessageId()).orElse(null);
        if (quoted == null) {
            log.debug("引用的訊息找不到對應抽取，改用整體清單：quoted={}", payload.quotedMessageId());
        }
        return quoted;
    }

    /** 新增的項目要掛在某一批抽取底下——沒指定就掛最近那批。 */
    private NoteExtraction latestExtraction(String lineUserId) {
        return noteRepository.findTopByLineUserIdOrderByIdDesc(lineUserId)
                .flatMap(note -> extractionRepository.findByNoteIdAndActiveTrue(note.getId()))
                .orElse(null);
    }

    /** 這位使用者目前還有效的項目，跨所有語音，依顯示順序排好。 */
    private List<NoteItem> upcoming(String lineUserId) {
        return itemRepository.findUpcoming(lineUserId, Instant.now(clock))
                .stream().sorted(NoteCategory.itemOrder()).toList();
    }
}
