package io.svra.note;

/** 筆記的生命週期：收到語音時 PENDING，轉錄結果回來後轉 COMPLETED 或 FAILED。 */
public enum NoteStatus {
    PENDING,
    COMPLETED,
    FAILED
}
