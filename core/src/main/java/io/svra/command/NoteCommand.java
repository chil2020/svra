package io.svra.command;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LLM 解析出來的指令。
 *
 * <p>一次可以有多個動作——使用者本來就會說「把第一筆跟第三筆刪掉，再加一筆下週三開會」。
 * 早期版本一句話只解析成一個動作，多的部分只能塞進 {@link #unhandled} 回報做不到，
 * 那是實作的限制外洩成使用者要遷就的規則。
 *
 * <p>🔴 <b>這個 record 就是 prompt 的一部分。</b>Spring AI 從它產生 JSON Schema，
 * 接在 prompt 後面送給模型，並附上一句「your output must adhere to ... without deviation」。
 * 也就是說 prompt 裡有<b>兩份指示</b>：手寫的規則，與從型別自動長出來的 schema——
 * 而後者的語氣更硬。兩份打架時模型聽 schema 的，所以每個選填欄位都要標
 * {@code @JsonProperty(required = false)}。理由見 {@link Op}。
 *
 * @param ops       要依序執行的動作；看不懂時是空的
 * @param reason    ops 為空時說明為什麼，會直接回給使用者
 * @param unhandled 這次沒能處理的部分。沉默地只做一半比看不懂更糟——
 *                  使用者會以為都交代了。
 */
record NoteCommand(List<Op> ops,
        @JsonProperty(required = false) String reason,
        @JsonProperty(required = false) String unhandled) {

    /**
     * 單一動作。
     *
     * <p>🔴 <b>除了 {@code action}，每個欄位都必須標
     * {@code @JsonProperty(required = false)}。</b>Spring AI 產生 schema 時
     * <b>預設把所有欄位都列進 {@code required}</b>，那段文字會原封不動進到 prompt 裡。
     *
     * <p>少了這些標註，症狀是這樣的：
     *
     * <ul>
     * <li>「幫我加一筆待辦：買咖啡豆」——手寫的規則說「沒講時間就不要填 occursAt」，
     * schema 說它必填。模型選了 schema，回了一段解釋說
     * 「JSON Schema 強制要求 occursAt……無法生成表示『無時間』的回應」，然後整句拒絕。
     * <b>它沒有搞錯，是我們給了兩份相反的指示。</b>另一條路更糟：它會<b>編一個日期</b>。</li>
     * <li>LIST 與 ADD 不指涉任何一筆，卻被逼著填 {@code itemIndex}
     * ——實測填過 {@code -1}，所以套用指令時得記得「不能因為它填了就當真」。</li>
     * </ul>
     *
     * <p>也就是說：<b>模型的怪行為有時候不是模型的問題，是我們給的型別在逼它。</b>
     * 而型別寫的那半份 prompt 不會出現在任何一個字串常數裡——
     * 改手寫規則改不到它，{@code CommandSchemaTest} 守著它。
     *
     * @param itemIndex     DELETE / UPDATE_* 用，清單上的編號（從 1 開始）
     * @param title         UPDATE_TITLE 的新標題，或 ADD 的標題
     * @param occursAt      UPDATE_TIME 的新時間，或 ADD 的時間；ISO-8601。
     *                      <b>ADD 沒有時間是正常的</b>——使用者還沒想好什麼時候做而已
     * @param timeSpecified 使用者這次有沒有講出幾點。跟抽取層的同名欄位是同一件事
     *                      （見 {@code ExtractedNote.Item}）：09:00 究竟是他說的，
     *                      還是規則補的，決定了同步到行事曆時是定時事件還是全天事件。
     *                      <p>UPDATE_TIME 填 false 或不填時<b>沿用那一筆原本的值</b>——
     *                      因為上面的規則要模型在「只講日期」時沿用原本的時刻，
     *                      時刻既然是沿用的，「時刻是不是講出來的」自然也該沿用
     * @param category      ADD 用：SCHEDULE / TODO / IDEA
     */
    /**
     * 「就是要看清單」，不經過模型。
     *
     * <p>給快速路徑用（見 {@link QuickCommand}）：按鈕送出的字串是固定的，
     * 意圖已經確定，沒有什麼要模型判斷的。
     */
    static NoteCommand listOnly() {
        return new NoteCommand(
                List.of(new Op(Action.LIST, null, null, null, null, null)), null, null);
    }

    record Op(Action action,
            @JsonProperty(required = false) Integer itemIndex,
            @JsonProperty(required = false) String title,
            @JsonProperty(required = false) String occursAt,
            @JsonProperty(required = false) Boolean timeSpecified,
            @JsonProperty(required = false) String category) {
    }

    public enum Action {
        /** 刪除某一筆 */
        DELETE,
        /** 改標題 */
        UPDATE_TITLE,
        /** 改時間 */
        UPDATE_TIME,
        /** 新增一筆 */
        ADD,
        /** 列出目前的項目。不改任何東西 */
        LIST
    }

    static NoteCommand unknown(String reason) {
        return new NoteCommand(List.of(), reason, null);
    }

    boolean isUnknown() {
        return ops == null || ops.isEmpty();
    }
}
