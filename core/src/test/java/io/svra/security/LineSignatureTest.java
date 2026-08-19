package io.svra.security;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 期望值由 {@code openssl dgst -sha256 -hmac 'test-secret' -binary | base64} 算出，
 * 不是用這支程式自己算的——否則等於拿自己驗自己，演算法或編碼寫錯也照樣全綠。
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
