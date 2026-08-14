package io.svra.note;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<Note, Long> {

    /**
     * ⚠️ 不能拿來當冪等機制用——「先查再插」有 race condition。
     * 冪等靠 DB 的 UNIQUE 約束；這個查詢用於已知訊息存在時把它撈出來。
     */
    Optional<Note> findBySourceMessageId(String sourceMessageId);

    /** 使用者沒引用特定推播時，指令套用在最近一則筆記上。 */
    Optional<Note> findTopByLineUserIdOrderByIdDesc(String lineUserId);

    List<Note> findByLineUserIdOrderByCreatedAtDesc(String lineUserId);
}
