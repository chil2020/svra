package io.svra.extract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.svra.IntegrationTest;
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
 *
 * <p><b>Redis 刻意<u>不</u>換成容器</b>，跟 Postgres 與佇列相反。抽取結果的快取鍵含
 * {@code PROMPT_VERSION}（見 {@code ExtractionCacheKeyGenerator}），所以同一版 prompt
 * 重跑會直接命中——一輪 eval 從 70 秒變成 3 秒。那個快取<b>就是要跨執行共用</b>，
 * 換成拋棄式容器等於把它關掉。
 *
 * <p>兩者的差別在於<b>會不會弄髒別人的東西</b>：快取寫進去只是快取，
 * 而 outbox 送出去的是推播給使用者的真實訊息。
 */
@Tag("eval")
@SpringBootTest
// 🔴 Testcontainers 不是為了「測得更真」，是為了<b>不要碰到正式資料</b>。
//
// 這支測試原本沒有這一行，於是 @SpringBootTest 用的是 application.yml 的預設值——
// 也就是 jdbc:postgresql://localhost:5432/svra，**開發者真正在用的那顆資料庫**。
// 症狀不是失敗，是靜悄悄地成功：2026-08-22 那次 eval 直接把 V8/V9/V10 三支
// migration 套用到了正式資料上，而沒有任何人要求它這麼做。
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        // 🔴 下面這兩行擋的是比 migration 嚴重得多的事。
        //
        // @SpringBootTest 啟的是**整個應用**，包含 OutboxPoller 與 RabbitMQ listener。
        // 而 Testcontainers 只換掉 Postgres：佇列與 Redis 的預設值仍然指向 localhost，
        // 也就是**真的那一套**。不擋的話，跑一次 eval 會：
        //
        //   ・讓 poller 去撈真的 outbox，把待送事件送出去（真的推播給使用者）
        //   ・讓 listener 去消費真的 transcribe.result，把別人的轉錄結果吃掉
        //
        // 而 eval 要跑 70 秒以上，那是很寬的一個窗。
        // 上一次沒出事，只是因為當下 outbox 剛好是空的——那是運氣，不是設計。
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // LineProperties 現在是 @NotBlank，少了會啟動失敗（決策 22）。
        // eval 只呼叫 LLM，不碰 LINE，給值只是為了讓 context 起得來。
        // 行事曆的白名單留空，所以憑證可以是假的也不會被驗（決策 27）。
        "svra.line.channel-secret=eval-secret",
        "svra.line.channel-access-token=eval-token",
})
class ExtractionEvalTest {

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
            long started = System.currentTimeMillis();
            List<NoteItem> actual;
            try {
                // 案例的 today 就是「錄音當下」，直接傳進去——
                // 不需要動時鐘，因為基準日已經是參數而不是環境。
                actual = NoteExtractor.toItems(extractor.extract(c.get("input").asString(),
                        LocalDate.parse(c.get("today").asString())
                                .atStartOfDay(ZONE).toInstant()));
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
            // 🔴 category 可以省略，而那不是偷懶。
            //
            // SCHEDULE 與 TODO 的界線本來就模糊：「星期五要交季報」判成哪一邊都說得通。
            // 有些案例真正要驗的是別的東西（例如時間精度），這時硬釘一個分類，
            // 測到的是「模型的分類習慣跟不跟我一樣」，而不是那個案例的重點。
            //
            // 產品端也是同一個判斷：匯入行事曆的閘門是「有沒有時間」而不是分類（決策 26），
            // 所以這裡也不該把分類當成必然。
            String category = want.has("category") ? want.get("category").asString() : null;
            List<String> keywords = new ArrayList<>();
            want.path("mustMention").forEach(k -> keywords.add(k.asString()));

            NoteItem match = actual.stream()
                    .filter(i -> category == null || i.getCategory().name().equals(category))
                    .filter(i -> keywords.isEmpty() || keywords.stream().anyMatch(k -> text(i).contains(k)))
                    .findFirst().orElse(null);

            if (match == null) {
                failures.add("找不到 %s且提到 %s 的項目"
                        .formatted(category == null ? "" : category + " ", keywords));
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

            // 🔴 時間精度：09:00 是使用者講的，還是規則補的。
            //    這一欄決定匯進行事曆時是定時事件還是全天事件（決策 26），
            //    而它跟 occursAt 一樣是模型判的——沒有 eval 守著就是在賭。
            if (want.has("timeSpecified")) {
                JsonNode wantFlag = want.get("timeSpecified");
                Boolean got = match.getTimeSpecified();
                Boolean exp = wantFlag.isNull() ? null : wantFlag.asBoolean();
                if (exp == null ? got != null : !exp.equals(got)) {
                    failures.add("「%s」的 timeSpecified 是 %s，預期 %s"
                            .formatted(match.getTitle(), got, exp));
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
