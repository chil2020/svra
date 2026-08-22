package io.svra.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommandExecutionRepository extends JpaRepository<CommandExecution, String> {

    /**
     * 記下「這則指令由我執行」，已經有人記過就什麼都不做。
     *
     * <p>與 {@code NoteRepository.insertPendingIfAbsent}、
     * {@code OutboxEventRepository.insertIfAbsent} 是同一個模式：
     * 衝突由資料庫原子地吞掉、不拋例外、不弄髒交易，判斷仍然發生在資料庫層。
     * 「先 exists 再 save」在這裡一樣不成立——兩個 poller 可能同時查到不存在。
     *
     * @return 1 = 這次由我執行；0 = 已經執行過了，這次是重跑
     */
    @Modifying
    @Query(value = """
            INSERT INTO command_executions (command_message_id, line_user_id)
            VALUES (:commandMessageId, :lineUserId)
            ON CONFLICT (command_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("commandMessageId") String commandMessageId,
            @Param("lineUserId") String lineUserId);
}
