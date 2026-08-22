package io.svra.notify;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 某一筆 outbox 事件的訊息已經送出去了。存在即代表「不要再送一次」。 */
@Entity
@Table(name = "outbox_deliveries")
class OutboxDelivery {

    @Id
    @Column(name = "outbox_event_id", updatable = false)
    private Long outboxEventId;

    @Column(name = "line_user_id", nullable = false, length = 64, updatable = false)
    private String lineUserId;

    /** LINE 給的訊息 id。推播失敗拿不到時為 null——但那時根本不會走到這裡。 */
    @Column(name = "line_message_id", length = 64, updatable = false)
    private String lineMessageId;

    @Column(name = "delivered_at", nullable = false, insertable = false, updatable = false)
    private Instant deliveredAt;

    protected OutboxDelivery() {
    }

    Long getOutboxEventId() {
        return outboxEventId;
    }

    String getLineUserId() {
        return lineUserId;
    }

    String getLineMessageId() {
        return lineMessageId;
    }

    Instant getDeliveredAt() {
        return deliveredAt;
    }
}
