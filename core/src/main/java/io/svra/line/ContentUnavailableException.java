package io.svra.line;

/**
 * 那則訊息的內容在 LINE 上已經沒了。
 *
 * <p>LINE 的文件寫著「Content that users send is automatically deleted after a
 * certain period of time」——<b>而它沒有公布那段時間有多長</b>。所以這件事不是
 * 「會不會發生」，是「什麼時候發生」：只要下載那一步卡住夠久
 * （poller 停了、RabbitMQ 掛了、機器關機一晚），檔案就會消失。
 *
 * <p>要跟其他下載失敗分開，因為<b>能做的事完全不同</b>：連線失敗重試就會好，
 * 而檔案沒了重試一萬次也不會回來——那時唯一有用的回應是<b>告訴使用者重傳一次</b>。
 * 一律當成「轉錄失敗」的話，他會以為是我們的模型不行，然後放棄。
 */
public class ContentUnavailableException extends RuntimeException {

    public ContentUnavailableException(String message) {
        super(message);
    }
}
