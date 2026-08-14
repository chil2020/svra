package io.svra.note;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteExtractionRepository extends JpaRepository<NoteExtraction, Long> {

    Optional<NoteExtraction> findByNoteIdAndActiveTrue(Long noteId);

    List<NoteExtraction> findByNoteIdOrderByCreatedAtDesc(Long noteId);

    /** 使用者引用某則推播下指令時，用推播的訊息 ID 反查是哪一批項目。 */
    Optional<NoteExtraction> findByNotifyMessageId(String notifyMessageId);
}
