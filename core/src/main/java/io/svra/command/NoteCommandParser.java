package io.svra.command;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import io.svra.note.NoteItem;
import io.svra.note.NoteCategory;

/**
 * 把使用者的一句話解析成指令。
 *
 * <p>
 * 🔴 承重點⑤。
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
      - ADD          新增一筆（要 title；有講時間就填 occursAt）
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
        只講日期沒講時間，UPDATE_TIME 沿用原本那筆的時間、ADD 用 09:00
      - 完全看不懂時，ops 留空並在 reason 用一句話說明，會直接回給使用者
      - 使用者講的事情裡若有你做不到的（例如「幫我排版」），把那部分寫進
        unhandled（用使用者的話複述），沒有就留空

      今天是 %s。
      """;

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
  public NoteCommand parse(String userText, List<NoteItem> items) {

    String system = SYSTEM.formatted(renderItems(items), LocalDate.now(clock.withZone(zone)));

    try {
      NoteCommand result = chatClient.prompt()
          .system(system)
          .user(userText)
          .call()
          .entity(NoteCommand.class);

      if (result.isUnknown()) {
        return result.reason() == null
            ? NoteCommand.unknown("看不懂這個指令，可以換個說法嗎？")
            : result;
      }

      // 一個動作不合法就整批退回。只做一半又不說，比什麼都不做更糟。
      for (NoteCommand.Op op : result.ops()) {
        String invalid = validate(op, items.size());
        if (invalid != null) {
          log.warn("指令驗證失敗：{}（{}）", userText, invalid);
          return NoteCommand.unknown(invalid);
        }
      }
      return result;

    } catch (Exception e) {
      log.warn("指令解析失敗：{}", userText, e);
      return NoteCommand.unknown("看不懂這個指令，可以換個說法嗎？");
    }
  }

  /** 把項目清單排成 LLM 讀得懂的樣子，編號與推播訊息一致。 */
  static String renderItems(List<NoteItem> items) {
    return IntStream.range(0, items.size())
        .mapToObj(i -> {
          NoteItem item = items.get(i);
          String when = item.getOccursAt() == null ? "無" : item.getOccursAt().toString();
          return "%d. [%s] %s（時間：%s）"
              .formatted(i + 1, item.getCategory(), item.getTitle(), when);
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
        if (op.action() == NoteCommand.Action.UPDATE_TIME && op.occursAt() == null) {
          yield "要改成什麼時間呢？";
        }
        yield null;
      }
      // ADD 與 LIST 不看 itemIndex——模型有時會順手填一個（實測填過 -1）。
      // 那個值不會被使用，不需要因此讓整批指令失敗。
      case ADD -> (op.title() == null || op.title().isBlank()) ? "要新增什麼呢？" : null;
      case LIST -> null;
    };
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
