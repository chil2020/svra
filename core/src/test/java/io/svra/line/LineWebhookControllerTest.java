package io.svra.line;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
 * U2 的驗收測試——你的目標是讓這三條變綠。
 *
 * <p><b>現在跑一定是紅的（LineWebhookController 還不存在），這是 TDD 的正常狀態。</b>
 *
 * <p>契約：
 * <ul>
 *   <li>{@code POST /webhook}，帶正確的 {@code X-Line-Signature} → 200</li>
 *   <li>簽章錯誤 → 401（不是 400，也不是 403：這是「你不是 LINE」的身分問題）</li>
 *   <li>沒帶 header → 401</li>
 * </ul>
 *
 * <p>下方的 VALID_SIGNATURE 是用 {@code test-secret} 對 {@code REQUEST_BODY}
 * 算出來的 HMAC-SHA256（Base64）。算法要你自己實作，這裡只給答案——
 * 你的程式要能自己算出同一個字串。
 *
 * <p>驗證方式（不想跑測試也可以用 curl）：
 * <pre>{@code
 * printf '%s' '{"events":[]}' | openssl dgst -sha256 -hmac "test-secret" -binary | base64
 * }</pre>
 *
 * <p>⚠️ 如果你的 controller 之後注入了其他 bean（例如發佈佇列訊息的 service），
 * 這個測試會因為找不到 bean 而失敗——那時候在類別上加 {@code @MockitoBean}
 * 把它 mock 掉即可（§4 的 {@code @WebMvcTest} 切片測試考點）。
 */
@WebMvcTest(LineWebhookController.class)
@EnableConfigurationProperties(LineProperties.class)
@TestPropertySource(properties = {
        "svra.line.channel-secret=test-secret",
        "svra.line.channel-access-token=dummy-token"
})
class LineWebhookControllerTest {

    /** LINE 送來的原始 body。驗簽必須對「這串原始字元」算，不能先反序列化再序列化回來。 */
    private static final String REQUEST_BODY = "{\"events\":[]}";

    /** HMAC-SHA256(REQUEST_BODY, "test-secret") 的 Base64。 */
    private static final String VALID_SIGNATURE = "Va12JSFB+Fs03rxzdvh7icVLk546dmNGSrPkkClJW/U=";

    @Autowired
    private MockMvc mockMvc;

    /**
     * {@code @WebMvcTest} 只載入 Web 層（controller、轉換器、例外處理），
     * <b>不會</b>載入 {@code @Service}、{@code @Repository} 這些 bean——
     * 這正是「切片測試」的用意：不啟動整個 application context，跑得快、
     * 失敗時範圍也明確。
     *
     * <p>代價是 controller 依賴的 service 得自己補上。{@code @MockitoBean}
     * 會產生一個 mock 並放進測試用的 context 裡。
     *
     * <p>這裡的三條測試只驗證「驗簽 → 狀態碼」這個契約，body 是
     * {@code {"events":[]}} 不會走到 noteService，所以不需要 stub 它的行為。
     */
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

    // ────────────────────────────────────────────────────────────────
    // 事件解析與派送（U3 後半）
    // ────────────────────────────────────────────────────────────────

    /** 真實的 LINE 語音訊息事件（省略我們不使用的欄位，但保留幾個以驗證寬鬆解析）。 */
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
