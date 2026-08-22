package io.svra.mq;

import org.springframework.stereotype.Component;

import io.svra.line.LinePushClient;
import io.svra.note.NoteService;

/**
 * 轉錄放棄時的收尾：讓 note 有終局，並讓使用者知道。
 *
 * <p>放棄有<b>兩條路</b>，而且它們失敗在不同的地方：
 *
 * <ul>
 * <li>outbox 重試耗盡——任務<b>根本沒送出去</b>（音檔下載不到、RabbitMQ 連不上）
 * <li>worker 把任務丟進 DLQ——任務<b>送出去了但做不完</b>（音檔解不開、模型爆了）
 * </ul>
 *
 * <p>第二條路一度是死路：DLQ 有宣告、有綁定，但沒有任何消費者。訊息躺在裡面，
 * 而此時 outbox 事件早已標成 SENT——重試機制完全碰不到它，note 就永遠停在 PENDING。
 * <b>「有 DLQ」和「有補償」是兩件事</b>：死信佇列只保證訊息不被丟掉，
 * 不保證有人會去看。
 */
@Component
class TranscriptionFailureReporter {

    private final NoteService noteService;
    private final LinePushClient pushClient;

    TranscriptionFailureReporter(NoteService noteService, LinePushClient pushClient) {
        this.noteService = noteService;
        this.pushClient = pushClient;
    }

    /**
     * 兩條路都呼叫這裡。{@code markTranscriptionFailed} 只有在真的從 PENDING
     * 轉成 FAILED 時才回傳使用者，所以兩條路先後抵達同一筆時只會通知一次。
     */
    void report(String sourceMessageId) {
        notify(sourceMessageId, "⚠️ 這段語音我轉錄失敗了，重試多次都沒成功。可以再傳一次嗎？");
    }

    /**
     * 音檔在 LINE 上已經被刪掉了——這不是我們轉錄不出來。
     *
     * <p>LINE 沒有公布使用者傳的檔案保留多久，只說「會在一段時間後自動刪除」。
     * 所以只要下載那一步卡住夠久（poller 停了、機器關機一晚），檔案就會消失。
     *
     * <p><b>訊息一定要跟轉錄失敗分開。</b>講成「轉錄失敗」的話，使用者會以為是
     * 我們的模型不行然後放棄；而實情是他只要重傳一次就好，
     * 而且那則語音<b>還在他的聊天室裡</b>。
     */
    void reportContentGone(String sourceMessageId) {
        notify(sourceMessageId, "⚠️ 這段語音在 LINE 上已經過期了，我抓不到檔案。\n"
                + "往上滑找到那則語音，長按 →「轉傳」給我一次就可以了。");
    }

    private void notify(String sourceMessageId, String text) {
        noteService.markTranscriptionFailed(sourceMessageId)
                .ifPresent(lineUserId -> pushClient.pushText(lineUserId, text));
    }
}
