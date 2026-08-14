package io.svra.extract;

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
    private static final List<NoteCategory> ORDER =
            List.of(NoteCategory.SCHEDULE, NoteCategory.TODO, NoteCategory.IDEA);

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

    @Transactional(readOnly = true)
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

        pushClient.pushText(note.getLineUserId(), render(extraction.getItems()));
    }

    static String render(List<NoteItem> items) {
        Map<NoteCategory, List<NoteItem>> byCategory = items.stream()
                .collect(Collectors.groupingBy(NoteItem::getCategory));

        StringBuilder sb = new StringBuilder("📝 已整理好你的語音筆記\n");
        for (NoteCategory category : ORDER) {
            List<NoteItem> group = byCategory.get(category);
            if (group == null || group.isEmpty()) {
                continue;
            }
            sb.append('\n').append(HEADINGS.get(category)).append('\n');
            for (NoteItem item : group) {
                sb.append("・").append(item.getTitle()).append('\n');
                if (item.getOccursAt() != null) {
                    sb.append("　　").append(WHEN.format(item.getOccursAt().atZone(ZONE))).append('\n');
                }
            }
        }
        return sb.toString().stripTrailing();
    }
}
