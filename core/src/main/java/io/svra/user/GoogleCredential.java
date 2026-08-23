package io.svra.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 一個使用者授權過的 Google 行事曆寫入權。沒授權的人<b>沒有這一列</b>。 */
@Entity
@Table(name = "google_credentials")
class GoogleCredential {

    @Id
    @Column(name = "line_user_id", length = 64, updatable = false)
    private String lineUserId;

    @Column(name = "refresh_token_encrypted", nullable = false)
    private String refreshTokenEncrypted;

    @Column(name = "calendar_id", nullable = false, length = 255)
    private String calendarId;

    @Column(name = "scope", nullable = false, length = 255)
    private String scope;

    @Column(name = "granted_at", nullable = false, insertable = false, updatable = false)
    private Instant grantedAt;

    /** NULL = 仍然有效。 */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected GoogleCredential() {
    }

    String getLineUserId() {
        return lineUserId;
    }

    String getRefreshTokenEncrypted() {
        return refreshTokenEncrypted;
    }

    String getCalendarId() {
        return calendarId;
    }

    String getScope() {
        return scope;
    }

    Instant getGrantedAt() {
        return grantedAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    boolean isActive() {
        return revokedAt == null;
    }
}
