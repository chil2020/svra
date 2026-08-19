package io.svra.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 取出下一批待送事件並鎖住。<b>必須在交易內呼叫。</b>
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 是多實例安全的關鍵：
     * 每個 poller 只拿得到別人沒鎖住的列，各自處理不重疊的批次。
     * 少了 {@code SKIP LOCKED} 會互相卡住；少了 {@code FOR UPDATE} 會重複處理同一批。
     *
     * <p>🔴 <b>到期時間比對用呼叫端傳進來的時刻，不用資料庫的 {@code now()}。</b>
     * {@code next_attempt_at} 是應用程式的時鐘寫的，拿資料庫的時鐘去比就是
     * <b>兩個時鐘在比大小</b>。整合測試抓到過：剛寫入的事件因為 JVM 比容器快幾毫秒，
     * 被自己的查詢判定成「還沒到期」而整批漏撈。本機只是延後一輪（2 秒）看不出來，
     * 但 app 與資料庫分開部署時（K8s）偏移會是秒級，退避時間就完全不是設定的那個值。
     *
     * @param now 呼叫端的「現在」，與寫入 next_attempt_at 用的是同一個時鐘
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit, @Param("now") Instant now);

    /**
     * 寫入一筆只該發生一次的事件，已經有同一個 dedupe_key 就什麼都不做。
     *
     * <p>理由與 {@code NoteRepository.insertPendingIfAbsent} 完全相同：
     * 讓唯一鍵衝突拋例外會弄髒整個交易，而這裡的交易還要寫別的東西。
     *
     * <p>{@code WHERE dedupe_key IS NOT NULL} 必須跟 V5 的部分唯一索引一字不差，
     * PostgreSQL 才推得出要用哪個索引來判斷衝突。
     *
     * @return 1 = 這次真的寫入了；0 = 這則訊息已經記過了
     */
    @Modifying
    @Query(value = """
            INSERT INTO outbox_events (aggregate_id, event_type, payload, status, dedupe_key)
            VALUES (:aggregateId, :eventType, :payload, 'PENDING', :dedupeKey)
            ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("aggregateId") String aggregateId,
            @Param("eventType") String eventType,
            @Param("payload") String payload,
            @Param("dedupeKey") String dedupeKey);
}
