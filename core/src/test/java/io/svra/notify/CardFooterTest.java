package io.svra.notify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡片結尾那一句是<b>操作說明</b>，不是裝飾。
 *
 * <p>編號只在那則訊息的錨點裡有意義：使用者引用它，指令就對得回同一批；
 * 不引用而直接打字的話，解析會退回「目前所有的項目」——那是另一份清單，
 * 「第二筆」指到別的東西，<b>而且會確實地執行</b>（決策 17）。
 *
 * <p>這幾句原本寫的是「直接回覆就可以修改」，理由是「少一個步驟」。
 * 那個觀察沒錯，錯的是結論：<b>我們把一個會讓人做錯事的捷徑寫成了指示。</b>
 *
 * <p>這支測試沒有斷言任何行為，它守的是一句話——而那句話決定了
 * 使用者會不會把指令下到一份他沒看到的清單上。
 */
class CardFooterTest {

    private static final List<String> FOOTERS = List.of(
            NoteNotifier.FOOT_EXTRACTED,
            NoteNotifier.FOOT_CURRENT,
            NoteNotifier.FOOT_UPDATED,
            NoteNotifier.FOOT_SYNCED);

    @Test
    @DisplayName("🔴 每一張卡都要叫使用者「回覆這則訊息」，不能只說「回覆」")
    void everyFooterTellsTheUserToReplyToThatMessage() {
        assertThat(FOOTERS).allSatisfy(footer ->
                assertThat(footer)
                        .as("結尾少了「這則訊息」，使用者就會直接打字，"
                                + "而那會讓編號對到另一份清單")
                        .contains("回覆這則訊息"));
    }

    @Test
    @DisplayName("不能出現「直接回覆」——那正是會出事的那個講法")
    void noFooterInvitesAPlainReply() {
        assertThat(FOOTERS).allSatisfy(footer ->
                assertThat(footer).doesNotContain("直接回覆"));
    }

    @Test
    @DisplayName("抬頭四種各不相同——使用者問的是什麼，決定了回覆該像什麼")
    void everyHeadingIsDistinct() {
        // 共用抬頭的話，「我剛問現況」跟「我剛改完東西」會收到同一句話，
        // 使用者無從判斷剛才那則指令到底有沒有生效。
        assertThat(List.of(
                NoteNotifier.HEAD_EXTRACTED,
                NoteNotifier.HEAD_CURRENT,
                NoteNotifier.HEAD_UPDATED,
                NoteNotifier.HEAD_SYNCED)).doesNotHaveDuplicates();
    }
}
