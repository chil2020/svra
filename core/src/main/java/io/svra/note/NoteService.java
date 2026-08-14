package io.svra.note;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 筆記的應用層邏輯。
 *
 * <p>🔴 <b>這是承重點②（冪等去重），由本人親手實作。</b>
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);
    
    private final NoteRepository noteRepository;

    public NoteService(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    /**
     * 記錄一則剛收到的語音訊息。
     *
     * <p><b>冪等契約</b>：同一個 {@code sourceMessageId} 不論被呼叫幾次，
     * 資料庫都只會有一筆 note。LINE 的 webhook 是 at-least-once——
     * 我們沒有在時限內回 200（處理太久、網路抖動、正在部署）時它就會重送，
     * 所以重複投遞是必然會發生的正常情況，不是例外狀況。
     *
     * @param lineUserId      LINE 使用者 ID
     * @param sourceMessageId LINE 訊息 ID（冪等的鍵）
     * @return {@code true} = 這是第一次收到，已建立新的 note；
     *         {@code false} = 重複投遞，先前已處理過，本次不做任何事
     */
    public boolean recordIncoming(String lineUserId, String sourceMessageId) {

        Note note = Note.pending(lineUserId, sourceMessageId);

        try {
            noteRepository.save(note);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 唯一鍵衝突 = LINE 重送了同一則訊息。這是正常情況，不是錯誤。
            //
            // ⚠️ 只捕捉這一個例外，不要用 catch (Exception)：
            // 連線失敗、欄位超長、NPE 都會被誤判成「已處理過」而回 200，
            // LINE 就不會重送 —— 訊息永久遺失，而且不會有人發現。
            // 其他例外讓它往外拋，webhook 回 500，LINE 才會重送。
            log.debug("重複訊息，已忽略：messageId={}", sourceMessageId);
            return false;
        }
    }
}
