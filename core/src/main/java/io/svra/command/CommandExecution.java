package io.svra.command;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 「這則指令已經執行過」的紀錄。一則指令訊息一列。
 *
 * <p>刻意沒有任何行為，也沒有公開的建構子：寫入一律走
 * {@link CommandExecutionRepository#insertIfAbsent}，理由與決策 2 相同——
 * 讓 JPA 去撞主鍵會把整個交易標成 rollback-only，而這個交易還要寫別的東西。
 */
@Entity
@Table(name = "command_executions")
class CommandExecution {

    @Id
    @Column(name = "command_message_id", length = 64, updatable = false)
    private String commandMessageId;

    /** 由資料庫的預設值填。這裡只讀不寫，所以標成不可插入。 */
    @Column(name = "executed_at", nullable = false, insertable = false, updatable = false)
    private Instant executedAt;

    protected CommandExecution() {
    }

    String getCommandMessageId() {
        return commandMessageId;
    }

    Instant getExecutedAt() {
        return executedAt;
    }
}
