package io.svra.extract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.ZoneId;
import java.util.ArrayList;
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

    /** 改 prompt 就要改這個，才比較得出「是模型的差異還是 prompt 的差異」。 */
    public static final String PROMPT_VERSION = "v6";

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
        下面有日曆表不代表每一筆都要有時間——想法（IDEA）多半沒有時間，
        使用者沒說時間就是 null，不要拿今天的日期去填
            - 逐字稿裡沒有的資訊不要自己補
            - 明顯是轉錄錯誤的專有名詞，若能從上下文判斷就修正，判斷不出來就照原樣

            日期對照表（用這個，不要自己推算星期）：
            %s
            """;

    private final ChatClient chatClient;
    private final ZoneId zone = ZoneId.of("Asia/Taipei");

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
    @Cacheable(cacheNames = LlmCacheConfig.EXTRACTION_CACHE,
            keyGenerator = "extractionCacheKeyGenerator")
    public ExtractedNote extract(String transcript, Instant recordedAt) {

        // 直接給日曆，而不是要模型自己算。實測光給「今天是星期五」還不夠——
        // 它得再推算「8/17 是星期幾」，而那一步會錯。
        String system = SYSTEM.formatted(calendar(LocalDate.ofInstant(recordedAt, zone)));
        String errorFeedback = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ExtractedNote result = chatClient.prompt()
                    .system(system)
                    .user(errorFeedback == null ? transcript
                            : transcript + "\n\n上次的輸出有問題：\n" + errorFeedback)
                    .call()
                    .entity(ExtractedNote.class);

            List<String> errors = validate(result, recordedAt);
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

    /** 今天起 14 天的日期與星期，讓模型查表而不是心算。 */
    private String calendar(LocalDate today) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            LocalDate d = today.plusDays(i);
            sb.append("            ").append(d)
                    .append("（").append(d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.TAIWAN)).append("）");
            if (i == 0) {
                sb.append(" ← 今天");
            } else if (i == 1) {
                sb.append(" ← 明天");
            }
            sb.append('\n');
        }
        return sb.toString().strip();
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
    static List<String> validate(ExtractedNote result, Instant recordedAt) {
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
        return errors;
    }

    static NoteItem toEntity(ExtractedNote.Item item) {
        Instant occursAt = null;
        if (item.occursAt() != null && !item.occursAt().isBlank()) {
            occursAt = Instant.parse(item.occursAt());
        }
        return new NoteItem(item.category(), item.title(), occursAt, item.detail(), item.tags());
    }
}
