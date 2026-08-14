package io.svra.command;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import io.svra.note.NoteItem;

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
      你要把使用者對一份筆記清單下的指令，解析成結構化動作。

      目前的清單（編號就是使用者說的「第幾筆」）：
      %s

      action 只能是這四種：
      - DELETE       刪掉某一筆
      - UPDATE_TITLE 改某一筆的標題
      - UPDATE_TIME  改某一筆的時間
      - UNKNOWN      看不懂，或指的項目不在清單裡

      規則：
      - itemIndex 必須是清單裡真實存在的編號。使用者可能說「第二筆」，
        也可能說內容（例如「阿里山那筆」），兩種都要對應到正確的編號
      - 使用者沒指明是哪一筆，而清單只有一筆時，就是那一筆；
        有多筆而指涉不清時回 UNKNOWN，不要猜
      - newOccursAt 用 ISO-8601（例如 2026-08-16T09:00:00+08:00）。
        只講日期沒講時間就沿用原本那筆的時間；原本沒有時間就用 09:00
      - action 是 UNKNOWN 時，reason 要用一句話說明，會直接回給使用者
      - 使用者可能一次講好幾件事，而你只做得到其中一件。把做不到的那些
        寫進 unhandled（用使用者的話複述），沒有就留空。
        例如「時間放前面，然後第二筆改成8/16」→ 做改時間，
        unhandled 填「時間放前面」

      今天是 %s。
      """;

  private final ChatClient chatClient;
  private final ZoneId zone = ZoneId.of("Asia/Taipei");

  public NoteCommandParser(ChatClient.Builder builder) {
    this.chatClient = builder.build();
  }

  /**
   * @param userText 使用者說的話
   * @param items    目前生效的項目，順序即編號順序
   */
  public NoteCommand parse(String userText, List<NoteItem> items) {

    String system = SYSTEM.formatted(renderItems(items), LocalDate.now(zone));

    try {
      NoteCommand result = chatClient.prompt()
          .system(system)
          .user(userText)
          .call()
          .entity(NoteCommand.class);

      // itemIndex 必須指向真實存在的項目
      if (result.action() != NoteCommand.Action.UNKNOWN
          && (result.itemIndex() == null || result.itemIndex() < 1 || result.itemIndex() > items.size())) {
        return new NoteCommand(NoteCommand.Action.UNKNOWN, null, null, null,
            "找不到你說的那一筆，可以說編號嗎？", null);
      }
      return result;

    } catch (Exception e) {
      log.warn("指令解析失敗：{}", userText, e);
      return new NoteCommand(NoteCommand.Action.UNKNOWN, null, null, null,
          "看不懂這個指令，可以換個說法嗎？", null);
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

  static Instant parseOccursAt(String iso) {
    return (iso == null || iso.isBlank()) ? null : Instant.parse(iso);
  }
}
