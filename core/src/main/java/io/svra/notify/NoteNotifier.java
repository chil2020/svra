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
    private final MessageAnchors anchors;

    public NoteNotifier(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            LinePushClient pushClient,
            MessageAnchors anchors) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.pushClient = pushClient;
        this.anchors = anchors;
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

        List<NoteItem> items = extraction.getOrderedItems();
        String messageId = pushClient.pushText(note.getLineUserId(), render(items));
        // 記下當時的編號順序，使用者引用這則訊息下指令時才對得回同一批。
        // 交易在推播成功後才提交——推播失敗就整筆回滾，由 outbox 重試。
        anchors.record(messageId, note.getLineUserId(), items.stream().map(NoteItem::getId).toList());
    }

    /** 抽取完成後推播用。 */
    public static String render(List<NoteItem> items) {
        return render(items, "📝 已整理好你的語音筆記", "\n引用這則訊息即可修改或刪除");
    }

    /**
     * 使用者問「現在有什麼行程」時的回覆。
     *
     * <p>抬頭要跟推播不一樣——共用同一句「已整理好你的語音筆記」的話，
     * 使用者問的是現況、收到的卻像是剛處理完一則語音，答非所問。
     *
     * <p>結尾不承諾「引用這則」而是說「直接回覆」：兩種都可以，
     * 但後者少一個步驟，而使用者常常是接著就講下一句。
     */
    public static String renderCurrent(List<NoteItem> items) {
        return render(items, "📋 目前還有這些", "\n直接回覆就可以修改或刪除");
    }

    /**
     * 指令執行完之後的回覆：<b>調整後的清單本身</b>。
     *
     * <p>不列「刪了什麼、改了什麼」——使用者要的是那則訊息的新版本，
     * 而清單本身就是最好的確認：他可以直接看到結果對不對，不用照著一段變更說明反推。
     */
    public static String renderUpdated(List<NoteItem> items) {
        return render(items, "✅ 已更新", "\n直接回覆就可以繼續修改");
    }

    /** 供推播與「列出行程」共用——兩邊必須是同一份編號。 */
    private static String render(List<NoteItem> items, String heading, String footer) {
        if (items.isEmpty()) {
            // 只有抬頭跟結尾的訊息看起來像壞掉了。空清單是正常狀態，要講出來。
            return heading + "\n\n（目前沒有任何項目）";
        }

        Map<NoteCategory, List<NoteItem>> byCategory = items.stream()
                .sorted(NoteCategory.itemOrder())
                .collect(Collectors.groupingBy(NoteItem::getCategory));

        StringBuilder sb = new StringBuilder(heading).append('\n');
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
        return sb.append(footer).toString();
    }
}
