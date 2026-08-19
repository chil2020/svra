package io.svra.extract;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import io.svra.llm.LlmCacheConfig;
import io.svra.llm.LlmProperties;

/**
 * 抽取結果快取怎麼存。
 *
 * <p>設定放在這個 package，是因為 {@link ExtractedNote} 是 package-private 的——
 * 型別不外流，那決定「這個型別怎麼序列化」的設定也不該外流。
 *
 * <p><b>用具型別的序列化器，不用 Generic 那個。</b>
 * {@code GenericJacksonJsonRedisSerializer} 預設不會把型別資訊寫進 JSON，
 * 讀回來是一個 {@code LinkedHashMap}——轉型時 {@code ClassCastException}，
 * 被 {@code LlmCacheConfig} 的 CacheErrorHandler 接住當成「快取讀取失敗」，
 * 於是<b>命中率永遠是 0，而應用完全正常運作、只是每次都在重打模型</b>。
 * 這個快取只裝一種型別，講明它就不需要在 JSON 裡帶型別資訊，
 * 存進 Redis 的內容也還看得懂。
 *
 * <p>{@code ExtractionCacheSerializationTest} 守著這件事。
 */
@Configuration
class ExtractionCacheConfig {

    private final LlmProperties properties;

    ExtractionCacheConfig(LlmProperties properties) {
        this.properties = properties;
    }

    @Bean
    RedisCacheManagerBuilderCustomizer extractionCacheCustomizer() {
        return builder -> builder.withCacheConfiguration(LlmCacheConfig.EXTRACTION_CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(properties.cacheTtl())
                        // null 不入快取：抽取失敗時回的就是 null，
                        // 把一次連線失敗記住 24 小時比不快取糟得多。
                        .disableCachingNullValues()
                        .serializeValuesWith(RedisSerializationContext.SerializationPair
                                .fromSerializer(new JacksonJsonRedisSerializer<>(ExtractedNote.class))));
    }
}
