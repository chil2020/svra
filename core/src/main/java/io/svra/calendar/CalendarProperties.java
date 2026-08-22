package io.svra.calendar;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

/**
 * 行事曆設定。
 *
 * <p>🔴 <b>這裡的必填是「有條件的」，而那個條件本身就是設計。</b>
 *
 * <p>四個 Google 憑證原本都是 {@code @NotBlank}——當時只有一種使用者，一定要授權。
 * 加了連結方案（決策 27）之後不再是這樣：<b>白名單是空的部署完全不需要 OAuth</b>，
 * 所有人走連結，那四個欄位留白是正確的狀態而不是漏填。
 *
 * <p>所以規則改成：<b>白名單非空、卻沒填憑證 → 啟動失敗。</b>
 * 那個組合<b>一定</b>是壞的（白名單裡的人會按到一顆註定失敗的按鈕），
 * 而漏填的症狀是「某幾個人的按鈕沒反應」——比整體壞掉更難查。
 *
 * <p>這仍然是決策 8 那個判斷（設定錯誤要在啟動時炸），只是把「錯誤」定義得更準：
 * <b>擋的是不可能成立的組合，不是沒用到的欄位。</b>
 *
 * @param oauthUserIds           可以讓後端直接寫入行事曆的 LINE userId。
 *                               <p>🔴 <b>憑證只有一份，所以這份名單實務上就是「擁有者」。</b>
 *                               下面的 refreshToken 與 calendarId 指向<b>某一個</b> Google
 *                               帳號的<b>某一本</b>行事曆——名單放兩個人，等於第二個人的行程
 *                               會寫進第一個人的行事曆。<b>那幾乎一定不是你要的。</b>
 *                               <p>它做成清單而不是單一值，是為了讓同一個人的兩個 LINE 帳號
 *                               能共用；真正要讓多個人各自匯進自己的行事曆，得先做
 *                               per-user 的 token 儲存，那時這一欄會整個消失
 *                               （見 {@code CalendarCapabilityAdapter}）
 * @param clientId               GCP OAuth client（Desktop app 類型）
 * @param clientSecret           同上。Desktop client 的 secret 本來就不算機密，
 *                               但它跟 refresh token 一起就能換 access token，所以照機密管
 * @param refreshToken           由 {@code deploy/google-calendar-auth.py} 一次性取得。
 *                               <b>consent screen 必須是 In Production</b>——停在 Testing 的話
 *                               Google 會在七天後撤銷它（見決策 26）
 * @param calendarId             專用子行事曆的 id，由同一支腳本建立並印出
 * @param defaultDurationMinutes 使用者講了幾點、但沒講多久時的事件長度。
 *                               <b>兩條路共用</b>：同一筆行程不該因為使用者有沒有授權，
 *                               就變成不同長度的事件
 */
@Validated
@ConfigurationProperties(prefix = "svra.calendar")
public record CalendarProperties(
        List<String> oauthUserIds,
        String clientId,
        String clientSecret,
        String refreshToken,
        String calendarId,
        @Positive int defaultDurationMinutes) {

    public CalendarProperties {
        // null 與空清單是同一件事（「沒有人走 OAuth」），但只有後者不用到處防 null。
        //
        // 🔴 順手清掉空白與空成員，而那不是防禦性程式碼：
        // 這一欄的來源是 .env 裡一行手打的逗號分隔字串，而 `U123, ,U456,` 這種寫法
        // 會綁出一個空字串成員。它永遠對不上任何 userId，所以功能上無害——
        // <b>但它會讓「白名單是空的」變成 false</b>，於是一個完全正確的
        // 純連結部署會被自己的驗證擋在啟動階段，而錯誤訊息說的是「憑證沒填齊」。
        // 一個多餘的逗號，症狀卻指向另一件事。
        oauthUserIds = oauthUserIds == null ? List.of()
                : oauthUserIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::trim)
                        .filter(id -> !id.isEmpty())
                        .toList();
    }

    /** 走 OAuth 的使用者。用 Set 是因為每張卡片都要查一次。 */
    Set<String> oauthUsers() {
        return Set.copyOf(oauthUserIds);
    }

    /**
     * 訊息寫得像給人看的，因為它就是給人看的——啟動失敗時這句話會出現在
     * 一整頁 Spring 的綁定例外中間，寫「must not be blank」等於沒說。
     */
    @AssertTrue(message = "svra.calendar.oauth-user-ids 有人，"
            + "但 client-id / client-secret / refresh-token / calendar-id 沒填齊。"
            + "白名單裡的人會按到一顆註定失敗的按鈕——"
            + "請跑 deploy/google-calendar-auth.py，或把白名單清空讓所有人走連結。")
    boolean isOauthConfiguredWhenWhitelisted() {
        return oauthUserIds.isEmpty()
                || (notBlank(clientId) && notBlank(clientSecret)
                        && notBlank(refreshToken) && notBlank(calendarId));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
