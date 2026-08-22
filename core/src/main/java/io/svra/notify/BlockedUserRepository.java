package io.svra.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BlockedUserRepository extends JpaRepository<BlockedUser, String> {

    /**
     * 記下封鎖，已經記過就什麼都不做。
     *
     * <p>走原生 upsert 而不是 {@code save()}，理由跟決策 2 一模一樣：
     * 使用者可以封鎖、解除、再封鎖，而 LINE 的 webhook 是 at-least-once——
     * 讓 JPA 去撞主鍵會把整個交易標成 rollback-only，而 webhook 那個交易
     * 還要處理同一批裡的其他事件。
     */
    @Modifying
    @Query(value = """
            INSERT INTO blocked_users (line_user_id) VALUES (:lineUserId)
            ON CONFLICT (line_user_id) DO NOTHING
            """, nativeQuery = true)
    int blockIfAbsent(@Param("lineUserId") String lineUserId);
}
