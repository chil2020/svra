package io.svra.extract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 把逐字稿抽成結構化項目。
 *
 * <p>
 * 🔴 承重點④。
 */
@Component
public class NoteExtractor {

    private static final Logger log = LoggerFactory.getLogger(NoteExtractor.class);

    /** 改 prompt 就要改這個，才比較得出「是模型的差異還是 prompt 的差異」。 */
    public static final String PROMPT_VERSION = "v1";

    private static final int MAX_ATTEMPTS = 2;

    private static final String SYSTEM = """
            你是一個把口語筆記整理成結構化資料的助手。

            使用者的輸入是語音轉錄的逐字稿，會有贅字、自我修正、講到一半跳題，
            也可能有轉錄錯誤（同音字）。請依語意判斷，不要逐字照抄。

            規則：
            - 一段話可能包含多件事，各自成為一個 item
            - category：TODO 待辦事項｜SCHEDULE 有明確時間的行程｜IDEA 想法或靈感
            - title 一句話講完，30 字以內
            - occursAt 用 ISO-8601（例如 2026-08-15T09:00:00+08:00）。
              只講到日期沒講時間就用當天 09:00。完全沒提到時間就填 null
            - 逐字稿裡沒有的資訊不要自己補
            - 明顯是轉錄錯誤的專有名詞，若能從上下文判斷就修正，判斷不出來就照原樣

            今天是 %s。使用者說「明天」「下週二」時以此為基準。
            """;

    private final ChatClient chatClient;
    private final ZoneId zone = ZoneId.of("Asia/Taipei");

    public NoteExtractor(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * @param transcript 語音轉錄的逐字稿
     * @return 抽取出來的項目；完全抽不出東西時回傳空清單
     */
    public List<NoteItem> extract(String transcript) {

        String system = SYSTEM.formatted(LocalDate.now(zone));
        String errorFeedback = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ExtractedNote result = chatClient.prompt()
                    .system(system)
                    .user(errorFeedback == null ? transcript
                            : transcript + "\n\n上次的輸出有問題：\n" + errorFeedback)
                    .call()
                    .entity(ExtractedNote.class);

            List<String> errors = validate(result);
            if (errors.isEmpty()) {
                return result.items().stream().map(NoteExtractor::toEntity).toList();
            }
            errorFeedback = String.join("\n", errors);
            log.warn("抽取驗證失敗（第 {} 次）：{}", attempt, errorFeedback);
        }

        log.error("抽取連續 {} 次驗證失敗，放棄", MAX_ATTEMPTS);
        return List.of();

    }

    /**
     * 領域驗證。schema 只保證格式，這裡檢查的是「內容合不合理」——
     * 模型可以回傳完全合法的 JSON，但把 2026 年的行程寫成 2025 年。
     *
     * @return 錯誤訊息；全部通過時回傳空清單
     */
    static List<String> validate(ExtractedNote result) {
        List<String> errors = new ArrayList<>();
        if (result == null || result.items() == null) {
            errors.add("沒有回傳 items");
            return errors;
        }

        Instant now = Instant.now();
        Instant lowerBound = now.minusSeconds(365L * 24 * 3600);
        Instant upperBound = now.plusSeconds(2 * 365L * 24 * 3600);

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
                                + " 超出合理範圍（今天前後一到兩年），年份可能推斷錯誤");
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
