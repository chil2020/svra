package io.svra.command;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final Clock clock;
    /**
     * 交易邊界用 TransactionTemplate 明寫，不用 {@code @Transactional}。
     *
     * <p>理由與 {@code NoteExtractionService} 相同：這裡<b>刻意有一段不在交易裡</b>，
     * 而註解只能標在整個方法上。寫在這裡，交易的起訖看得見。
     */
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
            PlatformTransactionManager transactionManager) {
        this.noteRepository = noteRepository;
        this.extractionRepository = extractionRepository;
        this.itemRepository = itemRepository;
        this.executionRepository = executionRepository;
        this.parser = parser;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
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
        // 🔴 指令是這個系統裡唯一沒有其他去重機制的入口。
        // 語音有 notes.source_message_id 的唯一約束擋著重送，文字訊息不建 note，
        // 只寫一筆 outbox——沒有冪等鍵的話 LINE 逾時重送就是執行兩次「刪掉第一筆」。
        // 這裡曾經寫著 catch (DataIntegrityViolationException)，但表上根本沒有
        // 約束可以違反，那是一段永遠不會執行的死碼。
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
     * 執行一則指令。
     *
     * <p>🔴 <b>刻意沒有 {@code @Transactional}。</b>中間要呼叫 LLM 解析意圖，地端模型
     * 十幾秒起跳；整段包在一個交易裡，等於那十幾秒全程佔著一條資料庫連線什麼也沒做，
     * 同時撐大交易的存活時間。與 {@code NoteExtractionService} 同一個判斷（決策 18）。
     *
     * <p>所以分成三段：<b>短交易讀取 → 交易外解析 → 短交易套用</b>。
     *
     * <p>🔴 <b>必須冪等，而且不是「插入」那種冪等。</b>outbox 是 at-least-once：
     * 處理器成功提交、poller 的 markSent 卻失敗時，同一筆 COMMAND 事件會再跑一次。
     * 重複插入有唯一鍵擋著，重複執行沒有——指令是<b>位置性</b>的，
     * 重跑時清單已經少了一筆，同樣的「第一筆」指向的是<b>另一個項目</b>。
     * 它會成功，會回覆「已刪除」，而刪掉的是別筆。守衛是 {@code command_executions} 的主鍵。
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
     * @param quotedExtractionId 引用對到的那一批；沒引用或對不上時為 null
     * @param quoteUnresolved    使用者引用了某則訊息，但它對不回任何生效的抽取
     * @param items              清單快照，順序即使用者看到的編號順序
     */
    private record Prepared(Long quotedExtractionId, boolean quoteUnresolved,
            List<ItemSnapshot> items) {
    }

    /** @return 可以往下做時的資料；不該往下做時為 null */
    private Prepared prepare(CommandPayload payload) {
        // 先查一次，是為了省掉一次 LLM 呼叫與一次限流額度。
        // 這不是權威的判斷——真正的判斷在 apply()，理由寫在那裡。
        if (executionRepository.existsById(payload.commandMessageId())) {
            log.info("指令已經執行過，跳過重跑（outbox 是 at-least-once）");
            return null;
        }

        // 有引用就鎖定那一批——編號跟那則推播上的一致。
        // 沒有引用時要看的是「目前還有什麼」，那會跨越多則語音，不是最後一則而已。
        NoteExtraction quoted = resolveQuoted(payload);
        // 引用了卻對不上 = 使用者說的「第幾筆」跟我們手上的清單不是同一份。
        // 還是用整體清單當解析的上下文（LIST／ADD 不指涉編號，照做無妨），
        // 但指名項目的動作不能執行——編號會指到別的東西。
        boolean quoteUnresolved = payload.quotedMessageId() != null && quoted == null;
        List<NoteItem> items = quoted != null
                ? quoted.getOrderedItems()
                : upcoming(payload.lineUserId());

        return new Prepared(quoted == null ? null : quoted.getId(), quoteUnresolved,
                items.stream().map(ItemSnapshot::of).toList());
    }

    /** 第三段：套用變更並回覆。整段在一個交易裡。 */
    private void apply(CommandPayload payload, Prepared prepared, NoteCommand command) {
        // 🔴 這裡才是權威的冪等判斷。
        //
        // 為什麼不寫在第一段：那裡提交就等於宣告「這則指令做過了」，而變更還沒發生。
        // 之後只要崩潰一次，重跑就會看到紀錄而跳過——指令靜默消失，沒有錯誤也沒有回覆，
        // 使用者只知道自己講的話沒有發生。at-most-once 比重複更難查。
        //
        // 寫在這裡，紀錄與變更同進同退：要嘛都發生，要嘛都沒發生而由 outbox 再試一次。
        if (executionRepository.insertIfAbsent(payload.commandMessageId()) == 0) {
            log.info("指令在解析期間已被執行，放棄這次結果");
            return;
        }

        // 回覆與變更同交易寫下，提交後才由 outbox 送出（決策 3：先寫意圖，再送訊息）。
        // 直接在這裡打 LINE 有兩個問題：HTTP 成功而交易回滾時，使用者會看到「已刪除」
        // 但資料沒變（決策 17 那種「說了但沒做」）；而且那次外部 I/O 全程佔著這個交易。
        //
        // 這個事件不填 dedupe_key：它是內部產生的，寫在一個自己有守衛的交易裡
        // （上面的執行紀錄），一則指令不可能寫出第二筆——理由見 OutboxEvent.dedupeKeyFor。
        outboxRepository.save(OutboxEvent.pending(
                payload.commandMessageId(),
                NoteService.EVENT_PUSH_TEXT_REQUESTED,
                toPushPayload(payload.lineUserId(), execute(payload, prepared, command))));
        log.info("指令已處理：{} 個動作", command.isUnknown() ? 0 : command.ops().size());
    }

    private String toPushPayload(String lineUserId, String text) {
        try {
            return objectMapper.writeValueAsString(new PushTextPayload(lineUserId, text));
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化推播 payload 失敗", e);
        }
    }

    /** 真正動手的地方。回傳要給使用者的訊息。 */
    private String execute(CommandPayload payload, Prepared prepared, NoteCommand command) {
        if (command.isUnknown()) {
            return "🤔 " + command.reason();
        }

        // 🔴 先把編號解析成項目，再開始動手。
        // 邊刪邊用編號取值的話，「刪掉第一筆跟第三筆」會刪錯第二筆——
        // 刪掉第一筆之後，原本的第三筆已經變成第二筆了。
        //
        // 解析經過兩層：編號 →（第一段的快照）id → 資料庫現在的那一筆。
        // 中間那層是關鍵：第一段到現在隔著一次 LLM 呼叫，清單可能已經被別的事情動過，
        // 拿編號重算會指到另一個項目。id 不會漂。
        Map<Long, NoteItem> byId = itemRepository.findAllById(
                prepared.items().stream().map(ItemSnapshot::id).toList())
                .stream().collect(Collectors.toMap(NoteItem::getId, Function.identity()));

        // 只有指名項目的動作要解析目標。ADD 與 LIST 不指涉任何一筆，
        // 但模型仍可能在那些動作上填 itemIndex——不能因為它填了就當真。
        List<NoteItem> targets = command.ops().stream()
                .map(op -> needsTarget(op.action()) ? targetFor(prepared, byId, op) : null)
                .toList();

        StringBuilder reply = new StringBuilder();
        boolean listRequested = false;
        boolean quoteBlocked = false;

        for (int i = 0; i < command.ops().size(); i++) {
            NoteCommand.Op op = command.ops().get(i);
            NoteItem target = targets.get(i);

            // 引用對不上時，編號指的是整份清單，不是使用者眼前那則訊息。
            // 照做的話會「確實地做錯事」——比報錯更糟。
            if (needsTarget(op.action()) && prepared.quoteUnresolved()) {
                quoteBlocked = true;
                continue;
            }

            // 指名的那一筆在這中間被刪掉了。說出來——沉默地跳過會讓使用者
            // 以為做到了（決策 17：只做一半的失敗處理比不做更難察覺）。
            if (needsTarget(op.action()) && target == null) {
                reply.append("⚠️ 第 ").append(op.itemIndex())
                        .append(" 筆已經不在清單上了，沒有動它\n");
                continue;
            }

            switch (op.action()) {
                case DELETE -> {
                    target.getExtraction().removeItem(target);
                    reply.append("🗑 已刪除：").append(target.getTitle()).append('\n');
                }
                case UPDATE_TITLE -> {
                    String before = target.getTitle();
                    target.rename(op.title());
                    reply.append("✏️ 已改標題：").append(before)
                            .append(" → ").append(op.title()).append('\n');
                }
                case UPDATE_TIME -> {
                    target.reschedule(NoteCommandParser.parseOccursAt(op.occursAt()));
                    reply.append("🕘 已改時間：").append(target.getTitle()).append('\n');
                }
                case ADD -> {
                    NoteExtraction owner = ownerForAdd(payload, prepared);
                    if (owner == null) {
                        reply.append("⚠️ 還沒有任何筆記可以加進去，先傳一則語音吧。\n");
                        break;
                    }
                    NoteItem added = new NoteItem(
                            NoteCommandParser.parseCategory(op.category()), op.title(),
                            NoteCommandParser.parseOccursAt(op.occursAt()), null, List.of());
                    owner.addItem(added);
                    reply.append("➕ 已新增：").append(op.title()).append('\n');
                }
                case LIST -> listRequested = true;
            }
        }

        if (listRequested) {
            // 重新查一次而不是沿用第一段那份——這一輪可能剛改過東西，
            // 要秀的是改完之後的樣子。
            if (!reply.isEmpty()) {
                reply.append('\n');
            }
            reply.append(NoteNotifier.renderCurrent(upcoming(payload.lineUserId())));
        }

        if (quoteBlocked) {
            reply.append("\n⚠️ 你引用的那則我對不上（可能已經被新版取代，或不是我推播的訊息）。")
                    .append("那上面的編號跟我現在看到的清單不一定是同一份，所以我沒有動任何一筆。")
                    .append("可以直接說內容，或引用最新那則。");
        }

        // 只做了一半就要說——不然使用者會以為都交代了。
        if (command.unhandled() != null && !command.unhandled().isBlank()) {
            reply.append("\n⚠️ 這部分我還不會處理：").append(command.unhandled());
        }

        String text = reply.toString().strip();
        // 一個字都沒有的回覆，對使用者等於沒回應——寧可說「什麼都沒做」。
        return text.isEmpty() ? "⚠️ 這則指令我沒能做到任何事。" : text;
    }

    /** @return 編號指到的那一筆；超出範圍或已經不在了則為 null */
    private static NoteItem targetFor(Prepared prepared, Map<Long, NoteItem> byId,
            NoteCommand.Op op) {
        Integer index = op.itemIndex();
        // 解析階段已經驗過範圍（NoteCommandParser.validate），這裡是最後一道——
        // 這個方法丟例外的話整個交易回滾，outbox 重試五次後放棄，使用者一則回覆都收不到。
        if (index == null || index < 1 || index > prepared.items().size()) {
            return null;
        }
        return byId.get(prepared.items().get(index - 1).id());
    }

    /** 新增的項目要掛在某一批抽取底下——有引用就掛那批，沒有就掛最近那批。 */
    private NoteExtraction ownerForAdd(CommandPayload payload, Prepared prepared) {
        if (prepared.quotedExtractionId() != null) {
            return extractionRepository.findById(prepared.quotedExtractionId()).orElse(null);
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
     * 使用者引用了某則推播時，精準定位到那一批；沒引用、或那則對不回任何生效的抽取時回 null。
     *
     * <p>🔴 <b>失效的版本也算對不上。</b>只用 notify_message_id 反查而不看 is_active，
     * 會對「已經被取代的那一批」執行——那些項目沒有任何介面看得到，使用者卻會收到
     * 「已刪除」。成功訊息加零效果，比報錯更難察覺（決策 17）。
     *
     * <p><b>失效的版本不是假想的。</b>早期的 {@code extractFor()} 會先停用舊版再重抽
     * （{@code 9ff3b0c} 加、{@code ad93b34} 拿掉），那段程式已經不在，<b>它留下的列還在</b>，
     * 而且每一筆都推播過——那些訊息還在使用者的對話紀錄裡，往上滑就引用得到。
     * 現在的程式不會再產生新的失效版本，但既有的那些讓這條路隨時走得到。
     *
     * <p>另一半更常發生：使用者引用任何一則不是我們推播的訊息——自己的舊訊息、
     * V4 之前的推播——都會走到這裡。以前那條路是靜默改用整體清單，
     * 然後拿使用者說的「第一筆」去對一份他沒看到的清單。
     */
    private NoteExtraction resolveQuoted(CommandPayload payload) {
        if (payload.quotedMessageId() == null) {
            return null;
        }
        NoteExtraction quoted = extractionRepository
                .findByNotifyMessageId(payload.quotedMessageId())
                .filter(NoteExtraction::isActive)
                .orElse(null);
        if (quoted == null) {
            log.info("引用的訊息對不回任何生效的抽取：quoted={}", payload.quotedMessageId());
        }
        return quoted;
    }

    /** 這位使用者目前還有效的項目，跨所有語音，依顯示順序排好。 */
    private List<NoteItem> upcoming(String lineUserId) {
        return itemRepository.findUpcoming(lineUserId, Instant.now(clock))
                .stream().sorted(NoteCategory.itemOrder()).toList();
    }
}
