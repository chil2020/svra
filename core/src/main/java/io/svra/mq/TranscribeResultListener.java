package io.svra.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

import io.svra.note.NoteService;

@Component
public class TranscribeResultListener {

    private static final Logger log = LoggerFactory.getLogger(TranscribeResultListener.class);

    private final NoteService noteService;

    public TranscribeResultListener(NoteService noteService) {
        this.noteService = noteService;
    }

    /**
     * 收轉錄結果。
     *
     * <p>⚠️ 這個方法拋出例外時，Spring AMQP 預設會 <b>requeue</b>——訊息回到佇列頭，
     * 立刻被同一個 listener 再收一次，形成無限迴圈（而且沒有退避）。
     * 對於「重試也不會成功」的錯誤要丟 {@link AmqpRejectAndDontRequeueException}。
     */
    @RabbitListener(queues = "${svra.mq.result-queue}")
    public void onResult(TranscribeResult result) {
        if (result == null || result.jobId() == null) {
            throw new AmqpRejectAndDontRequeueException("結果訊息缺少 job_id");
        }

        log.info("收到轉錄結果：jobId={} status={} chars={}",
                result.jobId(), result.status(),
                result.text() == null ? 0 : result.text().length());

        noteService.applyTranscription(
                result.jobId(), result.text(), result.language(), result.audioDurationSec());
    }
}
