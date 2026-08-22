package io.svra.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.svra.calendar.CalendarSync;
import io.svra.command.NoteCommandService;
import io.svra.line.LineProperties;
import io.svra.note.NoteService;
import io.svra.webhook.LineWebhookController;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 決策 22 的契約，走的是真的 filter chain：正確簽章 → 200，其餘 → 401。
 *
 * <p>這裡不驗事件怎麼分派（那是 {@code LineWebhookControllerTest} 的事），
 * 只驗「誰進得來」。用真的 Controller 當終點，是因為這個檔案要證明的其中一件事
 * 正是<b>請求真的走到了終點</b>——mock 一個假端點就證明不了 body 還讀得到。
 *
 * <p>期望簽章由 {@code openssl dgst -sha256 -hmac 'test-secret' -binary | base64}
 * 算出，不是用 production 程式算的，否則等於拿自己驗自己。
 */
@WebMvcTest(LineWebhookController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(LineProperties.class)
@TestPropertySource(properties = {
        "svra.line.channel-secret=test-secret",
        "svra.line.channel-access-token=dummy-token"
})
class WebhookSecurityTest {

    private static final String SECRET = "test-secret";

    private static final String REQUEST_BODY = "{\"events\":[]}";

    private static final String VALID_SIGNATURE = "Va12JSFB+Fs03rxzdvh7icVLk546dmNGSrPkkClJW/U=";

    private static final String AUDIO_EVENT_BODY = """
            {"events":[{
              "type":"message",
              "source":{"type":"user","userId":"U4af4980629"},
              "message":{"id":"325708","type":"audio","duration":3000}
            }]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NoteService noteService;

    @MockitoBean
    private NoteCommandService commandService;

    @MockitoBean
    private CalendarSync calendarSync;

    @Test
    @DisplayName("簽章正確 → 200")
    void validSignatureIsLetThrough() throws Exception {
        mockMvc.perform(post("/webhook")
                .header(LineSignatureAuthenticationFilter.SIGNATURE_HEADER, VALID_SIGNATURE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("簽章錯誤 → 401（身分問題，不是權限問題，所以不是 403）")
    void invalidSignatureIsRejected() throws Exception {
        mockMvc.perform(post("/webhook")
                .header(LineSignatureAuthenticationFilter.SIGNATURE_HEADER, "definitely-not-a-valid-signature")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("沒帶簽章 header → 401")
    void missingSignatureHeaderIsRejected() throws Exception {
        mockMvc.perform(post("/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("簽章對得上另一份 body 也沒用——簽的是這一份")
    void signatureOfDifferentBodyIsRejected() throws Exception {
        mockMvc.perform(post("/webhook")
                .header(LineSignatureAuthenticationFilter.SIGNATURE_HEADER,
                        LineSignature.generate(REQUEST_BODY, SECRET))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AUDIO_EVENT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("驗簽失敗時 Controller 完全不會被叫到")
    void rejectedRequestNeverReachesController() throws Exception {
        mockMvc.perform(post("/webhook")
                .header(LineSignatureAuthenticationFilter.SIGNATURE_HEADER, "nope")
                .contentType(MediaType.APPLICATION_JSON)
                .content(AUDIO_EVENT_BODY))
                .andExpect(status().isUnauthorized());

        verify(noteService, never()).recordIncoming(anyString(), anyString());
    }

    /**
     * 整個重構的關鍵證據：驗簽的 filter 已經把 body 讀掉一次，Controller 的
     * {@code @RequestBody} 還是拿得到完整內容。拿掉 {@link CachedBodyFilter}
     * 這一則就會失敗——事件會變成一個都解析不出來。
     */
    @Test
    @DisplayName("驗簽讀過 body 之後，Controller 仍然拿得到完整內容")
    void bodyIsStillReadableAfterSignatureCheck() throws Exception {
        mockMvc.perform(post("/webhook")
                .header(LineSignatureAuthenticationFilter.SIGNATURE_HEADER,
                        LineSignature.generate(AUDIO_EVENT_BODY, SECRET))
                .contentType(MediaType.APPLICATION_JSON)
                .content(AUDIO_EVENT_BODY))
                .andExpect(status().isOk());

        verify(noteService).recordIncoming("U4af4980629", "325708");
    }

    /**
     * 驗簽必須先收完整包才算得出 HMAC，也就是這段記憶體是<b>匿名請求</b>決定的。
     * 上限存在的意義就在這裡。回 413 而不是 401：此時還沒驗身分，
     * 說「你沒通過驗證」是不誠實的。
     */
    @Test
    @DisplayName("body 超過緩衝上限 → 413，擋在驗簽之前")
    void oversizedBodyIsRejectedBeforeAuthentication() throws Exception {
        String huge = "{\"events\":[\"" + "x".repeat(300 * 1024) + "\"]}";

        mockMvc.perform(post("/webhook")
                .header(LineSignatureAuthenticationFilter.SIGNATURE_HEADER, LineSignature.generate(huge, SECRET))
                .contentType(MediaType.APPLICATION_JSON)
                .content(huge))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("webhook 以外的路徑一律擋下（新開端點忘記想授權時，預設是拒絕）")
    void unknownPathIsDenied() throws Exception {
        mockMvc.perform(post("/something-else")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
                .andExpect(status().isForbidden());
    }

    /**
     * /error 要靠 dispatcher type 放行，不能靠放行這個路徑——外部請求永遠是 REQUEST
     * dispatch，只有容器自己發得動 ERROR dispatch。改成 {@code requestMatchers("/error")
     * .permitAll()} 的話這一則會紅。
     *
     * <p>反過來「ERROR dispatch 要放得過」這件事這裡驗不了：MockMvc 不發動容器的
     * error dispatch，也不把 security filter 套上去，加了測試也是兩種設定都綠。
     * 那一半交給 {@link WebhookSecurityIntegrationTest}。
     */
    @Test
    @DisplayName("直接從外部打 /error 仍然擋")
    void directRequestToErrorPathIsStillDenied() throws Exception {
        mockMvc.perform(get("/error")).andExpect(status().isForbidden());
    }
}
