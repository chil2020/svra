package io.svra.user;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * 把 refresh token 加密後才寫進資料庫。
 *
 * <p><b>它擋得住什麼，要講清楚。</b>擋的是「資料庫的內容外流」——備份檔、
 * 唯讀副本、以及<b>開發者自己跑 psql</b>（這個專案的開發過程中跑過幾十次，
 * 明文 token 會留在終端機捲軸裡）。<b>擋不住</b>應用程式被攻破：金鑰就在那個行程裡。
 *
 * <p>所以它不是萬靈丹，而是讓「DB 外流」不等於「所有人的行事曆寫入權外流」。
 *
 * <p>AES-256-GCM：GCM 同時提供機密性與完整性，所以密文被改過會在解密時就爆，
 * 而不是解出一段垃圾再拿去當 token 用。IV 每次隨機產生並存在密文前面——
 * <b>GCM 最致命的誤用就是重複使用同一組 key+IV</b>，那會直接洩漏明文。
 */
@Component
class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    SecretCipher(SecretProperties properties) {
        this.key = properties.hasKey() ? loadKey(properties.encryptionKey()) : null;
    }

    /** 沒設金鑰＝這個部署不走 OAuth（所有人用連結），完全合法。 */
    boolean isConfigured() {
        return key != null;
    }

    private static SecretKeySpec loadKey(String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "svra.secrets.encryption-key 不是合法的 base64。"
                            + "用 `openssl rand -base64 32` 產一把。", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "svra.secrets.encryption-key 解出來是 " + raw.length
                            + " bytes，AES-256 需要 " + KEY_BYTES + " bytes。"
                            + "用 `openssl rand -base64 32` 產一把。");
        }
        return new SecretKeySpec(raw, "AES");
    }

    String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // 不要把 plaintext 放進訊息裡——例外會被 log 起來
            throw new IllegalStateException("加密失敗", e);
        }
    }

    String decrypt(String encoded) {
        requireKey();
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("資料庫裡的密文不是合法的 base64", e);
        }
        if (combined.length <= IV_BYTES) {
            throw new IllegalStateException("資料庫裡的密文太短，不可能包含 IV 與 tag");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
            byte[] plaintext = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            // 🔴 這個訊息值得寫得很具體。GCM 的 tag 對不上只有兩種可能，
            // 而兩種的處理方式完全不同：換金鑰要找回舊的，密文被改過是資安事件。
            throw new IllegalStateException(
                    "解密失敗：金鑰跟當初加密時用的不是同一把，或密文被改過。"
                            + "換過金鑰的話，所有使用者都必須重新授權。", e);
        } catch (Exception e) {
            throw new IllegalStateException("解密失敗", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "要存取使用者憑證，但沒有設 svra.secrets.encryption-key。"
                            + "用 `openssl rand -base64 32` 產一把放進 .env。");
        }
    }
}
