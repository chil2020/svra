package io.svra.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** whisper worker 回傳的轉錄結果。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscribeResult(
        @JsonProperty("job_id") String jobId,
        String status,
        String text,
        String language,
        @JsonProperty("audio_duration_sec") Float audioDurationSec,
        @JsonProperty("elapsed_sec") Float elapsedSec,
        String model) {

    public boolean isCompleted() {
        return "completed".equals(status);
    }
}
