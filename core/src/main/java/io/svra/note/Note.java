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

/** schema 由 Flyway 管理，這裡只做映射（ddl-auto=validate 會檢查兩者一致）。 */
@Entity
@Table(name = "notes")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // 對應 BIGSERIAL
    private Long id;

    @Column(name = "line_user_id", nullable = false, length = 64)
    private String lineUserId;

    /** 冪等的鍵，DB 上有 UNIQUE 約束。 */
    @Column(name = "source_message_id", nullable = false, length = 64, updatable = false)
    private String sourceMessageId;

    /** 一定要 STRING。預設的 ORDINAL 存序數，日後在 enum 中間插值會讓舊資料全錯。 */
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

    /** JPA 要求無參建構子。 */
    protected Note() {
    }

    private Note(String lineUserId, String sourceMessageId) {
        this.lineUserId = lineUserId;
        this.sourceMessageId = sourceMessageId;
        this.status = NoteStatus.PENDING;
    }

    public static Note pending(String lineUserId, String sourceMessageId) {
        return new Note(lineUserId, sourceMessageId);
    }

    public void complete(String transcript, String language, Float audioDurationSec) {
        this.transcript = transcript;
        this.language = language;
        this.audioDurationSec = audioDurationSec;
        this.status = NoteStatus.COMPLETED;
    }

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
