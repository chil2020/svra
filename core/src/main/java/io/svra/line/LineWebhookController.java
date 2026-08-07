package io.svra.line;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LineWebhookController
 */
@RestController
@RequestMapping("/")
public class LineWebhookController {
    private final LineProperties lineProperties;
    private static final String ALGORITHM = "HmacSHA256";

    public LineWebhookController(LineProperties lineProperties) {
        this.lineProperties = lineProperties;
    }

    @PostMapping("webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature, @RequestBody String testEvent) {
        if (signature == null || !verifySignature(testEvent, lineProperties.channelSecret(), signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok().build();
    }

    /**
     * 驗證簽章
     * 
     * @param data              原始訊息資料
     * @param secret            共享密鑰
     * @param receivedSignature 客戶端傳來的簽章字串
     * @return 驗證是否成功
     */
    public static boolean verifySignature(String data, String secret, String receivedSignature) {
        try {
            // 1. 使用相同的資料與密鑰重新計算簽章
            String computedSignature = generateSignature(data, secret);

            // 2. 進行「常數時間」比對，防止時序攻擊 (Timing Attack)
            return MessageDigest.isEqual(
                    computedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 生成 HMAC-SHA256 簽章（回傳 Hex 格式字串）
     */
    public static String generateSignature(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                ALGORITHM);
        mac.init(secretKey);

        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        // Java 17+ 推薦使用 HexFormat，舊版 Java 可用第三方庫如 Apache Commons Codec
        // (Hex.encodeHexString)
        return Base64.getEncoder().encodeToString(rawHmac);
    }
}
