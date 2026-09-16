package io.svra.calendar;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import io.svra.user.Credentials;
import io.svra.user.GoogleAuthorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * 換 token 被 Google 拒絕之後，那一列該不該標記撤銷。
 *
 * <p>🔴 <b>這個判斷的兩個方向代價完全不對稱</b>，所以兩邊都要有測試守著：
 * 少標記一次，下次匯入時再發現，使用者多等一輪；多標記一次，是把一個授權
 * 好好的人踢回預填連結、逼他去重跑授權腳本——而他根本沒有做錯任何事。
 */
class GoogleTokenProviderRevocationTest {

    private static final String USER = "U-token-test";

    private final Credentials credentials = mock(Credentials.class);

    /** 建一個「token endpoint 一定回這個錯誤碼」的 provider。 */
    private GoogleTokenProvider rejectingWith(String errorCode) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build()
                .expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"" + errorCode + "\"}"));
        CalendarProperties properties = new CalendarProperties(
                List.of(USER), "client-id", "client-secret", "seed", "cal@example.com", 60);
        return new GoogleTokenProvider(builder, properties, Clock.systemUTC(), credentials);
    }

    private void refresh(GoogleTokenProvider provider) {
        provider.accessToken(USER,
                new GoogleAuthorization("refresh-token", "cal@example.com", "scope"));
    }

    @Test
    @DisplayName("invalid_grant → 這個人的授權真的沒了，標記撤銷")
    void invalidGrantRevokesTheCredential() {
        GoogleTokenProvider provider = rejectingWith("invalid_grant");

        assertThatThrownBy(() -> refresh(provider))
                .isInstanceOf(CalendarAuthorizationException.class);

        // 不標記的話，資料庫會一直說這個人的憑證是活的，
        // 而啟動檢查同時在喊「他的授權壞了」——兩邊對不上。
        verify(credentials).revoke(USER);
    }

    @Test
    @DisplayName("🔴 invalid_client → 壞的是應用程式的設定，不准動使用者的憑證")
    void invalidClientDoesNotRevokeAnyone() {
        GoogleTokenProvider provider = rejectingWith("invalid_client");

        assertThatThrownBy(() -> refresh(provider))
                .isInstanceOf(CalendarAuthorizationException.class);

        // client id/secret 填錯影響的是每一個人，而每個人的 refresh token
        // 其實都還是好的。在這裡標記撤銷，等於把一次部署失誤放大成
        // 「所有人都要重新授權」——而重新授權救不了一個打錯的 client secret。
        verify(credentials, never()).revoke(USER);
    }

    @Test
    @DisplayName("5xx → 是 Google 那邊的事，既不判死也不撤銷")
    void serverErrorsAreTransientAndNeverRevoke() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build()
                .expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON).body("{}"));
        CalendarProperties properties = new CalendarProperties(
                List.of(USER), "client-id", "client-secret", "seed", "cal@example.com", 60);
        GoogleTokenProvider provider =
                new GoogleTokenProvider(builder, properties, Clock.systemUTC(), credentials);

        assertThatThrownBy(() -> refresh(provider))
                .isNotInstanceOf(CalendarAuthorizationException.class);

        verify(credentials, never()).revoke(USER);
    }
}
