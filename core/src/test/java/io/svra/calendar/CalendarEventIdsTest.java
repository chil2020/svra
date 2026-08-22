package io.svra.calendar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 這幾個性質不是命名慣例，是<b>冪等機制本身</b>。
 *
 * <p>id 只要不再是決定性的，重複匯入就會在行事曆上生出第二筆——
 * 而那件事在生產環境是靜悄悄發生的。
 */
class CalendarEventIdsTest {

    @Test
    @DisplayName("🔴 同一個項目永遠算出同一個 id——重複匯入才會撞 409 而不是新增一筆")
    void isDeterministic() {
        assertThat(CalendarEventIds.of(42L)).isEqualTo(CalendarEventIds.of(42L));
    }

    @Test
    @DisplayName("不同項目算出不同 id")
    void isUniquePerItem() {
        assertThat(CalendarEventIds.of(1L)).isNotEqualTo(CalendarEventIds.of(2L));
    }

    @Test
    @DisplayName("符合 Google 的格式限制：base32hex（a-v 與 0-9）、長度 5–1024")
    void matchesGooglesIdRules() {
        for (long id : new long[] { 1L, 31L, 32L, 999L, Long.MAX_VALUE }) {
            assertThat(CalendarEventIds.of(id))
                    .as("id=%d", id)
                    .matches("[0-9a-v]{5,1024}")
                    // 前綴讓人在 Google 的回應或 log 裡一眼認得出是誰寫的
                    .startsWith("svra");
        }
    }

    @Test
    @DisplayName("還沒進資料庫的項目算不出 id，要當場炸而不是算出一個假的")
    void refusesItemsWithoutAnId() {
        assertThatThrownBy(() -> CalendarEventIds.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
