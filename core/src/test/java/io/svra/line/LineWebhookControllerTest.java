package io.svra.line;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
