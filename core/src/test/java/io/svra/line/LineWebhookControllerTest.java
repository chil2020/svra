package io.svra.line;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.svra.note.NoteService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契約：正確簽章 → 200；簽章錯誤或沒帶 header → 401。
 * VALID_SIGNATURE 是外部算出來的預期值（openssl dgst -sha256 -hmac），
 * 不是用production 程式算的，否則等於拿自己驗自己。
 */
@WebMvcTest(LineWebhookController.class)
@EnableConfigurationProperties(LineProperties.class)
@TestPropertySource(properties = {
        "svra.line.channel-secret=test-secret",
        "svra.line.channel-access-token=dummy-token"
})
class LineWebhookControllerTest {

    private static final String REQUEST_BODY = "{\"events\":[]}";

    private static final String VALID_SIGNATURE = "Va12JSFB+Fs03rxzdvh7icVLk546dmNGSrPkkClJW/U=";

    @Autowired
    private MockMvc mockMvc;

    /** @WebMvcTest 只載入 Web 層，@Service 不在其中，要自己補。 */
    @MockitoBean
    private NoteService noteService;

    @Test
    @DisplayName("簽章正確 → 200（webhook 要秒回，重活交給佇列）")
    void validSignatureReturns200() throws Exception {
        mockMvc.perform(post("/webhook")
                        .header("X-Line-Signature", VALID_SIGNATURE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("簽章錯誤 → 401（無法證明請求來自 LINE）")
    void invalidSignatureReturns401() throws Exception {
        mockMvc.perform(post("/webhook")
                        .header("X-Line-Signature", "definitely-not-a-valid-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("沒帶簽章 header → 401")
    void missingSignatureHeaderReturns401() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    /** 保留幾個不使用的欄位，驗證寬鬆解析。 */
    private static final String AUDIO_EVENT_BODY = """
            {"destination":"Uxxxxxxxxxx","events":[{
              "type":"message",
              "replyToken":"rt-abc",
              "webhookEventId":"01H",
              "source":{"type":"user","userId":"U4af4980629"},
              "message":{"id":"325708","type":"audio","duration":3000}
            }]}""";

    @Test
    @DisplayName("語音訊息 → 交給 NoteService 記錄，並帶著 userId 與 messageId")
    void audioMessageIsHandedToNoteService() throws Exception {
        mockMvc.perform(post("/webhook")
                .header("X-Line-Signature",
                        LineWebhookController.generateSignature(AUDIO_EVENT_BODY, "test-secret"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AUDIO_EVENT_BODY))
                .andExpect(status().isOk());

        verify(noteService).recordIncoming("U4af4980629", "325708");
    }

    @Test
    @DisplayName("文字訊息 → 目前不處理，但仍要回 200（回別的 LINE 會重送）")
    void textMessageIsIgnoredButStillReturns200() throws Exception {
        String body = """
                {"events":[{
                  "type":"message",
                  "source":{"type":"user","userId":"U4af4980629"},
                  "message":{"id":"999","type":"text","text":"hello"}
                }]}""";

        mockMvc.perform(post("/webhook")
                .header("X-Line-Signature",
                        LineWebhookController.generateSignature(body, "test-secret"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        verify(noteService, never()).recordIncoming(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("LINE 後台的驗證按鈕會送空的 events → 200，不當成錯誤")
    void emptyEventsReturns200() throws Exception {
        mockMvc.perform(post("/webhook")
                .header("X-Line-Signature", VALID_SIGNATURE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
                .andExpect(status().isOk());

        verify(noteService, never()).recordIncoming(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
