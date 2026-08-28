package io.svra.extract;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 送給模型的日期對照表。
 *
 * <p>🔴 <b>這張表少一欄，行程就會差整整七天。</b>
 *
 * <p>原本的表只標「今天」「明天」，其餘只有星期幾。但 14 天裡<b>每個星期幾都出現兩次</b>——
 * 使用者說「下禮拜一」，表格答得出「哪幾天是星期一」，卻答不出<b>哪一個</b>。
 * 模型從兩個候選裡挑，而它挑錯過：2026-08-28（週五）說「下禮拜一早上去高雄」，
 * 抽成 <b>9/07</b> 而不是 8/31。
 *
 * <p>那不是幻覺，是表格沒給它判斷的依據。而這張表存在的理由就是「不要讓模型心算」——
 * 只解決一半的話，剩下那一半正好是會出錯的那一半。
 */
class CalendarTableTest {

    /** 那次抽錯的當天。 */
    private static final LocalDate THE_FRIDAY = LocalDate.of(2026, 8, 28);

    @Test
    @DisplayName("🔴 表裡兩個星期一必須分得出來——這就是抽錯七天的那一題")
    void theTwoMondaysAreDistinguishable() {
        String table = NoteExtractor.calendar(THE_FRIDAY);

        assertThat(table)
                .as("下禮拜一＝8/31，而不是再下一個星期一")
                .contains("2026-08-31（星期一） ← 下週一")
                .contains("2026-09-07（星期一） ← 下下週一");
    }

    @Test
    @DisplayName("今天與明天的標記還在，而且也帶著週次")
    void todayAndTomorrowKeepTheirMarks() {
        String table = NoteExtractor.calendar(THE_FRIDAY);

        assertThat(table)
                .contains("2026-08-28（星期五） ← 今天、本週五")
                .contains("2026-08-29（星期六） ← 明天、本週六");
    }

    @Test
    @DisplayName("週的起點是星期一——從週五說「下禮拜」，指的是下一個星期一開始那一週")
    void theWeekStartsOnMonday() {
        String table = NoteExtractor.calendar(THE_FRIDAY);

        // 週日還屬於本週。用星期日當起點的話它會變成「下週日」，而那是錯的。
        assertThat(table).contains("2026-08-30（星期日） ← 本週日");
    }

    @Test
    @DisplayName("從星期一當天算，今天就是本週一，下一個才是下週一")
    void fromAMondayTheLabelsStillLineUp() {
        String table = NoteExtractor.calendar(LocalDate.of(2026, 8, 31));

        assertThat(table)
                .contains("2026-08-31（星期一） ← 今天、本週一")
                .contains("2026-09-07（星期一） ← 下週一");
    }

    @Test
    @DisplayName("14 天全部都有週次標籤，沒有一天是裸的")
    void everyRowCarriesAWeekLabel() {
        String table = NoteExtractor.calendar(THE_FRIDAY);

        String[] rows = table.split("\n");
        assertThat(rows).hasSize(14);
        for (String row : rows) {
            assertThat(row)
                    .as("這一列沒有週次標籤，模型又要自己猜了：%s", row)
                    .containsPattern("← .*(本週|下週|下下週)[一二三四五六日]");
        }
    }
}
