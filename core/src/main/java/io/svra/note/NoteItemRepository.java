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

    /**
     * 依 id 撈項目，<b>但只限這個使用者的</b>。
     *
     * <p>🔴 <b>這不是防禦性程式碼，是一條真實的攻擊路徑上的必要防線。</b>
     *
     * <p>這些 id 的來源是訊息錨點，而錨點是用「使用者引用的那則訊息 id」查出來的
     * ——那個值<b>來自 webhook，也就是使用者的裝置</b>。LINE 會轉發並簽章，
     * 所以驗簽擋不住它：簽的是 LINE 送來的內容，不是內容的真實性。
     *
     * <p>而 LINE 的 message id <b>不是猜不到的</b>。實際看過的幾筆：
     * {@code 628375010020688630}、{@code 628369132073255101}、{@code 628136367058059829}
     * ——同前綴、隨時間遞增。改過的 client 送一個偽造的 quotedMessageId 進來，
     * 就能拿到別人的項目，然後「刪掉第一筆」。
     *
     * <p>錨點那一層已經先擋一次（見 {@code MessageAnchors}），這裡是第二道：
     * <b>id 從哪裡來的不重要，動到的東西一定要是他自己的。</b>
     */
    @Query("""
            select i from NoteItem i
              join i.extraction e
              join Note n on n.id = e.noteId
             where n.lineUserId = :lineUserId and i.id in :ids
            """)
    List<NoteItem> findAllByIdAndUser(@Param("lineUserId") String lineUserId,
            @Param("ids") java.util.Collection<Long> ids);

    /** 單筆版本。同樣的理由，同樣不能省。 */
    @Query("""
            select i from NoteItem i
              join i.extraction e
              join Note n on n.id = e.noteId
             where n.lineUserId = :lineUserId and i.id = :id
            """)
    java.util.Optional<NoteItem> findByIdAndUser(@Param("lineUserId") String lineUserId,
            @Param("id") Long id);
}

