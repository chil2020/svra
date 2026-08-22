package io.svra.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 哪些 outbox 事件的訊息已經送出去了。
 *
 * <p>🔴 <b>outbox 是 at-least-once，而送訊息沒有辦法冪等。</b>
 * 行事曆那條可以靠決定性 event id 讓 Google 自己回 409（決策 26），
 * 但 LINE 沒有對應的東西——同一則訊息送兩次就是兩則訊息。
 *
 * <p>而重跑不是理論風險：poller 的 {@code dispatch()} 是<b>整批一個交易</b>，
 * {@code markSent} 要等整批跑完才提交，而同一批裡的抽取事件實測要跑 <b>17 秒</b>。
 * 在那 17 秒內重啟容器，同批已經推播出去的事件會全部重跑。
 *
 * <p><b>這不能做到真正的 exactly-once，而且做不到的原因是本質的</b>：
 * 副作用發生在外部系統，我們沒有辦法讓「LINE 收下訊息」與「我們記下這件事」
 * 在同一個交易裡。剩下的窗口是「LINE 已接受、而我們還沒寫下這一行」——
 * 那是毫秒級，而原本是十七秒級。
 *
 * <p>順帶成為推播的稽核軌跡：誰、什麼時候、LINE 給的訊息 id 是什麼。
 */
@Service
public class Deliveries {

    private static final Logger log = LoggerFactory.getLogger(Deliveries.class);

    private final OutboxDeliveryRepository repository;

    Deliveries(OutboxDeliveryRepository repository) {
        this.repository = repository;
    }

    /**
     * 這筆事件的訊息送過了嗎。
     *
     * <p>查得到就代表上一輪已經成功送出，這次是重跑——直接跳過，
     * 讓 poller 把它標成 SENT。
     */
    @Transactional(readOnly = true)
    public boolean alreadySent(long outboxEventId) {
        if (repository.existsById(outboxEventId)) {
            log.info("這筆事件的訊息已經送過了，不重送（outbox 是 at-least-once）");
            return true;
        }
        return false;
    }

    /**
     * 送出去之後立刻記下來。
     *
     * <p>自己開交易（handler 跑在交易外，見決策 18），所以這一行一提交就生效——
     * 不必等 poller 那個橫跨整批的交易。<b>那正是它能把窗口從十七秒縮到毫秒的原因。</b>
     */
    @Transactional
    public void recordSent(long outboxEventId, String lineUserId, String lineMessageId) {
        repository.recordIfAbsent(outboxEventId, lineUserId, lineMessageId);
    }
}
