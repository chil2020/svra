package io.svra.user;

/**
 * 一個使用者的行事曆授權，token 已經解密。
 *
 * <p>🔴 <b>toString 一定要覆寫。</b>record 會自動生成一個把所有欄位印出來的
 * toString，而這個型別的第一個欄位是 refresh token。任何一句
 * {@code log.debug("授權={}", auth)}、任何一個把它放進訊息的例外，
 * 都會把「這個人的行事曆永久寫入權」寫進 log 檔——
 * <b>而那正是加密要防的那份外洩，只是換了個檔案。</b>
 *
 * <p>加密只保護資料庫。這一行保護的是資料庫以外的所有地方。
 */
public record GoogleAuthorization(String refreshToken, String calendarId, String scope) {

    @Override
    public String toString() {
        return "GoogleAuthorization[calendarId=" + calendarId + ", scope=" + scope
                + ", refreshToken=(已隱藏)]";
    }
}
