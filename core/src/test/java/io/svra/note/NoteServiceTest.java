package io.svra.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;

import tools.jackson.databind.ObjectMapper;

import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用 mock 驗的是「repository 說已經有了的時候，這一層怎麼反應」。
 *
 * <p>⚠️ <b>這一組測試守不住冪等。</b>它曾經讓 mock 拋出
 * {@code DataIntegrityViolationException} 並斷言「有 catch 住」，全綠了好幾個月——
 * 而真實情況是唯一鍵衝突會把交易標成 rollback-only，catch 住也沒用，
 * commit 時照樣拋 {@code UnexpectedRollbackException} 給 webhook。
 * <b>mock 只證明得了呼叫端會處理某個例外，證明不了那個例外底下發生了什麼。</b>
 *
 * <p>真正在守這件事的是 {@code IdempotencyIntegrationTest}（跑真的 PostgreSQL）。
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
    @DisplayName("第一次收到 → 回傳 true")
    void firstDeliveryCreatesNote() {
        when(noteRepository.insertPendingIfAbsent(USER_ID, MESSAGE_ID)).thenReturn(1);

        boolean created = noteService.recordIncoming(USER_ID, MESSAGE_ID);

        assertThat(created).isTrue();
    }

    @Test
    @DisplayName("重複投遞 → insert 被資料庫吞掉（回 0），回傳 false")
    void duplicateDeliveryReturnsFalse() {
        when(noteRepository.insertPendingIfAbsent(USER_ID, MESSAGE_ID)).thenReturn(0);

        boolean created = noteService.recordIncoming(USER_ID, MESSAGE_ID);

        assertThat(created).isFalse();
    }

    @Test
    @DisplayName("重複投遞不可以把例外往外拋——拋出去會讓 webhook 回 500，LINE 就會再重送")
    void duplicateDeliveryDoesNotPropagateException() {
        when(noteRepository.insertPendingIfAbsent(USER_ID, MESSAGE_ID)).thenReturn(0);

        assertThatCode(() -> noteService.recordIncoming(USER_ID, MESSAGE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("第一次收到時，同一個交易裡也要寫下 outbox 事件，且帶著冪等鍵")
    void firstDeliveryAlsoWritesOutbox() {
        when(noteRepository.insertPendingIfAbsent(USER_ID, MESSAGE_ID)).thenReturn(1);

        noteService.recordIncoming(USER_ID, MESSAGE_ID);

        verify(outboxRepository).insertIfAbsent(
                eq(MESSAGE_ID),
                eq(NoteService.EVENT_TRANSCRIBE_REQUESTED),
                // 事件要帶著使用者，否則「這個人做過什麼」只能靠掃 payload 的 JSON
                eq(USER_ID),
                contains(MESSAGE_ID),
                eq(OutboxEvent.dedupeKeyFor(NoteService.EVENT_TRANSCRIBE_REQUESTED, MESSAGE_ID)));
    }

    @Test
    @DisplayName("重複投遞時不可以再寫 outbox，否則會重複發任務")
    void duplicateDeliveryDoesNotWriteOutbox() {
        when(noteRepository.insertPendingIfAbsent(USER_ID, MESSAGE_ID)).thenReturn(0);

        noteService.recordIncoming(USER_ID, MESSAGE_ID);

        verify(outboxRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    // ── U7：套用轉錄結果 ──────────────────────────────────────────



    @Test
    @DisplayName("收到結果 → 補上內容並轉為 COMPLETED")
    void resultCompletesTheNote() {
        Note note = Note.pending(USER_ID, MESSAGE_ID);
        when(noteRepository.findBySourceMessageId(MESSAGE_ID)).thenReturn(of(note));

        noteService.applyTranscription(MESSAGE_ID, "記得繳電費", "zh", 3.2f);

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

        noteService.applyTranscription(MESSAGE_ID, "第二次的結果", "zh", 3.2f);

        assertThat(note.getTranscript()).isEqualTo("第一次的結果");
    }

    @Test
    @DisplayName("找不到 note → 不丟例外（丟出去會讓訊息 requeue 成無限迴圈）")
    void missingNoteDoesNotThrow() {
        when(noteRepository.findBySourceMessageId(MESSAGE_ID)).thenReturn(empty());

        assertThatCode(() -> noteService.applyTranscription(MESSAGE_ID, "內容", "zh", 3.2f))
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
