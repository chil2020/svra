package io.svra.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 在驗簽之前把 body 收進記憶體，讓驗簽的 filter 與 Controller 都讀得到。
 *
 * <p>放在 {@link org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter}
 * 之前（見 {@link SecurityConfig}）。這裡不必自己算 filter 順序：
 * {@code FilterChainProxy} 內部的 {@code VirtualFilterChain} 跑完自己的 filter
 * 之後，是用「當下這個 request 物件」呼叫 {@code originalChain.doFilter(...)}，
 * 所以在 security chain 裡裝的 wrapper 會一路傳到 Controller。
 *
 * <p>（另一種寫法是用 {@code FilterRegistrationBean} 設 order 小於
 * {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}（-100）掛在容器層。
 * 效果一樣，但那個魔術數字得自己維護，而且設定會散到 SecurityConfig 之外。）
 */
class CachedBodyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CachedBodyFilter.class);

    private final int maxBytes;

    CachedBodyFilter(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CachedBodyHttpServletRequest cached;
        try {
            cached = new CachedBodyHttpServletRequest(request, this.maxBytes);
        } catch (BodyTooLargeException ex) {
            // 回 413 而不是 401：這個判斷發生在驗簽之前，我們還不知道對方是誰，
            // 說「你沒通過驗證」是不誠實的。413 也不洩漏任何東西。
            log.warn("拒絕過大的 request body：{}", ex.getMessage());
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }

        chain.doFilter(cached, response);
    }
}
