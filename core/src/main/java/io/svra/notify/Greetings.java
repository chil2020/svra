package io.svra.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteService;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

/**
 * 新使用者加好友時說的第一句話。
 *
 * <p>🔴 <b>在這之前，加了好友的人收到的是一片空白。</b>{@code follow} 事件跟貼圖、
 * 已讀一樣掉進「收得下但不處理」——他不知道要傳語音、不知道可以引用訊息修改、
 * 不知道有匯入按鈕。單人使用時這個缺口不存在（你自己知道怎麼用），
 * 開放給別人的那一刻它就是第一印象。
 *
 * <p>用 reply 而不是 push：{@code follow} 事件帶 replyToken，所以這一則
 * <b>不計入免費額度</b>（決策 28）。而歡迎訊息是每個新使用者都會收到的，
 * 用推播的話它會隨人數線性吃掉額度。
 */
@Service
public class Greetings {

    private static final Logger log = LoggerFactory.getLogger(Greetings.class);

    /**
     * 只講三件事：怎麼開始、整理完之後能做什麼、以及「現在就試」。
     *
     * <p>不列功能表——新使用者不會讀，而且他還沒有任何項目可以操作。
     * 唯一有用的資訊是「傳一段語音給我」，其餘都是等他傳完之後才用得到的。
     */
    private static final String WELCOME = """
            👋 我是 SVRA，你的語音筆記助理。

            直接傳一段語音給我，我會整理成行程、待辦和想法。
            講得零散也沒關係——一段話裡有好幾件事，我會拆開。

            整理好之後：
            ・回覆那則訊息就能改時間、改標題，或刪掉其中一筆
            ・有時間的項目會有一顆「加入行事曆」的按鈕
            ・想看目前有什麼，跟我說「列出行程」

            現在就傳一段語音試試看。""";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    Greetings(OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 走 outbox 而不是直接打 LINE：webhook 要秒回（決策 1），而且送失敗時
     * 該有重試——第一句話沒送出去，這個使用者就再也不會有第二次機會了。
     *
     * <p>帶冪等鍵：使用者可以封鎖再加回來，而 LINE 的 webhook 是 at-least-once。
     * 鍵用 {@code webhookEventId} 而不是 userId——<b>重新加好友本來就該再打一次招呼</b>，
     * 要擋的只有「同一次加好友被投遞兩次」。
     */
    @Transactional
    public void welcome(String lineUserId, String webhookEventId, String replyToken) {
        if (outboxRepository.insertIfAbsent(
                webhookEventId,
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                serialize(PushTextPayload.plain(lineUserId, WELCOME).repliedWith(replyToken)),
                "WELCOME:" + webhookEventId) == 0) {
            log.info("重複投遞的加好友事件，歡迎訊息不重發");
            return;
        }
        log.info("新使用者加入好友，已排入歡迎訊息");
    }

    private String serialize(PushTextPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化歡迎訊息失敗", e);
        }
    }
}
