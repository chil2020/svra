package io.svra.notify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import io.svra.note.NoteItem;
import io.svra.note.NoteCategory;

/** 只測排版，不呼叫 LINE。 */
class NoteNotifierRenderTest {

    private static NoteItem item(NoteCategory category, String title, String occursAt) {
        return new NoteItem(category, title,
                occursAt == null ? null : Instant.parse(occursAt), null, List.of());
    }

    @Test
    @DisplayName("編號跨分類連續，讓使用者能說「第幾筆」")
    void numbersItemsAcrossCategories() {
        String out = NoteNotifier.render(List.of(
                item(NoteCategory.IDEA, "想法A", null),
                item(NoteCategory.TODO, "待辦B", null),
                item(NoteCategory.SCHEDULE, "行程C", "2026-08-15T01:00:00Z")));

        // 排序後是 行程C(1) → 待辦B(2) → 想法A(3)
        assertThat(out).contains("1. 8/15(週六) 09:00").contains("2. 待辦B").contains("3. 想法A");
    }

    @Test
    @DisplayName("依行程→待辦→想法分組，不照傳入順序")
    void groupsInFixedOrder() {
        String out = NoteNotifier.render(List.of(
                item(NoteCategory.IDEA, "履歷用佇列深度當指標", null),
                item(NoteCategory.TODO, "繳電費", null),
                item(NoteCategory.SCHEDULE, "前往阿里山", "2026-08-15T01:00:00Z")));

        assertThat(out.indexOf("🗓 行程"))
                .isLessThan(out.indexOf("✅ 待辦"))
                .isLessThan(out.indexOf("💡 想法"));
    }

    @Test
    @DisplayName("時間換算成台北時間顯示")
    void formatsTimeInTaipei() {
        String out = NoteNotifier.render(List.of(
                item(NoteCategory.SCHEDULE, "前往阿里山", "2026-08-15T01:00:00Z")));

        // 01:00 UTC = 09:00 台北；E 在 zh-TW 是「週六」不是「六」
        // 時間在標題前面
        assertThat(out).contains("1. 8/15(週六) 09:00");
        assertThat(out.indexOf("8/15")).isLessThan(out.indexOf("前往阿里山"));
    }

    @Test
    @DisplayName("沒有時間的項目不顯示空白時間列")
    void omitsTimeLineWhenAbsent() {
        String out = NoteNotifier.render(List.of(item(NoteCategory.TODO, "繳電費", null)));

        assertThat(out).contains("1. 繳電費").doesNotContain("　　");
    }

    @Test
    @DisplayName("沒有的分類不出現空標題")
    void skipsEmptyCategories() {
        String out = NoteNotifier.render(List.of(item(NoteCategory.TODO, "繳電費", null)));

        assertThat(out).contains("✅ 待辦").doesNotContain("🗓 行程").doesNotContain("💡 想法");
    }
}
