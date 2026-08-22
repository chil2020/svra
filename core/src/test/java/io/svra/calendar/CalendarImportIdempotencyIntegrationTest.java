package io.svra.calendar;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import io.svra.IntegrationTest;
import io.svra.note.NoteService;
import io.svra.notify.MessageAnchors;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匯入請求這條路的兩層防線裡的<b>第一層</b>：重複的點擊寫不出第二筆事件。
 *
 * <p>第二層（決定性 event id 讓重複的寫入撞 409）發生在 Google 那一端，
 * 只有真的打 API 才驗得到，所以不在這裡——{@code CalendarEventIdsTest}
 * 守的是那個 id 至少是決定性的。
 *
 * <p>用真的 PostgreSQL：擋下重複的是 {@code uk_outbox_dedupe_key} 這個部分唯一索引，
 * 而 mock 沒有索引。理由同決策 2。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 這個測試裡的使用者是白名單成員——不然 postback 會被守衛擋下。
        "svra.calendar.oauth-user-ids=U4af4980629",
        "svra.calendar.client-id=test-client",
        "svra.calendar.client-secret=test-secret",
        "svra.calendar.refresh-token=test-refresh-token",
        "svra.calendar.calendar-id=test@group.calendar.google.com",
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class CalendarImportIdempotencyIntegrationTest {

    private static final String USER_ID = "U4af4980629";

    @Autowired
    private CalendarSync calendarSync;

    @Autowired
    private MessageAnchors anchors;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @BeforeEach
    void clearOutbox() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("🔴 LINE 重送同一個 postback → 只寫得出一筆同步事件")
    void redeliveredPostbackDoesNotQueueTheWorkTwice() {
        String cardId = seedCard(List.of(11L, 12L));
        String webhookEventId = "wh-" + UUID.randomUUID();

        // 同一次點擊，LINE 逾時重送。webhookEventId 在重送時不變。
        calendarSync.handlePostback(USER_ID, webhookEventId, "a=cal&c=" + cardId + "&i=*");
        calendarSync.handlePostback(USER_ID, webhookEventId, "a=cal&c=" + cardId + "&i=*");

        // 行事曆本身不會多一筆（決定性 event id 擋著），但少了這一層，
        // 使用者會收到兩則「已加入行事曆」，而且各吃一次免費推播額度。
        assertThat(syncEvents(webhookEventId)).hasSize(1);
    }

    @Test
    @DisplayName("「全部加入」從卡片的錨點展開，不是抓使用者現在所有的項目")
    void bulkImportExpandsFromTheCardsAnchor() {
        String cardId = seedCard(List.of(30L, 10L, 20L));

        calendarSync.handlePostback(USER_ID, "wh-" + UUID.randomUUID(),
                "a=cal&c=" + cardId + "&i=*");

        assertThat(payloadOf(cardId))
                // 使用者按的是眼前那張卡上的按鈕，掃進別則語音的行程是他沒要求過的事
                .contains("\"itemId\":30").contains("\"itemId\":10").contains("\"itemId\":20");
    }

    @Test
    @DisplayName("單筆匯入只帶那一筆，不會順手把整張卡掃進去")
    void singleImportTouchesOnlyThatItem() {
        String cardId = seedCard(List.of(41L, 42L));

        calendarSync.handlePostback(USER_ID, "wh-" + UUID.randomUUID(),
                "a=cal&c=" + cardId + "&i=42");

        assertThat(payloadOf(cardId)).contains("\"itemId\":42").doesNotContain("\"itemId\":41");
    }

    @Test
    @DisplayName("卡片對不上 → 說出來，而不是讓按鈕看起來壞掉")
    void unknownCardTellsTheUserInsteadOfDoingNothing() {
        String webhookEventId = "wh-" + UUID.randomUUID();

        calendarSync.handlePostback(USER_ID, webhookEventId, "a=cal&c=never-existed&i=*");

        assertThat(syncEvents(webhookEventId)).isEmpty();
        assertThat(outboxRepository.findAll())
                .filteredOn(e -> NoteService.EVENT_PUSH_TEXT_REQUESTED.equals(e.getEventType()))
                .as("沉默地什麼都不做的話，使用者只會看到按鈕沒反應（決策 17）")
                .isNotEmpty();
    }

    @Test
    @DisplayName("🔴 不在白名單的人送來 postback → 拒絕，而且要告訴他按鈕失效了")
    void postbackFromSomeoneNotWhitelistedIsRefused() {
        String cardId = seedCard(List.of(11L));
        String webhookEventId = "wh-" + UUID.randomUUID();

        // 卡片本來就只給白名單長 postback 按鈕，但**卡片是會過期的訊息**：
        // 某人今天在名單裡、明天被拿掉，他手機裡那則舊卡片上的按鈕還在。
        // 憑證只有一份，處理下去就是把別人的行程寫進擁有者的行事曆。
        calendarSync.handlePostback("U-someone-else", webhookEventId,
                "a=cal&c=" + cardId + "&i=*");

        assertThat(syncEvents(webhookEventId)).isEmpty();
        assertThat(outboxRepository.findAll())
                .filteredOn(e -> NoteService.EVENT_PUSH_TEXT_REQUESTED.equals(e.getEventType()))
                .as("沉默地忽略的話，使用者只會看到按鈕沒反應")
                .isNotEmpty();
    }

    @Test
    @DisplayName("不是我們的按鈕 → 說一聲不認識，不要當成匯入請求")
    void foreignPostbacksAreLeftAlone() {
        assertThat(calendarSync.handlePostback(USER_ID, "wh-x", "action=somethingElse"))
                .isFalse();
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    // ── 工具 ────────────────────────────────────────────────────────

    /** 假裝剛推播過一張卡片。錨點是「那張卡當時列了哪幾筆」的唯一來源。 */
    private String seedCard(List<Long> itemIds) {
        String cardId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        anchors.record("msg-" + UUID.randomUUID(), cardId, USER_ID, itemIds);
        return cardId;
    }

    private List<OutboxEvent> syncEvents(String webhookEventId) {
        return outboxRepository.findAll().stream()
                .filter(e -> NoteService.EVENT_CALENDAR_SYNC_REQUESTED.equals(e.getEventType()))
                .filter(e -> webhookEventId.equals(e.getAggregateId()))
                .toList();
    }

    private String payloadOf(String cardId) {
        return outboxRepository.findAll().stream()
                .filter(e -> NoteService.EVENT_CALENDAR_SYNC_REQUESTED.equals(e.getEventType()))
                .map(OutboxEvent::getPayload)
                .filter(p -> p.contains(cardId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("沒有寫下任何同步事件"));
    }
}
