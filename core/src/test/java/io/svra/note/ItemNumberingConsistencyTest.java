package io.svra.note;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import io.svra.notify.NoteNotifier;

/**
 * 使用者看到的編號，必須跟「用編號取項目」拿到的是同一筆。
 *
 * <p>這兩件事在不同模組：推播的排版在 notify，指令解析拿的是
 * {@code getOrderedItems()}。以前推播依分類重排、指令那邊用 JPA 給的原始順序，
 * 只要抽取結果不是剛好「行程→待辦→想法」，「刪掉第一筆」就會刪錯，
 * 而且不會有任何錯誤訊息。
 */
class ItemNumberingConsistencyTest {

    private static NoteItem item(NoteCategory category, String title) {
        return new NoteItem(category, title, Instant.parse("2026-08-20T01:00:00Z"), null, List.of());
    }

    @Test
    @DisplayName("推播上的編號 N，對應 getOrderedItems() 的第 N 筆")
    void pushNumberingMatchesLookupOrder() {
        // 刻意用「想法→待辦→行程」的順序建立，跟顯示順序相反
        NoteExtraction extraction = NoteExtraction.of(1L, "raw", "v-test");
        extraction.addItem(item(NoteCategory.IDEA, "想法A"));
        extraction.addItem(item(NoteCategory.TODO, "待辦B"));
        extraction.addItem(item(NoteCategory.SCHEDULE, "行程C"));

        List<NoteItem> ordered = extraction.getOrderedItems();
        String pushed = NoteNotifier.render(ordered);

        for (int i = 0; i < ordered.size(); i++) {
            String title = ordered.get(i).getTitle();
            assertThat(pushed)
                    .as("推播上第 %d 筆應該是「%s」", i + 1, title)
                    .containsPattern("(?s)" + (i + 1) + "\\. .{0,40}" + title);
        }
    }

    @Test
    @DisplayName("顯示順序是行程→待辦→想法，不是 enum 的宣告順序")
    void displayOrderIsNotDeclarationOrder() {
        assertThat(NoteCategory.DISPLAY_ORDER)
                .containsExactly(NoteCategory.SCHEDULE, NoteCategory.TODO, NoteCategory.IDEA);
        assertThat(List.of(NoteCategory.values()))
                .isNotEqualTo(NoteCategory.DISPLAY_ORDER);
    }
}
