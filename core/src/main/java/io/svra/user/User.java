package io.svra.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一個用過這個 bot 的人。
 *
 * <p>這個系統<b>沒有註冊</b>——使用者是 webhook 帶進來的。所以這張表不是
 * 「帳號」，而是「所有關於這個人的事實掛在哪裡」：他被封鎖了嗎、
 * 他授權過行事曆嗎（{@code google_credentials}）、他的資料有哪些（外鍵）。
 *
 * <p>在它存在之前，這些事實散在四個地方，其中三個是環境變數——
 * 而環境變數天生只能有一份。
 */
@Entity
@Table(name = "users")
class User {

    @Id
    @Column(name = "line_user_id", length = 64, updatable = false)
    private String lineUserId;

    /** 由資料庫的預設值填。這裡只讀不寫。 */
    @Column(name = "first_seen_at", nullable = false, insertable = false, updatable = false)
    private Instant firstSeenAt;

    /** NULL = 沒被封鎖。 */
    @Column(name = "blocked_at")
    private Instant blockedAt;

    protected User() {
    }

    String getLineUserId() {
        return lineUserId;
    }

    Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    Instant getBlockedAt() {
        return blockedAt;
    }
}
