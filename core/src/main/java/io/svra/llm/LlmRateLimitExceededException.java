package io.svra.llm;

/**
 * 超過限流額度。
 *
 * <p>刻意是 unchecked：呼叫端在 outbox 處理器裡，不處理才是對的——
 * 讓它往外傳，poller 就會退避後重試，而那正是超過額度時該做的事。
 */
public class LlmRateLimitExceededException extends RuntimeException {

    public LlmRateLimitExceededException(String message) {
        super(message);
    }
}
