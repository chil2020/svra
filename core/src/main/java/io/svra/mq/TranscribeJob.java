package io.svra.mq;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 送給 whisper worker 的轉錄任務。欄位名必須是 snake_case——
 * 對端是 Python，契約寫在 whisper-worker/main.py 的 docstring。
 *
 * @param jobId        用 LINE 的 message id，結果回來時據此找回那筆 note
 * @param languageHint 傳 null 讓 whisper 自動偵測
 */
public record TranscribeJob(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("audio_file") String audioFile,
        @JsonProperty("language_hint") String languageHint) {
}
