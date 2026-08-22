package io.svra.extract;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.svra.line.LinePushClient;
import io.svra.llm.LlmRateLimiter;
import io.svra.note.Note;
import io.svra.note.NoteRepository;
import io.svra.note.NoteService;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteExtraction;

@Service
public class NoteExtractionService {

    private static final Logger log = LoggerFactory.getLogger(NoteExtractionService.class);

    private final NoteRepository noteRepository;
    private final NoteExtractionRepository extractionRepository;
    private final NoteExtractor extractor;
    private final OutboxEventRepository outboxRepository;
    private final NoteService noteService;
    /** 抽不出東西時要說一聲——那條路沒有 outbox 事件可以走，只能直接推。 */
    private final LinePushClient pushClient;
    private final LlmRateLimiter rateLimiter;
    /**
     * 交易邊界用 TransactionTemplate 明寫，不用 {@code @Transactional}。
     *
     * <p>理由是這個類別<b>刻意有一段不在交易裡</b>，而註解只能標在整個方法上；
     * 拆成兩個 bean 也可以，但那會把「為什麼中間要斷開」這件事藏進類別關係裡。
     * 寫在這裡，交易的起訖看得見。
     */
    private final TransactionTemplate tx;
    private final String model;

    public NoteExtractionService(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            NoteExtractor extractor,
            OutboxEventRepository outboxRepository,
            NoteService noteService,
            LinePushClient pushClient,
            LlmRateLimiter rateLimiter,
            PlatformTransactionManager transactionManager,
            @Value("${spring.ai.ollama.chat.options.model:unknown}") String model) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.extractor = extractor;
        this.outboxRepository = outboxRepository;
        this.noteService = noteService;
        this.pushClient = pushClient;
        this.rateLimiter = rateLimiter;
        this.tx = new TransactionTemplate(transactionManager);
        this.model = model;
    }

    /**
     * 對某則 note 的逐字稿做抽取，結果存成新的一版。
     *
     * <p>🔴 <b>刻意沒有 {@code @Transactional}。</b>這個方法中間要呼叫 LLM，
     * 地端模型一次 12 秒起跳、驗證失敗重試就是兩倍。把整段包在一個交易裡，
     * 等於那 24 秒全程佔著一條資料庫連線什麼也沒做，同時撐大交易的存活時間
     * （長交易會擋住 vacuum，也讓連線池在尖峰時見底）。
     *
     * <p>所以分成三段：<b>短交易讀取 → 交易外呼叫模型 → 短交易寫入</b>。
     * 中間那段沒有交易，正是因為它做的不是資料庫的事。
     *
     * <p>🔴 必須冪等。outbox 是 at-least-once：處理器成功提交、外層 poller 交易卻失敗時，
     * 同一個 EXTRACT 事件會再跑一次。少了下面那道守衛，重跑會建立新版本並停用舊版——
     * 而新版是從原始逐字稿重抽的，<b>使用者刪掉的項目會復活、改過的標題會變回原文</b>，
     * 而且不會有任何錯誤訊息。
     */
    public void extractFor(String sourceMessageId) {
        // ── 第一段：短交易，把要用的東西讀出來 ──
        Pending pending = tx.execute(status -> loadPending(sourceMessageId));
        if (pending == null) {
            return;
        }

        // ── 第二段：沒有交易。限流與模型呼叫都不是資料庫的事 ──
        // 超過額度就往外拋，讓 outbox 的指數退避去處理——已經有一套退避了，
        // 不要在這裡自己等。
        rateLimiter.consume(pending.lineUserId());

        // 用 note 的建立時間當基準：「明天」指的是錄音那天的明天。
        long startedNanos = System.nanoTime();
        List<NoteItem> items = NoteExtractor.toItems(
                extractor.extract(pending.transcript(), pending.recordedAt()));
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;

        if (items.isEmpty()) {
            // 靜靜 return 的話，使用者傳了語音就只等到沉默——而 note 停在 COMPLETED，
            // 沒有任何欄位顯示這件事失敗過。跟「note 永遠停在 PENDING」是同一類問題。
            log.warn("抽不出任何項目，已通知使用者");
            pushClient.pushText(pending.lineUserId(),
                    "🤔 這段語音我沒抽出可以整理的內容，換個說法再試一次？");
            return;
        }

        // ── 第三段：短交易，抽取結果與推播意圖同進同退 ──
        tx.executeWithoutResult(status -> save(pending, items));
        // 耗時是這一行最有價值的欄位：抽取是整條路徑最慢的一段（12 秒起跳），
        // 而它有快取——命中與否差兩個數量級，看數字才分得出來。
        log.info("抽取完成：items={} model={} 耗時={}ms", items.size(), model, elapsedMillis);
    }

    /** 第一段要帶到交易外面的東西。只放值，不放 entity——交易結束它就 detached 了。 */
    private record Pending(Long noteId, String sourceMessageId, String lineUserId,
            String transcript, Instant recordedAt) {
    }

    /** @return 可以往下做時的資料；不該往下做時為 null（原因已在裡面處理掉） */
    private Pending loadPending(String sourceMessageId) {
        Note note = noteRepository.findBySourceMessageId(sourceMessageId).orElse(null);
        if (note == null) {
            log.error("要抽取但找不到 note");
            return null;
        }
        if (note.getTranscript() == null || note.getTranscript().isBlank()) {
            log.warn("逐字稿是空的，跳過抽取，已通知使用者");
            pushClient.pushText(note.getLineUserId(),
                    "🔇 這段語音我聽不出內容，可以再錄一次嗎？");
            return null;
        }
        // 已經有生效版本 = 上一輪已經成功（抽取結果與 NOTIFY 事件同交易寫入，
        // 有前者就一定有後者），這次是重送。直接跳過。
        //
        // 換模型重跑、兩版並存那條路（決策 9）不會走這裡——它需要一個
        // 「明確要求取代現行版本」的入口，而那個入口還沒做。目前這樣的取捨是：
        // 寧可不重抽，也不要在使用者沒要求時默默蓋掉他編輯過的東西。
        if (extractionRepository.findByNoteIdAndActiveTrue(note.getId()).isPresent()) {
            log.info("已有生效的抽取結果，跳過重抽（outbox 重送）");
            return null;
        }
        return new Pending(note.getId(), sourceMessageId, note.getLineUserId(),
                note.getTranscript(), note.getCreatedAt());
    }

    private void save(Pending pending, List<NoteItem> items) {
        // 再查一次：第二段花了十幾秒，這期間另一個實例可能已經寫進去了。
        // 真的撞上時 note_extractions 的部分唯一索引也還擋著（uk_active_extraction）。
        if (extractionRepository.findByNoteIdAndActiveTrue(pending.noteId()).isPresent()) {
            log.info("抽取期間已有別人寫入生效版本，放棄這次結果");
            return;
        }

        NoteExtraction extraction =
                NoteExtraction.of(pending.noteId(), model, NoteExtractor.PROMPT_VERSION);
        items.forEach(extraction::addItem);
        extractionRepository.save(extraction);

        // 推播是使用者唯一看得到的結果，掉了整條流程等於白做——
        // 所以跟抽取結果同交易寫下意圖，由 outbox 負責送達與重試。
        outboxRepository.save(OutboxEvent.pending(
                pending.sourceMessageId(),
                NoteService.EVENT_NOTIFY_REQUESTED,
                pending.lineUserId(),
                noteService.toPayload(pending.lineUserId(), pending.sourceMessageId())));
    }
}
