package io.svra.extract;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

/**
 * 抽取快取的鍵。
 *
 * <p><b>鍵裡放什麼，等於宣告「什麼改了就該重算」</b>：
 *
 * <ul>
 * <li>逐字稿——輸入變了當然要重算（取雜湊，逐字稿可能很長）
 * <li>錄音<b>日期</b>——prompt 裡的日曆表以它為基準。放日期不放時刻：
 *     日曆是以天為單位的，用時刻當鍵等於每次都 miss
 * <li>prompt 版本——改了 prompt 就該重算，這也是 {@code PROMPT_VERSION}
 *     存在的理由之一
 * <li>模型名稱——換模型就該重算，否則會拿舊模型的結果冒充新模型的表現
 * </ul>
 *
 * <p>使用者 ID <b>不在</b>鍵裡：同一段逐字稿誰講的結果都一樣。
 * 限流才是跟人綁定的，那件事在 {@code LlmRateLimiter}。
 */
@Component("extractionCacheKeyGenerator")
class ExtractionCacheKeyGenerator implements KeyGenerator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    private final String model;

    ExtractionCacheKeyGenerator(
            @Value("${spring.ai.ollama.chat.options.model:unknown}") String model) {
        this.model = model;
    }

    @Override
    public Object generate(Object target, Method method, Object... params) {
        String transcript = (String) params[0];
        Instant recordedAt = (Instant) params[1];

        return "%s|%s|%s|%s".formatted(
                model,
                NoteExtractor.PROMPT_VERSION,
                LocalDate.ofInstant(recordedAt, ZONE),
                sha256(transcript));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("算不出快取鍵", e);
        }
    }
}
