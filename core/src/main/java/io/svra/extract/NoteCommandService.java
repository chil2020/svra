package io.svra.extract;

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

/** 處理使用者用文字下的指令（刪除、改標題、改時間）。 */
@Service
public class NoteCommandService {

    private static final Logger log = LoggerFactory.getLogger(NoteCommandService.class);

    private final NoteRepository noteRepository;
    private final NoteExtractionRepository extractionRepository;
    private final NoteCommandParser parser;
    private final LinePushClient pushClient;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public NoteCommandService(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
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
        NoteExtraction extraction = resolveTarget(payload);
        if (extraction == null) {
            pushClient.pushText(payload.lineUserId(), "找不到可以修改的筆記，先傳一則語音吧。");
            return;
        }

        List<NoteItem> items = extraction.getItems();
        NoteCommand command = parser.parse(payload.text(), items);

        String reply = switch (command.action()) {
            case DELETE -> {
                NoteItem target = items.get(command.itemIndex() - 1);
                extraction.removeItem(target);
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
            case UNKNOWN -> "🤔 " + (command.reason() == null ? "看不懂這個指令。" : command.reason());
        };

        pushClient.pushText(payload.lineUserId(), reply);
        log.info("指令已處理：action={} messageId={}", command.action(), payload.commandMessageId());
    }

    /**
     * 有引用就用引用的那則推播精準定位；沒引用就退回這位使用者最近一次的抽取。
     */
    private NoteExtraction resolveTarget(CommandPayload payload) {
        if (payload.quotedMessageId() != null) {
            NoteExtraction quoted = extractionRepository
                    .findByNotifyMessageId(payload.quotedMessageId()).orElse(null);
            if (quoted != null) {
                return quoted;
            }
            log.debug("引用的訊息找不到對應抽取，退回最近一次：quoted={}", payload.quotedMessageId());
        }
        return noteRepository.findTopByLineUserIdOrderByIdDesc(payload.lineUserId())
                .flatMap(note -> extractionRepository.findByNoteIdAndActiveTrue(note.getId()))
                .orElse(null);
    }
}
