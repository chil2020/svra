package io.svra.security;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

import io.svra.line.LineProperties;

/**
 * 真正做判斷的地方：重算 HMAC，比對 {@code X-Line-Signature}。
 *
 * <p>filter 負責「從傳輸層搬出憑據」，provider 負責「這個憑據算不算數」。
 * 拆開的好處是判斷邏輯不綁 Servlet API——這個類別可以單獨測，不需要 MockMvc。
 */
class LineSignatureAuthenticationProvider implements AuthenticationProvider {

    /**
     * 授權用的角色。取名 WEBHOOK 而不是 USER：通過的是「這個請求來自 LINE」，
     * 不是「某個使用者登入了」。
     */
    static final String ROLE = "ROLE_LINE_WEBHOOK";

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority(ROLE));

    private final LineProperties lineProperties;

    LineSignatureAuthenticationProvider(LineProperties lineProperties) {
        this.lineProperties = lineProperties;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication.getCredentials() instanceof SignedPayload payload)) {
            throw new BadCredentialsException("缺少 LINE 簽章憑據");
        }
        if (payload.signature() == null) {
            throw new BadCredentialsException("請求沒有帶 " + LineSignatureAuthenticationFilter.SIGNATURE_HEADER);
        }
        if (payload.body() == null) {
            throw new BadCredentialsException("讀不到 request body，無法驗簽");
        }
        if (!LineSignature.matches(payload.body(), this.lineProperties.channelSecret(), payload.signature())) {
            throw new BadCredentialsException("LINE 簽章不符");
        }

        // 驗過之後就把 credentials 丟掉——通過的 token 不該再帶著簽章與整包 body
        // 留在 SecurityContext 裡，那只會讓它出現在 log 與例外堆疊上。
        return new PreAuthenticatedAuthenticationToken(authentication.getPrincipal(), null, AUTHORITIES);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PreAuthenticatedAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
