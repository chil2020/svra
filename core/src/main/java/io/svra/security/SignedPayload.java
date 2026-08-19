package io.svra.security;

/**
 * 從 HTTP 層抽出來、還沒被驗證的憑據。
 *
 * <p>兩個欄位都可能是 null／空的——「有沒有帶簽章」「body 讀不讀得到」都是
 * {@link LineSignatureAuthenticationProvider} 要判斷的事。filter 只負責搬運，
 * 不負責判斷：這是 Spring Security 把 filter 與 provider 拆開的用意。
 *
 * @param signature X-Line-Signature 標頭，沒帶就是 null
 * @param body      原始 body 位元組，讀取失敗就是 null
 */
record SignedPayload(String signature, byte[] body) {
}
