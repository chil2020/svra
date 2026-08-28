package io.svra.extract;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把逐字稿裡「下禮拜一」這種說法直接算成日期，<b>在送進模型之前</b>。
 *
 * <p>🔴 <b>這不是不相信模型，是量出來的。</b>同一個模型：
 * <ul>
 * <li>只給日曆表 + 那句話 → 答 2026-08-31，<b>而且自己說「查表查到標示為『下週一』的那一行」</b></li>
 * <li>只給兩個候選讓它選 → 選對</li>
 * <li><b>放進完整的抽取 prompt → 錯了四次以上</b>（先是 9/01，被驗證擋下後改成 9/07）</li>
 * </ul>
 *
 * <p>所以不是理解力問題，是<b>注意力問題</b>：770 字的規則把日期推理擠掉了。
 * 它的推理過程裡為了 title 那條規則來回糾結六次，日期只是順手一撈——
 * 而撈錯的時候，它甚至會<b>編造一列表格裡不存在的資料</b>來確認自己：
 *
 * <blockquote>Table says: 2026-09-01（星期一）← 下週一</blockquote>
 *
 * <p>表上寫的是 {@code 2026-08-31（星期一）← 下週一}。
 *
 * <h2>為什麼這不算「在 LLM 旁邊再造一個判斷器」</h2>
 *
 * 決策 33 拒絕過那件事（快速路徑只認完全相符，模糊比對留給 LLM），而這裡不一樣：
 * <b>「下禮拜一」對應哪一天是錄音時刻的全函數，沒有判斷的餘地。</b>
 * 需要判斷的東西才該給模型。
 *
 * <h2>只解析明確的，剩下的留給模型</h2>
 *
 * 有 {@code 這／本／下／下下} 前綴的才算。<b>光講「星期五」刻意不碰</b>——
 * 那可能是這週五也可能是下週五，取決於今天星期幾與說話的語境，
 * <b>而那正是需要判斷的部分</b>。現有的抽取對它是準的（見 eval 的 {@code sched-003}），
 * 硬要在這裡決定只會把一個對的東西弄壞。
 */
final class RelativeDates {

    /**
     * {@code (這|本|下|下下)(個)?(星期|禮拜|拜|週)(日字)}。
     *
     * <p>前綴<b>必填</b>——沒有前綴的「星期五」不在這裡處理。
     * {@code 個} 是選配：「下個禮拜一」跟「下禮拜一」是同一件事。
     *
     * <h4>🔴 {@code (?<!一)} 這個否定回顧，是被真實案例打臉之後補的</h4>
     *
     * 第一版沒有它，於是 eval 的 {@code sched-001} 從綠變紅：
     *
     * <pre>
     * 幫我記一下下週二早上十點要跟牙醫約診
     *      ↑↑
     * </pre>
     *
     * 「記一<b>下</b>」的那個下，跟後面的「下週二」黏成了「<b>下下週二</b>」，
     * 於是我算出 8/25，而正確答案是 8/18。<b>而模型原本是對的</b>——
     * 是我這個「決定性」的解析器把一個好的答案弄壞了。
     *
     * <p>「一下」是極常見的動詞後綴（記一下、看一下、等一下），而
     * 「一下下週X」當成「一＋下下週X」來讀幾乎不存在。所以規則是：
     * <b>前綴的第一個字前面不能是「一」</b>。
     *
     * <p>它也順帶處理對的情況：「等一下下週二的會議」＝ 等一下 ＋ 下週二——
     * 第一個下被擋掉，第二個下沒有，於是匹配到「下週二」，正確。
     *
     * <p>這件事本身是個提醒：<b>把判斷從模型搬到程式碼，不等於搬到了正確的地方</b>，
     * 只是換了一種會錯的方式。所以 {@code NoteExtractor} 那層星期驗證要留著。
     */
    private static final Pattern EXPLICIT_WEEKDAY = Pattern.compile(
            "(?<!一)(下下|下|這|本)(?:個)?(星期|禮拜|拜|週)([一二三四五六日天])");

    private RelativeDates() {
    }

    /**
     * 算出逐字稿裡每一個明確的相對星期。
     *
     * @return 「原文 → 日期」的對照，依出現順序、去重複。沒有就是空的
     */
    static Map<String, LocalDate> resolve(String transcript, LocalDate today) {
        Map<String, LocalDate> found = new LinkedHashMap<>();
        if (transcript == null) {
            return found;
        }
        // 週的起點用星期一（ISO-8601，台灣的日常用法也是這樣）：
        // 從週五說「下禮拜」，指的是下一個星期一開始的那一週。
        LocalDate thisMonday = today.with(DayOfWeek.MONDAY);

        Matcher m = EXPLICIT_WEEKDAY.matcher(transcript);
        while (m.find()) {
            int weeksAhead = switch (m.group(1)) {
                case "下下" -> 2;
                case "下" -> 1;
                default -> 0;   // 這、本
            };
            LocalDate day = thisMonday.plusWeeks(weeksAhead)
                    .plusDays(dayIndex(m.group(3)));
            found.put(m.group(), day);
        }
        return found;
    }

    /**
     * 給模型看的那幾行。
     *
     * <p>寫成「已經算好了，直接用」而不是「請參考」——<b>模型對建議會討價還價</b>，
     * 那正是它在 title 規則上做的事。這裡不要留任何餘地。
     */
    static String hint(Map<String, LocalDate> resolved) {
        if (resolved.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        resolved.forEach((phrase, day) -> lines.add(
                "・「" + phrase + "」＝ " + day + "（" + zh(day.getDayOfWeek()) + "）"));
        return "這句話裡的相對日期已經算好了，occursAt 直接用這些，不要自己再推算：\n"
                + String.join("\n", lines);
    }

    private static int dayIndex(String ch) {
        int i = "一二三四五六".indexOf(ch);
        // 「日」與「天」都是星期日，也就是週一起算的第 7 天
        return i >= 0 ? i : 6;
    }

    private static String zh(DayOfWeek day) {
        return "星期" + "一二三四五六日".charAt(day.getValue() - 1);
    }
}
