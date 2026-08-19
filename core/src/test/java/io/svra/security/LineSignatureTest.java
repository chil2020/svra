package io.svra.security;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 期望值由 {@code openssl dgst -sha256 -hmac 'test-secret' -binary | base64} 算出，
 * 不是用這支程式自己算的——否則等於拿自己驗自己，演算法或編碼寫錯也照樣全綠。
 * 這是密碼學實作的標準測法：known-answer test，答案得來自外部。
 *
 * <p>⚠️ <b>下面那串 Base64 不是憑證，是上面那道指令的輸出。</b>
 * 金鑰（{@code test-secret}）與輸入（<code>{"events":[]}</code>）都在同一個檔案裡，
 * 任何人都能重算，它不通往任何東西。
 *
 * <p>但它是一串 44 字元的高熵 Base64，<b>看起來就跟憑證一模一樣</b>——
 * GitGuardian 掃 PR 時確實報過一次。這是 KAT 無法避免的性質：
 * 雜湊的輸出本來就長這樣。處理方式是明講而不是改寫測試——
 * 為了讓掃描器安靜而削弱一個安全測試，方向完全相反。
 * 消音設定在 {@code .gitguardian.yaml} 與 {@code .gitleaks.toml}。
 */
class LineSignatureTest {

    private static final String SECRET = "test-secret";

    @Test
    @DisplayName("HMAC-SHA256 + Base64，與 openssl 的結果一致")
    void matchesExternallyComputedVector() {
        assertThat(LineSignature.generate("{\"events\":[]}", SECRET))
                .isEqualTo("Va12JSFB+Fs03rxzdvh7icVLk546dmNGSrPkkClJW/U=");
    }

    @Test
    @DisplayName("非 ASCII 的 body 用 UTF-8 計算")
    void handlesNonAsciiBody() {
        // openssl dgst -sha256 -hmac 'test-secret' <<< 的 UTF-8 位元組
        String body = "{\"text\":\"買牛奶\"}";

        assertThat(LineSignature.generate(body.getBytes(StandardCharsets.UTF_8), SECRET))
                .isEqualTo(LineSignature.generate(body, SECRET));
    }

    @Test
    @DisplayName("body 差一個位元組，簽章就對不上")
    void rejectsTamperedBody() {
        byte[] original = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        String signature = LineSignature.generate(original, SECRET);

        assertThat(LineSignature.matches(original, SECRET, signature)).isTrue();
        assertThat(LineSignature.matches("{\"events\":[ ]}".getBytes(StandardCharsets.UTF_8), SECRET, signature))
                .isFalse();
    }

    @Test
    @DisplayName("長度不同的垃圾字串不會炸，只是不相符")
    void rejectsGarbageSignatureWithoutThrowing() {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

        assertThat(LineSignature.matches(body, SECRET, "")).isFalse();
        assertThat(LineSignature.matches(body, SECRET, "not-base64-at-all")).isFalse();
    }
}
