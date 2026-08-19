package io.svra.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cacheTtl        同一段輸入的抽取結果留多久
 * @param rateLimit       每個使用者在 {@code rateLimitWindow} 內最多幾次 LLM 呼叫
 * @param rateLimitWindow 限流的時間窗
 */
@ConfigurationProperties(prefix = "svra.llm")
public record LlmProperties(Duration cacheTtl, int rateLimit, Duration rateLimitWindow) {
}
