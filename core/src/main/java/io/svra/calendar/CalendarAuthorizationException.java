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

    CalendarAuthorizationException(String message) {
        super(message);
    }
}
