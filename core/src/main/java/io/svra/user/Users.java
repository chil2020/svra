package io.svra.user;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用者的存在與狀態。
 *
 * <p>取代了原本 notify 模組裡的 {@code Blocklist}——封鎖從「一張獨立的表」
 * 變成使用者的一個欄位。搬家的理由：封鎖只是「關於這個人的事實」之一，
 * 而那類事實正在變多（憑證、之後可能有時區），每一個都開一張表會讓
 * 「刪掉這個人的所有資料」永遠少掃一張。
 */
@Service
public class Users {

    private static final Logger log = LoggerFactory.getLogger(Users.class);

    private final UserRepository repository;
    private final Clock clock;

    Users(UserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 讓這個人在資料庫裡存在。<b>webhook 收到任何帶 userId 的事件時第一件做的事。</b>
     *
     * <p>🔴 <b>不能只在 {@code follow} 事件建立。</b>兩個理由：
     * <ul>
     * <li>在這張表出現之前就加過好友的人，永遠不會再送一次 follow</li>
     * <li>webhook 是 at-least-once <b>但不是保證送達</b>——follow 漏接的話，
     * 那個人接下來每一次操作都會撞到外鍵，而症狀是「傳語音沒反應」</li>
     * </ul>
     *
     * <p>所以入口不看事件型別，一律 upsert。成本是一次會命中主鍵索引的
     * {@code ON CONFLICT DO NOTHING}。
     */
    @Transactional
    public void ensureExists(String lineUserId) {
        if (lineUserId == null || lineUserId.isBlank()) {
            return;
        }
        if (repository.insertIfAbsent(lineUserId) == 1) {
            log.info("第一次見到這個使用者，建立紀錄");
        }
    }

    /** 使用者封鎖或刪除了這個帳號。 */
    @Transactional
    public void block(String lineUserId) {
        ensureExists(lineUserId);
        if (repository.markBlocked(lineUserId, Instant.now(clock)) == 1) {
            log.info("使用者已封鎖本帳號，之後不再對他送訊息");
        }
    }

    /**
     * 使用者（重新）加了好友。
     *
     * <p>一定要清掉，否則封鎖過再回來的人會變成一個<b>永遠收不到訊息的幽靈</b>——
     * 他傳語音、系統照常處理、而回覆全部被這一層擋掉，log 上看起來一切正常。
     */
    @Transactional
    public void unblock(String lineUserId) {
        ensureExists(lineUserId);
        if (repository.clearBlocked(lineUserId) == 1) {
            log.info("使用者重新加入好友，解除封鎖標記");
        }
    }

    @Transactional(readOnly = true)
    public boolean isBlocked(String lineUserId) {
        return lineUserId != null
                && repository.existsByLineUserIdAndBlockedAtIsNotNull(lineUserId);
    }
}
