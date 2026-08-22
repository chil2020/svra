package io.svra.note;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.SvraProperties;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    public static final String EVENT_TRANSCRIBE_REQUESTED = "TRANSCRIBE_REQUESTED";
    public static final String EVENT_EXTRACT_REQUESTED = "EXTRACT_REQUESTED";
    public static final String EVENT_NOTIFY_REQUESTED = "NOTIFY_REQUESTED";
    public static final String EVENT_COMMAND_REQUESTED = "COMMAND_REQUESTED";
    /**
     * 「把這段文字推給使用者」。刻意不帶領域資訊——產生事件的模組決定要說什麼，
     * notify 只負責送到。有了它，任何模組都能把「要回覆」寫進自己的交易裡，
     * 而不必在交易中間打 LINE。
     */
    public static final String EVENT_PUSH_TEXT_REQUESTED = "PUSH_TEXT_REQUESTED";
    /**
     * 「把這幾筆的狀態同步到 Google 行事曆」。
     *
     * <p>兩個來源共用同一個型別：使用者按下卡片上的匯入鈕，以及指令改動了
     * 已經匯入過的項目。兩者要做的事一樣（寫進去或刪掉），差別只在
     * 「完成後要不要回覆使用者」，而那是 payload 的欄位，不是另一種事件。
     */
    public static final String EVENT_CALENDAR_SYNC_REQUESTED = "CALENDAR_SYNC_REQUESTED";

    private final NoteRepository noteRepository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    /** 收回訊息時要連音檔一起刪，而音檔的位置只有這裡知道。 */
    private final SvraProperties svra;

    public NoteService(NoteRepository noteRepository,
            OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper,
            SvraProperties svra) {
        this.noteRepository = noteRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.svra = svra;
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
        if (noteRepository.insertPendingIfAbsent(lineUserId, sourceMessageId) == 0) {
            // 🔴 INFO 而不是 DEBUG。這是決策 2 唯一會留下痕跡的地方——
            // 冪等真的擋下了一次重送。更實際的理由是：**重複投遞突然變頻繁是個訊號**
            // （代表 webhook 在逾時，LINE 才會重送），而 DEBUG 等於永遠不會注意到。
            log.info("重複投遞，已忽略——冪等擋下（LINE 是 at-least-once，這是正常現象）");
            return false;
        }

        // 走到這裡代表這個執行緒是唯一建立成功的那一個，所以 outbox 也不會撞號。
        // 仍然填冪等鍵：讓「這個事件只該發生一次」寫在事件上，而不是要讀者
        // 自己推論出 notes 有約束、所以 outbox 不會重複。
        outboxRepository.insertIfAbsent(
                sourceMessageId,
                EVENT_TRANSCRIBE_REQUESTED,
                toPayload(lineUserId, sourceMessageId),
                OutboxEvent.dedupeKeyFor(EVENT_TRANSCRIBE_REQUESTED, sourceMessageId));
        log.info("outbox 寫入：{}（與 note 同交易）", EVENT_TRANSCRIBE_REQUESTED);
        return true;
    }

    /**
     * 使用者收回了那則語音——把我們留下的東西一併忘掉。
     *
     * <p>LINE 的開發指南明確要求處理收回事件：
     * <i>"Process unsend events to respect user preferences and prevent unsent
     * messages from remaining accessible."</i>
     *
     * <p>對這個系統而言那不只是禮貌。他按下收回的時候，語音早就轉錄完、
     * 逐字稿與抽出來的行程都已經在資料庫裡了。<b>自己用無所謂，別人的語音就是另一回事。</b>
     *
     * <p><b>刪掉之後不回覆。</b>他收回訊息的意思就是「當作沒發生過」，
     * 這時候跳出一則「已經幫你刪掉囉」只是把那件事又講了一次。log 記得住就夠。
     *
     * <p>音檔也要刪。轉錄成功後 worker 本來就會刪（{@code WHISPER_DELETE_AUDIO}），
     * 但收回可能發生在轉錄之前——那時候檔案還躺在共用目錄裡。
     */
    @Transactional
    public void forgetMessage(String lineUserId, String sourceMessageId) {
        int removed = noteRepository.deleteBySourceMessage(lineUserId, sourceMessageId);
        deleteAudioIfPresent(sourceMessageId);
        if (removed > 0) {
            // 不記內容，只記發生過——那是唯一能證明我們真的忘了的痕跡。
            log.info("使用者收回訊息，已刪除對應的筆記與音檔");
        } else {
            // 收回貼圖、收回文字指令都會走到這裡，而那些本來就沒留下東西。
            log.debug("使用者收回的訊息沒有對應的筆記，不需要處理");
        }
    }

    /**
     * 刪不掉不能讓整件事失敗：資料庫那一半才是重點，而它已經提交了。
     * 檔案殘留是可以事後清的，把例外往外拋只會讓 webhook 回 500 讓 LINE 重送。
     */
    private void deleteAudioIfPresent(String sourceMessageId) {
        try {
            java.nio.file.Files.deleteIfExists(
                    java.nio.file.Path.of(svra.audioDir(), sourceMessageId + ".m4a"));
        } catch (java.io.IOException e) {
            log.warn("音檔刪不掉，要人工清理：messageId={}", sourceMessageId, e);
        }
    }

    public String toPayload(String lineUserId, String sourceMessageId) {
        try {
            return objectMapper.writeValueAsString(
                    new NoteEventPayload(lineUserId, sourceMessageId));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 outbox payload 失敗", e);
        }
    }

    /** 三種事件共用的 payload：轉錄、抽取、推播都只需要這兩個欄位。 */
    public record NoteEventPayload(String lineUserId, String sourceMessageId) {
    }

    /**
     * 把轉錄結果寫回對應的 note。
     *
     * <p>刻意收下四個值而不是 {@code TranscribeResult}——那是 RabbitMQ 的訊息格式，
     * 帶著 {@code @JsonProperty} 與 status／elapsedSec 這些傳輸層的東西。
     * 領域不該認識傳輸格式，翻譯由 listener 做。
     *
     * <p>
     * 🔴 承重點：必須冪等。outbox 是 at-least-once，同一個結果可能回來兩次。
     */
    @Transactional
    public void applyTranscription(String sourceMessageId, String text,
            String language, Float audioDurationSec) {
        noteRepository.findBySourceMessageId(sourceMessageId).ifPresentOrElse(
                note -> {
                    // 已完成就不覆蓋。結果可能重複回來（outbox 是 at-least-once），
                    // whisper 用 beam search 兩次結果可能有微小差異，
                    // 讓使用者看到的內容莫名其妙變動不划算。
                    if (note.getStatus() != NoteStatus.COMPLETED) {
                        note.complete(text, language, audioDurationSec);
                        // 抽取要呼叫 LLM，好幾秒。放這裡會拖長交易也卡住 listener——
                        // 跟「下載音檔不放在 webhook」同一個判斷，交給 outbox 非同步做。
                        outboxRepository.save(OutboxEvent.pending(
                                sourceMessageId,
                                EVENT_EXTRACT_REQUESTED,
                                toPayload(note.getLineUserId(), sourceMessageId)));
                    }
                },
                // 不能丟例外——listener 拋出去會讓訊息 requeue 成無限迴圈。
                // 也補不出新的 note：結果訊息裡沒有 lineUserId，而該欄位是 NOT NULL。
                () -> log.error("找不到對應的 note：messageId={}", sourceMessageId));
    }

    /**
     * 轉錄徹底放棄時，讓 note 也有終局——否則它會永遠停在 PENDING，
     * 使用者傳了語音卻等不到任何結果，也沒有人知道它已經被放棄了。
     *
     * <p>
     * 失敗的原因不存在 note 上：{@code outbox_events.last_error} 已經有了，
     * 兩邊用 source_message_id 對得起來。
     *
     * <p>
     * 放棄有<b>兩條路</b>：outbox 重試耗盡（任務根本沒送出去），
     * 以及 worker 把任務丟進 DLQ（送出去了但做不完）。兩條都會走到這裡，
     * 也可能先後抵達同一筆——所以只有真的從 PENDING 轉成 FAILED 的那一次
     * 才回傳使用者，避免同一件事通知兩遍。
     *
     * @return 需要被通知的使用者 ID；已經有終局或找不到 note 時為空
     */
    @Transactional
    public Optional<String> markTranscriptionFailed(String sourceMessageId) {
        Note note = noteRepository.findBySourceMessageId(sourceMessageId).orElse(null);
        if (note == null) {
            log.error("要標記失敗但找不到 note：messageId={}", sourceMessageId);
            return Optional.empty();
        }
        if (note.getStatus() != NoteStatus.PENDING) {
            log.debug("note 已有終局狀態，不再標記失敗：messageId={} status={}",
                    sourceMessageId, note.getStatus());
            return Optional.empty();
        }
        note.fail();
        log.warn("轉錄放棄，note 標記為 FAILED：messageId={}", sourceMessageId);
        return Optional.of(note.getLineUserId());
    }
}
