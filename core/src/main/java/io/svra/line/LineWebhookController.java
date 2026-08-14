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

import tools.jackson.databind.ObjectMapper;

import io.svra.command.NoteCommandService;
import io.svra.note.NoteService;

/**
 * 驗簽寫在 Controller 而不是 Filter：HMAC 要對原始 body 算，而 body 是一次性的
 * InputStream。放 Filter 得多包一層 ContentCachingRequestWrapper——目前只有一個
 * 端點，那層包裝不划算。端點變多時再抽出去。
 */
@RestController
@RequestMapping("/")
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final LineProperties lineProperties;
    private final ObjectMapper objectMapper;
    private final NoteService noteService;
    private final NoteCommandService commandService;

    public LineWebhookController(LineProperties lineProperties,
            ObjectMapper objectMapper,
            NoteService noteService,
            NoteCommandService commandService) {
        this.lineProperties = lineProperties;
        this.objectMapper = objectMapper;
        this.noteService = noteService;
        this.commandService = commandService;
    }

    /**
     * 驗簽失敗回 401（身分問題，不是格式問題）；通過就一律回 200——
     * 重複投遞或不處理的事件對 LINE 來說都算送達成功，回別的只會讓它重送。
     *
     * @param body 原始請求內容，不能先反序列化再序列化回來驗簽
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
            if (event.isAudioMessage()) {
                noteService.recordIncoming(event.source().userId(), event.message().id());
            } else if (event.isTextMessage()) {
                // 指令處理不能拖慢 webhook——一樣走 outbox，這裡只記下意圖。
                commandService.recordCommand(
                        event.source().userId(),
                        event.message().id(),
                        event.message().text(),
                        event.message().quotedMessageId());
            }
        }

        return ResponseEntity.ok().build();
    }

    public static boolean verifySignature(String data, String secret, String receivedSignature) {
        try {
            String computedSignature = generateSignature(data, secret);

            // 固定時間比對：一般的 equals 在第一個不同的位元組就回傳，
            // 攻擊者能從回應時間反推猜對了幾個字元。
            return MessageDigest.isEqual(
                    computedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("簽章驗證發生例外，視為驗證失敗", e);
            return false;
        }
    }

    /** 回傳 Base64（LINE 的格式，不是 Hex）。 */
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
