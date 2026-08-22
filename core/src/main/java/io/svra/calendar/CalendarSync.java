package io.svra.calendar;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteService;
import io.svra.notify.CalendarCapability;
import io.svra.notify.MessageAnchors;
import io.svra.notify.PushTextPayload;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

/**
 * 這個模組對外的入口：把「行事曆要跟著動」寫成一筆 outbox 事件。
 *
 * <p>兩個呼叫端，兩種交易語意：
 * <ul>
 * <li>{@link #requestImport} 由 webhook 呼叫，<b>自己開交易並帶冪等鍵</b>——
 * 來源是 LINE 的 postback，而 LINE 是 at-least-once</li>
 * <li>{@link #syncAfterCommand} 由指令執行呼叫，<b>參與呼叫端的交易</b>——
 * 「行事曆要跟著動」必須跟「項目真的改了」同進同退，
 * 否則會出現「資料改了但行事曆沒跟上，而且沒有人記得要跟上」</li>
 * </ul>
 */
@Service
public class CalendarSync {

    private static final Logger log = LoggerFactory.getLogger(CalendarSync.class);

    private final OutboxEventRepository outboxRepository;
    private final MessageAnchors anchors;
    private final CalendarCapability capability;
    private final ObjectMapper objectMapper;

    CalendarSync(OutboxEventRepository outboxRepository, MessageAnchors anchors,
            CalendarCapability capability, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.anchors = anchors;
        this.capability = capability;
        this.objectMapper = objectMapper;
    }

    /**
     * 卡片按鈕回傳的 data 前綴。
     *
     * <p>帶著 {@code a=}（action）是為了讓<b>以後多出別種按鈕時，這裡認得出不是自己的</b>。
     * 沒有前綴的話，任何一種新按鈕都會被當成匯入請求送進來。
     */
    private static final String ACTION_PREFIX = "a=cal&";

    /** 「這張卡上全部匯得進去的」。展開由後端做，理由見 {@code CardRenderer.postback}。 */
    private static final String ALL = "*";

    /**
     * 使用者按了卡片上的某顆按鈕。
     *
     * <p>webhook 只負責把 postback 的 data 原封不動遞進來——<b>那個字串的格式
     * 是這個模組自己的事</b>，webhook 不該認識它。多一種按鈕時只有這裡要改。
     *
     * <p>🔴 <b>交易邊界在這裡，不在下面的 {@link #requestImport}。</b>
     * 它原本標在後者身上，而這個方法直接呼叫它——<b>同一個物件內部的呼叫不會經過
     * Spring 的代理</b>，所以那個 {@code @Transactional} 完全沒有生效。
     * 症狀是 {@code insertIfAbsent} 的原生 UPDATE 查詢在沒有交易的情況下執行，
     * 直接拋 {@code InvalidDataAccessApiUsageException}：按鈕按下去，webhook 回 500，
     * LINE 重送，再爆一次。是 {@code CalendarImportIdempotencyIntegrationTest} 抓到的。
     *
     * @return 這顆按鈕是不是我們的。不是的話呼叫端自己決定怎麼記
     */
    @Transactional
    public boolean handlePostback(String lineUserId, String webhookEventId, String data,
            String replyToken) {
        if (data == null || !data.startsWith(ACTION_PREFIX)) {
            return false;
        }
        // 🔴 這道守衛不是多餘的，即使卡片本來就只給白名單長 postback 按鈕。
        //
        // 憑證只有一份：refresh token 與 calendarId 都指向<b>擁有者的</b>那本行事曆。
        // 所以「處理了一個不該處理的 postback」不是沒事發生，是<b>把別人的行程
        // 寫進擁有者的行事曆</b>。
        //
        // 而卡片是會過期的訊息：某個人今天在白名單裡、明天被拿掉，
        // 他手機裡那則舊卡片上的按鈕還在，按下去照樣送 postback 進來。
        // **按鈕長不長出來是排版，能不能執行是授權，兩件事不能共用同一個判斷。**
        if (!capability.canImportDirectly(lineUserId)) {
            log.warn("不在白名單的使用者送來匯入請求，已拒絕");
            notifyPlain(webhookEventId, lineUserId, replyToken,
                    "⚠️ 這顆按鈕已經失效了。說一聲「列出行程」，我給你一份新的清單。");
            return true;
        }
        String cardId = null;
        Long itemId = null;
        for (String pair : data.substring(ACTION_PREFIX.length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if ("c".equals(key)) {
                cardId = value;
            } else if ("i".equals(key) && !ALL.equals(value)) {
                itemId = parseItemId(value);
            }
        }
        if (cardId == null) {
            log.warn("匯入按鈕沒有帶卡片 id，忽略：data={}", data);
            return true;
        }
        requestImport(lineUserId, webhookEventId, replyToken, cardId, itemId);
        return true;
    }

    /** 帶不出數字就當成「全部」——按鈕是我們自己產的，走到這裡代表格式已經壞了。 */
    private static Long parseItemId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("匯入按鈕帶的項目 id 不是數字，改當成整張卡：i={}", value);
            return null;
        }
    }

    /**
     * 使用者按下了卡片上的匯入鈕。
     *
     * <p>冪等鍵用 LINE 的 {@code webhookEventId}：它在<b>重送時不變</b>，
     * 所以同一次點擊寫不出第二筆事件。少了它，逾時重送會讓使用者收到兩則
     * 「已加入行事曆」——行事曆本身不會多一筆（決定性 event id 擋著），
     * 但那兩則推播是真的，而且各吃一次免費額度。
     *
     * <p>🔴 這是決策 2 那個區分的實例：<b>這一層擋的是「重複記錄」，
     * 決定性 event id 擋的是「重複執行」。</b>兩層都要。
     *
     * <p>沒有自己的 {@code @Transactional}：交易由 {@link #handlePostback} 開，
     * 而且<b>必須</b>由它開——理由寫在那裡。private 也是刻意的：留成 public
     * 就等於留著一個「繞過交易邊界」的入口。
     *
     * @param webhookEventId LINE 給的事件 id，重送時不變
     * @param cardId         按鈕所在的那張卡，做完要用它重畫一份新的清單
     * @param itemId         要匯入哪一筆；{@code null} 代表「這張卡上全部」
     */
    private void requestImport(String lineUserId, String webhookEventId, String replyToken,
            String cardId, Long itemId) {
        List<Long> itemIds = resolve(cardId, itemId);
        if (itemIds == null) {
            // 卡片對不上——可能是很久以前的訊息，資料已經不在了。
            // 沉默地什麼都不做的話，使用者只會看到按鈕沒反應（決策 17）。
            log.info("匯入請求對不上任何卡片：cardId={}", cardId);
            notifyPlain(webhookEventId, lineUserId, replyToken,
                    "⚠️ 這張卡片我對不上了（可能太舊）。說一聲「列出行程」，"
                            + "我給你一份新的，上面的按鈕就能用。");
            return;
        }
        if (itemIds.isEmpty()) {
            log.info("匯入請求沒有對應到任何項目，忽略：cardId={}", cardId);
            return;
        }
        String payload = serialize(new CalendarSyncPayload(lineUserId, webhookEventId, replyToken,
                cardId, itemIds.stream().map(CalendarSyncPayload.Target::upsert).toList()));

        if (outboxRepository.insertIfAbsent(
                webhookEventId,
                NoteService.EVENT_CALENDAR_SYNC_REQUESTED,
                payload,
                OutboxEvent.dedupeKeyFor(
                        NoteService.EVENT_CALENDAR_SYNC_REQUESTED, webhookEventId)) == 0) {
            log.info("重複投遞的匯入請求，已忽略——冪等鍵擋下（決策 2）");
            return;
        }
        log.info("已記下匯入請求：項目數={}", itemIds.size());
    }

    /**
     * 「全部加入」那顆按鈕帶的是 {@code *}，要展開成實際的項目。
     *
     * <p>展開的來源是<b>卡片的錨點</b>，也就是那張卡當時列了哪幾筆——
     * 不是「使用者現在所有的項目」。使用者按的是眼前那張卡上的按鈕，
     * 把別則語音的行程一起掃進來會是他沒要求過的事。
     *
     * <p>單筆匯入不查錨點：按鈕上的 id 就是答案。但仍然要確認卡片存在，
     * 否則「卡片太舊」與「單筆匯入」會走出兩種不一致的行為。
     *
     * @return 卡片對不上時為 {@code null}
     */
    private List<Long> resolve(String cardId, Long itemId) {
        List<Long> onCard = anchors.itemIdsForCard(cardId).orElse(null);
        if (onCard == null) {
            return null;
        }
        return itemId == null ? onCard : List.of(itemId);
    }

    /**
     * 說明性的回覆（按鈕失效、卡片對不上）。
     *
     * <p>帶冪等鍵，因為這裡的來源是 LINE 的 postback，而 LINE 是 at-least-once：
     * 逾時重送同一個事件，使用者不該收到兩則一模一樣的說明。
     *
     * <p><b>擋不住的是「使用者自己連點五次」</b>——那會是五個不同的
     * {@code webhookEventId}，五則回覆。而那是對的：他按了五次，
     * 五次都給回饋才是誠實的，沉默才奇怪。
     */
    private void notifyPlain(String webhookEventId, String lineUserId, String replyToken,
            String text) {
        outboxRepository.insertIfAbsent(
                webhookEventId,
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                // 說明性的回覆是對按鈕的即時反應，token 一定還活著——這一則是免費的。
                serializePush(PushTextPayload.plain(lineUserId, text).repliedWith(replyToken)),
                "CALENDAR_NOTICE:" + webhookEventId);
    }

    /**
     * 指令改動了已經匯入過的項目，行事曆要跟著動。
     *
     * <p><b>不開自己的交易</b>：必須落在呼叫端那個「執行紀錄與所有變更同進同退」的
     * 交易裡。獨立出去的話，指令回滾而同步意圖留下，行事曆會被改成一個
     * 資料庫裡不存在的樣子。
     *
     * <p>不帶冪等鍵：呼叫端的 {@code command_executions} 已經是守衛，
     * 一則指令執行不出第二次（決策 24）。
     *
     * @param aggregateId 指令的訊息 id，讓 log 的關聯 id 串得起來
     * @param targets     空的就什麼都不寫——沒有匯入過的項目改了，行事曆本來就沒事
     */
    public void syncAfterCommand(String aggregateId, String lineUserId,
            List<CalendarSyncPayload.Target> targets) {
        if (targets.isEmpty()) {
            return;
        }
        outboxRepository.save(OutboxEvent.pending(
                aggregateId,
                NoteService.EVENT_CALENDAR_SYNC_REQUESTED,
                // anchorMessageId 為 null＝安靜地做。使用者剛剛才收到一份調整後的清單，
                // 再推一則「行事曆也更新了」只是噪音，而且每則都在吃免費額度。
                serialize(new CalendarSyncPayload(
                        lineUserId, aggregateId, null, null, targets))));
        log.info("已記下行事曆連動：{} 筆", targets.size());
    }

    private String serialize(CalendarSyncPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化行事曆同步 payload 失敗", e);
        }
    }

    private String serializePush(PushTextPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化推播 payload 失敗", e);
        }
    }
}
