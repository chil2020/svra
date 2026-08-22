package io.svra.notify;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「使用者引用的那則訊息，當時秀的是哪幾筆」。
 *
 * <p>放在 notify 是因為<b>訊息是這裡送出去的</b>——誰送的誰知道它裝了什麼。
 * command 讀它來解析「第三筆」，方向與現有的相依一致（command → notify）。
 */
@Service
public class MessageAnchors {

    private static final Logger log = LoggerFactory.getLogger(MessageAnchors.class);

    private final MessageAnchorRepository repository;
    private final Clock clock;

    MessageAnchors(MessageAnchorRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 記下一則剛送出去的清單訊息。
     *
     * <p>推播失敗時拿不到 messageId，那則訊息也不存在，沒有東西要錨——直接跳過。
     *
     * @param cardId 這則訊息若是 Flex 卡片，就是卡片上按鈕帶的那個 id；
     *               純文字訊息沒有按鈕，給 null
     */
    @Transactional
    public void record(String lineMessageId, String cardId, String lineUserId,
            List<Long> itemIds) {
        if (lineMessageId == null || itemIds.isEmpty()) {
            return;
        }
        repository.save(new MessageAnchor(
                lineMessageId, cardId, lineUserId, itemIds, Instant.now(clock)));
        log.debug("已記下訊息錨點：lineMessageId={} cardId={} 項目數={}",
                lineMessageId, cardId, itemIds.size());
    }

    /**
     * @param lineUserId 🔴 <b>一定要帶，而且一定要進 where。</b>
     *                   {@code lineMessageId} 來自 webhook 的 {@code quotedMessageId}——
     *                   也就是<b>使用者的裝置</b>。LINE 會轉發並簽章，所以驗簽擋不住它：
     *                   簽的是「這是 LINE 送來的」，不是「這個值是真的」。
     *                   <p>而 LINE 的 message id <b>不是猜不到的</b>：實際資料裡的幾筆
     *                   同前綴、隨時間遞增。改過的 client 送一個偽造的 quotedMessageId，
     *                   就能拿到別人的項目清單，然後「刪掉第一筆」。
     *                   <p>這一欄從 V7 就在表上了，只是查詢沒有用它。
     * @return 那則訊息當時秀的項目 id，順序即編號；不是這個人的訊息時為空
     */
    @Transactional(readOnly = true)
    public Optional<List<Long>> itemIdsFor(String lineUserId, String lineMessageId) {
        return repository.findByLineMessageIdAndLineUserId(lineMessageId, lineUserId)
                .map(MessageAnchor::getItemIds);
    }

    /**
     * 卡片上的按鈕被按下時，反查那張卡當時列了哪幾筆。
     *
     * <p>跟 {@link #itemIdsFor} 查的是同一列，只是換一把鑰匙——
     * 引用回覆帶的是 LINE 的 message id，按鈕帶的是我們自己給的卡片 id
     * （為什麼要兩把，見 V10）。
     *
     * <p>同樣要帶使用者：postback 的 {@code data} 一樣是從裝置送上來的，
     * 而卡片 id 雖然是 16 個隨機字元（猜不到），<b>「猜不到」不是一道防線</b>
     * ——那正是 message id 那邊犯過的錯。
     *
     * @return 對不上時為空。卡片可能是重啟前的舊版本、資料已經被清掉，或不是這個人的
     */
    @Transactional(readOnly = true)
    public Optional<List<Long>> itemIdsForCard(String lineUserId, String cardId) {
        return repository.findFirstByCardIdAndLineUserIdOrderByCreatedAtDesc(cardId, lineUserId)
                .map(MessageAnchor::getItemIds);
    }
}
