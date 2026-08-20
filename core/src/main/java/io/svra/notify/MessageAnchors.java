package io.svra.notify;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「使用者引用的那則訊息，當時秀的是哪幾筆」。
 *
 * <p>放在 notify 是因為<b>訊息是這裡送出去的</b>——誰送的誰知道它裝了什麼。
 * command 讀它來解析「第三筆」，方向與現有的相依一致（command → notify）。
 */
@Service
public class MessageAnchors {

    private static final Logger log = LoggerFactory.getLogger(MessageAnchors.class);

    private final MessageAnchorRepository repository;
    private final Clock clock;

    MessageAnchors(MessageAnchorRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 記下一則剛送出去的清單訊息。
     *
     * <p>推播失敗時拿不到 messageId，那則訊息也不存在，沒有東西要錨——直接跳過。
     */
    @Transactional
    public void record(String lineMessageId, String lineUserId, List<Long> itemIds) {
        if (lineMessageId == null || itemIds.isEmpty()) {
            return;
        }
        repository.save(new MessageAnchor(lineMessageId, lineUserId, itemIds, Instant.now(clock)));
        log.debug("已記下訊息錨點：lineMessageId={} 項目數={}", lineMessageId, itemIds.size());
    }

    /**
     * @return 那則訊息當時秀的項目 id，順序即編號；不是我們送出的訊息時為空
     */
    @Transactional(readOnly = true)
    public Optional<List<Long>> itemIdsFor(String lineMessageId) {
        return repository.findById(lineMessageId).map(MessageAnchor::getItemIds);
    }
}
