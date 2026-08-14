package io.svra.extract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteItem;

/**
 * 抽取層的回歸測試。
 *
 * <p><b>這不是單元測試。</b>它呼叫真的 LLM，慢、不具決定性，結果是各維度的準確率
 * 而不是通過／失敗。所以標了 {@code @Tag("eval")}，不在 {@code mvn test} 裡跑：
 *
 * <pre>cd core && mvn test -Dgroups=eval</pre>
 *
 * <p>存在的理由：改 prompt、換模型、升級框架，對抽取品質的影響看不出來，
 * 除非有一組固定的題目量著。
 */
@Tag("eval")
@SpringBootTest
@Import(ExtractionEvalTest.FixedClockConfig.class)
class ExtractionEvalTest {

    /**
     * 每題把時鐘撥到案例指定的 {@code today}。
     * 不這樣做的話「明天」「下週二」的預期答案每天都會腐爛，
     * 這組 eval 就不是回歸測試而是日曆。
     */
    static final MutableClock CLOCK = new MutableClock();

    @TestConfiguration
    static class FixedClockConfig {
        /**
         * 名稱刻意不叫 clock：跟 ClockConfig 同名會撞成
         * BeanDefinitionOverrideException（Boot 預設不允許覆寫）。
         * 用不同名稱加 @Primary，讓注入時選這個。
         */
        @Bean
        @Primary
        Clock evalClock() {
            return CLOCK;
        }
    }

    /** 可撥動的時鐘。只在測試用。 */
    static class MutableClock extends Clock {
        private Instant instant = Instant.now();
        private final ZoneId zone = ZoneId.of("Asia/Taipei");

        void setDate(LocalDate date) {
            this.instant = date.atStartOfDay(zone).toInstant();
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final Path CASES = Path.of("..", "eval", "cases.jsonl");
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    private NoteExtractor extractor;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("跑完整個 eval 集，輸出各維度準確率")
    void runEvalSuite() throws IOException {
        List<JsonNode> cases = new ArrayList<>();
        for (String line : Files.readAllLines(CASES)) {
            if (!line.isBlank()) {
                cases.add(objectMapper.readTree(line));
            }
        }

        Scoreboard board = new Scoreboard();
        System.out.println("\n" + "=".repeat(72));
        System.out.printf("Eval：%d 題　模型見 application.yml　prompt 版本 %s%n",
                cases.size(), NoteExtractor.PROMPT_VERSION);
        System.out.println("=".repeat(72));

        for (JsonNode c : cases) {
            String id = c.get("id").asString();
            CLOCK.setDate(LocalDate.parse(c.get("today").asString()));
            long started = System.currentTimeMillis();
            List<NoteItem> actual;
            try {
                actual = extractor.extract(c.get("input").asString());
            } catch (Exception e) {
                System.out.printf("%-12s ✖ 例外：%s%n", id, e.getMessage());
                board.record(id, List.of("抽取拋出例外"));
                continue;
            }
            long elapsed = System.currentTimeMillis() - started;

            List<String> failures = grade(c.get("expected"), actual);
            board.record(id, failures);

            System.out.printf("%-12s %s  %5.1fs  %s%n",
                    id, failures.isEmpty() ? "✔" : "✖", elapsed / 1000.0,
                    c.path("note").asString(""));
            failures.forEach(f -> System.out.println("               └─ " + f));
        }

        board.print();
        // 不 assert——eval 的產物是一張分數表，不是紅綠燈。
        // 要當成閘門時再依專案當下的標準加門檻。
    }

    /** @return 沒通過的檢查項；全部通過時是空清單 */
    private List<String> grade(JsonNode expected, List<NoteItem> actual) {
        List<String> failures = new ArrayList<>();

        // ── 數量 ──
        JsonNode count = expected.get("itemCount");
        if (count != null) {
            int min = count.isInt() ? count.asInt() : count.path("min").asInt(0);
            int max = count.isInt() ? count.asInt() : count.path("max").asInt(Integer.MAX_VALUE);
            if (actual.size() < min || actual.size() > max) {
                failures.add("數量 %d 不在預期的 %d~%d".formatted(actual.size(), min, max));
            }
        }

        // ── 逐項比對。用「分類 + 關鍵字」配對，不做逐字比對——
        //    同一段話有多種合理的整理方式，逐字比對只會逼人去對齊模型的表達習慣。
        for (JsonNode want : expected.path("items")) {
            String category = want.get("category").asString();
            List<String> keywords = new ArrayList<>();
            want.path("mustMention").forEach(k -> keywords.add(k.asString()));

            NoteItem match = actual.stream()
                    .filter(i -> i.getCategory().name().equals(category))
                    .filter(i -> keywords.isEmpty() || keywords.stream().anyMatch(k -> text(i).contains(k)))
                    .findFirst().orElse(null);

            if (match == null) {
                failures.add("找不到 %s 且提到 %s 的項目".formatted(category, keywords));
                continue;
            }

            // 時間
            if (want.has("occursAt")) {
                JsonNode wantAt = want.get("occursAt");
                String got = match.getOccursAt() == null ? null
                        : LOCAL.format(LocalDateTime.ofInstant(match.getOccursAt(), ZONE));
                String exp = wantAt.isNull() ? null : wantAt.asString();
                if (exp == null ? got != null : !exp.equals(got)) {
                    failures.add("「%s」的時間是 %s，預期 %s".formatted(match.getTitle(), got, exp));
                }
            }

            // 不該出現的字（口語雜訊被抄進標題）
            for (JsonNode bad : want.path("titleMustNotMention")) {
                if (match.getTitle().contains(bad.asString())) {
                    failures.add("標題含有雜訊「%s」：%s".formatted(bad.asString(), match.getTitle()));
                }
            }
        }

        // ── 跨欄位一致性：所有案例都檢查 ──
        // 這是 validate() 抓不到的那類錯誤：格式合法、日期也在合理範圍，
        // 但 detail 說的日期跟 occursAt 對不上。
        for (NoteItem item : actual) {
            String inconsistency = crossFieldCheck(item);
            if (inconsistency != null) {
                failures.add(inconsistency);
            }
        }
        return failures;
    }

    /** detail 裡若寫了「N月N日」，要跟 occursAt 同一天。 */
    private String crossFieldCheck(NoteItem item) {
        if (item.getDetail() == null || item.getOccursAt() == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("(\\d{1,2})月(\\d{1,2})[日號]").matcher(item.getDetail());
        if (!matcher.find()) {
            return null;
        }
        LocalDateTime at = LocalDateTime.ofInstant(item.getOccursAt(), ZONE);
        int month = Integer.parseInt(matcher.group(1));
        int day = Integer.parseInt(matcher.group(2));
        if (at.getMonthValue() != month || at.getDayOfMonth() != day) {
            return "跨欄位不一致：detail 說 %d/%d，occursAt 是 %s（「%s」）"
                    .formatted(month, day, at.toLocalDate(), item.getTitle());
        }
        return null;
    }

    private static String text(NoteItem item) {
        return item.getTitle() + " " + (item.getDetail() == null ? "" : item.getDetail());
    }

    /** 累積分數並印出摘要。 */
    private static class Scoreboard {
        private final Map<String, List<String>> results = new java.util.LinkedHashMap<>();

        void record(String id, List<String> failures) {
            results.put(id, failures);
        }

        void print() {
            long passed = results.values().stream().filter(List::isEmpty).count();
            int total = results.size();
            System.out.println("-".repeat(72));
            System.out.printf("通過 %d/%d（%.0f%%）%n", passed, total, 100.0 * passed / total);

            Map<String, Integer> byKind = new java.util.TreeMap<>();
            results.values().stream().flatMap(List::stream)
                    .map(f -> f.split("[：:]")[0])
                    .forEach(k -> byKind.merge(k, 1, Integer::sum));
            if (!byKind.isEmpty()) {
                System.out.println("失敗類型：");
                byKind.forEach((k, v) -> System.out.printf("  %-24s %d 次%n", k, v));
            }
            System.out.println("=".repeat(72) + "\n");
        }
    }
}
