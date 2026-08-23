package io.svra.user;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    /** 明擺著的假金鑰：解出來是一句話，不是隨機值。 */
    private static final String KEY =
            Base64.getEncoder().encodeToString("svra-test-key-do-not-use-in-prod".getBytes());

    private static SecretCipher cipherWith(String key) {
        return new SecretCipher(new SecretProperties(key));
    }

    @Test
    @DisplayName("加密再解密，拿回原來那串")
    void roundTrip() {
        SecretCipher cipher = cipherWith(KEY);

        String token = "1//0abcdefghijklmnop";

        assertThat(cipher.decrypt(cipher.encrypt(token))).isEqualTo(token);
    }

    @Test
    @DisplayName("🔴 同一段明文加密兩次，密文必須不一樣——IV 每次要重新產生")
    void encryptingTwiceGivesDifferentCiphertext() {
        SecretCipher cipher = cipherWith(KEY);

        String first = cipher.encrypt("同一個 token");
        String second = cipher.encrypt("同一個 token");

        // GCM 最致命的誤用就是重複使用同一組 key+IV，那會直接洩漏明文。
        // 密文相同 = IV 被固定住了，而那是看不出來的——兩邊都解得開。
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
    }

    @Test
    @DisplayName("🔴 換一把金鑰就解不開，而且訊息要說得出是這件事")
    void anotherKeyCannotDecrypt() {
        String encrypted = cipherWith(KEY).encrypt("token");
        String otherKey =
                Base64.getEncoder().encodeToString("another-key-32-bytes-long-aaaaaa".getBytes());

        assertThatThrownBy(() -> cipherWith(otherKey).decrypt(encrypted))
                // 「解密失敗」四個字沒有用——要能分辨「換過金鑰」與「密文被改過」，
                // 因為前者要找回舊金鑰，後者是資安事件。
                .hasMessageContaining("金鑰跟當初加密時用的不是同一把")
                .hasMessageContaining("重新授權");
    }

    @Test
    @DisplayName("🔴 密文被動過就要爆，不能解出一段垃圾當 token 用")
    void tamperedCiphertextIsRejected() {
        SecretCipher cipher = cipherWith(KEY);
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("token"));
        raw[raw.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(raw)))
                .hasMessageContaining("解密失敗");
    }

    @Test
    @DisplayName("金鑰長度不對 → 建構時就擋下，而且說得出要怎麼產一把")
    void aWrongLengthKeyFailsFast() {
        String tooShort = Base64.getEncoder().encodeToString("short".getBytes());

        assertThatThrownBy(() -> cipherWith(tooShort))
                .hasMessageContaining("AES-256 需要 32 bytes")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    @DisplayName("沒設金鑰是合法的（純連結部署），但真的要用時要說清楚")
    void noKeyIsLegalUntilYouActuallyNeedIt() {
        SecretCipher cipher = cipherWith(null);

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("token"))
                .hasMessageContaining("svra.secrets.encryption-key");
    }

    @Test
    @DisplayName("🔴 GoogleAuthorization 印出來不可以帶著 token")
    void theAuthorizationRecordNeverPrintsItsToken() {
        var auth = new GoogleAuthorization("1//0-secret-refresh-token",
                "cal@group.calendar.google.com", "scope");

        // record 預設的 toString 會把每個欄位都印出來，而第一個欄位是 refresh token。
        // 任何一句 log.debug("授權={}", auth) 都會把行事曆的永久寫入權寫進 log 檔——
        // **加密防的是同一份外洩，只是換了個檔案。**
        assertThat(auth.toString())
                .doesNotContain("1//0-secret-refresh-token")
                .contains("已隱藏")
                .contains("cal@group.calendar.google.com");
    }
}
