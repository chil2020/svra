package io.svra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 快取的共用設定。
 *
 * <p><b>每個快取存什麼型別、怎麼序列化，由擁有那個型別的模組自己決定</b>——
 * 見 {@code io.svra.extract} 底下的 cache 設定。這裡只放跨模組共通的部分，
 * 免得這個 package 為了設定序列化而反過來認識每一個業務型別。
 */
@Configuration
@EnableCaching
public class LlmCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmCacheConfig.class);

    /** 抽取結果的快取名稱。 */
    public static final String EXTRACTION_CACHE = "llm-extraction";

    /**
     * 🔴 Redis 掛掉不可以讓抽取跟著掛。
     *
     * <p>預設的處理方式是把例外往外拋——那等於讓快取變成正確性的相依，
     * 而<b>快取就是快取：掉了要能重算</b>（決策 14）。Redis 連不上時記一筆
     * warn 然後照常呼叫模型，使用者只會覺得慢一點。
     */
    @Bean
    CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("讀快取失敗，改為直接呼叫模型：cache={} key={}", cache.getName(), key, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("寫快取失敗，結果照常回傳：cache={} key={}", cache.getName(), key, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("清快取失敗：cache={} key={}", cache.getName(), key, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("清空快取失敗：cache={}", cache.getName(), e);
            }
        };
    }
}
