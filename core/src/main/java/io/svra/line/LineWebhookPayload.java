package io.svra.line;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LINE webhook 送來的 JSON 結構（只取我們用得到的欄位）。
 *
 * <p><b>為什麼每一層都加 {@code @JsonIgnoreProperties(ignoreUnknown = true)}：</b>
 * LINE 的事件物件有幾十個欄位，而且 LINE 隨時可能新增。若用嚴格模式，
 * 對方多送一個我們沒宣告的欄位就會反序列化失敗 → webhook 回 500 → LINE 判定失敗
 * → 重送 → 一直失敗。<b>對上游來的資料要寬鬆，對自己送出去的要嚴格</b>
 * （Postel's law）。
 *
 * <p>實際的 payload 長這樣（省略無關欄位）：
 * <pre>{@code
 * {
 *   "destination": "U...",
 *   "events": [{
 *     "type": "message",
 *     "replyToken": "...",
 *     "source":  { "type": "user", "userId": "U4af4980629..." },
 *     "message": { "id": "325708", "type": "audio", "duration": 3000 }
 *   }]
 * }
 * }</pre>
 *
 * <p>⚠️ 這個 record 只用於「解析內容」，<b>不能</b>拿它序列化回去驗簽——
 * 驗簽必須對原始的 request body 字元算，反序列化再序列化會改變空白與欄位順序。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LineWebhookPayload(List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String type, Source source, Message message) {

        /** 是不是「使用者傳了一則語音訊息」——目前唯一要處理的事件。 */
        public boolean isAudioMessage() {
            return "message".equals(type)
                    && message != null
                    && "audio".equals(message.type());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String type, String userId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String type) {
    }
}
