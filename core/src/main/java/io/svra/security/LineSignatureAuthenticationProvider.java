package io.svra.security;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(LineSignatureAuthenticationProvider.class);

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
            // 🔴 DEBUG 而不是 WARN。webhook 掛在公開網域上，掃描器與亂打的請求
            // 一定會有，而它們的共同特徵就是「根本沒帶簽章」。記成 WARN 只會把
            // log 洗掉，讓下面那個真正該看的訊息淹沒在雜訊裡。
            log.debug("拒絕未簽章的請求");
            throw new BadCredentialsException("請求沒有帶 " + LineSignatureAuthenticationFilter.SIGNATURE_HEADER);
        }
        if (payload.body() == null) {
            throw new BadCredentialsException("讀不到 request body，無法驗簽");
        }
        if (!LineSignature.matches(payload.body(), this.lineProperties.channelSecret(), payload.signature())) {
            // 🔴 這個要 WARN。**LINE 一定會帶對的簽章**，所以「帶了簽章但驗不過」
            // 幾乎不可能是外人——最可能是 channel secret 跟 LINE 後台不一致。
            // 不記的話，設定錯的症狀是「LINE 一直重送、log 一片乾淨」，無從查起。
            //
            // 不印簽章本身：它對不上就沒有診斷價值，印出來反而是把別人送的
            // 任意字串寫進 log。
            log.warn("簽章不符——對方帶了簽章卻驗不過。"
                    + "LINE 一定會帶正確簽章，所以最可能是 channel secret 跟後台不一致");
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
