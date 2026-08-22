package io.svra.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxDeliveryRepository extends JpaRepository<OutboxDelivery, Long> {

    /**
     * 記下「這筆事件的訊息送出去了」，已經記過就什麼都不做。
     *
     * <p>跟決策 2 那三個 {@code insertIfAbsent} 是同一個模式：衝突由資料庫原子地吞掉，
     * 不拋例外、不弄髒交易。兩個 poller 同時處理同一筆是不會發生的
     * （{@code SKIP LOCKED} 擋著），但「先查再寫」在任何情況下都不是冪等，
     * 而這一行的存在意義就是冪等。
     */
    @Modifying
    @Query(value = """
            INSERT INTO outbox_deliveries (outbox_event_id, line_user_id, line_message_id)
            VALUES (:outboxEventId, :lineUserId, :lineMessageId)
            ON CONFLICT (outbox_event_id) DO NOTHING
            """, nativeQuery = true)
    int recordIfAbsent(@Param("outboxEventId") long outboxEventId,
            @Param("lineUserId") String lineUserId,
            @Param("lineMessageId") String lineMessageId);

    @Modifying
    @Query(value = "DELETE FROM outbox_deliveries WHERE delivered_at < :before",
            nativeQuery = true)
    int deleteDeliveredBefore(@Param("before") java.time.Instant before);
}
