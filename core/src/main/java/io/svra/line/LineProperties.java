package io.svra.line;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * 🔴 兩個欄位都是 {@code @NotBlank}，少了就<b>啟動失敗</b>。
 *
 * <p>這跟決策 8 讓 {@code ddl-auto=validate} 在啟動時擋下 schema 不一致是同一個判斷：
 * <b>設定錯誤要在啟動時炸，不要等到執行到那一行。</b>
 *
 * <p>不驗的話會怎樣：{@code application.yml} 的預設是空字串，而空字串餵給
 * {@code SecretKeySpec} 會拋 {@code IllegalArgumentException: Empty key}——
 * 那既不是 {@code GeneralSecurityException}（{@code LineSignature} 接不到），
 * 也不是 {@code AuthenticationException}（filter 接不到），於是一路穿到最外層變成
 * <b>500</b>。而 LINE 收到 500 會重送，所以症狀是「應用啟動得好好的，
 * 每一則訊息都無限重送」，log 裡看不出跟設定有關。
 *
 * @param channelSecret      驗 webhook 簽章用
 * @param channelAccessToken 呼叫 Messaging API 用（下載音檔、reply/push）
 */
@Validated
@ConfigurationProperties(prefix = "svra.line")
public record LineProperties(
        @NotBlank String channelSecret,
        @NotBlank String channelAccessToken) {
}
