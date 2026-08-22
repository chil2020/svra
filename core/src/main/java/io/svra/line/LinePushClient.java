package io.svra.line;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * 把訊息送給使用者，走 reply 或 push。
 *
 * <p>🔴 <b>兩者的差別是錢。</b>LINE 的官方說明：
 * <i>"Reply messages are not included in the message count for your pricing plan."</i>
 * 而 push 會吃免費額度，<b>而那個額度是整個官方帳號共用的，不是每人一份</b>——
 * 免費方案每月 200 則，超過就完全不能發。開放給多人使用時，
 * 這一條直接決定同樣的錢能服務幾個人。
 *
 * <p>但 reply 不是隨時可用：token 單次使用、短效，而且只有「使用者剛做了什麼」
 * 才拿得到。所以三條路各自不同：
 *
 * <ul>
 * <li><b>語音抽取的卡片</b>——轉錄加抽取要數十秒，拿到結果時 token 早死了，只能 push</li>
 * <li><b>指令回覆</b>（實測約 7 秒）與<b>匯入回覆</b>（約 2 秒）——來得及，走 reply</li>
 * </ul>
 *
 * <p>由呼叫端決定帶不帶 token，這裡不猜。
 */
@Component
public class LinePushClient {

    private static final Logger log = LoggerFactory.getLogger(LinePushClient.class);
    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";
    private static final String REPLY_URL = "https://api.line.me/v2/bot/message/reply";

    /** 單則訊息上限 5000 字，超過整個請求會被拒絕。 */
    private static final int MAX_TEXT_LENGTH = 5000;

    /**
     * Flex 訊息的 altText 上限。它是被引用時與通知列上顯示的那一段，
     * 超過整個請求會被拒絕——而被拒絕的結果是使用者<b>什麼都沒收到</b>。
     */
    private static final int MAX_ALT_TEXT = 400;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LinePushClient(RestClient.Builder builder, LineProperties lineProperties,
            ObjectMapper objectMapper) {
        this.restClient = builder
                .defaultHeader("Authorization", "Bearer " + lineProperties.channelAccessToken())
                .build();
        this.objectMapper = objectMapper;
    }

    /** push 的回應。只取得到訊息 ID 就夠，quoteToken 目前沒用到。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PushResponse(List<SentMessage> sentMessages) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record SentMessage(String id, String quoteToken) {
        }
    }

    /** @return LINE 回傳的訊息 ID，供之後比對使用者的引用回覆；拿不到時回傳 null。 */
    public String pushText(String lineUserId, String text) {
        Map<String, Object> message = textMessage(text);
        return send(PUSH_URL, Map.of("to", lineUserId), message, "推播文字", lineUserId);
    }

    /**
     * 用 reply token 回覆。<b>不計入月額度</b>。
     *
     * @throws ReplyTokenExpiredException token 過期或已用過——呼叫端該改用推播
     */
    public String replyText(String replyToken, String text) {
        Map<String, Object> message = textMessage(text);
        return send(REPLY_URL, Map.of("replyToken", replyToken), message, "回覆文字", null);
    }

    private Map<String, Object> textMessage(String text) {
        String body = text.length() > MAX_TEXT_LENGTH
                ? text.substring(0, MAX_TEXT_LENGTH - 1) + "…"
                : text;
        if (text.length() > MAX_TEXT_LENGTH) {
            // 截斷是靜靜發生的，使用者只會看到訊息斷在一半。要看得見。
            log.warn("訊息內容超過 {} 字上限，已截斷：原長度={}", MAX_TEXT_LENGTH, text.length());
        }
        return Map.of("type", "text", "text", body);
    }

    /**
     * 推一張 Flex 卡片。
     *
     * <p>{@code flexJson} 是已經排好版的 {@code contents}，由 {@code CardRenderer}
     * 在寫下推播意圖的那個交易裡產生（理由見 {@code PushTextPayload}）。
     * 它以字串的形態經過 outbox，所以這裡要再解析回物件——
     * <b>序列化一次、解析一次、再序列化一次</b>。多的那一趟是有意的：
     * 換來的是 outbox 那一欄仍然只是文字，不必為了一種訊息型別改資料表。
     *
     * @param altText 被引用時、以及手機通知列上顯示的內容
     */
    public String pushFlex(String lineUserId, String altText, String flexJson) {
        return send(PUSH_URL, Map.of("to", lineUserId),
                flexMessage(altText, flexJson), "推播卡片", lineUserId);
    }

    /**
     * 用 reply token 回覆一張卡片。<b>不計入月額度</b>。
     *
     * @throws ReplyTokenExpiredException token 過期或已用過——呼叫端該改用推播
     */
    public String replyFlex(String replyToken, String altText, String flexJson) {
        return send(REPLY_URL, Map.of("replyToken", replyToken),
                flexMessage(altText, flexJson), "回覆卡片", null);
    }

    private Map<String, Object> flexMessage(String altText, String flexJson) {
        String alt = altText.length() > MAX_ALT_TEXT
                ? altText.substring(0, MAX_ALT_TEXT - 1) + "…"
                : altText;
        if (altText.length() > MAX_ALT_TEXT) {
            log.warn("altText 超過 {} 字上限，已截斷：原長度={}", MAX_ALT_TEXT, altText.length());
        }
        return Map.of(
                "type", "flex",
                "altText", alt,
                "contents", objectMapper.readValue(flexJson, Map.class));
    }

    /**
     * reply 與 push 只差在網址與收件欄位，其餘完全相同——連回應的形狀都一樣
     * （兩者都回 {@code sentMessages[].id}）。<b>那件事很重要</b>：
     * 訊息 id 是訊息錨點的鍵，reply 若不回傳它，使用者就無法引用回覆再改一次，
     * 決策 11 的指代解析會整條壞掉。
     *
     * @param kind 只為了讓 log 分得出來是哪一種——而且它現在還多了一個用途：
     *             數「回覆」與「推播」各有幾則，就知道免費額度實際省了多少
     */
    private String send(String url, Map<String, Object> target, Map<String, Object> message,
            String kind, String lineUserId) {
        long startedNanos = System.nanoTime();
        Map<String, Object> body = new java.util.HashMap<>(target);
        body.put("messages", List.of(message));

        return restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    // exchange() 不會因為 4xx/5xx 拋例外，狀態碼要自己檢查。
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw failure(url, response.getStatusCode(), response.bodyTo(Map.class),
                                lineUserId);
                    }
                    PushResponse parsed = response.bodyTo(PushResponse.class);
                    String messageId = (parsed == null || parsed.sentMessages() == null
                            || parsed.sentMessages().isEmpty())
                                    ? null
                                    : parsed.sentMessages().get(0).id();
                    // 不記內容——訊息內容就是使用者的筆記本體。
                    log.info("已送出{}：耗時={}ms lineMessageId={}",
                            kind, (System.nanoTime() - startedNanos) / 1_000_000, messageId);
                    return messageId;
                });
    }

    /**
     * 🔴 把「token 不能用了」跟其他失敗分開。
     *
     * <p>LINE 對過期或用過的 reply token 回 <b>400</b>，訊息是
     * {@code Invalid reply token}。那種重試一萬次也不會好——該立刻改用推播。
     * 而 5xx、逾時、連不上要退避重試，因為重試時 token 可能還活著，
     * 那一則就還是免費的。
     *
     * <p>不確定的一律當暫時：判斷保守的代價只是多吃一則額度，
     * 反過來把暫時判成永久，會白白放棄本來免費的那條路。
     */
    private static RuntimeException failure(String url, org.springframework.http.HttpStatusCode status,
            Map<?, ?> body, String lineUserId) {
        String message = body == null ? null : String.valueOf(body.get("message"));
        if (REPLY_URL.equals(url) && status.value() == 400
                && message != null && message.toLowerCase().contains("reply token")) {
            return new ReplyTokenExpiredException("reply token 不能用了：" + message);
        }
        return new IllegalStateException("LINE 回應 " + status
                + (message == null ? "" : "（" + message + "）")
                + (lineUserId == null ? "" : "，userId=" + lineUserId));
    }
}
