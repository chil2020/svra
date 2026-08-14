package io.svra.extract;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.svra.note.Note;
import io.svra.note.NoteRepository;
import io.svra.note.NoteService;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

@Service
public class NoteExtractionService {

    private static final Logger log = LoggerFactory.getLogger(NoteExtractionService.class);

    private final NoteRepository noteRepository;
    private final NoteExtractionRepository extractionRepository;
    private final NoteExtractor extractor;
    private final OutboxEventRepository outboxRepository;
    private final NoteService noteService;
    private final String model;

    public NoteExtractionService(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            NoteExtractor extractor,
            OutboxEventRepository outboxRepository,
            NoteService noteService,
            @Value("${spring.ai.ollama.chat.options.model:unknown}") String model) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.extractor = extractor;
        this.outboxRepository = outboxRepository;
        this.noteService = noteService;
        this.model = model;
    }

    /**
     * 對某則 note 的逐字稿做抽取，結果存成新的一版。
     *
     * <p>重跑時舊版本會被停用而不是刪除，使用者可以比較不同模型的結果再選一個。
     */
    @Transactional
    public void extractFor(String sourceMessageId) {
        Note note = noteRepository.findBySourceMessageId(sourceMessageId).orElse(null);
        if (note == null) {
            log.error("要抽取但找不到 note：messageId={}", sourceMessageId);
            return;
        }
        if (note.getTranscript() == null || note.getTranscript().isBlank()) {
            log.warn("逐字稿是空的，跳過抽取：messageId={}", sourceMessageId);
            return;
        }

        List<NoteItem> items = extractor.extract(note.getTranscript());
        if (items.isEmpty()) {
            log.warn("抽不出任何項目：messageId={}", sourceMessageId);
            return;
        }

        extractionRepository.findByNoteIdAndActiveTrue(note.getId())
                .ifPresent(NoteExtraction::deactivate);
        // 先讓舊版失效再寫新的：DB 的部分唯一索引不允許同時有兩個生效版本
        extractionRepository.flush();

        NoteExtraction extraction = NoteExtraction.of(note.getId(), model, NoteExtractor.PROMPT_VERSION);
        items.forEach(extraction::addItem);
        extractionRepository.save(extraction);

        // 推播是使用者唯一看得到的結果，掉了整條流程等於白做——
        // 所以跟抽取結果同交易寫下意圖，由 outbox 負責送達與重試。
        outboxRepository.save(OutboxEvent.pending(
                sourceMessageId,
                NoteService.EVENT_NOTIFY_REQUESTED,
                noteService.toPayload(note.getLineUserId(), sourceMessageId)));

        log.info("抽取完成：messageId={} model={} items={}", sourceMessageId, model, items.size());
    }
}
