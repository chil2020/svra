package io.svra.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 把 LINE 的簽章從 HTTP 請求裡撈出來，交給 {@link LineSignatureAuthenticationProvider} 判斷。
 *
 * <p>為什麼繼承 {@code AbstractPreAuthenticatedProcessingFilter} 而不是
 * {@code AuthenticationFilter}：
 * <ul>
 *   <li>語意對——「請求本身自帶身分證明，沒有登入流程」正是 pre-authentication。</li>
 *   <li>它的 {@code doFilter} 最後<b>一定</b>呼叫 {@code chain.doFilter}。
 *       {@code AuthenticationFilter} 成功後不會續傳，而且預設的 successHandler 是
 *       {@code SavedRequestAwareAuthenticationSuccessHandler}——會發 redirect。
 *       要用它就得另外塞一個覆寫四參數版 {@code onAuthenticationSuccess(req, res, chain, auth)}
 *       的 handler，對 webhook 來說是白繞。</li>
 * </ul>
 *
 * <p>失敗時它<b>不會</b>直接回應：{@code continueFilterChainOnUnsuccessfulAuthentication}
 * 預設為 true，清掉 SecurityContext 後照樣往下走，由 {@code AuthorizationFilter} 擋下、
 * {@code ExceptionTranslationFilter} 交給 entry point 決定回什麼。所以 401 是在
 * {@link SecurityConfig} 設定的，不在這裡。
 */
class LineSignatureAuthenticationFilter extends AbstractPreAuthenticatedProcessingFilter {

    static final String SIGNATURE_HEADER = "X-Line-Signature";

    /**
     * 這條 chain 只證明「請求來自持有 channel secret 的一方」，沒有使用者概念——
     * webhook 裡的 userId 是資料，不是通過驗證的身分。給一個固定的 principal
     * 就夠了，別讓它看起來像登入的人。
     */
    static final String PRINCIPAL = "line-platform";

    private static final Logger log = LoggerFactory.getLogger(LineSignatureAuthenticationFilter.class);

    /**
     * 永遠回傳非 null。基底類別在 principal 為 null 時會<b>直接跳過驗證</b>，
     * 那樣「沒帶簽章」就變成靜靜地不驗，而不是明確地失敗——要讓 provider 有機會
     * 說「這個不合格」，判斷才會集中在一個地方。
     */
    @Override
    protected Object getPreAuthenticatedPrincipal(HttpServletRequest request) {
        return PRINCIPAL;
    }

    /**
     * body 由 {@link CachedBodyFilter} 事先緩衝，所以這裡讀完之後 Controller 還讀得到。
     *
     * <p>注意這個方法是在基底類別的 try/catch 之<b>外</b>被呼叫的——在這裡拋
     * {@code AuthenticationException} 不會被接住，會變成 500。所以讀取失敗只記錄
     * 並回傳 null body，讓 provider 去拒絕。
     */
    @Override
    protected Object getPreAuthenticatedCredentials(HttpServletRequest request) {
        try {
            return new SignedPayload(request.getHeader(SIGNATURE_HEADER), request.getInputStream().readAllBytes());
        } catch (IOException ex) {
            log.warn("讀取 request body 失敗，視為驗證失敗", ex);
            return new SignedPayload(request.getHeader(SIGNATURE_HEADER), null);
        }
    }
}
