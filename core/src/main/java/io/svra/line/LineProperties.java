package io.svra.line;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param channelSecret      驗 webhook 簽章用
 * @param channelAccessToken 呼叫 Messaging API 用（下載音檔、reply/push）
 */
@ConfigurationProperties(prefix = "svra.line")
public record LineProperties(String channelSecret, String channelAccessToken) {
}
