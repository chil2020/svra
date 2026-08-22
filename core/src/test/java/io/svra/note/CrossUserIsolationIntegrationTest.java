package io.svra.note;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.svra.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一個使用者拿到另一個使用者的項目 id，也不能碰到它。
 *
 * <p>🔴 <b>這條路是真的走得通的，直到補上這道防線為止。</b>
 * 指令與匯入用的項目 id 來自訊息錨點，而錨點是用使用者裝置送上來的
 * {@code quotedMessageId} 或 postback {@code data} 查出來的。驗簽擋不住那個值——
 * 簽的是「這是 LINE 送來的」，不是「這個值是真的」。
 *
 * <p>而 LINE 的 message id <b>不是猜不到的</b>：實際資料裡的幾筆同前綴、隨時間遞增。
 *
 * <p>錨點那一層擋第一次（見 {@code MessageAnchorIntegrationTest}），
 * 這裡守的是第二道：<b>就算 id 不知怎麼地流出來了，動到的東西也一定要是他自己的。</b>
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
class CrossUserIsolationIntegrationTest {

    @Autowired
    private NoteRepository noteRepository;

    @Autowired
    private NoteExtractionRepository extractionRepository;

    @Autowired
    private NoteItemRepository itemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("🔴 拿到別人的項目 id，也撈不出來")
    void anotherUsersItemIdReturnsNothing() {
        String alice = "U-alice-" + UUID.randomUUID();
        String bob = "U-bob-" + UUID.randomUUID();
        Long aliceItem = seedItem(alice, "Alice 的行程");

        assertThat(itemRepository.findByIdAndUser(bob, aliceItem)).isEmpty();
        assertThat(itemRepository.findByIdAndUser(alice, aliceItem)).isPresent();
    }

    @Test
    @DisplayName("批次撈也一樣——混進別人的 id 只會被濾掉，不是整批失敗")
    void aBatchOnlyReturnsYourOwnItems() {
        String alice = "U-alice-" + UUID.randomUUID();
        String bob = "U-bob-" + UUID.randomUUID();
        Long aliceItem = seedItem(alice, "Alice 的行程");
        Long bobItem = seedItem(bob, "Bob 的行程");

        // 濾掉而不是整批拒絕：指令是位置性的，整批失敗會讓一句正常的話完全做不了事。
        // 被濾掉的那一筆在上層會顯示成「已經不在清單上了」，而那對使用者是誠實的。
        assertThat(itemRepository.findAllByIdAndUser(bob, List.of(aliceItem, bobItem)))
                .extracting(NoteItem::getId)
                .containsExactly(bobItem);
    }

    @Test
    @DisplayName("「目前還有什麼」只看得到自己的")
    void upcomingIsScopedToTheUser() {
        String alice = "U-alice-" + UUID.randomUUID();
        String bob = "U-bob-" + UUID.randomUUID();
        seedItem(alice, "Alice 的行程");
        Long bobItem = seedItem(bob, "Bob 的行程");

        assertThat(itemRepository.findUpcoming(bob, Instant.parse("2020-01-01T00:00:00Z")))
                .extracting(NoteItem::getId)
                .containsExactly(bobItem);
    }

    private Long seedItem(String lineUserId, String title) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            String messageId = "audio-" + UUID.randomUUID();
            noteRepository.insertPendingIfAbsent(lineUserId, messageId);
            Long noteId = noteRepository.findBySourceMessageId(messageId).orElseThrow().getId();
            NoteExtraction extraction = NoteExtraction.of(noteId, "test", "v-test");
            extraction.addItem(new NoteItem(NoteCategory.SCHEDULE, title,
                    Instant.parse("2026-08-25T07:00:00Z"), true, null, List.of()));
            return extractionRepository.save(extraction).getItems().get(0).getId();
        });
    }
}
