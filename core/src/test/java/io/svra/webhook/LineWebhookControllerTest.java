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
                  "message":{"id":"999","type":"text","text":"刪掉第二項"}
                }]}""").andExpect(status().isOk());

        verify(commandService).recordCommand("U4af4980629", "999", "刪掉第二項", null);
        verify(noteService, never()).recordIncoming(anyString(), anyString());
    }

    @Test
    @DisplayName("引用某則推播下的指令 → quotedMessageId 要一起傳下去（決策 11）")
    void quotedMessageIdIsPassedThrough() throws Exception {
        postWebhook("""
                {"events":[{
                  "type":"message",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "message":{"id":"999","type":"text","text":"改成三點",
                             "quotedMessageId":"888"}
                }]}""").andExpect(status().isOk());

        verify(commandService).recordCommand("U4af4980629", "999", "改成三點", "888");
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
        verify(calendarSync).handlePostback("U4af4980629", "01HXPOSTBACK", "a=cal&c=abc123&i=*");
        verify(commandService, never())
                .recordCommand(anyString(), anyString(), anyString(), any());
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

        verify(calendarSync, never()).handlePostback(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("不是我們的 postback → calendar 說不認識，webhook 照樣回 200")
    void unknownPostbackStillReturns200() throws Exception {
        when(calendarSync.handlePostback(anyString(), anyString(), anyString())).thenReturn(false);

        postWebhook("""
                {"events":[{
                  "type":"postback",
                  "webhookEventId":"01HOTHER",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "postback":{"data":"action=somethingElse"}
                }]}""").andExpect(status().isOk());
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
