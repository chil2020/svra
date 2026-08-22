package io.svra.line;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 下載 LINE 訊息的內容（音檔）。
 *
 * <p>內容走 api-data.line.me，跟一般 API 的 api.line.me 不同網域。
 */
@Component
public class LineContentClient {

    private static final String CONTENT_URL = "https://api-data.line.me/v2/bot/message/{messageId}/content";

    private final RestClient restClient;

    public LineContentClient(LineProperties lineProperties, RestClient.Builder builder) {
        this.restClient = builder
                .defaultHeader("Authorization", "Bearer " + lineProperties.channelAccessToken())
                .build();
    }

    /**
     * 把音檔下載到 target，回傳實際寫入的位置。
     *
     * <p>先寫暫存檔再 move：中途失敗不會留下半個檔案讓 worker 讀到。
     */
    public Path download(String messageId, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");

        try {
            restClient.get()
                    .uri(CONTENT_URL, messageId)
                    .exchange((request, response) -> {
                        // exchange() 不會因為 4xx/5xx 拋例外，狀態碼要自己檢查。
                        // 少了這段，錯誤頁面會被當成音檔寫下去，直到 worker 解碼失敗
                        // 才爆——那時候的錯誤訊息離真正的原因已經差了三層。
                        int code = response.getStatusCode().value();
                        // 🔴 404／410 ＝ 檔案已經被 LINE 刪掉了，重試不會讓它回來。
                        // 跟連線失敗分開，因為能做的事完全不同：那個重試就會好，
                        // 這個只能請使用者重傳（見 ContentUnavailableException）。
                        if (code == 404 || code == 410) {
                            throw new ContentUnavailableException(
                                    "LINE 上已經沒有這則訊息的內容了（回應 "
                                            + response.getStatusCode() + "）");
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IOException("LINE content API 回應 "
                                    + response.getStatusCode() + "，messageId=" + messageId);
                        }
                        try (InputStream in = response.getBody()) {
                            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return null;
                    });

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
