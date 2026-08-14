package io.svra.extract;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 只測排版，不呼叫 LINE。 */
class NoteNotifierRenderTest {

    private static NoteItem item(NoteCategory category, String title, String occursAt) {
        return new NoteItem(category, title,
                occursAt == null ? null : Instant.parse(occursAt), null, List.of());
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
        assertThat(out).contains("8/15(週六) 09:00");
    }

    @Test
    @DisplayName("沒有時間的項目不顯示空白時間列")
    void omitsTimeLineWhenAbsent() {
        String out = NoteNotifier.render(List.of(item(NoteCategory.TODO, "繳電費", null)));

        assertThat(out).contains("・繳電費").doesNotContain("　　");
    }

    @Test
    @DisplayName("沒有的分類不出現空標題")
    void skipsEmptyCategories() {
        String out = NoteNotifier.render(List.of(item(NoteCategory.TODO, "繳電費", null)));

        assertThat(out).contains("✅ 待辦").doesNotContain("🗓 行程").doesNotContain("💡 想法");
    }
}
