package io.svra.calendar;

/**
 * 從項目 id 推算 Google 行事曆的事件 id。
 *
 * <p>🔴 <b>這是這個功能的冪等機制，不是命名慣例。</b>
 *
 * <p>Google 的 {@code events.insert} 允許呼叫端自己指定 event id，重複的 id 會回
 * <b>409 {@code The requested identifier already exists}</b>。只要 id 是從
 * {@code note_items.id} 決定性算出來的，那麼「使用者手滑點兩次」「LINE 重送 postback」
 * 「poller 送出後、標記 SENT 前掛掉又重跑」——這三種重複<b>全部由 Google 那一端擋掉</b>，
 * 我們這裡不需要任何鎖、任何前置查詢。
 *
 * <p>這比決策 24 那套 {@code command_executions} 執行紀錄更強：執行紀錄擋不住
 * 「寫進外部系統成功、寫紀錄前掛掉」那個縫，而決定性 id 連那個縫都沒有——
 * 因為判斷發生在<b>外部系統自己</b>身上。
 *
 * <p>但它不是萬無一失，Google 自己講得很清楚：<b>因為系統是全球分散式的，
 * 無法保證 id 碰撞一定會在建立事件的當下就被偵測到</b>。
 * 極少數情況下仍可能生出兩筆。這個缺陷寫在 README 決策 26 裡，沒有假裝它不存在。
 *
 * <p>（原文的引號在這裡刻意拿掉了：Spring Modulith 的 {@code Documenter}
 * 會把 javadoc 抽成 JSON，而未跳脫的雙引號會讓 {@code ModularityTest} 整個炸掉。）
 *
 * <p>格式限制來自 Google：只能用 base32hex 的字元（小寫 {@code a-v} 與 {@code 0-9}），
 * 長度 5–1024。{@code Long.toString(id, 32)} 產生的正好落在那個字元集裡。
 */
final class CalendarEventIds {

    /**
     * 前綴。長度 4，加上至少一位數字就滿足「至少 5 字元」的下限。
     *
     * <p>它同時是<b>可讀性的最後一道</b>：在 Google 的 API 回應或 log 裡看到
     * {@code svra...} 就知道這筆是誰寫的。
     */
    private static final String PREFIX = "svra";

    /** {@code Long.MAX_VALUE} 在 32 進位下是 13 位，補滿讓 id 等長、看起來一致。 */
    private static final int WIDTH = 13;

    private CalendarEventIds() {
    }

    static String of(Long noteItemId) {
        if (noteItemId == null) {
            throw new IllegalArgumentException("項目還沒有 id，算不出事件 id");
        }
        String base32hex = Long.toString(noteItemId, 32);
        return PREFIX + "0".repeat(WIDTH - base32hex.length()) + base32hex;
    }
}
