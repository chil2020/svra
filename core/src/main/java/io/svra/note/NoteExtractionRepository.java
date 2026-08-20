package io.svra.note;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteExtractionRepository extends JpaRepository<NoteExtraction, Long> {

    Optional<NoteExtraction> findByNoteIdAndActiveTrue(Long noteId);

    List<NoteExtraction> findByNoteIdOrderByCreatedAtDesc(Long noteId);
}
