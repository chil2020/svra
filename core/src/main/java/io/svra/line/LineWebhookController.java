package io.svra.line;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.svra.note.NoteService;

/**
 * LINE Messaging API 的 webhook 接收端。
 *
 * <p><b>為什麼驗簽寫在 Controller 而不是 Filter：</b>
 * HMAC 必須對「原始的 request body 字元」計算，而 HTTP body 是一次性的
 * InputStream，讀過就沒了。放在 Filter 得多包一層
 * {@code ContentCachingRequestWrapper} 才能讓後面的 Controller 再讀一次——
 * 以目前只有一個 webhook 端點的規模，那層包裝的複雜度大於它換來的好處。
 * <b>端點變多時就該抽出去。</b>
 */
@RestController
@RequestMapping("/")
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final LineProperties lineProperties;
    private final ObjectMapper objectMapper;
    private final NoteService noteService;

    public LineWebhookController(LineProperties lineProperties,
            ObjectMapper objectMapper,
            NoteService noteService) {
        this.lineProperties = lineProperties;
        this.objectMapper = objectMapper;
        this.noteService = noteService;
    }

    /**
     * 接收 LINE 的事件推送。
     *
     * <p>驗簽失敗回 401——這是「你不是 LINE」的身分問題，不是請求格式問題。
     * 驗簽通過就一律回 200：即使是重複投遞、或我們不處理的事件類型，
     * 對 LINE 來說都算送達成功，回別的只會讓它重送。
     *
     * @param signature LINE 以 channel secret 對 raw body 算出的 HMAC-SHA256（Base64）
     * @param body      原始請求內容，<b>不能</b>先反序列化再序列化回來驗簽
     */
    @PostMapping("webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody String body) throws Exception {

        if (signature == null || !verifySignature(body, lineProperties.channelSecret(), signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        LineWebhookPayload payload = objectMapper.readValue(body, LineWebhookPayload.class);
        List<LineWebhookPayload.Event> events = payload.events();

        // LINE 後台的「驗證」按鈕會送 {"events":[]}，欄位也可能整個缺席
        if (events == null || events.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        for (LineWebhookPayload.Event event : events) {
            if (!event.isAudioMessage()) {
                continue;
            }
            noteService.recordIncoming(event.source().userId(), event.message().id());
        }

        return ResponseEntity.ok().build();
    }

    /**
     * 驗證 LINE 的簽章。
     *
     * @param data              原始請求內容
     * @param secret            channel secret
     * @param receivedSignature 對方在 X-Line-Signature 帶來的簽章
     */
    public static boolean verifySignature(String data, String secret, String receivedSignature) {
        try {
            String computedSignature = generateSignature(data, secret);

            // 固定時間比對，避免時序攻擊：一般的 equals 會在第一個不同的位元組就回傳，
            // 攻擊者能從回應時間的差異反推自己猜對了幾個字元。
            return MessageDigest.isEqual(
                    computedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("簽章驗證發生例外，視為驗證失敗", e);
            return false;
        }
    }

    /** 以 HMAC-SHA256 計算簽章，回傳 <b>Base64</b>（LINE 的格式，不是 Hex）。 */
    public static String generateSignature(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        SecretKeySpec secretKey = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                ALGORITHM);
        mac.init(secretKey);

        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(rawHmac);
    }
}
