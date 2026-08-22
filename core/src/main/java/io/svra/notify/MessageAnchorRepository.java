package io.svra.notify;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MessageAnchorRepository extends JpaRepository<MessageAnchor, String> {

    /**
     * 引用回覆走這條。
     *
     * <p>🔴 <b>用 {@code findById} 就錯了，即使 lineMessageId 是主鍵。</b>
     * 那個值來自 webhook 的 {@code quotedMessageId}，也就是使用者的裝置；
     * 而 LINE 的 message id 同前綴、隨時間遞增，<b>猜得到</b>。
     * 少了 lineUserId，一個偽造的引用就能拿到別人的項目清單。
     */
    Optional<MessageAnchor> findByLineMessageIdAndLineUserId(String lineMessageId,
            String lineUserId);

    /**
     * 卡片按鈕指回來時走這條——它帶的是 card_id，不是 LINE 的 message id。
     *
     * <p><b>{@code findFirst} 不是防禦性寫法，是必要的</b>：card_id 上沒有唯一約束
     * （理由見 V10），而推播的 at-least-once 會讓同一張卡以兩個 message id 存在。
     * 那兩列列的是同一份項目，取哪一列都對——取最新的，因為它對應使用者
     * 眼前那則訊息。
     */
    Optional<MessageAnchor> findFirstByCardIdAndLineUserIdOrderByCreatedAtDesc(
            String cardId, String lineUserId);

    /** 清掉很久以前的錨點。保留期比 outbox 長得多，理由見 {@code NotifyRetention}。 */
    @Modifying
    @Query(value = "DELETE FROM message_anchors WHERE created_at < :before", nativeQuery = true)
    int deleteCreatedBefore(@Param("before") java.time.Instant before);
}
