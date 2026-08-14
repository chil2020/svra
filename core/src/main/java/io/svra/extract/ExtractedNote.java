package io.svra.extract;

import java.util.List;

/**
 * LLM 的抽取結果。Spring AI 會從這個 record 產生 JSON Schema 塞進 prompt，
 * 回來再解析成物件——所以欄位名稱與註解都會影響模型的輸出品質。
 *
 * @param items 一段語音可能同時包含待辦、想法與行程，所以是多筆
 */
public record ExtractedNote(List<Item> items) {

    /**
     * @param category  TODO 待辦｜IDEA 想法｜SCHEDULE 行程
     * @param title     一句話摘要，不超過 30 字
     * @param occursAt  ISO-8601 日期時間；想法類或沒提到時間時為 null
     * @param detail    補充內容，沒有就 null
     * @param tags      主題標籤，例如 旅遊、財務、健康
     */
    public record Item(
            NoteCategory category,
            String title,
            String occursAt,
            String detail,
            List<String> tags) {
    }
}
