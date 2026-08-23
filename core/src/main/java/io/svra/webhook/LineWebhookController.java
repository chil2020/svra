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
import io.svra.calendar.CalendarSync;
import io.svra.command.NoteCommandService;
import io.svra.note.NoteService;
import io.svra.user.Users;
import io.svra.notify.Greetings;

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
    private final CalendarSync calendarSync;
    private final Greetings greetings;
    private final Users users;

    public LineWebhookController(NoteService noteService, NoteCommandService commandService,
            CalendarSync calendarSync, Greetings greetings, Users users) {
        this.noteService = noteService;
        this.commandService = commandService;
        this.calendarSync = calendarSync;
        this.greetings = greetings;
        this.users = users;
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
        // 🔴 這一行是所有外鍵的前提。notes、message_anchors、outbox_events……
        // 全部指向 users，所以「使用者列存在」必須發生在任何一筆使用者資料之前。
        //
        // 放在這裡而不是 follow 事件裡，因為 follow **靠不住**：在 users 表出現之前
        // 就加過好友的人不會再送一次，而漏接一則 follow 的症狀是那個人接下來
        // 每一次操作都撞外鍵——看起來像「傳語音沒反應」。
        //
        // 不看事件型別、一律 upsert。成本是一次命中主鍵索引的 ON CONFLICT DO NOTHING。
        users.ensureExists(userIdOf(event));

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
                    event.message().quotedMessageId(),
                    // 帶著 reply token：指令的回覆用它送不計額度，而額度是整個
                    // 官方帳號共用的，開放給多人時它直接決定能服務幾個人。
                    event.replyToken());
        } else if (event.isPostback()) {
            // 卡片上的按鈕。跟語音與指令一樣只寫意圖，真正呼叫 Google 的是 poller——
            // 「全部加入」可能是好幾次 HTTP 呼叫，而 webhook 不能做慢事（決策 1）。
            //
            // data 的格式不在這裡解析：那是 calendar 模組自己的事，
            // 多一種按鈕時 webhook 不必跟著改。
            if (!calendarSync.handlePostback(event.source().userId(),
                    event.webhookEventId(), event.postback().data(), event.replyToken())) {
                log.debug("不認識的 postback，忽略");
            }
        } else if (event.isFollow()) {
            // 🔴 在這之前，加了好友的人收到的是一片空白。單人使用時那不是缺口
            // （你自己知道怎麼用），開放給別人的那一刻它就是第一印象。
            log.info("新使用者加入好友");
            users.unblock(event.source().userId());
            greetings.welcome(event.source().userId(), event.webhookEventId(), event.replyToken());
        } else if (event.isUnfollow()) {
            log.info("使用者封鎖或刪除本帳號");
            users.block(event.source().userId());
        } else if (event.isUnsend()) {
            // LINE 的開發指南明確要求處理：他按下收回的時候，語音早就轉錄完、
            // 逐字稿與抽出來的行程都已經在資料庫裡了。
            noteService.forgetMessage(event.source().userId(), event.unsend().messageId());
        } else {
            // 貼圖、已讀、加入群組……收得下但不處理。看得到才知道「沒反應」是預期的。
            log.debug("不處理的事件型別：type={}", event.type());
        }
    }

    /**
     * 關聯 id：訊息事件用 LINE 的 message id，postback 沒有那個東西，
     * 退而用 {@code webhookEventId}——它也是那條路的冪等鍵，log 與資料對得起來。
     */
    /** {@code source} 在某些事件型別（例如帳號層級的通知）上可能整個缺席。 */
    private static String userIdOf(LineWebhookPayload.Event event) {
        return event.source() == null ? null : event.source().userId();
    }

    private static String eventMessageId(LineWebhookPayload.Event event) {
        return event.message() != null ? event.message().id() : event.webhookEventId();
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
