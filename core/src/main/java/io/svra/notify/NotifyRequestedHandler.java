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

    NotifyRequestedHandler(NoteNotifier notifier, ObjectMapper objectMapper) {
        this.notifier = notifier;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_NOTIFY_REQUESTED;
    }

    @Override
    public void handle(String payload) {
        notifier.notifyFor(
                objectMapper.readValue(payload, NoteEventPayload.class).sourceMessageId());
    }
}
