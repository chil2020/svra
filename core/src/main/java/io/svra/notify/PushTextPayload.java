package io.svra.notify;

/**
 * 「把這段文字推給這個人」。
 *
 * <p>刻意不帶任何領域資訊（note id、指令內容、抽取版本）：這個事件表達的就只是
 * 一次推播。誰要推、推什麼由產生事件的模組決定，notify 只負責送到——
 * 否則每加一種要回覆的情境，這裡就要多認識一個模組。
 */
public record PushTextPayload(String lineUserId, String text) {
}
