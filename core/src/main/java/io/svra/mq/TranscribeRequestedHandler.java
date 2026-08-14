package io.svra.mq;

import java.nio.file.Path;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.svra.SvraProperties;
import io.svra.line.LineContentClient;
import io.svra.note.NoteService;
import io.svra.note.NoteService.NoteEventPayload;
import io.svra.outbox.OutboxEventHandler;

/** 下載音檔並把轉錄任務丟進佇列。 */
@Component
class TranscribeRequestedHandler implements OutboxEventHandler {

    private final LineContentClient lineContentClient;
    private final RabbitTemplate rabbitTemplate;
    private final NoteService noteService;
    private final ObjectMapper objectMapper;
    private final MqProperties mq;
    private final SvraProperties svra;

    TranscribeRequestedHandler(LineContentClient lineContentClient,
            RabbitTemplate rabbitTemplate,
            NoteService noteService,
            ObjectMapper objectMapper,
            MqProperties mq,
            SvraProperties svra) {
        this.lineContentClient = lineContentClient;
        this.rabbitTemplate = rabbitTemplate;
        this.noteService = noteService;
        this.objectMapper = objectMapper;
        this.mq = mq;
        this.svra = svra;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_TRANSCRIBE_REQUESTED;
    }

    @Override
    public void handle(String payload) throws Exception {
        String messageId = parse(payload).sourceMessageId();
        Path target = Path.of(svra.audioDir(), messageId + ".m4a");
        lineContentClient.download(messageId, target);

        rabbitTemplate.convertAndSend(
                mq.exchange(),
                mq.jobRoutingKey(),
                new TranscribeJob(messageId, target.getFileName().toString(), null));
    }

    /** 放棄之後沒人會再送這則任務，note 不能永遠停在 PENDING。 */
    @Override
    public void onGiveUp(String payload) {
        noteService.markTranscriptionFailed(parse(payload).sourceMessageId());
    }

    private NoteEventPayload parse(String payload) {
        return objectMapper.readValue(payload, NoteEventPayload.class);
    }
}
