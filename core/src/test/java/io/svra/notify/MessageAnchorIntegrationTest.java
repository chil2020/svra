package io.svra.notify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import io.svra.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 錨點的順序<b>就是</b>使用者看到的編號，所以它必須原封不動地存進去、取出來。
 *
 * <p>為什麼要跑真的資料庫：{@code item_ids} 是 PostgreSQL 的 {@code bigint[]}，
 * 靠 Hibernate 的 {@code @JdbcTypeCode(ARRAY)} 映射成 {@code List<Long>}。
 * 專案裡只有 {@code note_items.tags} 這個 {@code text[]} 的先例，數字陣列沒驗過——
 * 而<b>順序錯掉不會拋例外</b>，只會讓「第三筆」安靜地指到別的項目。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 行事曆的設定跟 LINE 的一樣是 @NotBlank，少了就起不來（決策 8 的一貫做法）。
        // 這裡填假的：整合測試不會真的打 Google。
        "svra.calendar.client-id=test-client",
        "svra.calendar.client-secret=test-secret",
        "svra.calendar.refresh-token=test-refresh-token",
        "svra.calendar.calendar-id=test@group.calendar.google.com",
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class MessageAnchorIntegrationTest {

    private static final String USER_ID = "U4af4980629";

    @Autowired
    private MessageAnchors anchors;

    @Test
    @DisplayName("存進去什麼順序，取出來就是什麼順序——不是排序過的")
    void preservesTheExactOrderShownToTheUser() {
        String messageId = "anchor-" + UUID.randomUUID();
        // 刻意不是遞增：編號順序來自 NoteCategory.itemOrder()，跟 id 大小無關。
        // 存回來若被排序過，「第一筆」就會指到別的項目。
        List<Long> shown = List.of(30L, 10L, 20L);

        anchors.record(messageId, null, USER_ID, shown);

        assertThat(anchors.itemIdsFor(messageId))
                .as("順序就是編號，錯一個位置就是刪錯一筆")
                .contains(shown);
    }

    @Test
    @DisplayName("卡片 id 查得到同一份順序——按鈕跟引用回覆用的是兩把鑰匙、一列資料")
    void cardIdOpensTheSameAnchor() {
        String messageId = "anchor-" + UUID.randomUUID();
        String cardId = "card" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<Long> shown = List.of(30L, 10L, 20L);

        anchors.record(messageId, cardId, USER_ID, shown);

        // 引用回覆帶的是 LINE 的 message id，卡片按鈕帶的是我們自己給的 card id。
        // 兩把鑰匙必須開到同一份順序，否則「第三筆」跟「匯入這一筆」會指到不同東西。
        assertThat(anchors.itemIdsForCard(cardId)).contains(shown);
        assertThat(anchors.itemIdsFor(messageId)).contains(shown);
    }

    @Test
    @DisplayName("純文字訊息沒有卡片 id → 不會被任何按鈕查到")
    void plainMessagesHaveNoCardId() {
        anchors.record("anchor-" + UUID.randomUUID(), null, USER_ID, List.of(1L));

        // card_id 上是部分唯一索引（WHERE card_id IS NOT NULL），
        // 所以多則純文字訊息可以並存而不會撞鍵
        anchors.record("anchor-" + UUID.randomUUID(), null, USER_ID, List.of(2L));

        assertThat(anchors.itemIdsForCard("never-rendered")).isEmpty();
    }

    @Test
    @DisplayName("不是我們推播的訊息 → 查不到，讓呼叫端知道對不上")
    void unknownMessageHasNoAnchor() {
        assertThat(anchors.itemIdsFor("never-pushed-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("推播失敗拿不到 messageId → 不寫錨點，也不炸")
    void skipsWhenThereIsNoMessageId() {
        anchors.record(null, null, USER_ID, List.of(1L));
        // 沒有可查的鍵，唯一能驗的是它沒有拋例外——推播失敗時整筆交易會回滾重試，
        // 錨點不該是那條路上的絆腳石。
    }

    @Test
    @DisplayName("空清單不寫錨點——沒有東西可以指涉的訊息，引用它應該算對不上")
    void skipsEmptyLists() {
        String messageId = "empty-" + UUID.randomUUID();

        anchors.record(messageId, null, USER_ID, List.of());

        assertThat(anchors.itemIdsFor(messageId)).isEmpty();
    }
}
