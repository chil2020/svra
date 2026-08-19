package io.svra.extract;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.svra.note.NoteCategory;

/**
 * LLM 的抽取結果。Spring AI 會從這個 record 產生 JSON Schema 塞進 prompt，
 * 回來再解析成物件——所以欄位名稱與註解都會影響模型的輸出品質。
 *
 * <p>🔴 <b>「選填」要標出來，不然 schema 會說它必填。</b>Spring AI 預設把每個欄位
 * 都列進 {@code required}，而那份 schema 是接在 prompt 後面送出去的，
 * 還附著一句「不得偏離」。少了標註，等於 prompt 裡有兩份互相矛盾的指示——
 * 底下的規則說「沒提到時間就填 null」，schema 說 occursAt 必填。
 *
 * <p>🔴 <b>但不是每個欄位都該放寬，而這件事只有量了才知道。</b>
 * {@code detail} 與 {@code tags} 刻意<b>維持必填</b>：把 {@code detail} 也標成選填之後，
 * eval 的 {@code multi-002}（三天行程的真實逐字稿）從 4 筆變成 <b>8 筆</b>，
 * 8/8 掉到 7/8，重現三次，耗時也從 23 秒漲到 45 秒。
 *
 * <p>推測的機制：{@code detail} 必填時，每多切一筆就得多寫一段補充，
 * 那個成本會逼模型把同一天的事併在一起；放寬之後切分變得沒有代價。
 * <b>一個跟「要切幾筆」看起來毫無關係的欄位，決定了切分的粒度。</b>
 * 這種事沒有 eval 是看不出來的——上線後只會有人覺得「清單怎麼變得好碎」。
 *
 * @param items 一段語音可能同時包含待辦、想法與行程，所以是多筆
 */
record ExtractedNote(List<Item> items) {

    /**
     * @param category  TODO 待辦｜IDEA 想法｜SCHEDULE 行程
     * @param title     一句話摘要，不超過 30 字
     * @param occursAt  ISO-8601 日期時間；想法類或沒提到時間時為 null。
     *                  <b>這是「事情發生的時間」，不是收到訊息的時間</b>——
     *                  後者是 {@code notes.created_at}，而且它只當推算的基準
     * @param detail    補充內容，沒有就 null
     * @param tags      主題標籤，例如 旅遊、財務、健康
     */
    public record Item(
            NoteCategory category,
            String title,
            @JsonProperty(required = false) String occursAt,
            String detail,
            List<String> tags) {
    }
}
