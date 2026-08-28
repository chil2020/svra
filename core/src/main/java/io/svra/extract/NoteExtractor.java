package io.svra.extract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import io.svra.llm.LlmCacheConfig;
import io.svra.note.NoteItem;
import io.svra.note.NoteCategory;

/**
 * 把逐字稿抽成結構化項目。
 *
 * <p>
 * 🔴 承重點④。
 */
@Component
class NoteExtractor {

    private static final Logger log = LoggerFactory.getLogger(NoteExtractor.class);

    /**
     * 改 prompt 就要改這個，才比較得出「是模型的差異還是 prompt 的差異」。
     *
     * <p>🔴 <b>「prompt」不只是下面那個字串。</b>{@code ExtractedNote} 產生的 JSON Schema
     * 也會被送給模型，改了它同樣要升版本——v7 就是這樣來的：字串一個字沒動，
     * 只把三個欄位標成選填。不升的話，{@code ExtractionCacheKeyGenerator} 算出來的鍵不變，
     * 快取會餵回舊結果，而 eval 比出來的數字是假的。
     *
     * <p>v8：加上 {@code timeSpecified}，字串與 schema 兩邊都動了。
     * 它不是為了讓抽取更準，是為了讓<b>下游分得出 09:00 是講出來的還是補上去的</b>——
     * 見 {@code ExtractedNote.Item} 與決策 26。
     *
     * <p>v9：明講「有提到哪一天就要填 occursAt，不看分類」。
     * eval 的 {@code sched-003} 抓到的：「星期五要交季報」被判成 TODO 之後，
     * <b>連日期都一起不填了</b>——模型把「待辦多半沒有時間」讀成了「待辦不該有時間」。
     * 症狀在推播上看不太出來（少一行時間），但那一筆<b>永遠長不出匯入行事曆的按鈕</b>。
     *
     * <p>v10：把 {@code timeSpecified} 從選填改回必填。字串沒動，只動了 schema——
     * 而 eval 從 6/9 回到 9/9。v9 的三題失敗全部是「這一欄是 null」，
     * 也就是模型<b>根本沒回答</b>：schema 說可以省略，它就省略。決策 25 的第二個實例。
     */
    public static final String PROMPT_VERSION = "v11";

    private static final int MAX_ATTEMPTS = 2;

    private static final String SYSTEM = """
            你是一個把口語筆記整理成結構化資料的助手。

            使用者的輸入是語音轉錄的逐字稿，會有贅字、自我修正、講到一半跳題，
            也可能有轉錄錯誤（同音字）。請依語意判斷，不要逐字照抄。

            規則：
            - 一段話可能包含多件事，各自成為一個 item
            - category：TODO 待辦事項｜SCHEDULE 有明確時間的行程｜IDEA 想法或靈感
            - title 一句話講完，30 字以內。不要在 title 裡寫日期或星期——
        時間放 occursAt 就好，顯示時會另外排版，寫兩次只是重複
            - occursAt 用 ISO-8601（例如 2026-08-15T09:00:00+08:00）。
              只講到日期沒講時間就用當天 09:00。完全沒提到時間就填 null。
            - **只要提到了哪一天就要填 occursAt，跟 category 無關**。
              「星期五要交季報」是 TODO，但它有日期，occursAt 就要填星期五的 09:00。
              待辦沒有時間很常見，但那是因為使用者沒說，不是因為它是待辦
        下面有日曆表不代表每一筆都要有時間——想法（IDEA）多半沒有時間，
        使用者沒說時間就是 null，不要拿今天的日期去填
            - timeSpecified：使用者**有沒有真的講出幾點**。
              「下午三點開會」→ true；「星期三要開會」「明天交報告」→ false
              （那個 09:00 是上一條規則補的，不是他說的）。
              occursAt 為 null 時 timeSpecified 也填 null。
              **這一欄不要用猜的**：只問「逐字稿裡有沒有出現時刻」，有就 true，沒有就 false
            - 逐字稿裡沒有的資訊不要自己補
            - 明顯是轉錄錯誤的專有名詞，若能從上下文判斷就修正，判斷不出來就照原樣

            日期對照表（用這個，不要自己推算星期或週次）：
            %s
            """;

    private final ChatClient chatClient;
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    /**
     * 逐字稿裡的星期。{@code (星期|禮拜|週)} 後面接一個日字。
     *
     * <p>刻意不收「週末」「一週後」：前者不是特定某天，後者的「週」根本不是星期的意思。
     * 正則後面必須接日字，那兩個都不會中。
     */
    private static final Pattern WEEKDAY_IN_TRANSCRIPT =
            Pattern.compile("(?:星期|禮拜|拜|週)([一二三四五六日天])");

    NoteExtractor(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 呼叫模型抽取。
     *
     * <p>結果進 Redis 快取（key 見 {@code ExtractionCacheKeyGenerator}）。
     * 地端模型沒有金錢成本，但一次抽取 12 秒起跳，而<b>同樣的輸入會一再進來</b>：
     * eval 每次跑同一批案例、重跑舊資料、prompt 沒改而只是重啟。
     *
     * <p>失敗時回 {@code null} 而不是空清單，是為了<b>不讓失敗進快取</b>
     * （{@code disableCachingNullValues}）——把一次連線失敗記住 24 小時，
     * 比不快取糟得多。
     *
     * @param transcript 語音轉錄的逐字稿
     * @param recordedAt 錄音當下的時刻。「明天」「下週二」以它為基準，而不是現在——
     *                   平常兩者差幾秒沒影響，但重跑舊資料或佇列積壓時會整個錯開。
     * @return 抽取結果；連續驗證失敗時為 null
     */
    // 🔴 unless 不是最佳化，是**這個方法回 null 時的必要條件**。
    // Redis 的 cache 不收 null，少了它，「連續驗證失敗」這條路不會乾淨地回 null，
    // 而是拋一個講快取設定的例外——症狀跟成因完全對不上，
    // 而且它會蓋掉 log 裡那行真正有用的「抽取連續 N 次驗證失敗」。
    //
    // 這個洞原本就在，只是驗證很少耗盡所以沒踩到。加了星期檢查之後才浮出來。
    @Cacheable(cacheNames = LlmCacheConfig.EXTRACTION_CACHE,
            keyGenerator = "extractionCacheKeyGenerator",
            unless = "#result == null")
    public ExtractedNote extract(String transcript, Instant recordedAt) {

        // 直接給日曆，而不是要模型自己算。實測光給「今天是星期五」還不夠——
        // 它得再推算「8/17 是星期幾」，而那一步會錯。
        LocalDate today = LocalDate.ofInstant(recordedAt, ZONE);
        String system = SYSTEM.formatted(calendar(today));

        // 🔴 明確的相對星期由程式算好，不讓模型在一堆規則中間分神去查表。
        // 理由與量測見 RelativeDates——同一個模型單獨問答得對，放進完整 prompt 就錯，
        // 而且會編造一列表格裡不存在的資料來確認自己。
        String dateHint = RelativeDates.hint(RelativeDates.resolve(transcript, today));
        String userMessage = dateHint.isEmpty() ? transcript : transcript + "\n\n" + dateHint;

        String errorFeedback = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ExtractedNote result = chatClient.prompt()
                    .system(system)
                    .user(errorFeedback == null ? userMessage
                            : userMessage + "\n\n上次的輸出有問題：\n" + errorFeedback)
                    .call()
                    .entity(ExtractedNote.class);

            List<String> errors = validate(result, recordedAt, transcript);
            if (errors.isEmpty()) {
                return result;
            }
            errorFeedback = String.join("\n", errors);
            log.warn("抽取驗證失敗（第 {} 次）：{}", attempt, errorFeedback);
        }

        log.error("抽取連續 {} 次驗證失敗，放棄", MAX_ATTEMPTS);
        return null;
    }

    /** 把模型回的 DTO 轉成領域物件。抽不出東西（或抽取失敗）時是空清單。 */
    public static List<NoteItem> toItems(ExtractedNote result) {
        return result == null || result.items() == null
                ? List.of()
                : result.items().stream().map(NoteExtractor::toEntity).toList();
    }

    /**
     * 今天起 14 天的日期、星期<b>與週次</b>，讓模型查表而不是心算。
     *
     * <p>🔴 <b>週次那一欄是後來補的，而少了它會錯整整七天。</b>
     *
     * <p>原本的表只標「今天」「明天」，其餘只有星期幾。問題是 14 天裡
     * <b>每個星期幾都出現兩次</b>——使用者說「下禮拜一」，表格答得出
     * 「哪幾天是星期一」，卻答不出「<b>哪一個</b>是下週一」。
     * 模型從兩個候選裡挑，而它挑錯過（8/28 週五說「下禮拜一」，抽成 9/07 而不是 8/31）。
     *
     * <p>這不是幻覺，是<b>表格沒給它判斷的依據</b>。而這張表存在的理由就是
     * 「不要讓模型心算」——只解決一半的話，剩下那一半正好是會出錯的那一半。
     *
     * <p>週的起點用星期一（ISO-8601，台灣的日常用法也是這樣）：
     * 從週五說「下禮拜」，指的是下一個星期一開始的那一週。
     */
    // package-private 而不是 private：跟 GoogleCalendarClient.classify 同一個理由——
    // 這裡錯了不會拋例外，症狀是「行程差七天」，而那要等使用者發現才知道。
    static String calendar(LocalDate today) {
        // 本週的星期一。所有週次標籤都以它為原點，模型不必知道任何週次規則。
        LocalDate thisMonday = today.with(java.time.DayOfWeek.MONDAY);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            LocalDate d = today.plusDays(i);
            sb.append("            ").append(d)
                    .append("（").append(d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.TAIWAN)).append("）");

            List<String> marks = new ArrayList<>();
            if (i == 0) {
                marks.add("今天");
            } else if (i == 1) {
                marks.add("明天");
            }
            marks.add(weekLabel(thisMonday, d));

            sb.append(" ← ").append(String.join("、", marks)).append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * 「本週三」「下週一」「下下週六」——把日期換成使用者實際會講的說法。
     *
     * <p>標到「下下週」為止：14 天的表最多跨到第三週的頭幾天，而再遠的說法
     * （「下個月」「三週後」）本來就不該靠這張表回答。
     */
    private static String weekLabel(LocalDate thisMonday, LocalDate day) {
        long weeks = java.time.temporal.ChronoUnit.WEEKS.between(
                thisMonday, day.with(java.time.DayOfWeek.MONDAY));
        String prefix = switch ((int) weeks) {
            case 0 -> "本週";
            case 1 -> "下週";
            case 2 -> "下下週";
            default -> "第 " + (weeks + 1) + " 週的星期";
        };
        return prefix + "一二三四五六日".charAt(day.getDayOfWeek().getValue() - 1);
    }

    /**
     * 領域驗證。schema 只保證格式，這裡檢查的是「內容合不合理」——
     * 模型可以回傳完全合法的 JSON，但把 2026 年的行程寫成 2025 年。
     *
     * <p>合理範圍以 {@code recordedAt} 為基準，不是 {@code Instant.now()}。
     * 理由同 {@code ClockConfig} 的說明：相對日期是<b>資料本身的屬性</b>，
     * 不是執行到這一行時的環境時刻。用 now() 的話，重跑一年前的舊資料
     * 會把當時完全正確的日期判成「超出合理範圍」，然後無謂地重試兩次再放棄。
     * 順帶讓這個方法可以用固定時間測。
     *
     * @param recordedAt 錄音當下的時刻
     * @return 錯誤訊息；全部通過時回傳空清單
     */
    static List<String> validate(ExtractedNote result, Instant recordedAt, String transcript) {
        List<String> errors = new ArrayList<>();
        if (result == null || result.items() == null) {
            errors.add("沒有回傳 items");
            return errors;
        }

        Instant lowerBound = recordedAt.minusSeconds(365L * 24 * 3600);
        Instant upperBound = recordedAt.plusSeconds(2 * 365L * 24 * 3600);

        for (int i = 0; i < result.items().size(); i++) {
            ExtractedNote.Item item = result.items().get(i);
            String at = "items[" + i + "]";

            if (item.category() == null) {
                errors.add(at + ".category 不可為空");
            }
            if (item.title() == null || item.title().isBlank()) {
                errors.add(at + ".title 不可為空");
            }

            if (item.occursAt() != null && !item.occursAt().isBlank()) {
                try {
                    Instant t = Instant.parse(item.occursAt());
                    if (t.isBefore(lowerBound) || t.isAfter(upperBound)) {
                        errors.add(at + ".occursAt=" + item.occursAt()
                                + " 超出合理範圍（錄音當下前後一到兩年），年份可能推斷錯誤");
                    }
                } catch (Exception e) {
                    errors.add(at + ".occursAt=" + item.occursAt()
                            + " 不是合法的 ISO-8601，需為 2026-08-15T09:00:00+08:00 這種格式");
                }
            } else if (item.category() == NoteCategory.SCHEDULE) {
                errors.add(at + " 分類為 SCHEDULE 卻沒有 occursAt，可能分類錯誤");
            }
        }

        weekdayMismatch(result, transcript, recordedAt).ifPresent(errors::add);
        return errors;
    }

    /**
     * 逐字稿說了星期幾，抽出來的日期就得真的落在那一天。
     *
     * <p>🔴 <b>這一條防的是「模型編造日曆表的內容」。</b>實際發生過：2026-08-28（週五）
     * 說「下禮拜一早上去高雄」，抽成 2026-09-01。而模型的推理過程裡寫著
     *
     * <blockquote>Table says: 2026-09-01（星期一）← 下週一</blockquote>
     *
     * ——表上寫的是 {@code 2026-08-31（星期一）← 下週一}、{@code 2026-09-01（星期二）← 下週二}。
     * 它「查表」查到一列<b>不存在的資料</b>，然後還 confirm 了兩次。
     *
     * <p>單獨把表丟給模型問「下禮拜一是哪一天」它答得對；<b>加上完整的規則之後就編了</b>。
     * 推理過程顯示它幾乎所有力氣都花在 title 規則上來回糾結，日期只是順手一撈——
     * <b>而撈錯了沒有任何東西會反駁它</b>。這個方法就是那個反駁。
     *
     * <p>檢查的是<b>星期幾，不是哪一週</b>，而那是刻意的：不管使用者說的是「下週一」
     * 還是「下下週一」，答案都必須是星期一。所以這一條不必自己解析週次，
     * 卻抓得到「9/01 是星期二」。
     *
     * <h4>兩個保守的邊界</h4>
     *
     * <ul>
     * <li><b>逐字稿出現兩種以上的星期就跳過</b>——「星期三開會、星期五交報告」
     * 沒辦法判斷哪一筆該對哪一個</li>
     * <li>只要求<b>有一筆</b>落在那一天，不是每一筆都要——同一句話裡可能還有
     * 「明天」之類的其他項目，要求全部命中會誤殺</li>
     * </ul>
     *
     * <p>誤判的代價不對稱：漏抓只是維持現狀，錯抓會讓一個正確的抽取被重試到放棄。
     */
    private static Optional<String> weekdayMismatch(ExtractedNote result, String transcript,
            Instant recordedAt) {
        if (transcript == null) {
            return Optional.empty();
        }
        Set<DayOfWeek> mentioned = new LinkedHashSet<>();
        var m = WEEKDAY_IN_TRANSCRIPT.matcher(transcript);
        while (m.find()) {
            mentioned.add(toDayOfWeek(m.group(1)));
        }
        if (mentioned.size() != 1) {
            return Optional.empty();
        }
        DayOfWeek expected = mentioned.iterator().next();

        List<String> dated = result.items().stream()
                .map(ExtractedNote.Item::occursAt)
                .filter(o -> o != null && !o.isBlank())
                .toList();
        if (dated.isEmpty()) {
            return Optional.empty();
        }

        List<String> actual = new ArrayList<>();
        for (String iso : dated) {
            try {
                var day = Instant.parse(iso).atZone(ZONE);
                if (day.getDayOfWeek() == expected) {
                    return Optional.empty();
                }
                actual.add(iso.substring(0, 10) + "（" + zh(day.getDayOfWeek()) + "）");
            } catch (Exception ignored) {
                // 格式錯誤上面那一輪已經報過了，這裡不重複
                return Optional.empty();
            }
        }
        // 🔴 光說「星期對不上」不夠——實測模型會把同一個答案再交一次。
        // 它相信自己查到的那一列，所以要**把表上真正符合的那幾列攤在它面前**，
        // 讓它從一個封閉的清單裡選，而不是再查一次同一張表。
        return Optional.of("逐字稿說的是「" + zh(expected) + "」，但填的日期是 "
                + String.join("、", actual) + "，星期對不上。"
                + "表上的" + zh(expected) + "只有 " + candidates(expected, recordedAt)
                + "，請從這幾個裡面選一個。");
    }

    /** 日曆表 14 天裡符合這個星期的所有日期，帶上週次標籤。 */
    private static String candidates(DayOfWeek expected, Instant recordedAt) {
        LocalDate today = LocalDate.ofInstant(recordedAt, ZONE);
        LocalDate thisMonday = today.with(DayOfWeek.MONDAY);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            LocalDate d = today.plusDays(i);
            if (d.getDayOfWeek() == expected) {
                out.add(d + "（" + weekLabel(thisMonday, d) + "）");
            }
        }
        return String.join("、", out);
    }

    private static DayOfWeek toDayOfWeek(String ch) {
        return switch (ch) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            // 「星期日」與「星期天」是同一天
            default -> DayOfWeek.SUNDAY;
        };
    }

    private static String zh(DayOfWeek day) {
        return "星期" + "一二三四五六日".charAt(day.getValue() - 1);
    }

    static NoteItem toEntity(ExtractedNote.Item item) {
        Instant occursAt = null;
        if (item.occursAt() != null && !item.occursAt().isBlank()) {
            occursAt = Instant.parse(item.occursAt());
        }
        // 沒有時間就沒有「時間是不是講出來的」可言，一律存 null——
        // 讓模型填的 true/false 在這種情況下留下來，只會讓下游多一種要處理的組合。
        Boolean timeSpecified = occursAt == null ? null : item.timeSpecified();
        return new NoteItem(item.category(), item.title(), occursAt, timeSpecified,
                item.detail(), item.tags());
    }
}
