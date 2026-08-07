package io.svra.note;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Note 的資料存取。
 *
 * <p>方法名稱即查詢（derived query），Spring Data 會在啟動時解析成 SQL——
 * 打錯字是啟動就失敗，不是執行期才炸。
 */
public interface NoteRepository extends JpaRepository<Note, Long> {

    /**
     * 冪等時用來確認「這則訊息是不是已經處理過」。
     *
     * <p>⚠️ 注意：這個方法**不能**單獨拿來當冪等機制用
     * （先查再插有 race condition）。真正的保證是 DB 的 UNIQUE 約束——
     * 這個查詢只用於「已經捕捉到唯一鍵衝突之後，要把既有那筆撈出來」的場景。
     */
    Optional<Note> findBySourceMessageId(String sourceMessageId);

    /** 某使用者的筆記，新到舊（對應 idx_notes_user_created 索引）。 */
    List<Note> findByLineUserIdOrderByCreatedAtDesc(String lineUserId);
}
