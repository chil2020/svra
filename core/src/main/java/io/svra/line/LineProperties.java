package io.svra.line;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LINE Messaging API 的設定，對應 application.yml 的 {@code svra.line.*}。
 *
 * <p>用 record 做型別安全設定：Spring Boot 3 支援 constructor binding，
 * 欄位不可變、不需要 setter，也不會有「注入到一半的半成品物件」。
 *
 * @param channelSecret      驗 webhook 簽章用（X-Line-Signature 的 HMAC-SHA256 金鑰）
 * @param channelAccessToken 呼叫 Messaging API 用（下載音檔、reply/push）
 */
@ConfigurationProperties(prefix = "svra.line")
public record LineProperties(String channelSecret, String channelAccessToken) {
}
