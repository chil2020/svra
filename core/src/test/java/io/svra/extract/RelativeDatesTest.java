package io.svra.extract;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「下禮拜一」是錄音日的全函數，所以由程式算，不交給模型。
 * 這一組守的是那個函數本身。
 */
class RelativeDatesTest {

    /** 那次抽錯的當天。 */
    private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 28);

    @Test
    @DisplayName("🔴 「下禮拜一」從週五算是 8/31，不是 9/07")
    void nextMondayFromAFriday() {
        assertThat(RelativeDates.resolve("下禮拜一早上去高雄", FRIDAY))
                .containsExactly(java.util.Map.entry("下禮拜一", LocalDate.of(2026, 8, 31)));
    }

    @Test
    @DisplayName("這／本／下／下下，四種前綴都算得出來")
    void allFourPrefixes() {
        assertThat(RelativeDates.resolve("這週一、本週一、下週一、下下週一", FRIDAY))
                .containsExactly(
                        java.util.Map.entry("這週一", LocalDate.of(2026, 8, 24)),
                        java.util.Map.entry("本週一", LocalDate.of(2026, 8, 24)),
                        java.util.Map.entry("下週一", LocalDate.of(2026, 8, 31)),
                        java.util.Map.entry("下下週一", LocalDate.of(2026, 9, 7)));
    }

    @Test
    @DisplayName("星期／禮拜／拜／週都認得，「個」可有可無")
    void allTheWaysToSayIt() {
        for (String said : new String[] {
                "下星期一", "下禮拜一", "下拜一", "下週一", "下個星期一", "下個禮拜一"}) {
            assertThat(RelativeDates.resolve(said, FRIDAY).values())
                    .as(said).containsExactly(LocalDate.of(2026, 8, 31));
        }
    }

    @Test
    @DisplayName("「日」與「天」都是星期日，而且是那一週的最後一天")
    void sundayIsTheEndOfTheWeek() {
        // 下週是 8/31–9/06，所以下週日是 9/06
        for (String said : new String[] {"下週日", "下禮拜天"}) {
            assertThat(RelativeDates.resolve(said, FRIDAY).values())
                    .as(said).containsExactly(LocalDate.of(2026, 9, 6));
        }
    }

    @Test
    @DisplayName("🔴 沒有前綴的「星期五」刻意不碰——那需要判斷，該留給模型")
    void bareWeekdaysAreLeftAlone() {
        assertThat(RelativeDates.resolve("星期五要交季報", FRIDAY)).isEmpty();
        assertThat(RelativeDates.resolve("禮拜三開會", FRIDAY)).isEmpty();
    }

    @Test
    @DisplayName("「下週末」「兩個禮拜後」不是星期——後面沒接日字就不算")
    void weekWordsWithoutADayAreIgnored() {
        assertThat(RelativeDates.resolve("下週末去爬山", FRIDAY)).isEmpty();
        assertThat(RelativeDates.resolve("兩個禮拜後回診", FRIDAY)).isEmpty();
    }

    @Test
    @DisplayName("一句話裡有兩個都要算出來，順序照原文")
    void twoInOneSentence() {
        assertThat(RelativeDates.resolve("下週一開會，下週五交報告", FRIDAY).values())
                .containsExactly(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("同一個說法出現兩次只列一次")
    void repeatsAreDeduplicated() {
        assertThat(RelativeDates.resolve("下週一開會，下週一還要交報告", FRIDAY)).hasSize(1);
    }

    @Test
    @DisplayName("給模型的字要寫成「已經算好了」——留餘地它就會討價還價")
    void theHintLeavesNoRoomForDebate() {
        String hint = RelativeDates.hint(RelativeDates.resolve("下禮拜一早上去高雄", FRIDAY));

        assertThat(hint)
                .contains("已經算好了")
                .contains("不要自己再推算")
                .contains("「下禮拜一」＝ 2026-08-31（星期一）");
    }

    @Test
    @DisplayName("沒有相對日期時不要多送一段空話")
    void noHintWhenThereIsNothingToResolve() {
        assertThat(RelativeDates.hint(RelativeDates.resolve("記得繳電費", FRIDAY))).isEmpty();
    }

    // ── 斷詞陷阱 ──────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 「幫我記一下下週二」是下週二，不是下下週二")
    void theOneDownTrap() {
        // 第一版沒有 (?<!一)，「記一下」的下跟「下週二」黏成「下下週二」，
        // 算出 8/25。而模型原本是對的——是這個「決定性」的解析器把它弄壞的。
        assertThat(RelativeDates.resolve("幫我記一下下週二早上十點要跟牙醫約診",
                LocalDate.of(2026, 8, 14)).values())
                .containsExactly(LocalDate.of(2026, 8, 18));
    }

    @Test
    @DisplayName("「等一下下下週二」還是要算成下下週二")
    void theTrapDoesNotEatARealDoublePrefix() {
        assertThat(RelativeDates.resolve("等一下下下週二要開會", LocalDate.of(2026, 8, 14)).values())
                .containsExactly(LocalDate.of(2026, 8, 25));
    }

    @Test
    @DisplayName("「看一下週二的行程」沒有前綴，不該被解析")
    void oneDownFollowedByABareWeekday() {
        assertThat(RelativeDates.resolve("看一下週二的行程", LocalDate.of(2026, 8, 14))).isEmpty();
    }
}
