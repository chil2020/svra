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
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", "2026-08-16T09:00:00Z"), RECORDED_AT, null);
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("年份推斷錯誤 → 擋下來（這是 schema 擋不住的）")
    void wrongYearIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", "2019-08-16T09:00:00Z"), RECORDED_AT, null);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("超出合理範圍");
    }

    @Test
    @DisplayName("時間格式不是 ISO-8601 → 擋下來，且訊息要說明正確格式")
    void badDateFormatIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", "8月16號"), RECORDED_AT, null);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("ISO-8601");
    }

    @Test
    @DisplayName("分類是 SCHEDULE 卻沒有時間 → 可能分類錯誤")
    void scheduleWithoutTimeIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.SCHEDULE, "前往阿里山", null), RECORDED_AT, null);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("沒有 occursAt");
    }

    @Test
    @DisplayName("想法沒有時間 → 正常")
    void ideaWithoutTimePasses() {
        var errors = NoteExtractor.validate(one(NoteCategory.IDEA, "履歷可以用佇列深度當指標", null), RECORDED_AT, null);
        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("title 空白 → 擋下來")
    void blankTitleIsRejected() {
        var errors = NoteExtractor.validate(one(NoteCategory.IDEA, "  ", null), RECORDED_AT, null);
        assertThat(errors).hasSize(1);
    }

    // ── 星期對不上（v11 補的） ─────────────────────────────────────

    /**
     * 🔴 這一組守的是「模型編造日曆表的內容」。
     *
     * <p>真的發生過：2026-08-28（週五）說「下禮拜一早上去高雄」，抽成 2026-09-01。
     * 而模型的推理過程裡寫著「Table says: 2026-09-01（星期一）← 下週一」——
     * 表上根本沒有那一列。
     */
    /** 那次抽錯的當天：2026-08-28 是星期五。 */
    private static final Instant THE_FRIDAY = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    @DisplayName("🔴 逐字稿說「下禮拜一」，卻填了星期二 → 要擋下來")
    void aWeekdayThatDoesNotMatchTheTranscriptIsRejected() {
        // 2026-09-01T09:00+08:00 是星期二
        var errors = NoteExtractor.validate(
                one(NoteCategory.SCHEDULE, "前往高雄", "2026-09-01T01:00:00Z"),
                THE_FRIDAY, "下禮拜一早上去高雄");

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0))
                .as("回饋要具體到模型改得動，不能只說「日期錯了」")
                .contains("星期一")
                .contains("星期二")
                .as("要把表上符合的那幾列攤出來，讓模型從封閉清單裡選")
                .contains("2026-08-31")
                .contains("2026-09-07")
                .contains("下週一")
                .contains("下下週一");
    }

    @Test
    @DisplayName("星期對得上就放行——不管是下週一還是下下週一")
    void theRightWeekdayPassesRegardlessOfWhichWeek() {
        // 2026-08-31 與 2026-09-07 都是星期一
        for (String iso : new String[] {"2026-08-31T01:00:00Z", "2026-09-07T01:00:00Z"}) {
            assertThat(NoteExtractor.validate(
                    one(NoteCategory.SCHEDULE, "前往高雄", iso),
                    RECORDED_AT, "下禮拜一早上去高雄"))
                    .as("檢查的是星期幾，不是哪一週：%s", iso)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("「星期」「禮拜」「週」都認得，「星期天」等同「星期日」")
    void allTheWaysToSayAWeekday() {
        for (String said : new String[] {"星期日要回家", "禮拜天要回家", "週日要回家"}) {
            // 2026-08-30 是星期日
            assertThat(NoteExtractor.validate(
                    one(NoteCategory.SCHEDULE, "回家", "2026-08-29T16:00:00Z"),
                    RECORDED_AT, said))
                    .as(said).isEmpty();
        }
    }

    @Test
    @DisplayName("逐字稿出現兩種星期 → 跳過檢查，沒辦法判斷哪一筆對哪一個")
    void twoDifferentWeekdaysSkipTheCheck() {
        assertThat(NoteExtractor.validate(
                one(NoteCategory.SCHEDULE, "開會", "2026-09-01T01:00:00Z"),
                RECORDED_AT, "星期三開會，星期五交報告"))
                .isEmpty();
    }

    @Test
    @DisplayName("「下週末」「兩個禮拜後」不算指定星期——正則後面一定要接日字")
    void weekWordsWithoutADayAreNotWeekdays() {
        for (String said : new String[] {"下週末去爬山", "兩個禮拜後要交報告", "一週後回診"}) {
            assertThat(NoteExtractor.validate(
                    one(NoteCategory.SCHEDULE, "行程", "2026-09-01T01:00:00Z"),
                    RECORDED_AT, said))
                    .as(said).isEmpty();
        }
    }

    @Test
    @DisplayName("有一筆對得上就放行——同一句裡可能還有「明天」之類的其他項目")
    void oneMatchingItemIsEnough() {
        var result = new ExtractedNote(java.util.List.of(
                new ExtractedNote.Item(NoteCategory.SCHEDULE, "開會",
                        "2026-08-31T01:00:00Z", true, null, null),
                new ExtractedNote.Item(NoteCategory.TODO, "買牛奶",
                        "2026-08-29T01:00:00Z", false, null, null)));

        assertThat(NoteExtractor.validate(result, RECORDED_AT, "下禮拜一開會，明天買牛奶"))
                .isEmpty();
    }
}
