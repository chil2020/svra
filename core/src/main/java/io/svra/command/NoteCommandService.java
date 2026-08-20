package io.svra.command;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.llm.LlmRateLimiter;
import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteItemRepository;
import io.svra.note.NoteRepository;
import io.svra.note.NoteService;
import io.svra.notify.MessageAnchors;
import io.svra.notify.NoteNotifier;
import io.svra.notify.PushTextPayload;
import io.svra.outbox.OutboxEvent;
import io.svra.outbox.OutboxEventRepository;

/** 處理使用者用文字下的指令（刪除、改標題、改時間、新增、列出清單）。 */
@Service
public class NoteCommandService {

    private static final Logger log = LoggerFactory.getLogger(NoteCommandService.class);

    private final NoteRepository noteRepository;
    private final NoteExtractionRepository extractionRepository;
    private final NoteItemRepository itemRepository;
    private final CommandExecutionRepository executionRepository;
    private final NoteCommandParser parser;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final LlmRateLimiter rateLimiter;
    /** 解析「第三筆」要靠使用者引用的那則訊息當時的順序，而那由 notify 記著。 */
    private final MessageAnchors anchors;
    private final Clock clock;
    /** 交易邊界明寫，因為中間刻意有一段不在交易裡（見 applyCommand）。 */
    private final TransactionTemplate tx;

    public NoteCommandService(NoteRepository noteRepository,
            NoteExtractionRepository extractionRepository,
            NoteItemRepository itemRepository,
            CommandExecutionRepository executionRepository,
            Clock clock,
            NoteCommandParser parser,
            OutboxEventRepository outboxRepository,
            ObjectMapper objectMapper,
            LlmRateLimiter rateLimiter,
            MessageAnchors anchors,
            PlatformTransactionManager transactionManager) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.itemRepository = itemRepository;
        this.executionRepository = executionRepository;
        this.parser = parser;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.anchors = anchors;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 記下使用者的指令。解析要呼叫 LLM，不能拖慢 webhook——這裡只寫意圖，
     * 跟語音訊息一樣交給 outbox 非同步處理。
     */
    @Transactional
    public void recordCommand(String lineUserId, String commandMessageId,
            String text, String quotedMessageId) {
        // 🔴 指令是唯一沒有其他去重機制的入口：語音有 notes 的唯一約束擋著，
        // 文字訊息不建 note，只寫一筆 outbox。沒有冪等鍵，LINE 重送就執行兩次。
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new CommandPayload(
                    lineUserId, commandMessageId, text, quotedMessageId));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化指令 payload 失敗", e);
        }

        if (outboxRepository.insertIfAbsent(
                commandMessageId,
                NoteService.EVENT_COMMAND_REQUESTED,
                payload,
                OutboxEvent.dedupeKeyFor(
                        NoteService.EVENT_COMMAND_REQUESTED, commandMessageId)) == 0) {
            log.info("重複投遞的指令，已忽略——冪等鍵擋下（決策 2）");
        }
    }

    /** 存進 outbox 的指令內容。 */
    public record CommandPayload(
            String lineUserId,
            String commandMessageId,
            String text,
            String quotedMessageId) {
    }

    /**
     * 執行一則指令：<b>短交易讀取 → 交易外解析 → 短交易套用</b>。
     *
     * <p>中間那段不在交易裡，因為解析要呼叫 LLM，十幾秒起跳（決策 18）。
     *
     * <p>🔴 <b>必須冪等，而且不是「插入」那種冪等。</b>指令是<b>位置性</b>的：
     * 重跑時清單已經少了一筆，同樣的「第一筆」指向<b>另一個項目</b>，
     * 而且會成功、會回覆。守衛是 {@code command_executions} 的主鍵。
     */
    public void applyCommand(CommandPayload payload) {
        // ── 第一段：短交易，把解析要用的東西讀出來 ──
        Prepared prepared = tx.execute(status -> prepare(payload));
        if (prepared == null) {
            return;
        }

        // ── 第二段：沒有交易。限流與解析都不是資料庫的事 ──
        // 超過額度就往外拋，讓 outbox 的指數退避去處理。
        rateLimiter.consume(payload.lineUserId());
        NoteCommand command = parser.parse(payload.text(), prepared.items());

        // ── 第三段：短交易，執行紀錄與所有變更同進同退 ──
        tx.executeWithoutResult(status -> apply(payload, prepared, command));
    }

    /**
     * 第一段的產出。只放值不放 entity——交易一結束它就 detached 了。
     *
     * @param quoteUnresolved 使用者引用了某則訊息，但那不是我們推播的
     * @param items           清單快照，順序即使用者看到的編號順序
     */
    private record Prepared(boolean quoteUnresolved, boolean fromQuote, List<ItemSnapshot> items) {
    }

    /** @return 可以往下做時的資料；不該往下做時為 null */
    private Prepared prepare(CommandPayload payload) {
        // 先查一次只為了省掉一次 LLM 呼叫。權威的判斷在 apply()。
        if (executionRepository.existsById(payload.commandMessageId())) {
            log.info("指令已經執行過，跳過重跑（outbox 是 at-least-once）");
            return null;
        }

        // 有引用就用那則訊息當時的編號；沒引用就看「目前還有什麼」。
        List<ItemSnapshot> quoted = payload.quotedMessageId() == null ? null : quotedItems(payload);
        boolean quoteUnresolved = payload.quotedMessageId() != null && quoted == null;
        if (quoted != null) {
            return new Prepared(false, true, quoted);
        }
        // 對不上時仍用整體清單當解析的上下文（LIST／ADD 不指涉編號，照做無妨），
        // 但指名項目的動作不會執行——見 quotedItems。
        return new Prepared(quoteUnresolved, false,
                upcoming(payload.lineUserId()).stream().map(ItemSnapshot::of).toList());
    }

    /** 第三段：套用變更並回覆。整段在一個交易裡。 */
    private void apply(CommandPayload payload, Prepared prepared, NoteCommand command) {
        // 🔴 冪等判斷要跟變更同交易。寫在第一段的話，提交就宣告「做過了」而變更還沒發生，
        // 中途崩潰一次指令就靜默消失——at-most-once 比重複更難查。
        if (executionRepository.insertIfAbsent(payload.commandMessageId()) == 0) {
            log.info("指令在解析期間已被執行，放棄這次結果");
            return;
        }

        // 回覆與變更同交易寫下，由 outbox 送出（決策 3）。直接打 LINE 的話，
        // HTTP 成功而交易回滾時使用者會看到更新後的清單，但資料沒變。
        // 不填 dedupe_key：上面的執行紀錄已經是守衛，一則指令寫不出第二筆。
        Reply reply = execute(payload, prepared, command);
        outboxRepository.save(OutboxEvent.pending(
                payload.commandMessageId(),
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                toPushPayload(payload.lineUserId(), reply)));
        log.info("指令已處理：{} 個動作", command.isUnknown() ? 0 : command.ops().size());
    }

    private String toPushPayload(String lineUserId, Reply reply) {
        try {
            return objectMapper.writeValueAsString(
                    new PushTextPayload(lineUserId, reply.text(), reply.listedItemIds()));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化推播 payload 失敗", e);
        }
    }

    /**
     * 真正動手的地方。
     *
     * <p>回覆是<b>調整後的清單</b>，不是「改了什麼」的變更說明——使用者引用一則訊息
     * 要求調整，想拿回的就是那則訊息的新版本。清單本身即確認，他可以直接看結果對不對。
     * 只有<b>沒做到的事</b>才需要另外說（下面的 notices）。
     */
    private Reply execute(CommandPayload payload, Prepared prepared, NoteCommand command) {
        if (command.isUnknown()) {
            return Reply.plain("🤔 " + command.reason());
        }

        // 🔴 先把編號解析成項目，再開始動手：邊刪邊用編號取值的話，
        // 「刪掉第一筆跟第三筆」會刪錯第二筆。
        //
        // 解析經過兩層：編號 →（快照）id → 現在的那一筆。中間那層是關鍵——
        // 第一段到現在隔著一次 LLM 呼叫，拿編號重算會指到另一個項目，id 不會漂。
        Map<Long, NoteItem> byId = itemRepository.findAllById(
                prepared.items().stream().map(ItemSnapshot::id).toList())
                .stream().collect(Collectors.toMap(NoteItem::getId, Function.identity()));

        // ADD 與 LIST 不指涉任何一筆，但模型仍可能填 itemIndex——填了也不當真。
        List<NoteItem> targets = command.ops().stream()
                .map(op -> needsTarget(op.action()) ? targetFor(prepared, byId, op) : null)
                .toList();

        List<String> notices = new ArrayList<>();
        boolean quoteBlocked = false;
        // 分開記：有沒有真的動到資料，決定回覆說「已更新」還是「目前還有這些」。
        // 純 LIST 也回「已更新」的話，就是宣稱了一件沒發生的事。
        boolean changed = false;
        boolean listRequested = false;
        // 這次新增的還不在錨點裡，但它們該出現在「調整後」的清單上。
        List<NoteItem> added = new ArrayList<>();

        for (int i = 0; i < command.ops().size(); i++) {
            NoteCommand.Op op = command.ops().get(i);
            NoteItem target = targets.get(i);

            // 引用對不上時編號指的是整份清單，不是使用者眼前那則訊息。
            // 照做會「確實地做錯事」，比報錯更糟。
            if (needsTarget(op.action()) && prepared.quoteUnresolved()) {
                quoteBlocked = true;
                continue;
            }
            // 指名的那一筆在這中間被刪掉了。沉默跳過會讓使用者以為做到了（決策 17）。
            if (needsTarget(op.action()) && target == null) {
                notices.add("⚠️ 第 " + op.itemIndex() + " 筆已經不在清單上了，沒有動它");
                continue;
            }

            switch (op.action()) {
                case DELETE -> {
                    target.getExtraction().removeItem(target);
                    changed = true;
                }
                case UPDATE_TITLE -> {
                    target.rename(op.title());
                    changed = true;
                }
                case UPDATE_TIME -> {
                    target.reschedule(NoteCommandParser.parseOccursAt(op.occursAt()));
                    changed = true;
                }
                case ADD -> {
                    NoteExtraction owner = ownerForAdd(payload, prepared, byId);
                    if (owner == null) {
                        notices.add("⚠️ 還沒有任何筆記可以加進去，先傳一則語音吧。");
                        break;
                    }
                    NoteItem item = new NoteItem(
                            NoteCommandParser.parseCategory(op.category()), op.title(),
                            NoteCommandParser.parseOccursAt(op.occursAt()), null, List.of());
                    owner.addItem(item);
                    added.add(item);
                    changed = true;
                }
                // 回覆本來就是清單，不必特別處理——但要記下來，
                // 因為「只是想看」跟「什麼都沒做成」的回覆不一樣。
                case LIST -> listRequested = true;
            }
        }

        if (quoteBlocked) {
            notices.add("⚠️ 你引用的那則我對不上（可能已經被新版取代，或不是我推播的訊息）。"
                    + "那上面的編號跟我現在看到的清單不一定是同一份，所以我沒有動任何一筆。"
                    + "可以直接說內容，或引用最新那則。");
        }
        // 只做了一半就要說——不然使用者會以為都交代了。
        if (command.unhandled() != null && !command.unhandled().isBlank()) {
            notices.add("⚠️ 這部分我還不會處理：" + command.unhandled());
        }

        // 什麼都沒動、也沒要求看清單——秀一份他沒在看的清單只會更困惑，直接說原因。
        if (!changed && !listRequested) {
            return Reply.plain(notices.isEmpty()
                    ? "⚠️ 這則指令我沒能做到任何事。"
                    : String.join("\n", notices));
        }

        List<NoteItem> after = itemsAfterChange(payload, prepared, byId, added);
        String list = changed ? NoteNotifier.renderUpdated(after) : NoteNotifier.renderCurrent(after);
        String text = notices.isEmpty() ? list : String.join("\n", notices) + "\n\n" + list;
        return new Reply(text, after.stream().map(NoteItem::getId).toList());
    }

    /**
     * 調整後要秀哪一份清單，以及它就是新訊息的錨點。
     *
     * <p>有引用就秀<b>那則訊息的新版本</b>：錨點裡還活著的，加上這次新增的。
     * 不換成整體清單——使用者盯著的是那則訊息，混進其他語音的項目只會更難對照。
     *
     * <p>沒引用時本來操作的就是整體清單，秀整體（查詢會觸發 flush，
     * 讀到的是這個交易裡剛改完的樣子）。
     */
    private List<NoteItem> itemsAfterChange(CommandPayload payload, Prepared prepared,
            Map<Long, NoteItem> byId, List<NoteItem> added) {
        if (!prepared.fromQuote()) {
            return upcoming(payload.lineUserId());
        }
        List<NoteItem> after = new ArrayList<>();
        for (ItemSnapshot snapshot : prepared.items()) {
            NoteItem item = byId.get(snapshot.id());
            // 這一輪剛刪掉的，getExtraction() 已經不含它了
            if (item != null && item.getExtraction() != null
                    && item.getExtraction().getItems().contains(item)) {
                after.add(item);
            }
        }
        after.addAll(added);
        return after.stream().sorted(NoteCategory.itemOrder()).toList();
    }

    /** 回覆的內容，以及它列出的項目——後者會成為這則訊息的錨點。 */
    private record Reply(String text, List<Long> listedItemIds) {

        /** 不是清單的回覆（看不懂、什麼都沒做成），沒有編號可以指涉。 */
        static Reply plain(String text) {
            return new Reply(text, List.of());
        }
    }

    /** @return 編號指到的那一筆；超出範圍或已經不在了則為 null */
    private static NoteItem targetFor(Prepared prepared, Map<Long, NoteItem> byId,
            NoteCommand.Op op) {
        // 解析階段驗過範圍了，這裡是最後一道：丟例外的話整個交易回滾，
        // outbox 重試五次後放棄，使用者一則回覆都收不到。
        Integer index = op.itemIndex();
        if (index == null || index < 1 || index > prepared.items().size()) {
            return null;
        }
        return byId.get(prepared.items().get(index - 1).id());
    }

    /**
     * 新增的項目要掛在某一批抽取底下。
     *
     * <p>有引用就掛<b>使用者眼前那則訊息</b>裡還活著的第一筆所屬的那批，
     * 沒有就掛最近那批。掛哪一批對使用者不可見，但它決定了這一筆之後跟著誰一起被取代。
     */
    private NoteExtraction ownerForAdd(CommandPayload payload, Prepared prepared,
            Map<Long, NoteItem> byId) {
        if (prepared.fromQuote()) {
            NoteExtraction owner = prepared.items().stream()
                    .map(snapshot -> byId.get(snapshot.id()))
                    .filter(Objects::nonNull)
                    .map(NoteItem::getExtraction)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            if (owner != null) {
                return owner;
            }
        }
        return noteRepository.findTopByLineUserIdOrderByIdDesc(payload.lineUserId())
                .flatMap(note -> extractionRepository.findByNoteIdAndActiveTrue(note.getId()))
                .orElse(null);
    }

    private static boolean needsTarget(NoteCommand.Action action) {
        return action == NoteCommand.Action.DELETE
                || action == NoteCommand.Action.UPDATE_TITLE
                || action == NoteCommand.Action.UPDATE_TIME;
    }

    /**
     * 使用者引用的那則訊息當時秀了哪幾筆。
     *
     * <p>查錨點而不是重算：錨點存的是<b>那一刻的順序</b>，
     * 而重算會漂——清單若已經刪過東西，算出來的「第三筆」就是別的項目了，
     * 而使用者看著的是舊訊息。
     *
     * <p>對不上時（不是我們推播的訊息、V7 之前的舊訊息）回空。指名項目的動作
     * 不會執行——照做會拿使用者說的「第一筆」去對一份他沒看到的清單，
     * 那是「確實地做錯事」，比報錯更糟（決策 17）。
     */
    private List<ItemSnapshot> quotedItems(CommandPayload payload) {
        List<Long> ids = anchors.itemIdsFor(payload.quotedMessageId()).orElse(null);
        if (ids == null) {
            log.info("引用的訊息不是我推播的，或已經太舊：quoted={}", payload.quotedMessageId());
            return null;
        }
        // 錨點記的是 id，項目本身可能已經被刪掉——那些在 execute() 會回報「已經不在清單上」。
        Map<Long, NoteItem> alive = itemRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(NoteItem::getId, Function.identity()));
        return ids.stream().map(id -> ItemSnapshot.at(id, alive.get(id))).toList();
    }

    /** 這位使用者目前還有效的項目，跨所有語音，依顯示順序排好。 */
    private List<NoteItem> upcoming(String lineUserId) {
        return itemRepository.findUpcoming(lineUserId, Instant.now(clock))
                .stream().sorted(NoteCategory.itemOrder()).toList();
    }
}
