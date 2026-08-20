package io.svra.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import io.svra.LogContext;

/**
 * 消費死信佇列，把 worker 放棄掉的任務收尾。
 *
 * <p>沒有這一支的話，DLQ 只是個「訊息不會消失」的保證，而不是補償——
 * worker 一 reject，note 就永遠停在 PENDING，使用者傳了語音卻等不到任何回應，
 * 而 outbox 那邊早就把事件標成 SENT，重試機制碰不到它。
 *
 * <p><b>這裡不重試。</b>訊息會走到 DLQ，是因為 worker 已經判定「重試也不會成功」
 * （音檔解不開、模型載不起來）。再送一次只是把同一個失敗重演一遍——
 * 該做的是讓這筆有終局、並且讓人知道。
 */
@Component
class TranscribeDlqListener {

    private static final Logger log = LoggerFactory.getLogger(TranscribeDlqListener.class);

    private final TranscriptionFailureReporter failureReporter;

    TranscribeDlqListener(TranscriptionFailureReporter failureReporter) {
        this.failureReporter = failureReporter;
    }

    /** 任務送出去了，但 worker 做不完。 */
    @RabbitListener(queues = "${svra.mq.job-dlq}")
    void onDeadLetteredJob(TranscribeJob job) {
        if (job == null || job.jobId() == null) {
            // 收尾不了的訊息不能留在佇列裡打轉——沒有 jobId 就對不回任何一筆 note，
            // 再看幾次也一樣。
            throw new AmqpRejectAndDontRequeueException("死信訊息缺少 job_id，無法對應 note");
        }

        try (var ignored = LogContext.messageId(job.jobId())) {
            log.error("轉錄任務進了死信佇列，標記為失敗並通知使用者：audioFile={}", job.audioFile());
            failureReporter.report(job.jobId());
        }
    }

    /**
     * worker 做完了，但結果寫不回去（重試已經在 listener 那層退避過三次）。
     *
     * <p>逐字稿在這條路上是保得住的——它就在死信訊息裡。目前只做到「讓使用者知道」，
     * 補寫回資料庫需要一個能重放死信的入口，那還沒做。
     */
    @RabbitListener(queues = "${svra.mq.result-dlq}")
    void onDeadLetteredResult(TranscribeResult result) {
        if (result == null || result.jobId() == null) {
            throw new AmqpRejectAndDontRequeueException("死信結果缺少 job_id，無法對應 note");
        }

        try (var ignored = LogContext.messageId(result.jobId())) {
            log.error("轉錄結果進了死信佇列，標記為失敗並通知使用者：status={}", result.status());
            failureReporter.report(result.jobId());
        }
    }
}
