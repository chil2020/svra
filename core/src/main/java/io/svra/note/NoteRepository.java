package io.svra.note;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteRepository extends JpaRepository<Note, Long> {

    /**
     * 建立一筆 PENDING 的 note，已經有同一個 source_message_id 就什麼都不做。
     *
     * <p>🔴 <b>為什麼不是 save() + catch DataIntegrityViolationException。</b>
     * 那樣寫看起來可行，實測卻是壞的：唯一鍵衝突會讓 Hibernate 把交易標成
     * rollback-only，就算把例外接住，外層 commit 時仍會拋
     * {@code UnexpectedRollbackException}——它穿過 catch 傳到 webhook，
     * 回 500，LINE 再重送。<b>冪等機制反而變成無限重送的來源。</b>
     *
     * <p>而「把 insert 移到 REQUIRES_NEW 的內層交易」在這裡也不行：
     * note 與 outbox 事件<b>必須同進同退</b>，拆成兩個交易就等於放棄 outbox
     * 的全部價值（決策 3）。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 同時解決兩邊：衝突由資料庫原子地吞掉、
     * 不拋例外、不弄髒交易，而判斷依然發生在資料庫層——決策 2 的論點原封不動。
     * 後來者會阻塞到先來者提交為止，所以「回傳 1」的執行緒有且只有一個。
     *
     * @return 1 = 這次真的建立了；0 = 已經存在（重複投遞）
     */
    @Modifying
    @Query(value = """
            INSERT INTO notes (line_user_id, source_message_id, status)
            VALUES (:lineUserId, :sourceMessageId, 'PENDING')
            ON CONFLICT ON CONSTRAINT uk_notes_source_message_id DO NOTHING
            """, nativeQuery = true)
    int insertPendingIfAbsent(@Param("lineUserId") String lineUserId,
            @Param("sourceMessageId") String sourceMessageId);

    /**
     * ⚠️ 不能拿來當冪等機制用——「先查再插」有 race condition。
     * 冪等靠 DB 的 UNIQUE 約束；這個查詢用於已知訊息存在時把它撈出來。
     */
    Optional<Note> findBySourceMessageId(String sourceMessageId);

    /** 使用者沒引用特定推播時，指令套用在最近一則筆記上。 */
    Optional<Note> findTopByLineUserIdOrderByIdDesc(String lineUserId);

    List<Note> findByLineUserIdOrderByCreatedAtDesc(String lineUserId);
}
