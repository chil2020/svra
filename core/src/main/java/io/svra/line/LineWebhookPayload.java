package io.svra.line;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * LINE webhook 的事件內容，只取用得到的欄位。
 *
 * <p>每層都 {@code ignoreUnknown}：LINE 隨時可能新增欄位，嚴格模式會讓
 * 反序列化失敗 → 回 500 → LINE 重送 → 一直失敗。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LineWebhookPayload(List<Event> events) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String type, Source source, Message message) {

        /** 文字訊息＝使用者在下指令（刪除、修改行程等）。 */
        public boolean isTextMessage() {
            return "message".equals(type)
                    && message != null
                    && "text".equals(message.type());
        }

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
    public record Message(String id, String type, String text, String quotedMessageId) {
    }
}
