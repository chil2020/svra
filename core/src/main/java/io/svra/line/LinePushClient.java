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
 * 主動推訊息給使用者。
 *
 * <p>用 push 而不是 reply：reply token 只有 30 秒有效期，而轉錄加抽取要跑更久，
 * 拿到結果時 token 早就過期了。代價是 push 有月額度而 reply 沒有。
 */
@Component
public class LinePushClient {

    private static final Logger log = LoggerFactory.getLogger(LinePushClient.class);
    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";

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
        String body = text.length() > MAX_TEXT_LENGTH
                ? text.substring(0, MAX_TEXT_LENGTH - 1) + "…"
                : text;
        if (text.length() > MAX_TEXT_LENGTH) {
            // 截斷是靜靜發生的，使用者只會看到訊息斷在一半。要看得見。
            log.warn("推播內容超過 {} 字上限，已截斷：原長度={}", MAX_TEXT_LENGTH, text.length());
        }
        return push(lineUserId, Map.of("type", "text", "text", body), "文字", body.length());
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
        String alt = altText.length() > MAX_ALT_TEXT
                ? altText.substring(0, MAX_ALT_TEXT - 1) + "…"
                : altText;
        if (altText.length() > MAX_ALT_TEXT) {
            log.warn("altText 超過 {} 字上限，已截斷：原長度={}", MAX_ALT_TEXT, altText.length());
        }
        Map<String, Object> message = Map.of(
                "type", "flex",
                "altText", alt,
                "contents", objectMapper.readValue(flexJson, Map.class));
        return push(lineUserId, message, "卡片", flexJson.length());
    }

    /**
     * @param kind 只為了讓 log 分得出來是哪一種訊息——兩種的失敗長得一樣，
     *             但要看的東西不一樣（字數超標 vs 卡片太大）
     */
    private String push(String lineUserId, Map<String, Object> message, String kind, int size) {
        long startedNanos = System.nanoTime();

        return restClient.post()
                .uri(PUSH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("to", lineUserId, "messages", List.of(message)))
                .exchange((request, response) -> {
                    // exchange() 不會因為 4xx/5xx 拋例外，狀態碼要自己檢查。
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException(
                                "LINE push 回應 " + response.getStatusCode() + "，userId=" + lineUserId);
                    }
                    PushResponse parsed = response.bodyTo(PushResponse.class);
                    String messageId = (parsed == null || parsed.sentMessages() == null
                            || parsed.sentMessages().isEmpty())
                                    ? null
                                    : parsed.sentMessages().get(0).id();
                    // 不記內容——推播內容就是使用者的筆記本體。
                    log.info("已推送{}：大小={} 耗時={}ms lineMessageId={}",
                            kind, size, (System.nanoTime() - startedNanos) / 1_000_000, messageId);
                    return messageId;
                });
    }
}
