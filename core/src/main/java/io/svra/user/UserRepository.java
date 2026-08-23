package io.svra.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<User, String> {

    /**
     * 確保這個人有一列，已經有就什麼都不做。
     *
     * <p>🔴 <b>這是所有外鍵的前提</b>：notes、message_anchors、outbox_events……
     * 都指向這張表，所以「使用者列存在」必須發生在任何一筆使用者資料之前。
     *
     * <p>走原生 upsert 而不是 {@code save()}，理由跟決策 2 一樣：
     * 一個 webhook 可以帶好幾則事件，讓 JPA 去撞主鍵會把整個交易標成
     * rollback-only，而那個交易還要處理同一批裡的其他事件。
     *
     * @return 1 = 第一次見到這個人；0 = 已經認識了
     */
    @Modifying
    @Query(value = """
            INSERT INTO users (line_user_id) VALUES (:lineUserId)
            ON CONFLICT (line_user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("lineUserId") String lineUserId);

    /**
     * 標記封鎖。已經是封鎖狀態就不動——否則每次收到重複的 unfollow
     * 都會把時間往後推，而「他什麼時候封鎖的」就再也問不出來。
     */
    @Modifying
    @Query(value = """
            UPDATE users SET blocked_at = :now
             WHERE line_user_id = :lineUserId AND blocked_at IS NULL
            """, nativeQuery = true)
    int markBlocked(@Param("lineUserId") String lineUserId,
            @Param("now") java.time.Instant now);

    @Modifying
    @Query(value = """
            UPDATE users SET blocked_at = NULL
             WHERE line_user_id = :lineUserId AND blocked_at IS NOT NULL
            """, nativeQuery = true)
    int clearBlocked(@Param("lineUserId") String lineUserId);

    boolean existsByLineUserIdAndBlockedAtIsNotNull(String lineUserId);
}
