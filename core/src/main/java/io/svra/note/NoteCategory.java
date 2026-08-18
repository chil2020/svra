package io.svra.note;

import java.util.Comparator;
import java.util.List;

public enum NoteCategory {
    TODO,
    IDEA,
    SCHEDULE;

    /**
     * 顯示順序。**推播、指令解析、清單查詢三邊的編號都依這個順序**——
     * 使用者說的「第二筆」只有在三邊一致時才指得到同一個項目。
     *
     * <p>不用 enum 的宣告順序：宣告順序是資料庫存的值，改動它會動到既有資料，
     * 而顯示順序是純粹的呈現決定，兩者不該綁在一起。
     */
    public static final List<NoteCategory> DISPLAY_ORDER = List.of(SCHEDULE, TODO, IDEA);

    public int displayRank() {
        return DISPLAY_ORDER.indexOf(this);
    }

    /** 分類先、同分類內依 id——不給定序的話 @OneToMany 的順序是不保證的。 */
    public static Comparator<NoteItem> itemOrder() {
        return Comparator.<NoteItem>comparingInt(i -> i.getCategory().displayRank())
                .thenComparing(NoteItem::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
