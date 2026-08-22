package io.svra.notify;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.svra.line.LinePushClient;
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

    private final LinePushClient pushClient;
    private final ObjectMapper objectMapper;
    private final MessageAnchors anchors;

    PushTextRequestedHandler(LinePushClient pushClient, ObjectMapper objectMapper,
            MessageAnchors anchors) {
        this.pushClient = pushClient;
        this.objectMapper = objectMapper;
        this.anchors = anchors;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_PUSH_TEXT_REQUESTED;
    }

    @Override
    public void handle(String payload) {
        PushTextPayload push = objectMapper.readValue(payload, PushTextPayload.class);
        // 卡片與純文字走同一種事件：差別只在「這則訊息長什麼樣」，
        // 而那是 payload 的內容，不是另一種意圖。
        String lineMessageId = push.isCard()
                ? pushClient.pushFlex(push.lineUserId(), push.text(), push.flexJson())
                : pushClient.pushText(push.lineUserId(), push.text());
        // 只有推出去之後才拿得到 messageId，錨點也只能在這裡記——
        // 少了它，使用者引用這則回覆再改一次就會對不上（見 MessageAnchors）。
        anchors.record(lineMessageId, push.cardId(), push.lineUserId(), push.anchoredItemIds());
    }
}
