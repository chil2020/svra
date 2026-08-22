package io.svra.notify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteItem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用者看到的編號，必須跟「用編號取項目」拿到的是同一筆。
 *
 * <p>這兩件事在不同模組：卡片的排版在 notify，指令解析拿的是
 * {@code getOrderedItems()}。以前推播依分類重排、指令那邊用 JPA 給的原始順序，
 * 只要抽取結果不是剛好「行程→待辦→想法」，「刪掉第一筆」就會刪錯，
 * 而且不會有任何錯誤訊息。
 *
 * <p>測試放在 notify 而不是 note：要驗的是<b>兩邊的接縫</b>，
 * 而接縫的另一頭（排版）在這裡。
 */
class ItemNumberingConsistencyTest {

    private final CardRenderer renderer =
            new CardRenderer(JsonMapper.builder().build(), directImport());

    /** 這幾題不在乎按鈕長什麼樣，只在乎編號順序。 */
    private static CalendarCapability directImport() {
        return new CalendarCapability() {
            @Override public boolean canImportDirectly(String lineUserId) {
                return true;
            }
            @Override public String importLinkFor(NoteItem item) {
                return null;
            }
        };
    }

    private static NoteItem item(Long id, NoteCategory category, String title) {
        NoteItem item = new NoteItem(category, title,
                Instant.parse("2026-08-20T01:00:00Z"), true, null, List.of());
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    @Test
    @DisplayName("卡片上的編號 N，對應 getOrderedItems() 的第 N 筆")
    void cardNumberingMatchesLookupOrder() {
        // 刻意用「想法→待辦→行程」的順序建立，跟顯示順序相反
        NoteExtraction extraction = NoteExtraction.of(1L, "raw", "v-test");
        extraction.addItem(item(1L, NoteCategory.IDEA, "想法A"));
        extraction.addItem(item(2L, NoteCategory.TODO, "待辦B"));
        extraction.addItem(item(3L, NoteCategory.SCHEDULE, "行程C"));

        List<NoteItem> ordered = extraction.getOrderedItems();
        CardRenderer.Rendered card = renderer.render(ordered, "U-test", "抬頭", "結尾", List.of());

        // 驗 altText 而不是 flexJson：兩者由同一次走訪產生，但前者是線性的，
        // 後者中間隔著一堆排版屬性，用正規表示式去比只是在測 JSON 長什麼樣。
        for (int i = 0; i < ordered.size(); i++) {
            String title = ordered.get(i).getTitle();
            assertThat(card.altText())
                    .as("卡片上第 %d 筆應該是「%s」", i + 1, title)
                    .containsPattern("(?s)" + (i + 1) + "\\. .{0,40}" + title);
        }
    }

    @Test
    @DisplayName("🔴 錨點記的順序，就是卡片上排出來的順序")
    void anchorOrderIsTheRenderedOrder() {
        NoteExtraction extraction = NoteExtraction.of(1L, "raw", "v-test");
        extraction.addItem(item(1L, NoteCategory.IDEA, "想法A"));
        extraction.addItem(item(2L, NoteCategory.TODO, "待辦B"));
        extraction.addItem(item(3L, NoteCategory.SCHEDULE, "行程C"));

        CardRenderer.Rendered card =
                renderer.render(extraction.getOrderedItems(), "U-test", "抬頭", "結尾", List.of());

        // 兩者由同一次計算產生，這個測試守的是「以後也不要拆開算」
        assertThat(card.itemIds()).containsExactly(3L, 2L, 1L);
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
