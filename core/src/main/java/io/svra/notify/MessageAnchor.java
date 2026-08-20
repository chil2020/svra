package io.svra.notify;

import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一則推播出去的訊息，當時秀了哪些項目、依什麼順序。
 *
 * <p>使用者說「第三筆」時指的是<b>他眼前那則訊息上的第三筆</b>。存下當時的順序，
 * 解析編號就不必從清單現況重算——而重算會漂：那批若已經刪過東西，
 * 算出來的第三筆就是別的項目了。
 */
@Entity
@Table(name = "message_anchors")
class MessageAnchor {

    @Id
    @Column(name = "line_message_id", length = 64, updatable = false)
    private String lineMessageId;

    @Column(name = "line_user_id", nullable = false, length = 64, updatable = false)
    private String lineUserId;

    /** 順序就是使用者看到的編號順序。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "item_ids", nullable = false, columnDefinition = "bigint[]", updatable = false)
    private List<Long> itemIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MessageAnchor() {
    }

    MessageAnchor(String lineMessageId, String lineUserId, List<Long> itemIds, Instant createdAt) {
        this.lineMessageId = lineMessageId;
        this.lineUserId = lineUserId;
        this.itemIds = itemIds;
        this.createdAt = createdAt;
    }

    List<Long> getItemIds() {
        return itemIds;
    }

    String getLineUserId() {
        return lineUserId;
    }
}
