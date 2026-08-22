package io.svra.command;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteService;
import io.svra.outbox.OutboxEventHandler;

@Component
class CommandRequestedHandler implements OutboxEventHandler {

    private final NoteCommandService commandService;
    private final ObjectMapper objectMapper;

    CommandRequestedHandler(NoteCommandService commandService, ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_COMMAND_REQUESTED;
    }

    @Override
    public void handle(long eventId, String payload) {
        commandService.applyCommand(
                objectMapper.readValue(payload, NoteCommandService.CommandPayload.class));
    }
}
