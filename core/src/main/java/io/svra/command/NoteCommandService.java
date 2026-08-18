package io.svra.command;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.line.LinePushClient;
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
    private final Clock clock;

    public NoteCommandService(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            NoteItemRepository itemRepository,
            Clock clock,
            NoteCommandParser parser,
            LinePushClient pushClient,
            OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.parser = parser;
        this.pushClient = pushClient;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.itemRepository = itemRepository;
        this.clock = clock;
    }

    /**
     * 記下使用者的指令。解析要呼叫 LLM，不能拖慢 webhook——這裡只寫意圖，
     * 跟語音訊息一樣交給 outbox 非同步處理。
     */
    @Transactional
    public void recordCommand(String lineUserId, String commandMessageId,
            String text, String quotedMessageId) {
        try {
            outboxRepository.save(OutboxEvent.pending(
                    commandMessageId,
                    NoteService.EVENT_COMMAND_REQUESTED,
                    objectMapper.writeValueAsString(new CommandPayload(
                            lineUserId, commandMessageId, text, quotedMessageId))));
        } catch (DataIntegrityViolationException e) {
            log.debug("重複的指令訊息，已忽略：messageId={}", commandMessageId);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化指令 payload 失敗", e);
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

        if (items.isEmpty()) {
            pushClient.pushText(payload.lineUserId(), "目前沒有任何項目，先傳一則語音吧。");
            return;
        }
        NoteCommand command = parser.parse(payload.text(), items);

        String reply;
        reply = switch (command.action()) {
            case DELETE -> {
                NoteItem target = items.get(command.itemIndex() - 1);
                target.getExtraction().removeItem(target);
                yield "🗑 已刪除：" + target.getTitle();
            }
            case UPDATE_TITLE -> {
                NoteItem target = items.get(command.itemIndex() - 1);
                String before = target.getTitle();
                target.rename(command.newTitle());
                yield "✏️ 已改標題：\n" + before + "\n→ " + command.newTitle();
            }
            case UPDATE_TIME -> {
                NoteItem target = items.get(command.itemIndex() - 1);
                target.reschedule(NoteCommandParser.parseOccursAt(command.newOccursAt()));
                yield "🕘 已改時間：" + target.getTitle();
            }
            case LIST -> null;   // 清單稍後重繪：這一輪可能還做了修改，要反映修改後的結果
            case UNKNOWN -> "🤔 " + (command.reason() == null ? "看不懂這個指令。" : command.reason());
        };

        if (command.action() == NoteCommand.Action.LIST) {
            // 重新讀一次而不是沿用上面的 items——LIST 也可能跟修改指令一起下，
            // 要秀的是「改完之後」的樣子。
            reply = NoteNotifier.render(upcoming(payload.lineUserId()));
        }

        // 只做了一半就要說——不然使用者會以為兩件事都交代了。
        if (command.unhandled() != null && !command.unhandled().isBlank()) {
            reply += "\n\n⚠️ 這部分我還不會處理：" + command.unhandled();
        }

        pushClient.pushText(payload.lineUserId(), reply);
        log.info("指令已處理：action={} messageId={}", command.action(), payload.commandMessageId());
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

    /** 這位使用者目前還有效的項目，跨所有語音，依顯示順序排好。 */
    private List<NoteItem> upcoming(String lineUserId) {
        return itemRepository.findUpcoming(lineUserId, Instant.now(clock))
                .stream().sorted(NoteCategory.itemOrder()).toList();
    }
}
