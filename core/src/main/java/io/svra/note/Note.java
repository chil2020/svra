package io.svra.note;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 一則語音筆記。
 *
 * <p>schema 由 Flyway 管理（{@code V1__init.sql}），這裡只做映射——
 * {@code spring.jpa.hibernate.ddl-auto=validate} 會在啟動時檢查兩者是否一致，
 * 不一致就啟動失敗。單一真相來源是 SQL，不是 entity。
 */
@Entity
@Table(name = "notes")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 對應 BIGSERIAL
    private Long id;

    @Column(name = "line_user_id", nullable = false, length = 64)
    private String lineUserId;

    /**
     * LINE 訊息 ID——**冪等的鍵**。
     * DB 上有 UNIQUE 約束，重送時第二筆 INSERT 會被擋下。
     */
    @Column(name = "source_message_id", nullable = false, length = 64, updatable = false)
    private String sourceMessageId;

    /**
     * 一定要用 {@link EnumType#STRING}。
     * 預設的 ORDINAL 存的是序數（0、1、2），日後在 enum 中間插入一個值，
     * 資料庫裡的舊資料就會全部對應錯——這是 JPA 的經典地雷。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NoteStatus status;

    @Column(columnDefinition = "text")
    private String transcript;

    @Column(length = 16)
    private String language;

    @Column(name = "audio_duration_sec")
    private Float audioDurationSec;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求無參建構子；設成 protected 讓外部無法誤用。 */
    protected Note() {
    }

    private Note(String lineUserId, String sourceMessageId) {
        this.lineUserId = lineUserId;
        this.sourceMessageId = sourceMessageId;
        this.status = NoteStatus.PENDING;
    }

    /** 收到語音訊息、送出轉錄任務時建立。 */
    public static Note pending(String lineUserId, String sourceMessageId) {
        return new Note(lineUserId, sourceMessageId);
    }

    /** 轉錄成功：補上結果並轉為 COMPLETED。 */
    public void complete(String transcript, String language, Float audioDurationSec) {
        this.transcript = transcript;
        this.language = language;
        this.audioDurationSec = audioDurationSec;
        this.status = NoteStatus.COMPLETED;
    }

    /** 轉錄失敗（worker 例外／進 DLQ）。 */
    public void fail() {
        this.status = NoteStatus.FAILED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getLineUserId() {
        return lineUserId;
    }

    public String getSourceMessageId() {
        return sourceMessageId;
    }

    public NoteStatus getStatus() {
        return status;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getLanguage() {
        return language;
    }

    public Float getAudioDurationSec() {
        return audioDurationSec;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
