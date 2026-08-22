package io.svra.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.svra.line.LinePushClient;
import io.svra.line.ReplyTokenExpiredException;
import io.svra.note.NoteService;
import io.svra.outbox.OutboxEventHandler;

/**
 * 送出別的模組寫下的推播意圖。
 *
 * <p>沒有 {@code @Transactional}：它只做一件外部 I/O，沒有要跟資料庫同進同退的東西。
 * 失敗就往外拋，由 outbox 的指數退避處理。
 */
@Component
class PushTextRequestedHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PushTextRequestedHandler.class);

    private final LinePushClient pushClient;
    private final ObjectMapper objectMapper;
    private final MessageAnchors anchors;
    private final Blocklist blocklist;
    private final Deliveries deliveries;

    PushTextRequestedHandler(LinePushClient pushClient, ObjectMapper objectMapper,
            MessageAnchors anchors, Blocklist blocklist, Deliveries deliveries) {
        this.pushClient = pushClient;
        this.objectMapper = objectMapper;
        this.anchors = anchors;
        this.blocklist = blocklist;
        this.deliveries = deliveries;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_PUSH_TEXT_REQUESTED;
    }

    @Override
    public void handle(long eventId, String payload) {
        // 🔴 這一行擋的是「訊息已經送出去了，但 poller 在標記 SENT 之前掛掉」。
        // 那個窗口在同批有抽取事件時長達十七秒，而我今天為了驗證重啟了五次容器。
        if (deliveries.alreadySent(eventId)) {
            return;
        }
        PushTextPayload push = objectMapper.readValue(payload, PushTextPayload.class);
        // 封鎖的人收不到，送了也是白送。直接當成功——重試不會讓他變成沒封鎖，
        // 而讓事件卡在 PENDING 只會在 log 裡堆一堆註定沒有意義的失敗。
        if (blocklist.isBlocked(push.lineUserId())) {
            log.info("收件者已封鎖本帳號，略過這則訊息");
            return;
        }
        String lineMessageId = send(push);
        // 先記投遞再記錨點：兩者都是「送出去之後」的事，但只有前者擋得住重送。
        deliveries.recordSent(eventId, push.lineUserId(), lineMessageId);
        // 只有送出去之後才拿得到 messageId，錨點也只能在這裡記——
        // 少了它，使用者引用這則回覆再改一次就會對不上（見 MessageAnchors）。
        // reply 與 push 都會回傳 messageId，所以這一行兩條路共用。
        anchors.record(lineMessageId, push.cardId(), push.lineUserId(), push.anchoredItemIds());
    }

    /**
     * 有 token 就先試 reply（<b>不計入月額度</b>），失效才退回推播。
     *
     * <p>🔴 <b>只接 {@link ReplyTokenExpiredException}，不接其他失敗。</b>
     * token 失效重試一萬次也不會好，該立刻改用推播——使用者照樣收得到，
     * 只是吃掉一則額度。而網路抖動或 LINE 的 5xx 要往外拋讓 outbox 退避重試，
     * 因為重試時 token 可能還活著，那一則就還是免費的。
     *
     * <p>一律接住改推播的話，一次網路抖動就白白放棄了免費的那條路；
     * 一律重試的話，token 單次使用、五次必定全敗——<b>使用者完全收不到回覆</b>。
     */
    private String send(PushTextPayload push) {
        if (push.replyToken() != null) {
            try {
                return push.isCard()
                        ? pushClient.replyFlex(push.replyToken(), push.text(), push.flexJson())
                        : pushClient.replyText(push.replyToken(), push.text());
            } catch (ReplyTokenExpiredException expired) {
                log.info("reply token 已失效，改用推播（會吃一則免費額度）：{}", expired.getMessage());
            }
        }
        // 卡片與純文字走同一種事件：差別只在「這則訊息長什麼樣」，
        // 而那是 payload 的內容，不是另一種意圖。
        return push.isCard()
                ? pushClient.pushFlex(push.lineUserId(), push.text(), push.flexJson())
                : pushClient.pushText(push.lineUserId(), push.text());
    }
}
