package io.svra.note;

/**
 * 一則筆記的生命週期。
 *
 * <p>webhook 收到語音訊息時建立 {@link #PENDING}，轉錄結果回來後轉為
 * {@link #COMPLETED}；worker 端失敗（訊息進 DLQ）則標記 {@link #FAILED}。
 */
public enum NoteStatus {

    /** 已收到語音、已送出轉錄任務，尚未有結果。 */
    PENDING,

    /** 轉錄完成，transcript 已寫入。 */
    COMPLETED,

    /** 轉錄失敗（worker 例外或進 DLQ）。 */
    FAILED
}
