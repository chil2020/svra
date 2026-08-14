package io.svra.note;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.svra.mq.TranscribeResult;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    public static final String EVENT_TRANSCRIBE_REQUESTED = "TRANSCRIBE_REQUESTED";

    private final NoteRepository noteRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public NoteService(NoteRepository noteRepository,
            OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.noteRepository = noteRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 記錄一則剛收到的語音訊息，並在<b>同一個交易裡</b>寫下「要送轉錄任務」的意圖。
     *
     * <p>
     * 兩者同進同退是 outbox 的重點：交易提交 = 意圖已持久化，
     * 之後 poller 負責真的送出去。RabbitMQ 掛掉只會延遲，不會讓 note 卡在 PENDING。
     *
     * <p>
     * LINE 的 webhook 是 at-least-once，同一個 sourceMessageId 呼叫幾次都只留一筆。
     *
     * @return true = 第一次收到；false = 重複投遞
     */
    @Transactional
    public boolean recordIncoming(String lineUserId, String sourceMessageId) {
        try {
            noteRepository.save(Note.pending(lineUserId, sourceMessageId));
            outboxRepository.save(OutboxEvent.pending(
                    sourceMessageId,
                    EVENT_TRANSCRIBE_REQUESTED,
                    toPayload(lineUserId, sourceMessageId)));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 只接這一個。用 catch (Exception) 的話，連線失敗會被當成「已處理過」
            // 而回 200，LINE 就不再重送——訊息永久遺失且無人察覺。
            log.debug("重複訊息，已忽略：messageId={}", sourceMessageId);
            return false;
        }
    }

    private String toPayload(String lineUserId, String sourceMessageId) {
        try {
            return objectMapper.writeValueAsString(
                    new TranscribeRequested(lineUserId, sourceMessageId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 outbox payload 失敗", e);
        }
    }

    /** 存進 outbox 的事件內容。 */
    public record TranscribeRequested(String lineUserId, String sourceMessageId) {
    }

    /**
     * 把轉錄結果寫回對應的 note。
     *
     * <p>
     * 🔴 承重點：必須冪等。outbox 是 at-least-once，同一個結果可能回來兩次。
     */
    @Transactional
    public void applyTranscription(TranscribeResult result) {
        noteRepository.findBySourceMessageId(result.jobId()).ifPresentOrElse(
                note -> {
                    // 已完成就不覆蓋。結果可能重複回來（outbox 是 at-least-once），
                    // whisper 用 beam search 兩次結果可能有微小差異，
                    // 讓使用者看到的內容莫名其妙變動不划算。
                    if (note.getStatus() != NoteStatus.COMPLETED) {
                        note.complete(result.text(), result.language(), result.audioDurationSec());
                    }
                },
                // 不能丟例外——listener 拋出去會讓訊息 requeue 成無限迴圈。
                // 也補不出新的 note：結果訊息裡沒有 lineUserId，而該欄位是 NOT NULL。
                () -> log.error("找不到對應的 note：messageId={}", result.jobId()));
    }

    /**
     * outbox 徹底放棄時，讓 note 也有終局——否則它會永遠停在 PENDING，
     * 使用者傳了語音卻等不到任何結果，也沒有人知道它已經被放棄了。
     *
     * <p>
     * 失敗的原因不存在 note 上：{@code outbox_events.last_error} 已經有了，
     * 兩邊用 source_message_id 對得起來。
     */
    @Transactional
    public void markTranscriptionFailed(String sourceMessageId) {
        noteRepository.findBySourceMessageId(sourceMessageId).ifPresentOrElse(
                note -> {
                    note.fail();
                    log.warn("轉錄放棄，note 標記為 FAILED：messageId={}", sourceMessageId);
                },
                () -> log.error("要標記失敗但找不到 note：messageId={}", sourceMessageId));
    }
}
