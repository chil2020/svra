package io.svra.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ⚠️ 這些名稱必須與 {@code whisper-worker/main.py} 的 topology 完全一致。
 * AMQP 的 queue 參數不同時會直接讓 channel 出錯（PRECONDITION_FAILED）。
 */
@ConfigurationProperties(prefix = "svra.mq")
public record MqProperties(
        String exchange,
        String dlx,
        String jobQueue,
        String jobRoutingKey,
        String jobDlq,
        String resultQueue,
        String resultRoutingKey) {
}
