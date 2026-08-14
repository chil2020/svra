package io.svra.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 取出下一批待送事件並鎖住。<b>必須在交易內呼叫。</b>
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 是多實例安全的關鍵：
     * 每個 poller 只拿得到別人沒鎖住的列，各自處理不重疊的批次。
     * 少了 {@code SKIP LOCKED} 會互相卡住；少了 {@code FOR UPDATE} 會重複處理同一批。
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= now()
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);
}
