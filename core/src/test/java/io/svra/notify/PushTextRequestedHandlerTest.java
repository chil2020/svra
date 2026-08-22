package io.svra.notify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tools.jackson.databind.json.JsonMapper;

import io.svra.line.LinePushClient;
import io.svra.line.ReplyTokenExpiredException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * reply 與 push 的差別是錢：LINE 官方說明
 * <i>"Reply messages are not included in the message count for your pricing plan."</i>
 * 而 push 吃的那個免費額度是<b>整個官方帳號共用</b>的，不是每人一份。
 * 開放給多人使用時，這個選擇直接決定同樣的錢能服務幾個人。
 *
 * <p>所以這裡驗三件事：有 token 就走免費那條、token 失效要退回推播讓使用者<b>照樣收得到</b>、
 * 而暫時性失敗<b>不能</b>退回推播——那會在一次網路抖動時白白放棄免費的那一則。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushTextRequestedHandlerTest {

    private static final String USER_ID = "U4af4980629";
    private static final String FLEX = "{\"type\":\"bubble\"}";

    @Mock LinePushClient pushClient;
    @Mock MessageAnchors anchors;

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private PushTextRequestedHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PushTextRequestedHandler(pushClient, objectMapper, anchors);
    }

    private void handle(PushTextPayload payload) {
        handler.handle(objectMapper.writeValueAsString(payload));
    }

    @Test
    @DisplayName("🔴 有 reply token → 走 reply，不吃免費額度")
    void aReplyTokenTakesTheFreePath() {
        handle(PushTextPayload.card(USER_ID, "抬頭", List.of(1L), "card-1", FLEX)
                .repliedWith("rt-abc"));

        verify(pushClient).replyFlex("rt-abc", "抬頭", FLEX);
        verify(pushClient, never()).pushFlex(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("沒有 reply token → 只能推播（語音抽取那條路就是這樣）")
    void withoutATokenItFallsToPush() {
        handle(PushTextPayload.card(USER_ID, "抬頭", List.of(1L), "card-1", FLEX));

        verify(pushClient).pushFlex(USER_ID, "抬頭", FLEX);
        verify(pushClient, never()).replyFlex(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 token 失效 → 改用推播，使用者照樣收得到")
    void anExpiredTokenFallsBackToPush() {
        when(pushClient.replyFlex(anyString(), anyString(), anyString()))
                .thenThrow(new ReplyTokenExpiredException("Invalid reply token"));

        handle(PushTextPayload.card(USER_ID, "抬頭", List.of(1L), "card-1", FLEX)
                .repliedWith("rt-dead"));

        // 一律重試的話，token 單次使用、五次必定全敗——使用者完全收不到回覆。
        verify(pushClient).pushFlex(USER_ID, "抬頭", FLEX);
    }

    @Test
    @DisplayName("🔴 暫時性失敗不能退回推播——重試時 token 可能還活著，那一則就還是免費的")
    void aTransientFailureIsNotSwallowed() {
        when(pushClient.replyFlex(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("LINE 回應 503"));

        assertThatThrownBy(() ->
                handle(PushTextPayload.card(USER_ID, "抬頭", List.of(1L), "card-1", FLEX)
                        .repliedWith("rt-abc")))
                .isInstanceOf(IllegalStateException.class);

        // 接住改推播的話，一次網路抖動就白白放棄了免費的那條路
        verify(pushClient, never()).pushFlex(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("純文字也走同一套判斷")
    void plainTextUsesTheSameRules() {
        handle(PushTextPayload.plain(USER_ID, "⚠️ 這顆按鈕已經失效了").repliedWith("rt-abc"));
        verify(pushClient).replyText("rt-abc", "⚠️ 這顆按鈕已經失效了");

        handle(PushTextPayload.plain(USER_ID, "轉錄失敗了"));
        verify(pushClient).pushText(USER_ID, "轉錄失敗了");
    }

    @Test
    @DisplayName("🔴 兩條路都要記錨點——reply 也會回傳 messageId")
    void bothPathsRecordTheAnchor() {
        when(pushClient.replyFlex(anyString(), anyString(), anyString())).thenReturn("msg-reply");
        when(pushClient.pushFlex(anyString(), anyString(), anyString())).thenReturn("msg-push");

        handle(PushTextPayload.card(USER_ID, "抬頭", List.of(7L), "card-a", FLEX)
                .repliedWith("rt-abc"));
        handle(PushTextPayload.card(USER_ID, "抬頭", List.of(8L), "card-b", FLEX));

        // 少了這個，使用者引用回覆再改一次就會對不上——決策 11 的指代解析整條壞掉。
        verify(anchors).record("msg-reply", "card-a", USER_ID, List.of(7L));
        verify(anchors).record("msg-push", "card-b", USER_ID, List.of(8L));
    }

    @Test
    @DisplayName("退回推播時，錨點記的是推播那則的 id，不是失敗的 reply")
    void theAnchorFollowsTheMessageThatActuallyWentOut() {
        when(pushClient.replyFlex(anyString(), anyString(), anyString()))
                .thenThrow(new ReplyTokenExpiredException("Invalid reply token"));
        when(pushClient.pushFlex(anyString(), anyString(), anyString())).thenReturn("msg-push");

        handle(PushTextPayload.card(USER_ID, "抬頭", List.of(9L), "card-c", FLEX)
                .repliedWith("rt-dead"));

        verify(anchors).record("msg-push", "card-c", USER_ID, List.of(9L));
    }
}
