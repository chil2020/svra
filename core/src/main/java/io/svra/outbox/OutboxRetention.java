package io.svra.outbox;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.svra.RetentionProperties;

/**
 * 清掉做完很久的 outbox 事件。
 *
 * <p>這張表原本只增不減。個人規模下要很久才會有感，但那不是理由——
 * <b>一張沒有人負責清理的表，是一個等著變成問題的東西</b>。
 *
 * <p>🔴 <b>只刪 SENT。</b>FAILED 的是「還沒有人去看的問題」，
 * 而它們的 payload 是理解那個問題的唯一線索（SENT 的 payload 在標記時就清掉了）。
 * 把失敗連同證據一起刪掉，等於讓系統自己湮滅自己的錯誤紀錄。
 *
 * <p>PENDING 的更不能刪：那是還沒做的事。
 */
@Component
class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    private final OutboxEventRepository repository;
    private final RetentionProperties retention;
    private final Clock clock;

    OutboxRetention(OutboxEventRepository repository, RetentionProperties retention, Clock clock) {
        this.repository = repository;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * 每天凌晨四點跑一次。
     *
     * <p>用 cron 而不是 fixedDelay：清理是<b>維護</b>不是業務，該挑一個沒有人在用的時段。
     * 排在 poller 之外的時間也讓「刪除拖慢了送訊息」這件事不會發生。
     */
    @Scheduled(cron = "${svra.retention.cron:0 0 4 * * *}", zone = "Asia/Taipei")
    @Transactional
    public void purge() {
        Instant before = Instant.now(clock).minus(retention.sentEvents());
        int removed = repository.deleteSentBefore(before);
        if (removed > 0) {
            log.info("清掉 {} 筆做完超過 {} 的 outbox 事件", removed, retention.sentEvents());
        }
    }
}
