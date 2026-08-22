package io.svra.notify;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 已經封鎖或刪除這個官方帳號的使用者。存在即代表「不要再對他做事」。 */
@Entity
@Table(name = "blocked_users")
class BlockedUser {

    @Id
    @Column(name = "line_user_id", length = 64, updatable = false)
    private String lineUserId;

    /** 由資料庫的預設值填。這裡只讀不寫。 */
    @Column(name = "blocked_at", nullable = false, insertable = false, updatable = false)
    private Instant blockedAt;

    protected BlockedUser() {
    }

    String getLineUserId() {
        return lineUserId;
    }

    Instant getBlockedAt() {
        return blockedAt;
    }
}
