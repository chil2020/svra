package io.svra.note;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.svra.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用者收回語音時，我們留下的東西要一起消失。
 *
 * <p>LINE 的開發指南明確要求處理收回事件。對這個系統而言那不只是禮貌：
 * 他按下收回的時候，語音早就轉錄完、逐字稿與抽出來的行程都在資料庫裡了。
 *
 * <p>🔴 <b>用真的資料庫測，而且非測不可：</b>刪除靠的是 {@code note_extractions}
 * 與 {@code note_items} 上的 {@code ON DELETE CASCADE}（V3）。那兩個約束原本只是
 * 資料完整性，在這裡變成了<b>刪除的正確性</b>——少了它們，逐字稿沒了而抽出來的
 * 行程還留著，而 mock 看不到這件事。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class ForgetMessageIntegrationTest {

    @Autowired
    private NoteService noteService;

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteExtractionRepository extractionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("收回語音 → 筆記、抽取結果、項目一起消失")
    void forgettingRemovesEverythingDownstream() {
        Seed seed = seed("U" + UUID.randomUUID().toString().replace("-", ""));

        noteService.forgetMessage(seed.userId(), seed.messageId());

        assertThat(noteRepository.findBySourceMessageId(seed.messageId())).isEmpty();
        // 少了 CASCADE，這兩行會是「還在」——而使用者以為東西刪掉了
        assertThat(extractionRepository.findById(seed.extractionId())).isEmpty();
        assertThat(itemCount(seed.extractionId())).isZero();
    }

    @Test
    @DisplayName("🔴 別人的 message id 刪不掉自己的東西")
    void anotherUsersMessageIdCannotDeleteYourNote() {
        Seed mine = seed("U-owner-" + UUID.randomUUID());

        // message id 猜不到，所以實務上踩不到——但「靠猜不到」跟「擋得住」是兩件事，
        // 而這是一個刪除操作。
        noteService.forgetMessage("U-someone-else", mine.messageId());

        assertThat(noteRepository.findBySourceMessageId(mine.messageId())).isPresent();
    }

    @Test
    @DisplayName("收回沒有筆記的訊息（貼圖、文字指令）→ 什麼都不做，也不能爆")
    void forgettingSomethingWeNeverStoredIsHarmless() {
        noteService.forgetMessage("U-whoever", "never-seen-" + UUID.randomUUID());
    }

    @Test
    @DisplayName("同一則收回兩次 → 第二次是沒事發生（LINE 是 at-least-once）")
    void forgettingTwiceIsSafe() {
        Seed seed = seed("U" + UUID.randomUUID().toString().replace("-", ""));

        noteService.forgetMessage(seed.userId(), seed.messageId());
        noteService.forgetMessage(seed.userId(), seed.messageId());

        assertThat(noteRepository.findBySourceMessageId(seed.messageId())).isEmpty();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private record Seed(String userId, String messageId, Long extractionId) {
    }

    private Seed seed(String userId) {
        String messageId = "audio-" + UUID.randomUUID();
        Long extractionId = new TransactionTemplate(transactionManager).execute(status -> {
            noteRepository.insertPendingIfAbsent(userId, messageId);
            Long noteId = noteRepository.findBySourceMessageId(messageId).orElseThrow().getId();
            NoteExtraction extraction = NoteExtraction.of(noteId, "test", "v-test");
            extraction.addItem(new NoteItem(NoteCategory.SCHEDULE, "開會",
                    java.time.Instant.parse("2026-08-25T07:00:00Z"), true, null, List.of()));
            return extractionRepository.save(extraction).getId();
        });
        return new Seed(userId, messageId, extractionId);
    }

    private int itemCount(Long extractionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM note_items WHERE extraction_id = ?", Integer.class,
                extractionId);
    }
}
