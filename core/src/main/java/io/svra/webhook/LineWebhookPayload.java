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

    /**
     * @param webhookEventId LINE 給每個事件的 id。<b>重送時不變</b>，
     *                       所以它是 postback 這條路唯一的冪等鍵——
     *                       postback 沒有 message id，而語音與文字指令用的是那個
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String type, String webhookEventId, String replyToken,
            Source source, Message message, Postback postback, Unsend unsend) {

        /** 文字訊息＝使用者在下指令（刪除、修改行程等）。 */
        public boolean isTextMessage() {
            return isMessageOfType("text");
        }

        public boolean isAudioMessage() {
            return isMessageOfType("audio");
        }

        /**
         * 使用者把這個官方帳號加為好友（或封鎖之後又解除）。
         *
         * <p>這是新使用者的<b>第一個</b>事件，而且它帶 replyToken——
         * 歡迎訊息因此不計入免費額度（決策 28）。
         */
        public boolean isFollow() {
            return "follow".equals(type) && source != null && source.userId() != null;
        }

        /**
         * 使用者封鎖了這個官方帳號，或把它從好友刪除。
         *
         * <p>沒有 replyToken——他已經收不到訊息了，這只是一則通知。
         */
        public boolean isUnfollow() {
            return "unfollow".equals(type) && source != null && source.userId() != null;
        }

        /**
         * 使用者收回了一則訊息。
         *
         * <p>LINE 的開發指南明確要求處理它：
         * <i>"Process unsend events to respect user preferences and prevent unsent
         * messages from remaining accessible."</i>
         *
         * <p>對這個系統而言那不只是禮貌問題：他收回那則語音時，我們早就轉錄完
         * 並把逐字稿存進資料庫了。自己用無所謂，<b>別人的語音就是另一回事</b>。
         */
        public boolean isUnsend() {
            return "unsend".equals(type)
                    && unsend != null
                    && unsend.messageId() != null
                    && source != null
                    && source.userId() != null;
        }

        /**
         * 使用者按了訊息卡片上的按鈕。
         *
         * <p>{@code source} 與 {@code webhookEventId} 一起檢查，理由同
         * {@link #isMessageOfType}：少了任一個，後面就是一個 NPE，
         * 而 NPE 會讓 webhook 回 500、讓 LINE 重送、然後再爆一次。
         */
        public boolean isPostback() {
            return "postback".equals(type)
                    && postback != null
                    && postback.data() != null
                    && source != null
                    && source.userId() != null
                    && webhookEventId != null;
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

    /**
     * 按鈕帶回來的東西。
     *
     * <p>只有 {@code data}——LINE <b>不會</b>告訴我們那顆按鈕在哪一則訊息上，
     * 所以「這張卡列了哪幾筆」必須自己塞進 data 裡（見 V10 與 {@code CardRenderer}）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Postback(String data) {
    }

    /**
     * 被收回的那則訊息。
     *
     * @param messageId 對應 {@code notes.source_message_id}——語音是唯一會留下資料的
     *                  訊息型別，所以這個 id 足以把該忘掉的東西找出來
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Unsend(String messageId) {
    }
}
