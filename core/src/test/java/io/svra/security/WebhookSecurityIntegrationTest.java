package io.svra.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import io.svra.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 決策 22 在<b>真的 servlet 容器</b>上的行為。
 *
 * <p>為什麼不能只靠 {@code WebhookSecurityTest}：MockMvc 不會發動容器的 ERROR
 * dispatch，也不把 security filter 套到那一趟上。而正是那一趟出過事——容器要回
 * 錯誤狀態時會把請求再送一次到 {@code /error}，那個路徑不符合 webhook 與 actuator
 * 的 matcher，會掉進 denyAll，於是<b>真正的狀態碼被改寫成 403</b>。400 變 403、
 * 413 也變 403，兩個都是 MockMvc 下全綠、實際 curl 才現形的。
 *
 * <p>用 JDK 的 {@link HttpClient} 而不是框架的測試 client：這裡要斷言的就是原始
 * 狀態碼，中間少一層包裝就少一個「是不是它幫我改寫了」的疑問。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTest.class)
@TestPropertySource(properties = {
        // 行事曆的設定跟 LINE 的一樣是 @NotBlank，少了就起不來（決策 8 的一貫做法）。
        // 這裡填假的：整合測試不會真的打 Google。
        "svra.calendar.client-id=test-client",
        "svra.calendar.client-secret=test-secret",
        "svra.calendar.refresh-token=test-refresh-token",
        "svra.calendar.calendar-id=test@group.calendar.google.com",
        "svra.line.channel-secret=test-secret",
        "svra.line.channel-access-token=dummy-token",
        // actuator 走獨立埠，才測得到「換了埠也一樣吃 security」這件事
        "management.server.port=0",
        "svra.outbox.poll-interval-ms=3600000",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 🔴 關掉這兩個健康指標，否則這個測試在 CI 上一定紅。
        //
        // 下面斷言 /actuator/health 回 200，而 health 是「聚合」的——只要有一個
        // 指標 DOWN 就是 503。CI 的 core job 沒有起 RabbitMQ 與 Redis，
        // 所以那兩個必然 DOWN。本機因為 compose 在跑所以看不出來，
        // 是典型的「在我機器上是好的」。
        //
        // 關掉它們不會削弱這個測試：它要證明的是 **security chain 讓 actuator 過**，
        // 不是「這套系統現在很健康」。後者是另一件事，也不該由安全測試來守。
        "management.health.rabbit.enabled=false",
        "management.health.redis.enabled=false",
})
class WebhookSecurityIntegrationTest {

    private static final String SECRET = "test-secret";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    private int postWebhook(String body, String signature) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/webhook"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)));
        if (signature != null) {
            request.header(LineSignatureAuthenticationFilter.SIGNATURE_HEADER, signature);
        }
        return this.http.send(request.build(), BodyHandlers.discarding()).statusCode();
    }

    private int signAndPost(String body) throws Exception {
        return postWebhook(body, LineSignature.generate(body, SECRET));
    }

    private int get(int onPort, String path) throws Exception {
        return this.http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path)).GET().build(),
                BodyHandlers.discarding()).statusCode();
    }

    @Test
    @DisplayName("簽章正確 → 200；錯誤或缺席 → 401")
    void signatureContractHoldsOnRealContainer() throws Exception {
        assertThat(signAndPost("{\"events\":[]}")).isEqualTo(200);
        assertThat(postWebhook("{\"events\":[]}", "bogus")).isEqualTo(401);
        assertThat(postWebhook("{\"events\":[]}", null)).isEqualTo(401);
    }

    /**
     * 這一則就是 curl 抓到、MockMvc 抓不到的那個洞。JSON 壞掉時 Spring MVC 判定 400，
     * 容器接著把請求再送一次到 /error——那一趟若被 denyAll 擋下，客戶端看到的會是 403，
     * 真正的原因整個消失。
     */
    @Test
    @DisplayName("簽章正確但 JSON 壞掉 → 400，不能被 ERROR dispatch 改寫成 403")
    void malformedBodyKeepsItsRealStatus() throws Exception {
        assertThat(signAndPost("{\"events\": THIS-IS-NOT-JSON}")).isEqualTo(400);
    }

    @Test
    @DisplayName("body 超過緩衝上限 → 413，同樣不能被改寫成 403")
    void oversizedBodyKeepsItsRealStatus() throws Exception {
        assertThat(signAndPost("{\"events\":[\"" + "x".repeat(300 * 1024) + "\"]}")).isEqualTo(413);
    }

    @Test
    @DisplayName("webhook 以外的路徑一律擋下")
    void unknownPathIsDenied() throws Exception {
        assertThat(get(this.port, "/something-else")).isEqualTo(403);
    }

    /**
     * 決策 20 的守門測試。actuator 在獨立埠上，但那個埠<b>不會</b>自動豁免 security：
     * Boot 的 {@code ServletManagementChildContextConfiguration} 會把父 context 的
     * springSecurityFilterChain 註冊進 management 子 context。少了 actuator 那條
     * chain，Prometheus 會靜靜地抓不到。
     */
    @Test
    @DisplayName("actuator 在獨立的 management 埠上仍然通（Prometheus 抓得到）")
    void actuatorStaysReachableOnManagementPort() throws Exception {
        assertThat(get(this.managementPort, "/actuator/health")).isEqualTo(200);
        assertThat(get(this.managementPort, "/actuator/prometheus")).isEqualTo(200);
    }

    @Test
    @DisplayName("management 埠上非 actuator 的路徑照樣擋")
    void nonActuatorPathOnManagementPortIsDenied() throws Exception {
        assertThat(get(this.managementPort, "/something-else")).isEqualTo(403);
    }
}
