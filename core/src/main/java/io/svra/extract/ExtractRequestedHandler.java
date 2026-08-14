package io.svra.extract;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteService;
import io.svra.note.NoteService.NoteEventPayload;
import io.svra.outbox.OutboxEventHandler;

@Component
class ExtractRequestedHandler implements OutboxEventHandler {

    private final NoteExtractionService extractionService;
    private final ObjectMapper objectMapper;

    ExtractRequestedHandler(NoteExtractionService extractionService, ObjectMapper objectMapper) {
        this.extractionService = extractionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_EXTRACT_REQUESTED;
    }

    @Override
    public void handle(String payload) {
        extractionService.extractFor(
                objectMapper.readValue(payload, NoteEventPayload.class).sourceMessageId());
    }
}
