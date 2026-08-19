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

    /**
     * 「這件事只該做一次」的鍵，資料庫上有部分唯一索引（見 V5）。
     *
     * <p>可以是 null——不是每種事件都只該發生一次。填了就代表重複寫入會被資料庫擋下，
     * 由呼叫端捕捉 {@code DataIntegrityViolationException} 當成「已經記過了」。
     */
    @Column(name = "dedupe_key", length = 160, updatable = false)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(String aggregateId, String eventType, String payload, String dedupeKey) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.dedupeKey = dedupeKey;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
        this.createdAt = Instant.now();
    }

    /**
     * 可重複發生的事件（例如重跑抽取會再推播一次）。
     *
     * <p>{@code nextAttemptAt} 設成建立當下＝「立刻到期」。這是唯一還在實體裡
     * 讀時鐘的地方，因為它表達的是「不用等」而不是一個要跟別人比對的時間點。
     */
    public static OutboxEvent pending(String aggregateId, String eventType, String payload) {
        return new OutboxEvent(aggregateId, eventType, payload, null);
    }

    /**
     * 只該發生一次的事件，其冪等鍵。
     *
     * <p>用在「來源是我們控制不了投遞次數的入站訊息」——LINE 的 webhook 是
     * at-least-once，同一則訊息可能進來好幾次。內部產生的事件不需要：
     * 它們寫在有自己守衛的交易裡（例如 note 的狀態轉移）。
     *
     * <p>帶鍵的寫入走 {@code OutboxEventRepository.insertIfAbsent}，不走這個實體——
     * 讓 JPA 去撞唯一鍵會弄髒整個交易，理由寫在那個方法上。
     */
    public static String dedupeKeyFor(String eventType, String sourceMessageId) {
        return eventType + ":" + sourceMessageId;
    }

    public void markSent(Instant now) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    /**
     * 送失敗：累加次數並排下次重試（指數退避），超過上限就標 FAILED。
     *
     * <p>退避是必要的——RabbitMQ 掛掉時若每秒重試，只會在它恢復的瞬間被打爆。
     *
     * <p>時刻由呼叫端給，不在這裡讀時鐘：{@code next_attempt_at} 寫進去之後，
     * 是要拿去跟 poller 查詢時的「現在」比大小的。<b>兩邊必須是同一個時鐘</b>，
     * 而讓實體自己去讀，就沒有任何地方保證得了這件事。
     */
    public void markFailed(String error, Instant now) {
        this.attempts++;
        this.lastError = error;
        if (this.attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.FAILED;
        } else {
            long backoffSec = (long) Math.pow(2, this.attempts);
            this.nextAttemptAt = now.plusSeconds(backoffSec);
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

    public String getDedupeKey() {
        return dedupeKey;
    }
}
