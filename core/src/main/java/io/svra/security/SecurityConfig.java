package io.svra.security;

import java.util.List;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

import jakarta.servlet.DispatcherType;

import io.svra.line.LineProperties;

/**
 * 決策 22：LINE webhook 的驗簽從 Controller 移進 Spring Security。
 *
 * <p>三條 chain，由上而下比對：webhook → actuator → 其他一律拒絕。
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    /**
     * webhook body 的緩衝上限。
     *
     * <p>驗簽必須先收完整包才能算 HMAC，也就是<b>任何匿名請求都能讓我們配置記憶體</b>——
     * 這是這種驗證方式的本質，不是實作缺陷。LINE 的 webhook 一次頂多帶幾則事件，
     * 實際大小是數 KB；256 KiB 已經非常寬鬆，而它擋掉的是「不設限就沒有上限」。
     */
    private static final int MAX_WEBHOOK_BODY_BYTES = 256 * 1024;

    /**
     * 註冊成 bean 有兩個作用：一是讓 provider 可以單獨被測與被替換，
     * 二是讓 {@code UserDetailsServiceAutoConfiguration} 退讓——沒有任何
     * {@code AuthenticationProvider} bean 時 Boot 會自己造一個預設使用者，
     * 並在啟動 log 印一組隨機密碼。那個帳號在這個專案裡沒有意義。
     */
    @Bean
    LineSignatureAuthenticationProvider lineSignatureAuthenticationProvider(LineProperties lineProperties) {
        return new LineSignatureAuthenticationProvider(lineProperties);
    }

    /**
     * 對外唯一的入口。通過的條件只有一個：body 的 HMAC 對得上 channel secret。
     */
    @Bean
    @Order(1)
    SecurityFilterChain webhookFilterChain(HttpSecurity http, LineSignatureAuthenticationProvider provider)
            throws Exception {

        LineSignatureAuthenticationFilter signatureFilter = new LineSignatureAuthenticationFilter();
        signatureFilter.setAuthenticationManager(new ProviderManager(List.of(provider)));

        http.securityMatcher("/webhook")
                // webhook 沒有瀏覽器、沒有 session，CSRF token 無從取得也無從驗證。
                // 不關掉的話 POST 會直接被 CsrfFilter 擋成 403。
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                // 驗簽失敗時 AbstractPreAuthenticatedProcessingFilter 不會自己回應，
                // 它清掉 SecurityContext 後繼續往下走。最後擋下來的是 AuthorizationFilter，
                // 而 ExceptionTranslationFilter 預設回 403。401 得在這裡明講——
                // 這是身分問題（無法證明來自 LINE），不是權限問題。
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 順序不用自己算：LineSignatureAuthenticationFilter 繼承自
                // AbstractPreAuthenticatedProcessingFilter，而後者在 Spring Security 的
                // FilterOrderRegistration 裡有登記，addFilter() 會沿著父類別找到它的位置。
                .addFilterBefore(new CachedBodyFilter(MAX_WEBHOOK_BODY_BYTES),
                        AbstractPreAuthenticatedProcessingFilter.class)
                .addFilter(signatureFilter)
                .authorizeHttpRequests(auth -> auth.anyRequest()
                        .hasAuthority(LineSignatureAuthenticationProvider.ROLE));

        return http.build();
    }

    /**
     * actuator 維持不驗證——但現在是<b>明講</b>的不驗證。
     *
     * <p>決策 20 的理由沒有變（8444 不對外發布，保護靠的是網路邊界不是密碼），
     * 變的是預設值：security 一上 classpath，Boot 的
     * {@code ServletManagementChildContextConfiguration} 就會把父 context 的
     * {@code springSecurityFilterChain} 也註冊進 management 子 context
     * （{@code @ConditionalOnBean(name = "springSecurityFilterChain", search = ANCESTORS)}）。
     * 換了埠<b>不等於</b>豁免。少了這條 chain，8444 會掉進下面的 denyAll，
     * Prometheus 直接抓不到——而且是啟動時完全沒有徵兆的那種壞法。
     */
    @Bean
    @Order(2)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    /**
     * 其餘一律拒絕。
     *
     * <p>8443 上除了 /webhook 沒有別的東西，這條 chain 平常不會被用到——它存在是為了
     * 讓「多開了一個端點卻忘記想授權」的預設結果是<b>擋下來</b>，而不是放行。
     *
     * <p>ERROR dispatch 是例外，而且非放不可。容器要回一個錯誤狀態時，會把請求<b>再
     * 送一次</b>到 /error；Boot 註冊 security filter 的 dispatcher types 預設就含
     * ERROR，所以那一趟也會走 filter chain，而 /error 不符合上面兩條的 matcher，
     * 就掉進這裡被 denyAll 擋掉——結果是<b>真正的狀態碼被改寫成 403</b>：
     * Controller 判定的 400 變 403，上面那個 413 也變 403。
     *
     * <p>這條路 MockMvc 驗不出來（它不做容器的 error dispatch），是實際起 app 打
     * curl 才看見的。用 dispatcher type 而不是放行 /error 這個路徑：ERROR dispatch
     * 只有容器自己發得動，外部請求永遠是 REQUEST，直接打 /error 依然會被擋。
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain denyAllFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().denyAll());

        return http.build();
    }
}
