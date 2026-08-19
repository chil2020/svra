package io.svra.line;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 憑證沒設好，要在<b>啟動時</b>失敗。
 *
 * <p>不驗的話會怎樣：{@code application.yml} 把 channel secret 的預設值寫成空字串，
 * 而空字串餵給 {@code SecretKeySpec} 會拋 {@code IllegalArgumentException: Empty key}——
 * 那既不是 {@code GeneralSecurityException}（{@code LineSignature} 的 catch 接不到），
 * 也不是 {@code AuthenticationException}（Spring Security 的 filter 接不到），
 * 於是一路穿到最外層變成 <b>500</b>。而 LINE 收到 500 會重送。
 *
 * <p>症狀因此是最難查的那一種：<b>應用啟動得好好的、健康檢查是綠的，
 * 但每一則訊息都在無限重送</b>，而 log 裡看不出這跟設定有關。
 *
 * <p>這跟決策 8 讓 {@code ddl-auto=validate} 在啟動時擋下 schema 不一致是同一個判斷。
 */
class LinePropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations
                    .of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableLineProperties.class);

    @Test
    @DisplayName("兩個憑證都給了 → 啟動成功")
    void bothCredentialsPresentStartsUp() {
        runner.withPropertyValues(
                "svra.line.channel-secret=a-secret",
                "svra.line.channel-access-token=a-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LineProperties.class).channelSecret()).isEqualTo("a-secret");
                });
    }

    @Test
    @DisplayName("channel secret 是空的 → 啟動失敗，而不是等到第一則 webhook 才 500")
    void blankSecretFailsFast() {
        runner.withPropertyValues(
                "svra.line.channel-secret=",
                "svra.line.channel-access-token=a-token")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("channel secret 整個沒設 → 啟動失敗")
    void missingSecretFailsFast() {
        runner.withPropertyValues("svra.line.channel-access-token=a-token")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("access token 是空的 → 啟動失敗（下載音檔與推播都會用到）")
    void blankAccessTokenFailsFast() {
        runner.withPropertyValues(
                "svra.line.channel-secret=a-secret",
                "svra.line.channel-access-token=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LineProperties.class)
    static class EnableLineProperties {
    }
}
