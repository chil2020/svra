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

import io.svra.calendar.CalendarSync;
import io.svra.calendar.CalendarSyncPayload;
import io.svra.llm.LlmRateLimiter;
import io.svra.note.NoteCategory;
import io.svra.note.NoteExtraction;
import io.svra.note.NoteExtractionRepository;
import io.svra.note.NoteItem;
import io.svra.note.NoteItemRepository;
import io.svra.note.NoteRepository;
import io.svra.note.NoteService;
import io.svra.notify.Greetings;
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
    /** 回覆長什麼樣由 notify 決定——這裡只負責決定「要回哪一份清單」。 */
    private final NoteNotifier notifier;
    /** 改到已經匯入行事曆的項目時，行事曆要跟著動。 */
    private final CalendarSync calendarSync;
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
            NoteNotifier notifier,
            CalendarSync calendarSync,
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
        this.notifier = notifier;
        this.calendarSync = calendarSync;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 記下使用者的指令。解析要呼叫 LLM，不能拖慢 webhook——這裡只寫意圖，
     * 跟語音訊息一樣交給 outbox 非同步處理。
     */
    @Transactional
    public void recordCommand(String lineUserId, String commandMessageId,
            String text, String quotedMessageId, String replyToken) {
        // 🔴 指令是唯一沒有其他去重機制的入口：語音有 notes 的唯一約束擋著，
        // 文字訊息不建 note，只寫一筆 outbox。沒有冪等鍵，LINE 重送就執行兩次。
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new CommandPayload(
                    lineUserId, commandMessageId, text, quotedMessageId, replyToken));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化指令 payload 失敗", e);
        }

        if (outboxRepository.insertIfAbsent(
                commandMessageId,
                NoteService.EVENT_COMMAND_REQUESTED,
                lineUserId,
                payload,
                OutboxEvent.dedupeKeyFor(
                        NoteService.EVENT_COMMAND_REQUESTED, commandMessageId)) == 0) {
            log.info("重複投遞的指令，已忽略——冪等鍵擋下（決策 2）");
        }
    }

    /**
     * 存進 outbox 的指令內容。
     *
     * @param replyToken 解析要跑 LLM（實測約 7 秒），而 reply token 撐得住那麼久——
     *                   所以指令的回覆送得出免費的那一種。失效才退回推播。
     */
    public record CommandPayload(
            String lineUserId,
            String commandMessageId,
            String text,
            String quotedMessageId,
            String replyToken) {
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
        //
        // 🔴 快速路徑排在限流<b>之前</b>，而那個順序就是這段程式碼的重點。
        //
        // rateLimiter 保護的是「只有一份」的 Ollama。讓一個**完全不打 LLM** 的指令
        // 先跟它請額度，是把保護措施套在不需要保護的東西上——而後果不是浪費，
        // 是**使用者傳了幾則語音撞到上限之後，連按鈕都按不動了**，
        // 為了一個根本用不到 Ollama 的操作。
        QuickCommand quick = QuickCommand.resolve(payload.text());

        if (quick == QuickCommand.HELP) {
            tx.executeWithoutResult(status -> replyWithHelp(payload));
            return;
        }

        NoteCommand command;
        if (quick == QuickCommand.LIST) {
            command = NoteCommand.listOnly();
        } else {
            // 超過額度就往外拋，讓 outbox 的指數退避去處理。
            rateLimiter.consume(payload.lineUserId());
            command = parser.parse(payload.text(), prepared.items());
        }

        // ── 第三段：短交易，執行紀錄與所有變更同進同退 ──
        tx.executeWithoutResult(status -> apply(payload, prepared, command));
    }

    /**
     * 「使用說明」：不動任何資料，只回一段文字。
     *
     * <p>冪等守衛照樣要有，理由跟 {@link #apply} 一模一樣——outbox 是 at-least-once，
     * 少了它，重投一次使用者就收到兩份說明。<b>不改資料不等於可以重複做。</b>
     */
    private void replyWithHelp(CommandPayload payload) {
        if (executionRepository.insertIfAbsent(
                payload.commandMessageId(), payload.lineUserId()) == 0) {
            log.info("說明已經回過了，跳過重跑（outbox 是 at-least-once）");
            return;
        }
        outboxRepository.save(OutboxEvent.pending(
                payload.commandMessageId(),
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                payload.lineUserId(),
                serialize(PushTextPayload.plain(payload.lineUserId(), Greetings.helpText())
                        .repliedWith(payload.replyToken()))));
        log.info("已回覆使用說明");
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
        if (executionRepository.insertIfAbsent(
                payload.commandMessageId(), payload.lineUserId()) == 0) {
            log.info("指令在解析期間已被執行，放棄這次結果");
            return;
        }

        // 回覆與變更同交易寫下，由 outbox 送出（決策 3）。直接打 LINE 的話，
        // HTTP 成功而交易回滾時使用者會看到更新後的清單，但資料沒變。
        // 不填 dedupe_key：上面的執行紀錄已經是守衛，一則指令寫不出第二筆。
        Outcome outcome = execute(payload, prepared, command);
        outboxRepository.save(OutboxEvent.pending(
                payload.commandMessageId(),
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                payload.lineUserId(),
                serialize(outcome.reply().repliedWith(payload.replyToken()))));

        // 🔴 行事曆的連動也寫在<b>這個</b>交易裡，理由跟回覆完全一樣。
        // 拆出去的話，指令回滾而同步意圖留下，行事曆會被改成一個資料庫裡不存在的樣子；
        // 反過來漏寫，使用者改了時間、收到漂亮的新清單，而行事曆默默停在舊時間——
        // 那是決策 17 講的那種失敗：只做一半，而且沒有人會發現。
        calendarSync.syncAfterCommand(
                payload.commandMessageId(), payload.lineUserId(), outcome.calendarTargets());

        log.info("指令已處理：{} 個動作", command.isUnknown() ? 0 : command.ops().size());
    }

    private String serialize(PushTextPayload reply) {
        try {
            return objectMapper.writeValueAsString(reply);
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
    private Outcome execute(CommandPayload payload, Prepared prepared, NoteCommand command) {
        if (command.isUnknown()) {
            return Outcome.plain(payload.lineUserId(), "🤔 " + command.reason());
        }

        // 🔴 先把編號解析成項目，再開始動手：邊刪邊用編號取值的話，
        // 「刪掉第一筆跟第三筆」會刪錯第二筆。
        //
        // 解析經過兩層：編號 →（快照）id → 現在的那一筆。中間那層是關鍵——
        // 第一段到現在隔著一次 LLM 呼叫，拿編號重算會指到另一個項目，id 不會漂。
        // 🔴 帶使用者：這些 id 來自錨點，而錨點是用使用者裝置送上來的 quotedMessageId
        // 查出來的。錨點那一層已經擋過一次，這裡是第二道。
        Map<Long, NoteItem> byId = itemRepository.findAllByIdAndUser(payload.lineUserId(),
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
        // 已經匯入過行事曆的項目被動到時，行事曆要跟著動。沒匯入過的不在這裡面——
        // 那些項目在 Google 那邊根本不存在，寫下同步意圖只是製造註定 404 的請求。
        List<CalendarSyncPayload.Target> calendarTargets = new ArrayList<>();

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
                    // 🔴 event id 要在刪掉<b>之前</b>拿。orphanRemoval 一提交那一列就沒了，
                    // poller 兩秒後撿起同步事件時已經無從回查——所以意圖必須自帶它（決策 3）。
                    if (target.getGoogleEventId() != null) {
                        calendarTargets.add(
                                CalendarSyncPayload.Target.delete(target.getGoogleEventId()));
                    }
                    target.getExtraction().removeItem(target);
                    changed = true;
                }
                case UPDATE_TITLE -> {
                    target.rename(op.title());
                    syncIfImported(calendarTargets, target);
                    changed = true;
                }
                case UPDATE_TIME -> {
                    target.reschedule(NoteCommandParser.parseOccursAt(op.occursAt()),
                            timeSpecifiedAfter(op, target));
                    syncIfImported(calendarTargets, target);
                    changed = true;
                }
                case ADD -> {
                    NoteExtraction owner = ownerForAdd(payload, prepared, byId);
                    if (owner == null) {
                        notices.add("⚠️ 還沒有任何筆記可以加進去，先傳一則語音吧。");
                        break;
                    }
                    // 新增的項目不會自動進行事曆：使用者沒按過那顆按鈕。
                    // 回覆的卡片上它會帶著「加入行事曆」，要不要進去由他決定。
                    NoteItem item = new NoteItem(
                            NoteCommandParser.parseCategory(op.category()), op.title(),
                            NoteCommandParser.parseOccursAt(op.occursAt()), op.timeSpecified(),
                            null, List.of());
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
            return Outcome.plain(payload.lineUserId(), notices.isEmpty()
                    ? "⚠️ 這則指令我沒能做到任何事。"
                    : String.join("\n", notices));
        }

        List<NoteItem> after = itemsAfterChange(payload, prepared, byId, added);
        // 排版交給 notify——這裡只決定「要回哪一份清單」。
        // 提醒（做不到的部分、引用對不上）由它排在清單前面，而不是在這裡串成一段文字：
        // 卡片上那是獨立的一行，串起來就沒辦法分開排版了。
        PushTextPayload reply = changed
                ? notifier.updatedCard(payload.lineUserId(), after, notices)
                : notifier.currentCard(payload.lineUserId(), after, notices);
        return new Outcome(reply, calendarTargets);
    }

    /**
     * 已經在行事曆上的項目被改了，就寫一筆同步意圖。
     *
     * <p>沒有 {@code googleEventId} 的不寫——那些項目在 Google 那邊根本不存在，
     * 寫下去只是製造註定 404 的請求，而 404 會被判成永久失敗、推一則假的失敗通知。
     */
    private static void syncIfImported(List<CalendarSyncPayload.Target> targets, NoteItem item) {
        if (item.getGoogleEventId() != null) {
            targets.add(CalendarSyncPayload.Target.upsert(item.getId()));
        }
    }

    /**
     * 改完時間之後，「時刻是不是使用者講的」該是什麼。
     *
     * <p>模型說 true 就是 true——他這次講了幾點。說 false 或沒說時<b>沿用原本的值</b>，
     * 因為 prompt 要它在「只講日期」時沿用原本那筆的時刻：<b>時刻既然是沿用的，
     * 「時刻是不是講出來的」自然也該沿用</b>。
     *
     * <p>一律填 false 的話，「把三點那筆改到下週三」會讓一個定時事件變成全天事件，
     * 而使用者從頭到尾沒有要求過那件事。
     */
    private static Boolean timeSpecifiedAfter(NoteCommand.Op op, NoteItem target) {
        return Boolean.TRUE.equals(op.timeSpecified())
                ? Boolean.TRUE
                : target.getTimeSpecified();
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

    /**
     * 一則指令的兩個產物：<b>要回什麼</b>，以及<b>行事曆要跟著做什麼</b>。
     *
     * <p>兩者必須一起交出來，因為它們都必須寫在同一個交易裡。分兩次算的話，
     * 中間任何一步失敗都會讓兩邊對不上。
     */
    private record Outcome(PushTextPayload reply, List<CalendarSyncPayload.Target> calendarTargets) {

        /** 不是清單的回覆（看不懂、什麼都沒做成），沒有編號可以指涉，也沒有東西要同步。 */
        static Outcome plain(String lineUserId, String text) {
            return new Outcome(PushTextPayload.plain(lineUserId, text), List.of());
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
        List<Long> ids = anchors.itemIdsFor(payload.lineUserId(), payload.quotedMessageId())
                .orElse(null);
        if (ids == null) {
            log.info("引用的訊息不是我推播的，或已經太舊：quoted={}", payload.quotedMessageId());
            return null;
        }
        // 錨點記的是 id，項目本身可能已經被刪掉——那些在 execute() 會回報「已經不在清單上」。
        Map<Long, NoteItem> alive = itemRepository
                .findAllByIdAndUser(payload.lineUserId(), ids).stream()
                .collect(Collectors.toMap(NoteItem::getId, Function.identity()));
        return ids.stream().map(id -> ItemSnapshot.at(id, alive.get(id))).toList();
    }

    /** 這位使用者目前還有效的項目，跨所有語音，依顯示順序排好。 */
    private List<NoteItem> upcoming(String lineUserId) {
        return itemRepository.findUpcoming(lineUserId, Instant.now(clock))
                .stream().sorted(NoteCategory.itemOrder()).toList();
    }
}
