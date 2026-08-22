package io.svra.notify;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteService;
import io.svra.note.NoteService.NoteEventPayload;
import io.svra.outbox.OutboxEventHandler;

@Component
class NotifyRequestedHandler implements OutboxEventHandler {

    private final NoteNotifier notifier;
    private final ObjectMapper objectMapper;
    private final Deliveries deliveries;

    NotifyRequestedHandler(NoteNotifier notifier, ObjectMapper objectMapper,
            Deliveries deliveries) {
        this.notifier = notifier;
        this.objectMapper = objectMapper;
        this.deliveries = deliveries;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_NOTIFY_REQUESTED;
    }

    @Override
    public void handle(long eventId, String payload) {
        // 這是使用者最常看到的那張卡片，重送就是同一份筆記跳出來兩次。
        if (deliveries.alreadySent(eventId)) {
            return;
        }
        NoteEventPayload event = objectMapper.readValue(payload, NoteEventPayload.class);
        String lineMessageId = notifier.notifyFor(event.sourceMessageId());
        if (lineMessageId != null) {
            deliveries.recordSent(eventId, event.lineUserId(), lineMessageId);
        }
        // null ＝ 根本沒送（找不到 note、沒有生效的抽取、已封鎖）。不記投遞——
        // 記了的話，之後真的補得出結果時反而會被自己擋住。
    }
}
