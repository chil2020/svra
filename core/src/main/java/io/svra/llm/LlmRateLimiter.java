package io.svra.llm;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 每個使用者在時間窗內能打幾次模型。
 *
 * <p>用 Redis 的 {@code INCR} 而不是在 JVM 裡記數：{@code INCR} 是原子的，
 * 而且計數放在行程外面——多實例部署時每個 pod 各記各的等於沒有限流，
 * 重啟就歸零也等於沒有限流。跟決策 2 的判斷是同一個：
 * <b>要跨執行緒、跨實例、跨重啟都成立的保證，就不能放在單一 JVM 裡。</b>
 *
 * <p>限的是什麼：地端 Ollama 只有一份，同時湧進來的抽取請求會互相搶 CPU，
 * 結果是每一個都變慢。這裡不是在省錢（模型在自己機器上），是在<b>保護
 * 唯一那個推論資源</b>——這也是換成地端之後，決策 14 的理由要跟著改寫的地方。
 */
@Component
public class LlmRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LlmRateLimiter.class);

    private final StringRedisTemplate redis;
    private final LlmProperties properties;

    LlmRateLimiter(StringRedisTemplate redis, LlmProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 用掉一次額度。
     *
     * <p>超過額度時丟例外而不是等待：呼叫端都跑在 outbox 處理器裡，
     * 拋出去就會走既有的指數退避重試——<b>已經有一套退避了，不要再自己寫一套</b>。
     * 在這裡阻塞反而會把 poller 的執行緒佔住。
     *
     * <p>Redis 連不上時<b>放行</b>並記 warn。限流是保護措施不是正確性要求，
     * 為了它讓整個功能停擺是本末倒置。
     *
     * @throws LlmRateLimitExceededException 超過額度
     */
    public void consume(String lineUserId) {
        Duration window = properties.rateLimitWindow();
        // key 帶時間窗序號：窗換了 key 就換，不需要另外清舊資料
        String key = "svra:llm:rate:%s:%d".formatted(
                lineUserId, System.currentTimeMillis() / window.toMillis());

        Long used;
        try {
            used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                // 只有建立那一次要設存活時間，之後設會把時間窗一直往後延
                redis.expire(key, window);
            }
        } catch (RuntimeException e) {
            log.warn("Redis 不可用，這次不限流：userId={}", lineUserId, e);
            return;
        }

        if (used != null && used > properties.rateLimit()) {
            // 沒有這一行的話，被限流會被 poller 記成通用的「outbox 重試」，
            // 跟「Ollama 掛了」長得一模一樣，要讀例外訊息才分得出來。
            // 而這兩件事的處置完全不同：一個是等，一個是去把 Ollama 拉起來。
            log.warn("觸發限流，這次不打模型：userId={} 已用 {}/{}（{} 窗）。"
                    + "會由 outbox 退避重試，不會遺失",
                    lineUserId, used, properties.rateLimit(), window);
            throw new LlmRateLimitExceededException(
                    "%s 在 %s 內已用掉 %d 次，上限 %d"
                            .formatted(lineUserId, window, used, properties.rateLimit()));
        }
    }
}
