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

    /**
     * 這件事是為誰做的。
     *
     * <p>🔴 <b>不是為了業務邏輯，是為了出事時查得到。</b>aggregate_id 對語音是
     * notes.source_message_id，但對文字指令是指令的 message id、對 postback 是
     * webhookEventId——後兩者都不在 notes 裡，所以「這個使用者做過什麼」
     * 原本要靠掃描 payload 的 JSON 才查得到，而那沒有索引。
     *
     * <p>可為 null：舊資料補不回來，而且「不知道」比一個猜出來的值誠實。
     */
    @Column(name = "line_user_id", length = 64, updatable = false)
    private String lineUserId;

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

    private OutboxEvent(String aggregateId, String eventType, String lineUserId,
            String payload, String dedupeKey) {
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.lineUserId = lineUserId;
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
    public static OutboxEvent pending(String aggregateId, String eventType, String lineUserId,
            String payload) {
        return new OutboxEvent(aggregateId, eventType, lineUserId, payload, null);
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

    /**
     * 判死：不退避、不再重試。
     *
     * <p>給處理器丟出 {@link OutboxPermanentFailureException} 時用——
     * 那代表失敗的原因不會隨時間改變（授權被撤銷、行事曆被刪）。
     *
     * <p>{@code attempts} 照樣累加：它記的是「試過幾次」，而這一次<b>確實試過了</b>。
     * 把它留在 0 會讓 log 與資料看起來像是從沒送出過。
     */
    public void markPermanentlyFailed(String error) {
        this.attempts++;
        this.lastError = error;
        this.status = OutboxStatus.FAILED;
    }

    /**
     * 送出去了。
     *
     * <p>🔴 <b>payload 一併清掉，而那是刻意的。</b>
     *
     * <p>推播的 payload 裡有整張卡片（實測最長 2248 字元）——標題、時間、補充內容，
     * 也就是<b>使用者的筆記本體</b>。這個專案從一開始就有一條政策：log 不記內容
     * （見 {@code LinePushClient}、{@code NoteCommandParser} 的註解），
     * 理由是「推播內容就是使用者的筆記本體」。<b>而 outbox 這張表一直不受那條政策管。</b>
     *
     * <p>更實際的問題是它讓一個承諾做不到：使用者收回語音時我們刪掉 notes，
     * 但那則語音抽出來的卡片內容還躺在 outbox 裡，<b>而且永遠不會被清掉</b>。
     *
     * <p>SENT 的事件不會再被重跑，payload 對它已經沒有用途。FAILED 的<b>要留</b>
     * ——那時候需要它來理解到底發生了什麼，而那正是它唯一還有價值的時候。
     */
    public void markSent(Instant now) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
        this.payload = "";
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

    public String getLineUserId() {
        return lineUserId;
    }
}
