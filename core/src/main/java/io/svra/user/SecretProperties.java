package io.svra.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param encryptionKey base64 編碼的 32 bytes，用 {@code openssl rand -base64 32} 產。
 *                      <p>🔴 <b>弄丟它 = 所有使用者都要重新授權。</b>資料庫裡的
 *                      refresh token 是用它加密的，換一把就全部解不開。
 *                      <p>留白是合法的：那代表這個部署所有人走預填連結、
 *                      沒有任何憑證要存（決策 27）。
 */
@ConfigurationProperties(prefix = "svra.secrets")
public record SecretProperties(String encryptionKey) {

    boolean hasKey() {
        return encryptionKey != null && !encryptionKey.isBlank();
    }
}
