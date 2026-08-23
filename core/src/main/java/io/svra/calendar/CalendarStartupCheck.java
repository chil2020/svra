package io.svra.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.svra.user.Credentials;

/**
 * 啟動完成後，替每個授權過的使用者換一次 access token。
 *
 * <p>驗證擋得住「沒填」，擋不住「填了但是錯的」。而錯的 refresh token 的症狀是
 * 「按鈕按下去，幾分鐘後收到一則失敗通知」——那時你已經在用了。
 * 跟 {@code ddl-auto=validate} 在啟動時擋下 schema 不一致是同一個判斷（決策 8）。
 *
 * <p>🔴 <b>逐個使用者驗，而不是驗「那一顆 token」。</b>憑證變成 per-user 之後，
 * 「授權是好的」不再是一個布林值——可能三個人裡有一個的 token 過期了，
 * 而只驗第一個（或只驗設定檔那組）會讓另外兩個人的問題完全隱形。
 *
 * <p>放在 {@code ApplicationReadyEvent} 而不是建構子：換 token 是網路呼叫，
 * 塞在 bean 初始化裡會讓「Google 暫時連不上」變成「應用起不來」，
 * 而那兩件事的嚴重程度差很多。同理，這裡不丟例外——refresh token 也可能在
 * <b>運行中</b>失效，那條路本來就得靠推播處理。記一行 error 讓人看得見，
 * 比較合乎比例。
 */
@Component
class CalendarStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(CalendarStartupCheck.class);

    private final GoogleTokenProvider tokenProvider;
    private final Credentials credentials;

    CalendarStartupCheck(GoogleTokenProvider tokenProvider, Credentials credentials) {
        this.tokenProvider = tokenProvider;
        this.credentials = credentials;
    }

    /**
     * 一定要排在 {@code CalendarCredentialsBootstrap} 後面——它還沒把 .env 種進去的話，
     * 這裡會查到一張空表，然後很有信心地印出「沒有人授權過」。
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    void verify() {
        var userIds = credentials.activeUserIds();
        if (userIds.isEmpty()) {
            // 沒有人授權＝所有人走連結（決策 27），根本不會用到 OAuth。
            // 還是去換 token 的話，每次啟動都會印一行紅色的「授權是壞的」——
            // 而那個部署一切正常。**會一直誤報的檢查，等於沒有檢查。**
            return;
        }

        int broken = 0;
        for (String lineUserId : userIds) {
            try {
                credentials.find(lineUserId).ifPresent(
                        auth -> tokenProvider.accessToken(lineUserId, auth));
            } catch (Exception e) {
                broken++;
                log.error("使用者 {} 的行事曆授權目前是壞的，他的匯入按鈕不會動——"
                        + "請重跑 deploy/google-calendar-auth.py 取得新的 refresh token",
                        mask(lineUserId), e);
            }
        }
        log.info("行事曆授權檢查完畢：授權人數={} 壞掉={}", userIds.size(), broken);
    }

    /**
     * 只印前 8 碼。
     *
     * <p>這裡沒有 MDC 可用（不在任何一則訊息的處理流程上），而完全不指名的話，
     * 三個人裡壞掉一個時<b>看不出是哪一個</b>——那則 error 就只能告訴你
     * 「有事發生」。前 8 碼足以在幾個人之間分辨，又不會把完整的 userId
     * 留在 log 檔裡。
     */
    private static String mask(String lineUserId) {
        return lineUserId.length() <= 8 ? lineUserId : lineUserId.substring(0, 8) + "…";
    }
}
