package io.svra.note;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteItemRepository extends JpaRepository<NoteItem, Long> {

    /**
     * 這位使用者「還沒過去」的項目，跨所有語音。
     *
     * <p>只看最後一則語音是不夠的——使用者問「現在有什麼行程」，
     * 要的是全部待辦事項，不是最後那則的內容。
     *
     * <p>沒有時間的項目（待辦、想法）也一併列出：它們沒有「過期」的概念，
     * 不會因為時間經過就自動失效。
     */
    @Query("""
            select i from NoteItem i
              join i.extraction e
              join Note n on n.id = e.noteId
             where n.lineUserId = :lineUserId
               and e.active = true
               and (i.occursAt is null or i.occursAt >= :from)
            """)
    List<NoteItem> findUpcoming(@Param("lineUserId") String lineUserId,
            @Param("from") Instant from);
}
