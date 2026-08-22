package io.svra.extract;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import io.svra.note.NoteCategory;

/**
 * 只測領域驗證，不呼叫 LLM。
 * schema 保證格式，這裡擋的是「格式對但內容不合理」——那才是真正會出錯的地方。
 */
class NoteExtractorValidationTest {

    /**
     * 錄音當下。合理範圍以它為基準而不是 Instant.now()——
     * 用「現在」的話，這些測試會隨著時間經過而慢慢失效。
     */
    private static final Instant RECORDED_AT = Instant.parse("2026-08-15T01:00:00Z");

    private static ExtractedNote one(NoteCategory category, String title, String occursAt) {
        return new ExtractedNote(List.of(
                new ExtractedNote.Item(category, title, occursAt, null, null, List.of())));
    }

    @Test
    @DisplayName("正常的行程 → 通過")
    void validSchedulePasses() {
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", "2026-08-16T09:00:00Z"), RECORDED_AT);
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("年份推斷錯誤 → 擋下來（這是 schema 擋不住的）")
    void wrongYearIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", "2019-08-16T09:00:00Z"), RECORDED_AT);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("超出合理範圍");
    }

    @Test
    @DisplayName("時間格式不是 ISO-8601 → 擋下來，且訊息要說明正確格式")
    void badDateFormatIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", "8月16號"), RECORDED_AT);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("ISO-8601");
    }

    @Test
    @DisplayName("分類是 SCHEDULE 卻沒有時間 → 可能分類錯誤")
    void scheduleWithoutTimeIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", null), RECORDED_AT);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("沒有 occursAt");
    }

    @Test
    @DisplayName("想法沒有時間 → 正常")
    void ideaWithoutTimePasses() {
        var errors = NoteExtractor.validate(one(NoteCategory.IDEA, "履歷可以用佇列深度當指標", null), RECORDED_AT);
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("title 空白 → 擋下來")
    void blankTitleIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.IDEA, "  ", null), RECORDED_AT);
        assertThat(errors).hasSize(1);
    }
}
