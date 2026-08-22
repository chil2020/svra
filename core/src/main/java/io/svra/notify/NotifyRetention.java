package io.svra.notify;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.svra.RetentionProperties;

/**
 * 清掉很久以前的訊息錨點與投遞紀錄。
 *
 * <p>🔴 <b>這個保留期比 outbox 長得多，而那不是隨手挑的。</b>
 * 錨點決定使用者能不能<b>引用一則舊訊息</b>來下指令，而 LINE 的聊天紀錄是永久的
 * ——他隨時可能往上滑找到三個月前那張卡片。刪太快的結果是
 * 「回覆它，卻被告訴『這則我對不上』」，而那看起來像壞掉。
 *
 * <p>所以這裡的取捨是明確的：<b>寧可多留，也不要讓一個正常的操作失敗。</b>
 * outbox 那邊剛好相反——做完的事沒有人會回頭看。
 */
@Component
class NotifyRetention {

    private static final Logger log = LoggerFactory.getLogger(NotifyRetention.class);

    private final MessageAnchorRepository anchors;
    private final OutboxDeliveryRepository deliveries;
    private final RetentionProperties retention;
    private final Clock clock;

    NotifyRetention(MessageAnchorRepository anchors, OutboxDeliveryRepository deliveries,
            RetentionProperties retention, Clock clock) {
        this.anchors = anchors;
        this.deliveries = deliveries;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(cron = "${svra.retention.cron:0 0 4 * * *}", zone = "Asia/Taipei")
    @Transactional
    public void purge() {
        Instant before = Instant.now(clock).minus(retention.messageAnchors());
        int removedAnchors = anchors.deleteCreatedBefore(before);
        // 投遞紀錄跟著一起清：它擋的是「同一筆 outbox 事件重跑」，而那筆事件
        // 早就被 outbox 的保留期刪掉了——留著它只剩稽核價值，而稽核跟錨點同期。
        int removedDeliveries = deliveries.deleteDeliveredBefore(before);
        if (removedAnchors > 0 || removedDeliveries > 0) {
            log.info("清掉 {} 筆訊息錨點與 {} 筆投遞紀錄（超過 {}）",
                    removedAnchors, removedDeliveries, retention.messageAnchors());
        }
    }
}
