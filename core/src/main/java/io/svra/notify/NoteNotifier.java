package io.svra.notify;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.svra.line.LinePushClient;
import io.svra.note.Note;
import io.svra.note.NoteRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteCategory;

/** 把抽取結果排版後推回 LINE。 */
@Service
public class NoteNotifier {

    private static final Logger log = LoggerFactory.getLogger(NoteNotifier.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.TAIWAN);

    private static final Map<NoteCategory, String> HEADINGS = Map.of(
            NoteCategory.SCHEDULE, "🗓 行程",
            NoteCategory.TODO, "✅ 待辦",
            NoteCategory.IDEA, "💡 想法");

    /** 決定區塊順序：有時間的排前面，想法放最後。 */
    // 顯示順序由 NoteCategory 統一定義——這裡再維護一份，就會跟指令解析那邊漂移。

    private final NoteRepository noteRepository;
    private final NoteExtractionRepository extractionRepository;
    private final LinePushClient pushClient;

    public NoteNotifier(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            LinePushClient pushClient) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.pushClient = pushClient;
    }

    @Transactional
    public void notifyFor(String sourceMessageId) {
        Note note = noteRepository.findBySourceMessageId(sourceMessageId).orElse(null);
        if (note == null) {
            log.error("要推播但找不到 note：messageId={}", sourceMessageId);
            return;
        }

        NoteExtraction extraction = extractionRepository
                .findByNoteIdAndActiveTrue(note.getId()).orElse(null);
        if (extraction == null || extraction.getItems().isEmpty()) {
            log.warn("沒有生效的抽取結果，跳過推播：messageId={}", sourceMessageId);
            return;
        }

        String messageId = pushClient.pushText(note.getLineUserId(), render(extraction.getOrderedItems()));
        // 記下來，使用者引用這則訊息下指令時才對應得回這批項目。
        // 交易在推播成功後才提交——推播失敗就整筆回滾，由 outbox 重試。
        extraction.recordNotified(messageId);
    }

    /** 供推播與「列出行程」共用——兩邊必須是同一份編號。 */
    public static String render(List<NoteItem> items) {
        Map<NoteCategory, List<NoteItem>> byCategory = items.stream()
                .sorted(NoteCategory.itemOrder())
                .collect(Collectors.groupingBy(NoteItem::getCategory));

        StringBuilder sb = new StringBuilder("📝 已整理好你的語音筆記\n");
        int index = 0;
        for (NoteCategory category : NoteCategory.DISPLAY_ORDER) {
            List<NoteItem> group = byCategory.get(category);
            if (group == null || group.isEmpty()) {
                continue;
            }
            sb.append('\n').append(HEADINGS.get(category)).append('\n');
            for (NoteItem item : group) {
                // 編號讓使用者能說「第二筆」。跨分類連續編，不是各分類重新數。
                sb.append(++index).append(". ");
                // 時間放標題前面：掃視一串行程時，先找的是時間不是標題。
                if (item.getOccursAt() != null) {
                    sb.append(WHEN.format(item.getOccursAt().atZone(ZONE))).append('\n').append("　 ");
                }
                sb.append(item.getTitle()).append('\n');
            }
        }
        return sb.append("\n引用這則訊息即可修改或刪除").toString();
    }
}
