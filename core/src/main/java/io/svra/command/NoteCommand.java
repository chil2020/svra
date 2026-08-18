package io.svra.command;

import java.util.List;

/**
 * LLM 解析出來的指令。
 *
 * <p>一次可以有多個動作——使用者本來就會說「把第一筆跟第三筆刪掉，再加一筆下週三開會」。
 * 早期版本一句話只解析成一個動作，多的部分只能塞進 {@link #unhandled} 回報做不到，
 * 那是實作的限制外洩成使用者要遷就的規則。
 *
 * @param ops       要依序執行的動作；看不懂時是空的
 * @param reason    ops 為空時說明為什麼，會直接回給使用者
 * @param unhandled 這次沒能處理的部分。沉默地只做一半比看不懂更糟——
 *                  使用者會以為都交代了。
 */
record NoteCommand(List<Op> ops, String reason, String unhandled) {

    /**
     * 單一動作。
     *
     * @param itemIndex DELETE / UPDATE_* 用，清單上的編號（從 1 開始）
     * @param title     UPDATE_TITLE 的新標題，或 ADD 的標題
     * @param occursAt  UPDATE_TIME 的新時間，或 ADD 的時間；ISO-8601
     * @param category  ADD 用：SCHEDULE / TODO / IDEA
     */
    record Op(Action action, Integer itemIndex, String title, String occursAt, String category) {
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
