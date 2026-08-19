package io.svra.webhook;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.svra.command.NoteCommandService;
import io.svra.note.NoteService;

/**
 * 走到這裡的請求都已經通過驗簽（見決策 22 與 {@code io.svra.security.SecurityConfig}）。
 *
 * <p>驗簽曾經寫在這個類別裡，理由是「HMAC 要對原始 body 算，而 body 是一次性的
 * InputStream」——那個理由本身沒錯，錯的是結論：把橫切關注點留在 Controller，
 * 代價是這個方法得同時處理「你是誰」與「你要做什麼」。現在 body 由
 * {@code CachedBodyFilter} 事先緩衝，Controller 只剩後者，連 raw body 都不必碰，
 * 可以直接讓 Spring 反序列化。
 */
@RestController
@RequestMapping("/")
public class LineWebhookController {

    private final NoteService noteService;
    private final NoteCommandService commandService;

    public LineWebhookController(NoteService noteService, NoteCommandService commandService) {
        this.noteService = noteService;
        this.commandService = commandService;
    }

    /**
     * 一律回 200——重複投遞或不處理的事件對 LINE 來說都算送達成功，
     * 回別的只會讓它重送。
     */
    @PostMapping("webhook")
    public ResponseEntity<Void> handleWebhook(@RequestBody LineWebhookPayload payload) {

        List<LineWebhookPayload.Event> events = payload.events();

        // LINE 後台的「驗證」按鈕會送 {"events":[]}，欄位也可能整個缺席
        if (events == null || events.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        for (LineWebhookPayload.Event event : events) {
            if (event.isAudioMessage()) {
                noteService.recordIncoming(event.source().userId(), event.message().id());
            } else if (event.isTextMessage()) {
                // 指令處理不能拖慢 webhook——一樣走 outbox，這裡只記下意圖。
                commandService.recordCommand(
                        event.source().userId(),
                        event.message().id(),
                        event.message().text(),
                        event.message().quotedMessageId());
            }
        }

        return ResponseEntity.ok().build();
    }
}
