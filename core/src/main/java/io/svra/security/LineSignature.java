package io.svra.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * LINE webhook 的 HMAC-SHA256 驗簽。
 *
 * <p>簽章算的是<b>原始 body 位元組</b>——先反序列化再序列化回來會因為空白、
 * 欄位順序、數字格式的差異而對不上。所以這裡的參數是 {@code byte[]} 而不是
 * {@code String}：轉成 String 再轉回來要經過一次 charset 解碼與編碼，
 * 而 LINE 不保證 Content-Type 會帶 charset。少繞一圈就少一個出錯的地方。
 */
final class LineSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private LineSignature() {
    }

    /** 回傳 Base64（LINE 的格式，不是 Hex）。 */
    static String generate(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException ex) {
            // HmacSHA256 是 JDK 保證存在的演算法，走到這裡代表金鑰是空的之類的
            // 設定錯誤，不是「這次請求驗不過」。不要吞掉當成驗證失敗。
            throw new IllegalStateException("無法計算 HMAC 簽章", ex);
        }
    }

    /** 便利多載：測試與外部工具比對用。 */
    static String generate(String body, String secret) {
        return generate(body.getBytes(StandardCharsets.UTF_8), secret);
    }

    /**
     * 固定時間比對。一般的 {@code equals()} 在第一個不同的位元組就回傳，
     * 攻擊者能從回應時間反推猜對了幾個字元。
     *
     * <p>{@code MessageDigest.isEqual} 只在長度相同時是固定時間的，長度不同會提早
     * 回傳——但 Base64 過的 SHA-256 永遠是 44 個字元，長度不同等於根本不是簽章，
     * 這件事沒有洩漏價值。
     */
    static boolean matches(byte[] body, String secret, String received) {
        return MessageDigest.isEqual(
                generate(body, secret).getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8));
    }
}
