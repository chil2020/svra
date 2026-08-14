package io.svra.outbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /** 重試上限。超過就標 FAILED，不再自動重送，等人來看。 */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(String aggregateId, String eventType, String payload) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
    }

    public static OutboxEvent pending(String aggregateId, String eventType, String payload) {
        return new OutboxEvent(aggregateId, eventType, payload);
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    /**
     * 送失敗：累加次數並排下次重試（指數退避），超過上限就標 FAILED。
     *
     * <p>退避是必要的——RabbitMQ 掛掉時若每秒重試，只會在它恢復的瞬間被打爆。
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error;
        if (this.attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.FAILED;
        } else {
            long backoffSec = (long) Math.pow(2, this.attempts);
            this.nextAttemptAt = Instant.now().plusSeconds(backoffSec);
        }
    }

    public Long getId() {
        return id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
