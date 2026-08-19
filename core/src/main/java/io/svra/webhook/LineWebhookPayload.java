package io.svra.webhook;

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
            return isMessageOfType("text");
        }

        public boolean isAudioMessage() {
            return isMessageOfType("audio");
        }

        /**
         * 這裡<b>也要檢查 source</b>。整份 payload 的每一層都做了 ignoreUnknown，
         * 就是為了「LINE 加欄位不會讓我們回 500 → 被無限重送」——而
         * {@code source.userId()} 一度是直接取值的，群組來源或非使用者事件
         * 拿不到 userId 時就是一個 NPE，剛好破了那個例。
         *
         * <p>回錯的東西只會讓 LINE 再送一次（決策 1），所以「收得下但不處理」
         * 一定要好過「處理不了就爆掉」。
         */
        private boolean isMessageOfType(String messageType) {
            return "message".equals(type)
                    && message != null
                    && messageType.equals(message.type())
                    && source != null
                    && source.userId() != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String type, String userId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String type, String text, String quotedMessageId) {
    }
}
