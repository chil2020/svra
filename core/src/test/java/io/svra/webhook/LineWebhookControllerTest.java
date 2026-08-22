package io.svra.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.svra.calendar.CalendarSync;
import io.svra.notify.Blocklist;
import io.svra.notify.Greetings;
import io.svra.command.NoteCommandService;
import io.svra.note.NoteService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 事件分派的契約：什麼樣的事件交給誰。
 *
 * <p>{@code addFilters = false} 是刻意的——驗簽在決策 22 之後屬於 filter chain，
 * 由 {@code WebhookSecurityTest} 守著。這裡關掉 filter 之後，每一則測試都不必再
 * 算簽章，failure message 也就只會指向分派邏輯本身。
 */
@WebMvcTest(LineWebhookController.class)
@AutoConfigureMockMvc(addFilters = false)
class LineWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** @WebMvcTest 只載入 Web 層，@Service 不在其中，要自己補。 */
    @MockitoBean
    private NoteService noteService;

    @MockitoBean
    private NoteCommandService commandService;

    @MockitoBean
    private CalendarSync calendarSync;

    @MockitoBean
    private Greetings greetings;

    @MockitoBean
    private Blocklist blocklist;

    /** 保留幾個不使用的欄位，驗證寬鬆解析。 */
    private static final String AUDIO_EVENT_BODY = """
            {"destination":"Uxxxxxxxxxx","events":[{
              "type":"message",
              "replyToken":"rt-abc",
              "webhookEventId":"01H",
              "source":{"type":"user","userId":"U4af4980629"},
              "message":{"id":"325708","type":"audio","duration":3000}
            }]}""";

    private org.springframework.test.web.servlet.ResultActions postWebhook(String body) throws Exception {
        return mockMvc.perform(post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("語音訊息 → 交給 NoteService，並帶著 userId 與 messageId")
    void audioMessageIsHandedToNoteService() throws Exception {
        postWebhook(AUDIO_EVENT_BODY).andExpect(status().isOk());

        verify(noteService).recordIncoming("U4af4980629", "325708");
    }

    @Test
    @DisplayName("文字訊息 → 交給 NoteCommandService，不進 NoteService")
    void textMessageIsHandedToCommandService() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"message",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "replyToken":"rt-cmd",
                  "message":{"id":"999","type":"text","text":"刪掉第二項"}
                }]}""").andExpect(status().isOk());

        // reply token 一起傳下去：指令的回覆用它送不計免費額度，而額度是整個
        // 官方帳號共用的——漏傳不會壞掉，只會安靜地多花錢。
        verify(commandService).recordCommand("U4af4980629", "999", "刪掉第二項", null, "rt-cmd");
        verify(noteService, never()).recordIncoming(anyString(), anyString());
    }

    @Test
    @DisplayName("引用某則推播下的指令 → quotedMessageId 要一起傳下去（決策 11）")
    void quotedMessageIdIsPassedThrough() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"message",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "replyToken":"rt-cmd",
                  "message":{"id":"999","type":"text","text":"改成三點",
                             "quotedMessageId":"888"}
                }]}""").andExpect(status().isOk());

        verify(commandService).recordCommand("U4af4980629", "999", "改成三點", "888", "rt-cmd");
    }

    @Test
    @DisplayName("卡片按鈕 → 交給 calendar，並帶著 webhookEventId（那是它唯一的冪等鍵）")
    void postbackIsHandedToCalendarSync() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"postback",
                  "replyToken":"rt-abc",
                  "webhookEventId":"01HXPOSTBACK",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "postback":{"data":"a=cal&c=abc123&i=*"}
                }]}""").andExpect(status().isOk());

        // postback 沒有 message id，冪等只能靠 webhookEventId——它在重送時不變。
        // 傳錯或漏傳的話，逾時重送就會讓使用者收到兩則「已加入行事曆」。
        verify(calendarSync).handlePostback(
                "U4af4980629", "01HXPOSTBACK", "a=cal&c=abc123&i=*", "rt-abc");
        verify(commandService, never())
                .recordCommand(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("postback 少了 webhookEventId → 收得下但不處理，不是爆掉")
    void postbackWithoutWebhookEventIdIsIgnored() throws Exception {
        // 沒有它就沒有冪等鍵。硬做下去的話，一次逾時重送就是重複執行——
        // 而回 500 只會讓 LINE 再送一次同樣缺欄位的東西（決策 1）。
        postWebhook("""
                {"events":[{
                  "type":"postback",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "postback":{"data":"a=cal&c=abc123&i=*"}
                }]}""").andExpect(status().isOk());

        verify(calendarSync, never())
                .handlePostback(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("不是我們的 postback → calendar 說不認識，webhook 照樣回 200")
    void unknownPostbackStillReturns200() throws Exception {
        when(calendarSync.handlePostback(anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        postWebhook("""
                {"events":[{
                  "type":"postback",
                  "webhookEventId":"01HOTHER",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "postback":{"data":"action=somethingElse"}
                }]}""").andExpect(status().isOk());
    }

    @Test
    @DisplayName("🔴 加好友 → 送歡迎訊息，並解除封鎖標記")
    void followTriggersAWelcome() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"follow",
                  "replyToken":"rt-follow",
                  "webhookEventId":"01HFOLLOW",
                  "source":{"type":"user","userId":"U4af4980629"}
                }]}""").andExpect(status().isOk());

        // 在這之前，加了好友的人收到的是一片空白——不知道要傳語音、
        // 不知道可以引用訊息修改、不知道有匯入按鈕。
        verify(greetings).welcome("U4af4980629", "01HFOLLOW", "rt-follow");
        // 封鎖過再回來的人，標記不清掉就會變成永遠收不到訊息的幽靈
        verify(blocklist).unblock("U4af4980629");
    }

    @Test
    @DisplayName("封鎖 → 記下來，之後不要再對他做事")
    void unfollowIsRecorded() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"unfollow",
                  "webhookEventId":"01HUNFOLLOW",
                  "source":{"type":"user","userId":"U4af4980629"}
                }]}""").andExpect(status().isOk());

        verify(blocklist).block("U4af4980629");
        verify(greetings, never()).welcome(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("🔴 收回訊息 → 忘掉那則語音留下的所有東西")
    void unsendForgetsTheNote() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"unsend",
                  "webhookEventId":"01HUNSEND",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "unsend":{"messageId":"325708"}
                }]}""").andExpect(status().isOk());

        // 他按下收回時，語音早就轉錄完、逐字稿與行程都在資料庫裡了。
        // LINE 的開發指南明確要求處理這個事件。
        verify(noteService).forgetMessage("U4af4980629", "325708");
    }

    @Test
    @DisplayName("收回事件少了 messageId → 收得下但不處理，不是爆掉")
    void unsendWithoutMessageIdIsIgnored() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"unsend",
                  "source":{"type":"user","userId":"U4af4980629"}
                }]}""").andExpect(status().isOk());

        verify(noteService, never()).forgetMessage(anyString(), anyString());
    }

    @Test
    @DisplayName("LINE 後台的驗證按鈕會送空的 events → 200，不當成錯誤")
    void emptyEventsReturns200() throws Exception {
        postWebhook("{\"events\":[]}").andExpect(status().isOk());

        verify(noteService, never()).recordIncoming(anyString(), anyString());
    }

    @Test
    @DisplayName("events 欄位整個缺席 → 200（回別的 LINE 會重送）")
    void missingEventsFieldReturns200() throws Exception {
        postWebhook("{\"destination\":\"Uxxxx\"}").andExpect(status().isOk());

        verify(noteService, never()).recordIncoming(anyString(), anyString());
    }

    @Test
    @DisplayName("群組事件拿不到 userId → 收得下但不處理，不是爆掉")
    void eventWithoutUserIdIsIgnored() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"message",
                  "source":{"type":"group","groupId":"C123"},
                  "message":{"id":"777","type":"audio"}
                }]}""").andExpect(status().isOk());

        verify(noteService, never()).recordIncoming(anyString(), anyString());
    }
}
