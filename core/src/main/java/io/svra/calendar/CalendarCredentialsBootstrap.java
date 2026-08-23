package io.svra.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.svra.user.Credentials;
import io.svra.user.GoogleAuthorization;
import io.svra.user.Users;

/**
 * 把 {@code .env} 裡那一組憑證種進 {@code google_credentials}。
 *
 * <p>🔴 <b>它存在是為了避免「一張沒有人寫的表」。</b>per-user 的授權流程
 * （讓使用者自己在 LINE 裡跑完 OAuth）還沒做，如果只建表不填，
 * 那張表會空到那天為止——而空的表沒有人會發現欄位設計錯了。
 *
 * <p>所以改成：<b>環境變數是種子，程式碼只讀表。</b>表從第一天就是活的，
 * 讀取路徑（{@code Credentials.find}）從第一天就在跑真的資料，
 * 之後多一個使用者就只是多一列。
 *
 * <p><b>寫入規則刻意是「不一樣才寫」</b>，不是每次啟動覆蓋：
 * <ul>
 * <li>一般重啟＝完全的 no-op，不會在 log 裡留下噪音，也不會動 {@code granted_at}</li>
 * <li>換過 token（例如 Testing 模式七天到期後重跑授權腳本）＝寫入並<b>大聲說</b>，
 * 因為那正是你想確認有沒有生效的時刻</li>
 * </ul>
 *
 * <p><b>被種的人，.env 說了算。</b>列在 {@code oauth-user-ids} 裡就代表
 * 「這個人的憑證由部署設定管理」。真正的 per-user 授權上線後，
 * 把那份名單清空，這個類別就自動什麼都不做。
 */
@Component
class CalendarCredentialsBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CalendarCredentialsBootstrap.class);

    /** 與 {@code deploy/google-calendar-auth.py} 的 SCOPE 一字不差。 */
    private static final String SCOPE = "https://www.googleapis.com/auth/calendar.app.created";

    private final CalendarProperties properties;
    private final Credentials credentials;
    private final Users users;

    CalendarCredentialsBootstrap(CalendarProperties properties, Credentials credentials,
            Users users) {
        this.properties = properties;
        this.credentials = credentials;
        this.users = users;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        if (properties.oauthUserIds().isEmpty()) {
            // 純連結部署（決策 27）。沒有憑證要種，也不需要加密金鑰。
            log.info("沒有設定要種的行事曆憑證，所有使用者走預填連結");
            return;
        }

        if (!credentials.canStoreCredentials()) {
            // 🔴 這個組合擋在啟動時說出來，而不是等第一次匯入才炸。
            // 症狀會是「按鈕按下去、幾分鐘後收到失敗通知」——那時你已經在用了。
            log.error("設定了 oauth-user-ids 卻沒有 svra.secrets.encryption-key，"
                    + "憑證無法加密儲存，這些人的匯入按鈕不會動。"
                    + "用 `openssl rand -base64 32` 產一把放進 .env");
            return;
        }

        for (String lineUserId : properties.oauthUserIds()) {
            // 外鍵擋著：使用者列一定要先在。名單是手打的，裡面可能有從沒跟 bot
            // 說過話的 id——那也沒關係，他之後加好友時就已經有一列了。
            users.ensureExists(lineUserId);
            seedOne(lineUserId);
        }
    }

    private void seedOne(String lineUserId) {
        GoogleAuthorization existing = credentials.find(lineUserId).orElse(null);
        boolean unchanged = existing != null
                && existing.refreshToken().equals(properties.refreshToken())
                && existing.calendarId().equals(properties.calendarId())
                && existing.scope().equals(SCOPE);
        if (unchanged) {
            return;
        }

        credentials.store(lineUserId, properties.refreshToken(), properties.calendarId(), SCOPE);
        log.warn("已用 .env 的設定{}這個使用者的行事曆憑證",
                existing == null ? "建立" : "更新");
    }
}
