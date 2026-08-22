package io.svra.calendar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行事曆的設定有兩種合法狀態，而<b>中間那個組合一定是壞的</b>。
 *
 * <ul>
 * <li>白名單空的、憑證也空 → 所有人走預填連結（決策 27）。<b>合法</b></li>
 * <li>白名單有人、憑證填齊 → 那些人一鍵匯入（決策 26）。<b>合法</b></li>
 * <li>白名單有人、憑證沒填 → 那些人會按到一顆註定失敗的按鈕。<b>要在啟動時炸</b></li>
 * </ul>
 *
 * <p>為什麼不乾脆全部 {@code @NotBlank}：那會讓一個<b>正確的</b>純連結部署起不來，
 * 而它根本不需要 OAuth。決策 8 說的是「設定錯誤要在啟動時炸」，
 * 而「沒用到的欄位留白」不是錯誤。
 *
 * <p>漏填的症狀特別難查：不是整個系統壞掉，是<b>某幾個人的按鈕沒反應</b>，
 * 而其他人一切正常。
 */
class CalendarPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableCalendarProperties.class)
            .withPropertyValues("svra.calendar.default-duration-minutes=60");

    @Test
    @DisplayName("白名單空的、憑證也空 → 啟動成功（純連結部署）")
    void noWhitelistNeedsNoCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CalendarProperties.class).oauthUserIds()).isEmpty();
        });
    }

    @Test
    @DisplayName("🔴 空字串＝沒有人，不是「一個空字串的人」")
    void emptyStringIsAnEmptyWhitelist() {
        // application.yml 寫的是 ${CALENDAR_OAUTH_USER_IDS:}，.env 沒設時
        // 它就是一個空字串。若綁成 [""]，白名單就「非空」，
        // 而憑證留白的正確部署會在啟動時被自己的驗證擋下來。
        runner.withPropertyValues("svra.calendar.oauth-user-ids=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CalendarProperties.class).oauthUserIds()).isEmpty();
                });
    }

    @Test
    @DisplayName("逗號結尾或多餘空白不該生出空白的成員")
    void trailingCommasAndSpacesDoNotCreatePhantomMembers() {
        runner.withPropertyValues(
                "svra.calendar.oauth-user-ids=U123, ,U456,",
                "svra.calendar.client-id=cid",
                "svra.calendar.client-secret=secret",
                "svra.calendar.refresh-token=refresh",
                "svra.calendar.calendar-id=cal")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CalendarProperties.class).oauthUserIds())
                            .containsExactly("U123", "U456");
                });
    }

    @Test
    @DisplayName("白名單有人、憑證填齊 → 啟動成功")
    void whitelistWithCredentialsStartsUp() {
        runner.withPropertyValues(
                "svra.calendar.oauth-user-ids=U123,U456",
                "svra.calendar.client-id=cid",
                "svra.calendar.client-secret=secret",
                "svra.calendar.refresh-token=refresh",
                "svra.calendar.calendar-id=cal@group.calendar.google.com")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CalendarProperties.class).oauthUserIds())
                            .containsExactly("U123", "U456");
                });
    }

    @Test
    @DisplayName("🔴 白名單有人卻沒填憑證 → 啟動失敗，而且訊息要說得出怎麼修")
    void whitelistWithoutCredentialsFailsFast() {
        runner.withPropertyValues("svra.calendar.oauth-user-ids=U123")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 驗整條堆疊而不是最外層的訊息：Spring 把綁定失敗包了一層，
                    // 而我們寫給人看的那句話在裡面的 BindValidationException 上。
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("oauth-user-ids")
                            .hasStackTraceContaining("google-calendar-auth.py");
                });
    }

    @Test
    @DisplayName("憑證只填一半也算沒填齊——半套的授權跟沒有授權一樣會失敗")
    void partialCredentialsAlsoFail() {
        runner.withPropertyValues(
                "svra.calendar.oauth-user-ids=U123",
                "svra.calendar.client-id=cid",
                "svra.calendar.client-secret=secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("空白字串等於沒填——YAML 的 ${VAR:} 預設值就是空字串")
    void blankIsTreatedAsMissing() {
        runner.withPropertyValues(
                "svra.calendar.oauth-user-ids=U123",
                "svra.calendar.client-id=cid",
                "svra.calendar.client-secret=secret",
                "svra.calendar.refresh-token=",
                "svra.calendar.calendar-id=cal")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CalendarProperties.class)
    static class EnableCalendarProperties {
    }
}
