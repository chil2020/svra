package io.svra.outbox;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.svra.SvraProperties;
import io.svra.line.LineContentClient;
import io.svra.mq.MqProperties;
import io.svra.mq.TranscribeJob;
import io.svra.note.NoteService;
import io.svra.note.NoteService.TranscribeRequested;

/**
 * 把 outbox 裡的待送事件真的送出去。
 *
 * <p>
 * 🔴 承重點③：這支是 outbox 模式能不能成立的關鍵。
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    /** 一批的上限。下載音檔會佔住資料庫的列鎖，批次太大會讓交易拖很久。 */
    private static final int BATCH_SIZE = 5;

    private final OutboxEventRepository outboxRepository;
    private final NoteService noteService;
    private final LineContentClient lineContentClient;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MqProperties mq;
    private final SvraProperties svra;

    public OutboxPoller(OutboxEventRepository outboxRepository,
            NoteService noteService,
            LineContentClient lineContentClient,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            MqProperties mq,
            SvraProperties svra) {
        this.outboxRepository = outboxRepository;
        this.noteService = noteService;
        this.lineContentClient = lineContentClient;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.mq = mq;
        this.svra = svra;
    }

    @Scheduled(fixedDelayString = "${svra.outbox.poll-interval-ms:2000}")
    @Transactional
    public void dispatch() {
        List<OutboxEvent> batch = outboxRepository.lockNextBatch(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                TranscribeRequested req = objectMapper.readValue(event.getPayload(), TranscribeRequested.class);
                String messageId = req.sourceMessageId();

                Path target = Path.of(svra.audioDir(), messageId + ".m4a");
                lineContentClient.download(messageId, target);

                rabbitTemplate.convertAndSend(
                        mq.exchange(),
                        mq.jobRoutingKey(),
                        new TranscribeJob(messageId, target.getFileName().toString(), null));

                event.markSent();
            } catch (Exception e) {
                log.warn("outbox 發送失敗：id={} attempts={}", event.getId(), event.getAttempts(), e);
                event.markFailed(e.toString());

                // 重試耗盡就不會再有人處理這則訊息了，note 不能留在 PENDING
                if (event.getStatus() == OutboxStatus.FAILED) {
                    noteService.markTranscriptionFailed(event.getAggregateId());
                }
                // 不 throw —— 這一批的其他筆要能繼續
            }
        }

    }
}
