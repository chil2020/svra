package io.svra.notify;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.svra.line.LinePushClient;
import io.svra.note.Note;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteItemRepository;
import io.svra.note.NoteRepository;

/**
 * 把清單排版後送回 LINE。
 *
 * <p>訊息是 Flex 卡片而不是純文字，因為每一筆有時間的項目上都要有一顆
 * 「加入行事曆」的按鈕，而 <b>quick reply 只活到下一則訊息</b>——
 * 使用者常常接著就講下一句，那顆按鈕就沒了。卡片裡的按鈕會一直留在訊息上，
 * 捲回去還能按（決策 26）。
 *
 * <p>代價是純文字訊息可以長按複製，Flex 不行。這是永久失去的能力，不是暫時的取捨。
 */
@Service
public class NoteNotifier {

    private static final Logger log = LoggerFactory.getLogger(NoteNotifier.class);

    /**
     * 抬頭與結尾。四種情境用四組，因為<b>使用者問的是什麼，決定了回覆該像什麼</b>。
     *
     * <p>🔴 <b>每一句結尾都必須說「回覆這則訊息」，而那不是文案偏好。</b>
     *
     * <p>編號只在<b>那則訊息的錨點</b>裡有意義。使用者引用它，指令就對得回同一批；
     * 而<b>不引用、直接打字</b>的話，解析會退回「這位使用者目前所有的項目」——
     * 那是另一份清單，「第二筆」指到的是別的東西，<b>而且會確實地執行</b>。
     *
     * <p>指令回覆與匯入回覆這兩張卡尤其危險：它們列的是<b>那一批</b>，不是整體清單，
     * 兩者幾乎不可能剛好一樣。
     *
     * <p>這幾句原本寫的是「直接回覆就可以修改」——理由是「少一個步驟，
     * 而使用者常常接著就講下一句」。那個觀察沒錯，錯的是結論：
     * <b>我們把一個會讓人做錯事的捷徑，寫成了指示。</b>
     * 由 {@code CardFooterTest} 守著，別再改回去。
     */
    static final String HEAD_EXTRACTED = "📝 已整理好你的語音筆記";
    static final String FOOT_EXTRACTED = "回覆這則訊息就可以修改或刪除";
    static final String HEAD_CURRENT = "📋 目前還有這些";
    static final String FOOT_CURRENT = "回覆這則訊息就可以修改或刪除";
    static final String HEAD_UPDATED = "✅ 已更新";
    static final String FOOT_UPDATED = "回覆這則訊息就可以繼續修改";
    static final String HEAD_SYNCED = "📅 已加入 Google 行事曆";
    static final String FOOT_SYNCED = "回覆這則訊息改時間，行事曆會跟著更新";

    private final NoteRepository noteRepository;
    private final NoteExtractionRepository extractionRepository;
    private final NoteItemRepository itemRepository;
    private final LinePushClient pushClient;
    private final MessageAnchors anchors;
    private final CardRenderer renderer;

    public NoteNotifier(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            NoteItemRepository itemRepository,
            LinePushClient pushClient,
            MessageAnchors anchors,
            CardRenderer renderer) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.itemRepository = itemRepository;
        this.pushClient = pushClient;
        this.anchors = anchors;
        this.renderer = renderer;
    }

    /** 抽取完成後的推播。 */
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

        CardRenderer.Rendered card = renderer.render(extraction.getOrderedItems(),
                note.getLineUserId(), HEAD_EXTRACTED, FOOT_EXTRACTED, List.of());
        String messageId = pushClient.pushFlex(note.getLineUserId(), card.altText(), card.flexJson());
        // 記下當時的編號順序，使用者引用這則訊息下指令時才對得回同一批。
        // 交易在推播成功後才提交——推播失敗就整筆回滾，由 outbox 重試。
        anchors.record(messageId, card.cardId(), note.getLineUserId(), card.itemIds());
    }

    /**
     * 指令執行完之後的回覆：<b>調整後的清單本身</b>。
     *
     * <p>不列「刪了什麼、改了什麼」——使用者要的是那則訊息的新版本，
     * 而清單本身就是最好的確認：他可以直接看到結果對不對，不用照著一段變更說明反推。
     */
    public PushTextPayload updatedCard(String lineUserId, List<NoteItem> items,
            List<String> notices) {
        return card(lineUserId, items, HEAD_UPDATED, FOOT_UPDATED, notices);
    }

    /**
     * 使用者問「現在有什麼行程」時的回覆。
     *
     * <p>抬頭要跟推播不一樣——共用同一句「已整理好你的語音筆記」的話，
     * 使用者問的是現況、收到的卻像是剛處理完一則語音，答非所問。
     *
     * <p>結尾跟其他三種一樣要說「回覆這則訊息」。<b>就算這張卡列的剛好是整體清單，
     * 也不能因此鼓勵直接打字</b>——「剛好一樣」不是保證，
     * 而使用者學到的是一個在別張卡上會出錯的習慣。理由見上面的常數。
     */
    public PushTextPayload currentCard(String lineUserId, List<NoteItem> items,
            List<String> notices) {
        return card(lineUserId, items, HEAD_CURRENT, FOOT_CURRENT, notices);
    }

    /**
     * 匯入行事曆之後的回覆：<b>同一份清單的新版本</b>。
     *
     * <p>不回「已加入 N 筆」的說明，理由跟指令回覆一樣——清單本身就是確認。
     * 而且新卡片上那幾顆按鈕會變成「已加入」，狀態一眼看得到。
     *
     * <p><b>LINE 送出去的訊息改不了</b>，所以舊那張卡上的按鈕仍然寫著「加入行事曆」。
     * 那不會造成問題（再按一次是冪等的），但也代表狀態只在<b>新訊息</b>上是準的。
     *
     * @param cardId 使用者按的是哪一張卡；對不上時退成一句純文字確認
     */
    @Transactional(readOnly = true)
    public PushTextPayload calendarSyncedCard(String lineUserId, String cardId) {
        List<Long> itemIds = anchors.itemIdsForCard(cardId).orElse(null);
        if (itemIds == null) {
            log.warn("匯入完成但找不到卡片錨點，退成純文字確認：cardId={}", cardId);
            return PushTextPayload.plain(lineUserId, HEAD_SYNCED);
        }
        return card(lineUserId, itemRepository.findAllById(itemIds),
                HEAD_SYNCED, FOOT_SYNCED, List.of());
    }

    /**
     * 一張卡片的完整內容，含錨點要用的編號順序。
     *
     * <p>🔴 <b>排版在這裡做完，不留到推播的時候。</b>這則訊息該長什麼樣取決於
     * 呼叫端那個交易提交當下的資料；留給 poller 兩秒後再渲染的話，
     * 中間插進一則語音就會讓回覆變成別的東西。理由寫在 {@link PushTextPayload}。
     */
    private PushTextPayload card(String lineUserId, List<NoteItem> items,
            String heading, String footer, List<String> notices) {
        CardRenderer.Rendered rendered =
                renderer.render(items, lineUserId, heading, footer, notices);
        return PushTextPayload.card(lineUserId, rendered.altText(), rendered.itemIds(),
                rendered.cardId(), rendered.flexJson());
    }
}
