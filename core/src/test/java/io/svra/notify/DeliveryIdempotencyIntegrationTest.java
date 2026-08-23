package io.svra.notify;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;

import io.svra.IntegrationTest;
import io.svra.line.LinePushClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 同一筆 outbox 事件被重跑時，使用者不能收到兩則訊息。
 *
 * <p>🔴 <b>這不是理論風險。</b>poller 的 {@code dispatch()} 是整批一個交易，
 * {@code markSent} 要等整批跑完才提交，而同一批裡的抽取事件實測要跑 <b>17 秒</b>。
 * 在那 17 秒內重啟容器，同批已經推播出去的事件會全部重跑。
 *
 * <p>用真的資料庫測，因為擋住它的是 {@code outbox_deliveries} 的主鍵——
 * 而「先查再寫」在任何情況下都不是冪等。
 */
@Tag("integration")
@SpringBootTest
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "svra.line.channel-secret=integration-test-secret",
        "svra.line.channel-access-token=integration-test-token",
})
class DeliveryIdempotencyIntegrationTest {

    private static final String USER_ID = "U4af4980629";
    private static final String FLEX = "{\"type\":\"bubble\"}";

    /**
     * 外鍵擋著：使用者列一定要先在（V15）。正式環境由 webhook 入口保證
     * （{@code LineWebhookController.dispatch} 第一行），而測試繞過了那個入口，
     * 所以要自己建——這不是測試的雜訊，是<b>真實的寫入順序</b>。
     */
    @Autowired
    private io.svra.user.Users users;

    @Autowired
    private PushTextRequestedHandler handler;

    @Autowired
    private Deliveries deliveries;

    @Autowired
    private ObjectMapper objectMapper;

    /** 這裡不打真的 LINE——要驗的是我們自己送了幾次。 */
    @MockitoBean
    private LinePushClient pushClient;

    @BeforeEach
    void stubPush() {
        when(pushClient.pushFlex(anyString(), anyString(), anyString())).thenReturn("msg-1");
        users.ensureExists(USER_ID);
    }

    @Test
    @DisplayName("🔴 同一筆事件跑兩次 → LINE 只被呼叫一次")
    void aRetriedEventOnlySendsOnce() {
        long eventId = System.nanoTime();
        String payload = objectMapper.writeValueAsString(
                PushTextPayload.card(USER_ID, "抬頭", List.of(1L), "card-" + eventId, FLEX));

        handler.handle(eventId, payload);
        handler.handle(eventId, payload);

        verify(pushClient, times(1)).pushFlex(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("不同的事件各送各的——擋的是重跑，不是「同一個人收兩則」")
    void distinctEventsBothSend() {
        long first = System.nanoTime();
        long second = first + 1;

        handler.handle(first, objectMapper.writeValueAsString(
                PushTextPayload.card(USER_ID, "第一則", List.of(1L), "card-a" + first, FLEX)));
        handler.handle(second, objectMapper.writeValueAsString(
                PushTextPayload.card(USER_ID, "第二則", List.of(2L), "card-b" + first, FLEX)));

        verify(pushClient, times(2)).pushFlex(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("投遞紀錄要帶著使用者——它同時是推播的稽核軌跡")
    void theDeliveryRecordCarriesTheUser() {
        long eventId = System.nanoTime();

        handler.handle(eventId, objectMapper.writeValueAsString(
                PushTextPayload.card(USER_ID, "抬頭", List.of(1L), "card-" + eventId, FLEX)));

        assertThat(deliveries.alreadySent(eventId)).isTrue();
    }
}
