package io.svra.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;
import org.springframework.dao.DataIntegrityViolationException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.svra.mq.TranscribeResult;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用 mock 讓 repository 拋出唯一鍵衝突，驗證的是「衝突時程式怎麼反應」。
 * 「資料庫真的會擋下第二筆」靠 V1__init.sql 的 UNIQUE 約束 + ddl-auto=validate。
 * 更完整的做法是 Testcontainers 跑整合測試，目前沒做（相依與 CI 時間的取捨）。
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceTest {

    private static final String USER_ID = "U4af4980629";
    private static final String MESSAGE_ID = "325708";

    @Mock
    private NoteRepository noteRepository;

    @Mock
    private OutboxEventRepository outboxRepository;

    /** payload 序列化用真的，mock 掉的話就測不到 payload 是否組得出來。 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private NoteService noteService;

    @Test
    @DisplayName("第一次收到 → 建立 note，回傳 true")
    void firstDeliveryCreatesNote() {
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean created = noteService.recordIncoming(USER_ID, MESSAGE_ID);

        assertThat(created).isTrue();
        verify(noteRepository, times(1)).save(any(Note.class));
    }

    @Test
    @DisplayName("重複投遞 → 唯一鍵衝突，回傳 false")
    void duplicateDeliveryReturnsFalse() {
        when(noteRepository.save(any(Note.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notes_source_message_id"));

        boolean created = noteService.recordIncoming(USER_ID, MESSAGE_ID);

        assertThat(created).isFalse();
    }

    @Test
    @DisplayName("重複投遞不可以把例外往外拋——拋出去會讓 webhook 回 500，LINE 就會再重送")
    void duplicateDeliveryDoesNotPropagateException() {
        when(noteRepository.save(any(Note.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notes_source_message_id"));

        assertThatCode(() -> noteService.recordIncoming(USER_ID, MESSAGE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("建立的 note 狀態是 PENDING，且帶著正確的 message id")
    void createdNoteIsPendingWithSourceMessageId() {
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> {
            Note saved = inv.getArgument(0);
            assertThat(saved.getSourceMessageId()).isEqualTo(MESSAGE_ID);
            assertThat(saved.getLineUserId()).isEqualTo(USER_ID);
            assertThat(saved.getStatus()).isEqualTo(NoteStatus.PENDING);
            return saved;
        });

        noteService.recordIncoming(USER_ID, MESSAGE_ID);

        verify(noteRepository).save(any(Note.class));
    }

    @Test
    @DisplayName("第一次收到時，同一個交易裡也要寫下 outbox 事件")
    void firstDeliveryAlsoWritesOutbox() {
        when(noteRepository.save(any(Note.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any(OutboxEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        noteService.recordIncoming(USER_ID, MESSAGE_ID);

        verify(outboxRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("重複投遞時不可以再寫 outbox，否則會重複發任務")
    void duplicateDeliveryDoesNotWriteOutbox() {
        when(noteRepository.save(any(Note.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notes_source_message_id"));

        noteService.recordIncoming(USER_ID, MESSAGE_ID);

        verify(outboxRepository, never()).save(any(OutboxEvent.class));
    }

    // ── U7：套用轉錄結果 ──────────────────────────────────────────

    private static TranscribeResult completedResult(String text) {
        return new TranscribeResult(MESSAGE_ID, "completed", text, "zh", 3.2f, 1.8f, "small");
    }

    @Test
    @DisplayName("收到結果 → 補上內容並轉為 COMPLETED")
    void resultCompletesTheNote() {
        Note note = Note.pending(USER_ID, MESSAGE_ID);
        when(noteRepository.findBySourceMessageId(MESSAGE_ID)).thenReturn(of(note));

        noteService.applyTranscription(completedResult("記得繳電費"));

        assertThat(note.getStatus()).isEqualTo(NoteStatus.COMPLETED);
        assertThat(note.getTranscript()).isEqualTo("記得繳電費");
        assertThat(note.getLanguage()).isEqualTo("zh");
        assertThat(note.getAudioDurationSec()).isEqualTo(3.2f);
    }

    @Test
    @DisplayName("已經是 COMPLETED → 不覆蓋，第一個結果為準")
    void duplicateResultDoesNotOverwrite() {
        Note note = Note.pending(USER_ID, MESSAGE_ID);
        note.complete("第一次的結果", "zh", 3.2f);
        when(noteRepository.findBySourceMessageId(MESSAGE_ID)).thenReturn(of(note));

        noteService.applyTranscription(completedResult("第二次的結果"));

        assertThat(note.getTranscript()).isEqualTo("第一次的結果");
    }

    @Test
    @DisplayName("找不到 note → 不丟例外（丟出去會讓訊息 requeue 成無限迴圈）")
    void missingNoteDoesNotThrow() {
        when(noteRepository.findBySourceMessageId(MESSAGE_ID)).thenReturn(empty());

        assertThatCode(() -> noteService.applyTranscription(completedResult("內容")))
                .doesNotThrowAnyException();
    }

    // ── outbox 耗盡重試時的收尾 ────────────────────────────────────

    @Test
    @DisplayName("outbox 放棄時，note 要標成 FAILED，不能留在 PENDING")
    void markTranscriptionFailedSetsNoteFailed() {
        Note note = Note.pending(USER_ID, MESSAGE_ID);
        when(noteRepository.findBySourceMessageId(MESSAGE_ID)).thenReturn(of(note));

        noteService.markTranscriptionFailed(MESSAGE_ID);

        assertThat(note.getStatus()).isEqualTo(NoteStatus.FAILED);
    }
}
