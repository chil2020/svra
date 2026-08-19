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

    PushTextRequestedHandler(LinePushClient pushClient, ObjectMapper objectMapper) {
        this.pushClient = pushClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_PUSH_TEXT_REQUESTED;
    }

    @Override
    public void handle(String payload) {
        PushTextPayload push = objectMapper.readValue(payload, PushTextPayload.class);
        pushClient.pushText(push.lineUserId(), push.text());
    }
}
