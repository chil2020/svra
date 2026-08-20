package io.svra.webhook;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.svra.LogContext;
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

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

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

        long startedNanos = System.nanoTime();
        List<LineWebhookPayload.Event> events = payload.events();

        // LINE 後台的「驗證」按鈕會送 {"events":[]}，欄位也可能整個缺席
        if (events == null || events.isEmpty()) {
            log.info("收到空的 webhook（LINE 後台的驗證按鈕會這樣送）");
            return ResponseEntity.ok().build();
        }

        for (LineWebhookPayload.Event event : events) {
            // 🔴 MDC 逐則事件設，不是逐個請求：一個 webhook 可以帶多則訊息，
            // 各自是不同的 id。設在迴圈外的話，第二則之後的 log 會掛在第一則的 id 下。
            try (var ignored = LogContext.messageId(eventMessageId(event))) {
                dispatch(event);
            }
        }

        // 🔴 決策 1 的證據就在這個數字。webhook 的價值是「秒回」，
        // 而「秒回」在 log 裡看不到的話，那句宣稱就沒有任何東西撐著。
        // 這裡量的是 controller 的耗時，不含驗簽與反序列化——那兩段在 filter 裡，
        // 但它們是固定成本，會膨脹的是下面這段（DB 寫入）。
        log.info("webhook 處理完畢，回 200：事件數={} 耗時={}ms",
                events.size(), elapsedMillis(startedNanos));
        return ResponseEntity.ok().build();
    }

    private void dispatch(LineWebhookPayload.Event event) {
        if (event.isAudioMessage()) {
            boolean created = noteService.recordIncoming(event.source().userId(), event.message().id());
            if (created) {
                log.info("收到語音訊息");
            }
            // 重複投遞的那一行由 NoteService 記——它才知道是不是重複
        } else if (event.isTextMessage()) {
            // 指令處理不能拖慢 webhook——一樣走 outbox，這裡只記下意圖。
            // 只記長度與有沒有引用，不記內容（原文只在解析失敗時才留，見 NoteCommandParser）
            log.info("收到文字指令：字數={} 引用={}",
                    event.message().text() == null ? 0 : event.message().text().length(),
                    event.message().quotedMessageId() == null ? "無" : "有");
            commandService.recordCommand(
                    event.source().userId(),
                    event.message().id(),
                    event.message().text(),
                    event.message().quotedMessageId());
        } else {
            // 貼圖、加好友、已讀……收得下但不處理。看得到才知道「沒反應」是預期的。
            log.debug("不處理的事件型別：type={}", event.type());
        }
    }

    /** 事件不一定帶得到 message id（非訊息事件），拿不到就讓 MDC 留空。 */
    private static String eventMessageId(LineWebhookPayload.Event event) {
        return event.message() == null ? null : event.message().id();
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
