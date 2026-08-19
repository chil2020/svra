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
        noteService.markTranscriptionFailed(sourceMessageId).ifPresent(
                lineUserId -> pushClient.pushText(lineUserId,
                        "⚠️ 這段語音我轉錄失敗了，重試多次都沒成功。可以再傳一次嗎？"));
    }
}
