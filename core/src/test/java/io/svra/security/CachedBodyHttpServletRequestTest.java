package io.svra.security;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 這個 wrapper 的全部價值就是「同一份 body 讀得到第二次」。
 *
 * <p>{@code MockHttpServletRequest.getInputStream()} 會把建立出來的 stream 快取起來，
 * 第二次呼叫拿到的是同一個、已經耗盡的 stream——跟真實容器一樣。所以下面第一個測試
 * 如果拿掉 wrapper 就會失敗，不是在測 mock 的行為。
 */
class CachedBodyHttpServletRequestTest {

    private static final byte[] BODY = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

    private static MockHttpServletRequest requestWith(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook");
        request.setContent(body);
        return request;
    }

    @Test
    @DisplayName("原始 request 的 body 只讀得到一次（這是問題本身）")
    void rawRequestBodyIsSingleUse() throws Exception {
        MockHttpServletRequest raw = requestWith(BODY);

        assertThat(raw.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(raw.getInputStream().readAllBytes()).isEmpty();
    }

    @Test
    @DisplayName("包過之後，驗簽讀一次、Controller 再讀一次，兩次都拿到完整內容")
    void cachedBodyCanBeReadRepeatedly() throws Exception {
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(requestWith(BODY), 1024);

        assertThat(cached.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(cached.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(cached.body()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("getReader() 與 getInputStream() 讀的是同一份，互不影響")
    void readerAndInputStreamAreIndependent() throws Exception {
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(requestWith(BODY), 1024);

        assertThat(cached.getReader().readLine()).isEqualTo("{\"events\":[]}");
        assertThat(cached.getInputStream().readAllBytes()).isEqualTo(BODY);
    }

    @Test
    @DisplayName("isFinished() 照實回報，不是寫死 false")
    void reportsStreamState() throws Exception {
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(requestWith(BODY), 1024);

        var stream = cached.getInputStream();
        assertThat(stream.isReady()).isTrue();
        assertThat(stream.isFinished()).isFalse();

        stream.readAllBytes();
        assertThat(stream.isFinished()).isTrue();
    }

    @Test
    @DisplayName("剛好等於上限：放行")
    void allowsBodyExactlyAtLimit() throws Exception {
        byte[] body = new byte[64];

        assertThat(new CachedBodyHttpServletRequest(requestWith(body), 64).body()).hasSize(64);
    }

    @Test
    @DisplayName("超過上限：拒絕，而不是先吃進記憶體再說")
    void rejectsBodyOverLimit() {
        byte[] body = new byte[65];

        assertThatThrownBy(() -> new CachedBodyHttpServletRequest(requestWith(body), 64))
                .isInstanceOf(BodyTooLargeException.class)
                .hasMessageContaining("64");
    }

    @Test
    @DisplayName("空 body 不算錯，交給下游決定")
    void allowsEmptyBody() throws Exception {
        assertThat(new CachedBodyHttpServletRequest(requestWith(new byte[0]), 64).body()).isEmpty();
    }
}
