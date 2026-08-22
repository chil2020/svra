package io.svra.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 誰已經收不到訊息了。
 *
 * <p>放在 notify 是因為<b>它是「送不送得出去」的一部分</b>——這個模組的職責就是
 * 把訊息送到人手上，而「這個人已經把我們封鎖了」正是那件事的前提。
 *
 * <p>單人使用時這個概念沒有意義（使用者就是你自己）。開放給多人之後它每天都可能
 * 發生，而系統原本對它一無所知：{@code unfollow} 事件跟貼圖、已讀一樣掉進
 * 「收得下但不處理」。
 *
 * <p><b>省的不是錢</b>——LINE 對封鎖者的訊息本來就不計費。省的是不要在 log 裡
 * 堆一堆註定沒有意義的推播，以及讓「這個人已經不在了」這件事有地方可查。
 */
@Service
public class Blocklist {

    private static final Logger log = LoggerFactory.getLogger(Blocklist.class);

    private final BlockedUserRepository repository;

    Blocklist(BlockedUserRepository repository) {
        this.repository = repository;
    }

    /** 使用者封鎖或刪除了這個帳號。 */
    @Transactional
    public void block(String lineUserId) {
        if (repository.blockIfAbsent(lineUserId) == 1) {
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
        if (repository.existsById(lineUserId)) {
            repository.deleteById(lineUserId);
            log.info("使用者重新加入好友，解除封鎖標記");
        }
    }

    @Transactional(readOnly = true)
    public boolean isBlocked(String lineUserId) {
        return lineUserId != null && repository.existsById(lineUserId);
    }
}
