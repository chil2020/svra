package io.svra.calendar;

import io.svra.outbox.OutboxPermanentFailureException;

/**
 * 授權沒了：refresh token 被撤銷、Google 帳號改過密碼，或 consent screen
 * 還停在 Testing 而 Google 在七天後把它收走了。
 *
 * <p>它是永久性失敗的一種，但<b>要跟其他永久性失敗分得開</b>——
 * 因為使用者要做的事完全不同：這一種要人去重跑授權腳本，
 * 其他的（行事曆被刪、權限不足）他自己在 Google 那邊處理。
 *
 * <p>用型別而不是比對訊息字串：訊息是給人看的，改一個字就會讓判斷失效，
 * 而失效的症狀是「使用者收到一句幫不上忙的『同步失敗』」——
 * 那正是決策 26 想避免的東西。
 */
class CalendarAuthorizationException extends OutboxPermanentFailureException {

    /** Google token endpoint 回的 error 代碼。不是從那條路來的就是 {@code null}。 */
    private final String reason;

    CalendarAuthorizationException(String message) {
        this(null, message);
    }

    CalendarAuthorizationException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * 這個<b>使用者的</b> refresh token 確定沒了嗎？決定要不要把那一列標記撤銷。
     *
     * <p>🔴 <b>只有 token endpoint 的 {@code invalid_grant} 算數。</b>
     * {@code invalid_client} 明確不算——那是應用程式的 client id/secret 壞了，
     * 影響的是每一個人，而每個人的 refresh token 其實都還是好的。拿它去標記撤銷，
     * 會把一次部署設定失誤放大成「所有人都得重跑授權腳本」。
     *
     * <p>Calendar API 那側的 401（見 {@link GoogleCalendarClient} 的 classify）
     * 也不算：那裡沒有 token endpoint 的判斷，證據不夠強。
     * <b>誤判的代價不對稱</b>——少標記一次，下次匯入再發現；多標記一次，
     * 是逼一個授權好好的人去重跑腳本。所以預設（reason 為 null）是不撤銷。
     */
    boolean userGrantIsDead() {
        return "invalid_grant".equals(reason);
    }
}
