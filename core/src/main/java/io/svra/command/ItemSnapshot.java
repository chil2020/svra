package io.svra.command;

import java.time.Instant;

import io.svra.note.NoteCategory;
import io.svra.note.NoteItem;

/**
 * 帶到交易外面用的項目快照。
 *
 * <p>解析要在交易外跑（LLM 十幾秒），而 entity 到那時已經 detached——
 * 現在讀得到只是因為剛好沒人碰 lazy 欄位，那種「剛好」不該寫進契約。
 *
 * <p>帶著 {@code id} 是關鍵：第三段要動的是資料庫裡的那一筆，靠 id 重新載入。
 * 若改用編號重算，第一段到第三段之間清單只要變過（另一則語音剛抽完、
 * 使用者又下了一句指令），同一個「第二筆」就會指到別的東西。
 */
record ItemSnapshot(Long id, NoteCategory category, String title, Instant occursAt) {

    static ItemSnapshot of(NoteItem item) {
        return new ItemSnapshot(item.getId(), item.getCategory(),
                item.getTitle(), item.getOccursAt());
    }
}
