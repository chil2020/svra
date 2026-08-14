package io.svra.extract;

/**
 * LLM 解析出來的指令。
 *
 * @param action     要做什麼
 * @param itemIndex  目標項目的編號（推播訊息上的數字，從 1 開始）；找不到對象時為 null
 * @param newTitle   UPDATE_TITLE 用
 * @param newOccursAt UPDATE_TIME 用，ISO-8601
 * @param reason     UNKNOWN 時說明為什麼看不懂，會回給使用者
 */
public record NoteCommand(
        Action action,
        Integer itemIndex,
        String newTitle,
        String newOccursAt,
        String reason) {

    public enum Action {
        /** 刪除某一筆 */
        DELETE,
        /** 改標題 */
        UPDATE_TITLE,
        /** 改時間 */
        UPDATE_TIME,
        /** 看不懂或沒有對應的項目 */
        UNKNOWN
    }
}
