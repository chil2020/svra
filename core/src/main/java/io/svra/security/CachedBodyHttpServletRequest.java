package io.svra.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * 把 request body 先讀進 {@code byte[]}，讓它可以被讀第二次。
 *
 * <p>為什麼不能用 {@link org.springframework.web.filter.ContentCachingRequestWrapper}：
 * 那個類別的 {@code getInputStream()} 回傳的是<b>包住底層 stream</b> 的
 * {@code ContentCachingInputStream}，它「邊讀邊記」。驗簽的 filter 讀完之後底層
 * 已經耗盡，Controller 的 {@code @RequestBody} 拿到的是空的。它的設計目的是事後
 * 記 log（{@code AbstractRequestLoggingFilter}），不是 replay——spring-boot#10452
 * 記錄了這個限制。要 replay 只能像這樣在<b>建構子</b>就把 body 收完。
 *
 * <p>ASP.NET Core 的 {@code Request.EnableBuffering()} 做的是同一件事，只是框架
 * 幫你包成一行。Servlet 沒有對應 API，所以這 30 行得自己寫。
 */
final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    /**
     * @param maxBytes body 上限。這一層跑在驗簽<b>之前</b>，也就是任何匿名請求都能
     *                 讓我們配置記憶體——沒有上限的話，一個 {@code Transfer-Encoding:
     *                 chunked} 的請求就能把 heap 吃光。不能靠 Content-Length 判斷，
     *                 chunked 根本不帶那個標頭，只能邊讀邊數。
     * @throws BodyTooLargeException 超過上限
     */
    CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        // readNBytes 會讀到剛好 n 個位元組或 EOF 為止。多要一個位元組，
        // 讀得到就代表超標——比先讀完再檢查長度安全。
        byte[] read = request.getInputStream().readNBytes(maxBytes + 1);
        if (read.length > maxBytes) {
            throw new BodyTooLargeException(maxBytes);
        }
        this.body = read;
    }

    /** 已緩衝的原始位元組。驗簽必須用這個，不能用反序列化再序列化回來的結果。 */
    byte[] body() {
        return this.body;
    }

    /**
     * 每次呼叫都給一個全新的 stream，這才是「可重讀」的意思。
     *
     * <p>{@code isFinished()} / {@code isReady()} 要照實回報。寫死 {@code false}
     * 在同步讀取下看起來沒事（容器不看），但只要有人走非同步 IO 就會壞掉，
     * 而且是那種很難查的壞法。
     */
    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream delegate = new ByteArrayInputStream(this.body);
        return new ServletInputStream() {

            @Override
            public int read() {
                return delegate.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return delegate.read(b, off, len);
            }

            @Override
            public int available() {
                return delegate.available();
            }

            @Override
            public boolean isFinished() {
                return delegate.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // 資料已經全在記憶體裡，沒有「等資料到」這回事。
                throw new UnsupportedOperationException("已緩衝的 body 不支援非同步讀取");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
    }

    /** 沒帶 charset 就用 UTF-8，不要落到平台預設——那會依作業系統而變。 */
    private Charset charset() {
        String encoding = getCharacterEncoding();
        return (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
    }

    /**
     * 原始請求可能是 chunked（Content-Length 為 -1）。既然 body 已經全在手上，
     * 就照實回報長度，免得下游依 Content-Length 做決定時拿到 -1。
     */
    @Override
    public int getContentLength() {
        return this.body.length;
    }

    @Override
    public long getContentLengthLong() {
        return this.body.length;
    }
}
