package io.svra.notify;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.svra.note.NoteCategory;
import io.svra.note.NoteItem;

/**
 * 把一份項目清單排成一張 Flex 卡片。
 *
 * <p>🔴 <b>順序、編號、錨點，三者由同一次計算產生。</b>
 * 使用者說「第三筆」時指的是卡片上那個編號，而解析靠的是錨點記下的 id 順序——
 * 兩邊只要有一邊自己再排一次，就有漂掉的機會。{@link Rendered#itemIds()}
 * 回傳的就是渲染時實際用的順序，呼叫端直接拿去當錨點，<b>不要自己再排</b>。
 *
 * <p>純文字版本仍然產生，它有兩個用途：Flex 訊息的 {@code altText}
 * （被引用時聊天室裡顯示的就是它），以及推播失敗時的退路。
 *
 * <p>它是 public 的，理由只有一個：<b>「編號」這件事的測試必須跨模組</b>。
 * 使用者說的「第三筆」由 command 解析、由這裡排版，而那個一致性只有
 * 把兩邊放在一起才驗得到（見 {@code ItemNumberingConsistencyTest}）。
 * 除了組裝與測試之外，不要從別的模組直接用它——排版的入口是 {@link NoteNotifier}。
 */
@Component
public class CardRenderer {

    private static final Logger log = LoggerFactory.getLogger(CardRenderer.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.TAIWAN);
    /** 只知道日期的項目——印出一個沒人講過的時刻，只會讓人以為那是真的。 */
    private static final DateTimeFormatter WHEN_DAY =
            DateTimeFormatter.ofPattern("M/d(E)", Locale.TAIWAN);

    private static final Map<NoteCategory, String> HEADINGS = Map.of(
            NoteCategory.SCHEDULE, "🗓 行程",
            NoteCategory.TODO, "✅ 待辦",
            NoteCategory.IDEA, "💡 想法");

    /**
     * 單一 bubble 的 JSON 上限是 10KB（LINE 的規格）。留一成緩衝，
     * 因為超過的話整個請求被拒絕，使用者收到的是<b>什麼都沒有</b>。
     */
    private static final int MAX_BUBBLE_BYTES = 9_000;

    /** altText 的上限。它是被引用時顯示的那一行，超過會被拒。 */
    private static final int MAX_ALT_TEXT = 400;

    /** 灰階與綠色在 LINE 的淺色與深色主題下都讀得動，不指定背景色讓它跟著主題走。 */
    private static final String MUTED = "#8C8C8C";
    private static final String ACCENT = "#1DB446";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final CalendarCapability calendar;

    public CardRenderer(ObjectMapper objectMapper, CalendarCapability calendar) {
        this.objectMapper = objectMapper;
        this.calendar = calendar;
    }

    /**
     * @param altText 純文字版本，同時是被引用時顯示的內容
     * @param flexJson Flex 的 {@code contents}
     * @param cardId  這張卡的 id，寫在每顆按鈕的 postback data 裡
     * @param itemIds 實際排進卡片的項目，<b>順序即編號</b>——直接拿去當錨點
     */
    record Rendered(String altText, String flexJson, String cardId, List<Long> itemIds) {
    }

    /**
     * @param lineUserId 決定匯入按鈕長什麼樣——授權過的人是 postback，
     *                   其他人是一條開行事曆預填頁的連結（見 {@link CalendarCapability}）
     * @param notices    要放在清單前面的提醒（做不到的部分、引用對不上）。沒有就給空清單
     */
    Rendered render(List<NoteItem> items, String lineUserId, String heading, String footer,
            List<String> notices) {
        String cardId = newCardId();
        boolean direct = calendar.canImportDirectly(lineUserId);
        List<NoteItem> ordered = items.stream().sorted(NoteCategory.itemOrder()).toList();

        // 太長就從尾巴砍，砍到塞得進一個 bubble 為止。
        // 靜靜地被 LINE 拒絕的話，使用者收到的是「什麼都沒有」——那比少幾筆糟得多。
        // 同一個判斷見 LinePushClient 的 5000 字截斷。
        int hidden = 0;
        String flexJson;
        while (true) {
            List<NoteItem> shown = ordered.subList(0, ordered.size() - hidden);
            flexJson = toJson(bubble(shown, cardId, direct, heading, footer, notices, hidden));
            if (flexJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_BUBBLE_BYTES
                    || shown.size() <= 1) {
                break;
            }
            hidden++;
        }
        if (hidden > 0) {
            log.warn("清單太長塞不進一張卡片，末尾 {} 筆沒有顯示：總數={}", hidden, ordered.size());
        }

        List<NoteItem> shown = ordered.subList(0, ordered.size() - hidden);
        return new Rendered(
                altText(shown, heading, notices),
                flexJson,
                cardId,
                shown.stream().map(NoteItem::getId).toList());
    }

    // ── Flex ──────────────────────────────────────────────────────────────

    private Map<String, Object> bubble(List<NoteItem> items, String cardId, boolean direct,
            String heading, String footer, List<String> notices, int hidden) {
        List<Object> contents = new ArrayList<>();
        contents.add(text(heading, "md", true, null));

        for (String notice : notices) {
            contents.add(text(notice, "sm", false, MUTED));
        }

        if (items.isEmpty()) {
            // 只有抬頭跟結尾的卡片看起來像壞掉了。空清單是正常狀態，要講出來。
            contents.add(text("（目前沒有任何項目）", "sm", false, MUTED));
        } else {
            contents.addAll(itemBlocks(items, cardId, direct));
        }

        if (hidden > 0) {
            contents.add(text("⋯ 還有 " + hidden + " 筆沒顯示，說「列出行程」可以再看一次",
                    "xs", false, MUTED));
        }

        // 🔴 「全部加入」只有直接匯入那條路做得到。
        // 預填連結一條只能帶一筆事件，那是 Google 的規格——
        // 做不到的事就不要長出按鈕，一顆按下去只會說「做不到」的按鈕比沒有更氣人。
        if (direct && importableIds(items).size() > 1) {
            contents.add(button("📅 全部加入行事曆", "primary",
                    postback(cardId, "*", "全部加入行事曆")));
        }
        contents.add(text(footer.strip(), "xs", false, MUTED));

        return Map.of(
                "type", "bubble",
                "body", Map.of(
                        "type", "box",
                        "layout", "vertical",
                        "spacing", "sm",
                        "contents", contents));
    }

    /** 分類區塊與跨分類的連續編號。<b>順序必須跟純文字版一模一樣。</b> */
    private List<Object> itemBlocks(List<NoteItem> items, String cardId, boolean direct) {
        Map<NoteCategory, List<NoteItem>> byCategory = items.stream()
                .collect(Collectors.groupingBy(NoteItem::getCategory,
                        LinkedHashMap::new, Collectors.toList()));

        List<Object> blocks = new ArrayList<>();
        int index = 0;
        for (NoteCategory category : NoteCategory.DISPLAY_ORDER) {
            List<NoteItem> group = byCategory.get(category);
            if (group == null || group.isEmpty()) {
                continue;
            }
            blocks.add(text(HEADINGS.get(category), "sm", true, ACCENT));
            for (NoteItem item : group) {
                blocks.add(itemBlock(item, ++index, cardId, direct));
            }
        }
        return blocks;
    }

    private Map<String, Object> itemBlock(NoteItem item, int index, String cardId,
            boolean direct) {
        List<Object> lines = new ArrayList<>();
        // 時間放標題前面：掃視一串行程時，先找的是時間不是標題。
        if (item.getOccursAt() != null) {
            lines.add(text(index + ". " + formatWhen(item), "xs", false, MUTED));
            lines.add(text(item.getTitle(), "sm", true, null));
        } else {
            lines.add(text(index + ". " + item.getTitle(), "sm", true, null));
        }
        if (canImport(item)) {
            addImportButton(lines, item, cardId, direct);
        }
        return Map.of("type", "box", "layout", "vertical", "spacing", "xs",
                "margin", "md", "contents", lines);
    }

    /**
     * 兩種使用者，兩種按鈕。
     *
     * <p>授權過的走 postback：後端直接寫進行事曆，之後改時間會連動，
     * 而且按鈕文字會反映「已加入」。
     *
     * <p>其他人走 URI 連結：點下去開 Google 的預填頁，使用者要再按一次儲存。
     * <b>後端永遠不知道他按了沒</b>（URI action 不回報），所以這一種<b>沒有</b>
     * 「已加入」狀態——按鈕永遠是同一句話。那不是漏做，是這條路的性質：
     * 沒有 event id，就算知道他按了也不能連動（決策 27）。
     *
     * <p>連結組不出來時（{@code null}）就不長按鈕。走到那裡代表這一筆有時間、
     * 但連 URL 都塞不下——寧可少一顆按鈕，也不要送出一則會被 LINE 整個拒收的訊息。
     */
    private void addImportButton(List<Object> lines, NoteItem item, String cardId,
            boolean direct) {
        if (direct) {
            lines.add(button(
                    item.getGoogleEventId() == null ? "＋ 加入行事曆" : "✓ 已加入・重新同步",
                    "secondary",
                    postback(cardId, String.valueOf(item.getId()), "匯入行事曆")));
            return;
        }
        String link = calendar.importLinkFor(item);
        if (link != null) {
            lines.add(button("＋ 加入行事曆", "secondary", uri(link)));
        }
    }

    /** 開一個網址。label 由 {@link #button} 補上，跟 postback 那條路一致。 */
    private static Map<String, Object> uri(String url) {
        return Map.of("type", "uri", "uri", url);
    }

    /**
     * 匯入的閘門是「有沒有時間」，不是「被判成哪一類」。
     *
     * <p>分類是模型判的，而 SCHEDULE 與 TODO 的界線本來就模糊——
     * 「明天要交報告」很可能被判成 TODO 卻帶著時間，而那絕對是該進行事曆的東西。
     * 拿一個會錯的判斷當功能開關，錯的時候使用者只會看到「按鈕不見了」，
     * 而且不會知道為什麼。
     */
    private static boolean canImport(NoteItem item) {
        return item.getOccursAt() != null;
    }

    private static List<Long> importableIds(List<NoteItem> items) {
        return items.stream().filter(CardRenderer::canImport).map(NoteItem::getId).toList();
    }

    /**
     * 按鈕帶回來的東西。
     *
     * <p>{@code displayText} 讓點擊<b>立刻</b>在聊天室出現一則使用者自己發的訊息。
     * 這一步是免費的體感補償：真正的處理走 outbox，最快也要兩秒才有回應，
     * 中間沒有任何回饋的話，按鈕看起來就像壞的。
     *
     * <p>{@code i=*} 代表「這張卡上所有匯得進去的」，由後端從錨點展開。
     * 不把 id 全部塞進來是因為 data 有 300 字元上限，而清單長度沒有上限——
     * 塞得下不代表塞得穩。
     */
    private static Map<String, Object> postback(String cardId, String items, String displayText) {
        return Map.of(
                "type", "postback",
                "label", displayText,
                "data", "a=cal&c=" + cardId + "&i=" + items,
                "displayText", displayText);
    }

    private static Map<String, Object> button(String label, String style,
            Map<String, Object> action) {
        Map<String, Object> withLabel = new LinkedHashMap<>(action);
        withLabel.put("label", label);
        return Map.of("type", "button", "style", style, "height", "sm",
                "margin", "sm", "action", withLabel);
    }

    private static Map<String, Object> text(String content, String size, boolean bold,
            String color) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "text");
        node.put("text", content);
        node.put("size", size);
        node.put("wrap", true);
        if (bold) {
            node.put("weight", "bold");
        }
        if (color != null) {
            node.put("color", color);
        }
        return node;
    }

    // ── 純文字（altText，同時是推播失敗時的退路） ─────────────────────────

    /**
     * 卡片的純文字版本。
     *
     * <p>它會出現在兩個地方：使用者引用這則訊息時的引用框，以及 LINE 的推播通知列。
     * 兩處都只看得到開頭幾十個字，所以<b>抬頭要能自己站得住</b>。
     */
    static String altText(List<NoteItem> items, String heading, List<String> notices) {
        StringBuilder sb = new StringBuilder(heading);
        for (String notice : notices) {
            sb.append('\n').append(notice);
        }
        if (items.isEmpty()) {
            sb.append("\n\n（目前沒有任何項目）");
        } else {
            int index = 0;
            Map<NoteCategory, List<NoteItem>> byCategory = items.stream()
                    .collect(Collectors.groupingBy(NoteItem::getCategory));
            for (NoteCategory category : NoteCategory.DISPLAY_ORDER) {
                List<NoteItem> group = byCategory.get(category);
                if (group == null || group.isEmpty()) {
                    continue;
                }
                sb.append('\n').append(HEADINGS.get(category));
                for (NoteItem item : group) {
                    sb.append('\n').append(++index).append(". ");
                    if (item.getOccursAt() != null) {
                        sb.append(formatWhen(item)).append(' ');
                    }
                    sb.append(item.getTitle());
                }
            }
        }
        String text = sb.toString();
        return text.length() <= MAX_ALT_TEXT ? text : text.substring(0, MAX_ALT_TEXT - 1) + "…";
    }

    /**
     * 🔴 <b>只知道日期的項目不印時刻。</b>
     *
     * <p>{@code occursAt} 存的 09:00 有兩種來源，而 {@code timeSpecified} 分得出來
     * （見決策 26）。既然分得出來，就不該把一個沒人講過的「09:00」印在使用者眼前——
     * 那是 v8 之前的做法，而它讓人以為自己約了早上九點。
     *
     * <p>v8 之前的舊資料（{@code null}）當成只知道日期：那時候確實沒有人記下這件事，
     * 印一個可能是編的時刻，比少印一個資訊糟。
     */
    private static String formatWhen(NoteItem item) {
        var at = item.getOccursAt().atZone(ZONE);
        return Boolean.TRUE.equals(item.getTimeSpecified())
                ? WHEN.format(at)
                : WHEN_DAY.format(at);
    }

    /** 16 個 base36 字元。夠短塞得進 postback data，夠長不會撞。 */
    private static String newCardId() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(36), 36));
        }
        return sb.toString();
    }

    private String toJson(Map<String, Object> bubble) {
        try {
            return objectMapper.writeValueAsString(bubble);
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化 Flex 卡片失敗", e);
        }
    }
}
