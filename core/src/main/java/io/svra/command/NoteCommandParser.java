package io.svra.command;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import io.svra.note.NoteCategory;

/**
 * 把使用者的一句話解析成指令。
 *
 * <p>
 * 🔴 承重點⑤。
 *
 * <p><b>log 政策</b>：成功路徑只記數量與長度，<b>使用者的原話只在 WARN 以上出現</b>。
 * 理由是修 prompt 需要原文——「解析失敗」而不知道使用者講了什麼，就只能猜；
 * 但那是個人筆記內容，不該在一切正常時也躺在 log 裡。
 * 逐字稿那邊同一個標準（{@code TranscribeResultListener} 只記字數）。
 */
@Component
class NoteCommandParser {

    private static final Logger log = LoggerFactory.getLogger(NoteCommandParser.class);

    private static final String SYSTEM = """
      你要把使用者對一份筆記清單下的指令，解析成一串結構化動作。

      目前的清單（編號就是使用者說的「第幾筆」）：
      %s

      ops 是動作陣列，一次可以有多個。每個動作的 action 只能是：
      - DELETE       刪掉某一筆（要 itemIndex）
      - UPDATE_TITLE 改某一筆的標題（要 itemIndex 與 title）
      - UPDATE_TIME  改某一筆的時間（要 itemIndex 與 occursAt）
      - ADD          新增一筆（只有 title 必填；沒講時間就不要填 occursAt）
      - LIST         列出目前的清單（不需要其他欄位）

      規則：
      - 使用者說幾件事就給幾個動作。「第一筆跟第三筆刪掉」= 兩個 DELETE，
        「刪掉第二筆，再加一筆下週三開會」= 一個 DELETE 加一個 ADD
      - itemIndex 一律以「上面這份清單」的編號為準。多筆刪除時各自寫各自的編號，
        不要因為前面刪掉了就把後面的編號往前挪——編號在執行前就已經決定
      - itemIndex 必須是清單裡真實存在的編號。使用者可能說「第二筆」，
        也可能說內容（例如「阿里山那筆」），兩種都要對應到正確的編號
      - 使用者沒指明是哪一筆，而清單只有一筆時，就是那一筆；
        有多筆而指涉不清時不要猜，讓 ops 空著並在 reason 說明
      - ADD 的 category 只能是 SCHEDULE（有時間的行程）、TODO（要做的事）、
        IDEA（想法）。判斷不出來就填 TODO
      - occursAt 用 ISO-8601（例如 2026-08-16T09:00:00+08:00）。
        只講日期沒講幾點時（例如「下週三」），UPDATE_TIME 沿用原本那筆的時間、ADD 用 09:00
      - timeSpecified：使用者這次**有沒有真的講出幾點**。
        「改到下午三點」→ true；「改到下週三」「加一筆星期五交報告」→ false。
        這一欄不要用猜的，只問逐字裡有沒有出現時刻
      - **待辦沒有時間是正常的**，使用者還沒想好什麼時候做而已。
        整句話都沒提到時間時就不要填 occursAt，直接建立一筆沒有時間的項目——
        不要因此拒絕、不要反問時間，更不要自己編一個日期
      - 完全看不懂時，ops 留空並在 reason 用一句話說明，會直接回給使用者
      - 使用者講的事情裡若有你做不到的（例如「幫我排版」），把那部分寫進
        unhandled（用使用者的話複述），沒有就留空
      - reason 與 unhandled 會**原封不動顯示在聊天視窗裡**給使用者看。
        用他聽得懂的話寫：不要提欄位名稱（occursAt、itemIndex）、動作代號
        （ADD、DELETE、LIST），也不要引用這份規則本身。
        ✗「itemIndex 超出 ops 可解析的範圍」
        ✓「你說的那一筆我對不上，可以說編號嗎？」

      今天是 %s。
      """;

    /** 看不懂時的固定說法。模型寫的句子若帶著內部術語，就退回這一句。 */
    private static final String FALLBACK_REASON = "看不懂這個指令，可以換個說法嗎？";
    /** 同上，用在「這部分我還不會處理：」後面。 */
    private static final String FALLBACK_UNHANDLED = "你說的其中一部分";

    /**
     * 不該出現在使用者眼前的字：這份 prompt 的欄位名與動作代號。
     *
     * <p>回覆是中文的，所以英文識別字幾乎不可能是使用者自己講的話。
     * 誤判的代價也只是換成一句比較籠統的回覆，比讓術語漏出去輕。
     */
    private static final Pattern JARGON = Pattern.compile(
            "occursAt|itemIndex|timeSpecified|unhandled|\\bops\\b|\\bJSON\\b|\\bnull\\b"
                    + "|\\bDELETE\\b|\\bUPDATE_TITLE\\b|\\bUPDATE_TIME\\b"
                    + "|\\bADD\\b|\\bLIST\\b|\\bSCHEDULE\\b|\\bTODO\\b|\\bIDEA\\b",
            Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final Clock clock;
    private final ZoneId zone = ZoneId.of("Asia/Taipei");

    /** 時鐘用注入的而不是 LocalDate.now()，「下週二」才驗算得了。 */
    NoteCommandParser(ChatClient.Builder builder, Clock clock) {
        this.chatClient = builder.build();
        this.clock = clock;
    }

    /**
     * @param userText 使用者說的話
     * @param items    目前生效的項目，順序即編號順序
     */
    public NoteCommand parse(String userText, List<ItemSnapshot> items) {

        String system = SYSTEM.formatted(renderItems(items), LocalDate.now(clock.withZone(zone)));

        try {
            NoteCommand result = chatClient.prompt()
                    .system(system)
                    .user(userText)
                    .call()
                    .entity(NoteCommand.class);

            if (result.isUnknown()) {
                String reason = userFacing(result.reason(), FALLBACK_REASON);
                return NoteCommand.unknown(reason == null ? FALLBACK_REASON : reason);
            }

            // 一個動作不合法就整批退回。只做一半又不說，比什麼都不做更糟。
            for (NoteCommand.Op op : result.ops()) {
                String invalid = validate(op, items.size());
                if (invalid != null) {
                    log.warn("指令驗證失敗：{}（{}）", userText, invalid);
                    return NoteCommand.unknown(invalid);
                }
            }
            return new NoteCommand(result.ops(), result.reason(),
                    userFacing(result.unhandled(), FALLBACK_UNHANDLED));

        } catch (Exception e) {
            log.warn("指令解析失敗：{}", userText, e);
            return NoteCommand.unknown(FALLBACK_REASON);
        }
    }

    /**
     * 模型寫給使用者看的句子，出去之前過一道濾網。
     *
     * <p>{@code reason} 與 {@code unhandled} 會<b>原封不動</b>顯示在聊天視窗裡，
     * 而它們是模型寫的——模型讀得到 prompt 裡的欄位名與動作代號，於是實測回過
     * 「未指定時間（occursAt），根據規則 ADD 動作需填寫 occursAt」。
     * 那是講給開發者聽的話，出現在使用者的聊天視窗裡。
     *
     * <p>prompt 已經明講這兩欄是給人看的，但 <b>prompt 是請求不是保證</b>——
     * 同一個模型換個溫度、換個版本就可能又漏出來。要擋得住就要有程式擋。
     *
     * @return 原句；帶著術語時回傳 {@code fallback}；原本就是空的時候回 {@code null}
     */
    static String userFacing(String modelText, String fallback) {
        if (modelText == null || modelText.isBlank()) {
            return null;
        }
        if (JARGON.matcher(modelText).find()) {
            log.warn("模型的回覆帶著內部術語，已改用固定說法：{}", modelText);
            return fallback;
        }
        return modelText;
    }

    /** 把項目清單排成 LLM 讀得懂的樣子，編號與推播訊息一致。 */
    static String renderItems(List<ItemSnapshot> items) {
        return IntStream.range(0, items.size())
                .mapToObj(i -> {
                    ItemSnapshot item = items.get(i);
                    // 已經被刪掉的項目仍然佔著編號——抽掉的話後面每一筆都會往前挪，
                    // 而使用者看的是那則舊訊息上的編號。
                    if (item.gone()) {
                        return "%d. （這一筆已經被刪掉了）".formatted(i + 1);
                    }
                    String when = item.occursAt() == null ? "無" : item.occursAt().toString();
                    return "%d. [%s] %s（時間：%s）"
                            .formatted(i + 1, item.category(), item.title(), when);
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("（清單是空的）");
    }

    /** @return 不合法的原因；合法時為 null */
    private static String validate(NoteCommand.Op op, int itemCount) {
        return switch (op.action()) {
            case DELETE, UPDATE_TITLE, UPDATE_TIME -> {
                Integer index = op.itemIndex();
                if (index == null || index < 1 || index > itemCount) {
                    yield "找不到你說的那一筆，可以說編號嗎？";
                }
                if (op.action() == NoteCommand.Action.UPDATE_TITLE
                        && (op.title() == null || op.title().isBlank())) {
                    yield "要改成什麼標題呢？";
                }
                if (op.action() == NoteCommand.Action.UPDATE_TIME) {
                    if (op.occursAt() == null) {
                        yield "要改成什麼時間呢？";
                    }
                    yield unparsableTime(op.occursAt());
                }
                yield null;
            }
            // ADD 與 LIST 不看 itemIndex——模型有時會順手填一個（實測填過 -1）。
            // 那個值不會被使用，不需要因此讓整批指令失敗。
            case ADD -> (op.title() == null || op.title().isBlank())
                    ? "要新增什麼呢？"
                    : unparsableTime(op.occursAt());
            case LIST -> null;
        };
    }

    /**
     * 🔴 時間<b>必須在這裡</b>就解析過一次。
     *
     * <p>這一步原本不在驗證裡，真正的 {@code Instant.parse()} 到套用指令的迴圈中途才跑。
     * 模型只要少給時區（{@code 2026-08-16T09:00}）就會拋 {@code DateTimeParseException}——
     * 而那時前面幾個動作已經改過資料，例外讓整個交易回滾，outbox 重試五次後放棄，
     * <b>使用者一則回覆都收不到</b>。明明有「看不懂就回一句話」的完整路徑，卻走不到。
     *
     * <p>驗證的意義是<b>在動手之前就知道做不做得到</b>。
     *
     * @return 不合法的原因；可以解析或根本沒填時為 null
     */
    private static String unparsableTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            Instant.parse(iso);
            return null;
        } catch (DateTimeParseException e) {
            log.warn("模型給的時間解析不了：{}", iso);
            return "我看不懂那個時間，可以說得更明確嗎？（例如「8月16號早上九點」）";
        }
    }

    /** 判斷不出來就當待辦——寧可分類錯，也不要因為分類而整批失敗。 */
    static NoteCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return NoteCategory.TODO;
        }
        try {
            return NoteCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NoteCategory.TODO;
        }
    }

    static Instant parseOccursAt(String iso) {
        return (iso == null || iso.isBlank()) ? null : Instant.parse(iso);
    }
}
