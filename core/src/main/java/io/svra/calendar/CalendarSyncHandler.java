package io.svra.calendar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteItem;
import io.svra.note.NoteItemRepository;
import io.svra.note.NoteService;
import io.svra.notify.NoteNotifier;
import io.svra.notify.PushTextPayload;
import io.svra.outbox.OutboxEventHandler;
import io.svra.outbox.OutboxEventRepository;

/**
 * 第六種 outbox 事件：把項目的狀態推到 Google 行事曆上。
 *
 * <p>結構跟 {@code NoteCommandService.applyCommand} 一樣是
 * <b>短交易讀 → 交易外做 I/O → 短交易寫</b>，理由也一樣（決策 18）：
 * 中間那段是對外部服務的 HTTP 呼叫，把它包在交易裡只會讓一條資料庫連線
 * 空等網路。
 *
 * <p>🔴 <b>中間那段失敗時，第三段不會跑，而那是安全的。</b>
 * 已經寫進 Google 的那幾筆沒有記下 event id，重試會再 upsert 一次——
 * 但 event id 是決定性的，第二次會撞 409 轉成更新，結果一模一樣。
 * <b>這就是為什麼冪等要靠決定性 id 而不是「先查有沒有做過」</b>：
 * 後者在這個縫裡會判斷錯。
 */
@Component
class CalendarSyncHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncHandler.class);

    private final GoogleCalendarClient client;
    private final NoteItemRepository itemRepository;
    private final OutboxEventRepository outboxRepository;
    private final NoteNotifier notifier;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    CalendarSyncHandler(GoogleCalendarClient client,
            NoteItemRepository itemRepository,
            OutboxEventRepository outboxRepository,
            NoteNotifier notifier,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.client = client;
        this.itemRepository = itemRepository;
        this.outboxRepository = outboxRepository;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public String eventType() {
        return NoteService.EVENT_CALENDAR_SYNC_REQUESTED;
    }

    @Override
    public void handle(String payload) {
        CalendarSyncPayload sync = parse(payload);

        // ── 第一段：短交易，把要做什麼算出來 ──
        List<Job> jobs = tx.execute(status -> plan(sync));
        if (jobs == null || jobs.isEmpty()) {
            // 走得到這裡的是「按了但沒事可做」——例如那張卡上的項目後來都被刪了。
            // 仍然要回一張卡：使用者按了按鈕，沉默就是讓他以為壞了。
            log.info("沒有需要同步到行事曆的項目");
            tx.executeWithoutResult(status -> replyIfRequested(sync));
            return;
        }

        // ── 第二段：沒有交易。這裡全部是對 Google 的 HTTP 呼叫 ──
        List<Job> done = new ArrayList<>();
        for (Job job : jobs) {
            if (job.remove()) {
                client.delete(job.eventId());
            } else {
                client.upsert(job.eventId(), job.summary(), job.detail(),
                        job.occursAt(), job.timeSpecified());
            }
            done.add(job);
        }

        // ── 第三段：短交易，把 event id 記下來，並把回覆寫進 outbox ──
        tx.executeWithoutResult(status -> {
            persist(done);
            replyIfRequested(sync);
        });
    }

    /**
     * 重試耗盡或被判死。<b>這裡是使用者唯一會知道出事的地方。</b>
     *
     * <p>連動失敗特別需要說出來：使用者改了時間、收到了一份漂亮的新清單，
     * 完全不會想到行事曆上那一筆還停在舊時間。沉默地失敗比不做更糟（決策 17）。
     *
     * <p>訊息分兩種寫法，因為要做的事不同：授權失效要人去重跑腳本，
     * 其他失敗只要知道「這幾筆沒同步到」。分不出來的話，
     * 使用者收到的永遠是一句幫不上忙的「同步失敗」。
     */
    @Override
    public void onGiveUp(String payload, Exception cause) {
        CalendarSyncPayload sync = parse(payload);
        boolean authGone = cause instanceof CalendarAuthorizationException;

        String text = authGone
                ? "⚠️ Google 行事曆的授權失效了，這 %d 筆沒有同步到。\n"
                        .formatted(sync.targets().size())
                        + "重新授權之前，匯入按鈕都不會有作用。"
                : "⚠️ 有 %d 筆沒能同步到 Google 行事曆，我已經停止重試。\n"
                        .formatted(sync.targets().size())
                        + "行事曆上那幾筆可能停在舊的內容，需要的話手動改一下。";

        // 同樣帶鍵：判死與重試耗盡走的是同一個 onGiveUp，而外層那個交易若沒提交成功，
        // 事件會留在 PENDING 被再撿一次——那時不該再推一則一樣的失敗通知。
        tx.executeWithoutResult(status -> outboxRepository.insertIfAbsent(
                sync.cardId() == null ? "calendar-sync" : sync.cardId(),
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                serialize(PushTextPayload.plain(sync.lineUserId(), text)),
                "CALENDAR_FAILED:" + sync.requestId()));
        log.info("已寫下行事曆同步失敗的通知");
    }

    /**
     * 算出這次真正要對 Google 做哪些事。
     *
     * <p>三種情況會讓一個 UPSERT 目標變成別的：
     * <ul>
     * <li><b>項目已經被刪了</b>——刪除那條路會自己寫一筆 DELETE，這裡跳過就好</li>
     * <li><b>時間被改成沒有了</b>——它不再是一件「有時間的事」，
     * 匯入過的話要從行事曆上拿掉，否則行事曆會留著一筆資料庫裡已經不存在的行程</li>
     * <li><b>本來就沒有時間、也沒匯入過</b>——沒事可做</li>
     * </ul>
     */
    private List<Job> plan(CalendarSyncPayload sync) {
        List<Job> jobs = new ArrayList<>();
        for (CalendarSyncPayload.Target target : sync.targets()) {
            if (target.op() == CalendarSyncPayload.Op.DELETE) {
                if (target.googleEventId() == null) {
                    // 沒匯入過的項目被刪掉——行事曆上本來就沒有它，沒事可做。
                    // 產生 target 的那一端已經擋過一次，這裡是最後一道：
                    // 讓它往下走會變成 delete(null)，一個 NPE 換來五次退避重試。
                    log.warn("刪除的同步目標沒有 event id，跳過");
                    continue;
                }
                jobs.add(Job.remove(null, target.googleEventId()));
                continue;
            }
            NoteItem item = itemRepository.findById(target.itemId()).orElse(null);
            if (item == null) {
                log.info("要同步的項目已經不在了，跳過：itemId={}", target.itemId());
                continue;
            }
            if (item.getOccursAt() == null) {
                if (item.getGoogleEventId() != null) {
                    jobs.add(Job.remove(item.getId(), item.getGoogleEventId()));
                }
                continue;
            }
            jobs.add(new Job(item.getId(), CalendarEventIds.of(item.getId()), false,
                    item.getTitle(), item.getDetail(), item.getOccursAt(),
                    item.getTimeSpecified()));
        }
        return jobs;
    }

    /**
     * 把「這一筆現在在行事曆上」記進資料庫。
     *
     * <p>這一欄同時是卡片上按鈕文字的依據，所以拿掉的時候要寫回 null——
     * 不然使用者會看到一顆寫著「已加入・重新同步」、但實際上什麼都沒有的按鈕。
     */
    private void persist(List<Job> done) {
        for (Job job : done) {
            if (job.itemId() == null) {
                continue;   // 指令刪除的那些，資料庫裡已經沒有這一列了
            }
            itemRepository.findById(job.itemId())
                    .ifPresent(item -> item.markCalendarEvent(job.remove() ? null : job.eventId()));
        }
    }

    /**
     * 使用者主動按的才回覆；指令引發的連動安靜地做完。
     *
     * <p>🔴 <b>回覆要帶冪等鍵，因為 outbox 是 at-least-once（決策 3）。</b>
     * 這個處理器整段跑完、第三段的交易也提交了，但 poller 在
     * {@code markSent()} 之前掛掉——重跑時 Google 那端沒事
     * （決定性 event id 撞 409 轉更新），<b>但這一行會再寫一筆推播</b>，
     * 於是使用者收到兩張「已加入行事曆」，各吃一次免費額度。
     *
     * <p>我把外部寫入做成冪等的時候漏掉了回覆本身。指令那條路一直有
     * {@code command_executions} 擋著同一件事，這條路沒有對應的東西——
     * 而 {@code requestId} 剛好可以當那個鍵。
     */
    private void replyIfRequested(CalendarSyncPayload sync) {
        if (sync.cardId() == null) {
            return;
        }
        // 回的是那則訊息的<b>新版本</b>，不是「已加入 N 筆」的說明——
        // 跟決策 11 讓指令回覆變成調整後的清單是同一個判斷：清單本身就是最好的確認，
        // 而且新卡片上的按鈕會變成「已加入」，狀態一眼看得到。
        if (outboxRepository.insertIfAbsent(
                sync.cardId(),
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                serialize(notifier.calendarSyncedCard(sync.lineUserId(), sync.cardId())
                        .repliedWith(sync.replyToken())),
                "CALENDAR_REPLY:" + sync.requestId()) == 0) {
            log.info("這次同步的回覆已經寫過了，不重複推播（outbox 是 at-least-once）");
        }
    }

    private CalendarSyncPayload parse(String payload) {
        return objectMapper.readValue(payload, CalendarSyncPayload.class);
    }

    private String serialize(PushTextPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化推播 payload 失敗", e);
        }
    }

    /**
     * 一件對 Google 要做的事。
     *
     * @param itemId 對應的項目；指令刪除的那些是 null（那一列已經不存在）
     */
    private record Job(Long itemId, String eventId, boolean remove, String summary,
            String detail, Instant occursAt, Boolean timeSpecified) {

        static Job remove(Long itemId, String eventId) {
            return new Job(itemId, eventId, true, null, null, null, null);
        }
    }
}
