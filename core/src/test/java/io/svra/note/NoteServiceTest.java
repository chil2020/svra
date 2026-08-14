package io.svra.note;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * U8 冪等去重的驗收測試——你的目標是讓這四條變綠。
 *
 * <p><b>現在跑一定是紅的（NoteService 還是 throw UnsupportedOperationException），
 * 這是 TDD 的正常狀態。</b>
 *
 * <p><b>測試策略的取捨（面試可能被問）：</b>
 * 這裡用 mock 讓 repository 拋出 {@link DataIntegrityViolationException}，
 * 驗證的是「<b>發生唯一鍵衝突時，我的程式怎麼反應</b>」。
 * 至於「資料庫真的會擋下第二筆」這件事本身，靠的是 {@code V1__init.sql} 的
 * UNIQUE 約束 ＋ {@code ddl-auto=validate}（啟動時檢查 entity 與 schema 一致）。
 *
 * <p>更完整的做法是用 Testcontainers 起一個真的 PostgreSQL 跑整合測試，
 * 那樣連「約束確實存在」都會被測到。目前沒做，理由是相依與 CI 時間的取捨——
 * <b>這個取捨要講得出來，不要假裝沒有這個缺口。</b>
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceTest {

    private static final String USER_ID = "U4af4980629";
    private static final String MESSAGE_ID = "325708";

    @Mock
    private NoteRepository noteRepository;

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
}
