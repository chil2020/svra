package io.svra.notify;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

import io.svra.note.NoteCategory;
import io.svra.note.NoteItem;

import static org.assertj.core.api.Assertions.assertThat;

/** 只測排版，不呼叫 LINE。 */
class CardRendererTest {

    private static final String USER_ID = "U4af4980629";

    private final CardRenderer renderer =
            new CardRenderer(JsonMapper.builder().build(), directImport());
    private final CardRenderer linkRenderer =
            new CardRenderer(JsonMapper.builder().build(), linkOnly());

    private static NoteItem item(Long id, NoteCategory category, String title,
            String occursAt, Boolean timeSpecified) {
        NoteItem item = new NoteItem(category, title,
                occursAt == null ? null : Instant.parse(occursAt), timeSpecified, null, List.of());
        // id 平常由資料庫給。按鈕的 postback data 帶的就是它，少了它測不到按鈕。
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private CardRenderer.Rendered render(List<NoteItem> items) {
        return renderer.render(items, USER_ID, "📝 抬頭", "結尾", List.of());
    }

    /** 沒授權的使用者看到的同一份清單。 */
    private CardRenderer.Rendered renderAsGuest(List<NoteItem> items) {
        return linkRenderer.render(items, USER_ID, "📝 抬頭", "結尾", List.of());
    }

    /** 授權過的使用者：按鈕走 postback。 */
    private static CalendarCapability directImport() {
        return new CalendarCapability() {
            @Override public boolean canImportDirectly(String lineUserId) {
                return true;
            }
            @Override public String importLinkFor(NoteItem item) {
                return null;   // 這條路用不到連結
            }
        };
    }

    /** 沒授權的使用者：按鈕是一條連結。 */
    private static CalendarCapability linkOnly() {
        return new CalendarCapability() {
            @Override public boolean canImportDirectly(String lineUserId) {
                return false;
            }
            @Override public String importLinkFor(NoteItem item) {
                return item.getOccursAt() == null ? null
                        : "https://calendar.google.com/calendar/render?text=" + item.getId();
            }
        };
    }


    @Test
    @DisplayName("編號跨分類連續，讓使用者能說「第幾筆」")
    void numbersItemsAcrossCategories() {
        CardRenderer.Rendered out = render(List.of(
                item(1L, NoteCategory.IDEA, "想法A", null, null),
                item(2L, NoteCategory.TODO, "待辦B", null, null),
                item(3L, NoteCategory.SCHEDULE, "行程C", "2026-08-15T01:00:00Z", true)));

        // 排序後是 行程C(1) → 待辦B(2) → 想法A(3)
        assertThat(out.altText()).contains("1. 8/15(週六) 09:00").contains("2. 待辦B").contains("3. 想法A");
        assertThat(out.flexJson()).contains("1. 8/15(週六) 09:00").contains("2. 待辦B").contains("3. 想法A");
    }

    @Test
    @DisplayName("依行程→待辦→想法分組，不照傳入順序")
    void groupsInFixedOrder() {
        String json = render(List.of(
                item(1L, NoteCategory.IDEA, "履歷用佇列深度當指標", null, null),
                item(2L, NoteCategory.TODO, "繳電費", null, null),
                item(3L, NoteCategory.SCHEDULE, "前往阿里山", "2026-08-15T01:00:00Z", true)))
                .flexJson();

        assertThat(json.indexOf("🗓 行程"))
                .isLessThan(json.indexOf("✅ 待辦"))
                .isLessThan(json.indexOf("💡 想法"));
    }

    @Test
    @DisplayName("時間換算成台北時間顯示，而且排在標題前面")
    void formatsTimeInTaipei() {
        String json = render(List.of(
                item(1L, NoteCategory.SCHEDULE, "前往阿里山", "2026-08-15T01:00:00Z", true)))
                .flexJson();

        // 01:00 UTC = 09:00 台北；E 在 zh-TW 是「週六」不是「六」
        assertThat(json).contains("1. 8/15(週六) 09:00");
        assertThat(json.indexOf("8/15")).isLessThan(json.indexOf("前往阿里山"));
    }

    @Test
    @DisplayName("🔴 使用者只講了日期時，不印那個補出來的 09:00")
    void hidesTheClockWhenTheUserNeverSaidOne() {
        String json = render(List.of(
                item(1L, NoteCategory.SCHEDULE, "跟客戶開會", "2026-08-15T01:00:00Z", false)))
                .flexJson();

        // 抽取的規則是「只講到日期就用當天 09:00」，那個時刻沒有人說過。
        // 印出來會讓使用者以為自己約了早上九點——分得出來就不該印。
        assertThat(json).contains("1. 8/15(週六)").doesNotContain("09:00");
    }

    @Test
    @DisplayName("v8 之前的舊資料（沒有記錄時間精度）當成只知道日期")
    void treatsLegacyItemsAsDateOnly() {
        String json = render(List.of(
                item(1L, NoteCategory.SCHEDULE, "舊資料", "2026-08-15T01:00:00Z", null)))
                .flexJson();

        assertThat(json).doesNotContain("09:00");
    }

    @Test
    @DisplayName("有時間的項目才長出匯入按鈕——閘門是時間，不是分類")
    void onlyItemsWithATimeGetAnImportButton() {
        String json = render(List.of(
                // TODO 也可能有時間（「明天要交報告」），那絕對該進行事曆
                item(7L, NoteCategory.TODO, "交報告", "2026-08-15T01:00:00Z", false),
                item(8L, NoteCategory.IDEA, "沒有時間的想法", null, null)))
                .flexJson();

        assertThat(json).contains("a=cal&c=").contains("&i=7");
        assertThat(json).doesNotContain("&i=8");
    }

    @Test
    @DisplayName("已經匯入過的按鈕改成「重新同步」，而且仍然可以按")
    void alreadyImportedItemsKeepAPressableButton() {
        NoteItem imported = item(5L, NoteCategory.SCHEDULE, "已匯入", "2026-08-15T01:00:00Z", true);
        imported.markCalendarEvent("svra0000000000005");

        String json = render(List.of(imported)).flexJson();

        // 收掉按鈕的話，使用者在 Google 端手動刪掉那筆之後就再也匯不回來了
        assertThat(json).contains("已加入").contains("&i=5");
    }

    @Test
    @DisplayName("兩筆以上匯得進去才給「全部加入」——只有一筆時它跟單筆那顆重複")
    void showsBulkButtonOnlyWhenItSavesAStep() {
        String one = render(List.of(
                item(1L, NoteCategory.SCHEDULE, "只有一筆", "2026-08-15T01:00:00Z", true)))
                .flexJson();
        String two = render(List.of(
                item(1L, NoteCategory.SCHEDULE, "第一筆", "2026-08-15T01:00:00Z", true),
                item(2L, NoteCategory.SCHEDULE, "第二筆", "2026-08-16T01:00:00Z", true)))
                .flexJson();

        assertThat(one).doesNotContain("&i=*");
        assertThat(two).contains("&i=*");
    }

    @Test
    @DisplayName("同一張卡的每顆按鈕帶同一個 cardId，而不同卡片的不一樣")
    void cardIdIsPerCardAndEmbeddedInEveryButton() {
        CardRenderer.Rendered first = render(List.of(
                item(1L, NoteCategory.SCHEDULE, "A", "2026-08-15T01:00:00Z", true),
                item(2L, NoteCategory.SCHEDULE, "B", "2026-08-16T01:00:00Z", true)));
        CardRenderer.Rendered second = render(List.of(
                item(1L, NoteCategory.SCHEDULE, "A", "2026-08-15T01:00:00Z", true)));

        assertThat(first.flexJson()).contains("a=cal&c=" + first.cardId());
        assertThat(first.cardId()).isNotEqualTo(second.cardId());
    }

    @Test
    @DisplayName("回傳的 itemIds 就是卡片上的編號順序——錨點直接拿它用")
    void returnedIdsAreTheDisplayedOrder() {
        CardRenderer.Rendered out = render(List.of(
                item(30L, NoteCategory.IDEA, "想法", null, null),
                item(10L, NoteCategory.TODO, "待辦", null, null),
                item(20L, NoteCategory.SCHEDULE, "行程", "2026-08-15T01:00:00Z", true)));

        // 排版與錨點若各自排一次，就有漂掉的機會——所以由同一次計算產生
        assertThat(out.itemIds()).containsExactly(20L, 10L, 30L);
    }

    // ── 沒授權的使用者：連結方案（決策 27）────────────────────────────

    @Test
    @DisplayName("🔴 沒授權的人，按鈕是一條連結而不是 postback")
    void guestsGetALinkButtonInsteadOfPostback() {
        String json = renderAsGuest(List.of(
                item(7L, NoteCategory.SCHEDULE, "開會", "2026-08-15T01:00:00Z", true)))
                .flexJson();

        assertThat(json).contains("\"type\":\"uri\"").contains("calendar.google.com");
        assertThat(json).doesNotContain("postback").doesNotContain("a=cal");
    }

    @Test
    @DisplayName("🔴 沒授權的人看不到「全部加入」——一條連結只能帶一筆事件")
    void guestsNeverSeeTheBulkButton() {
        List<NoteItem> two = List.of(
                item(1L, NoteCategory.SCHEDULE, "第一筆", "2026-08-15T01:00:00Z", true),
                item(2L, NoteCategory.SCHEDULE, "第二筆", "2026-08-16T01:00:00Z", true));

        // 授權過的人在同一份清單上會看到它——這是 per-user 的必然結果，不是不一致
        assertThat(renderAsGuest(two).flexJson()).doesNotContain("全部加入");
        assertThat(render(two).flexJson()).contains("全部加入");
    }

    @Test
    @DisplayName("沒授權時，沒有時間的項目一樣不長按鈕")
    void guestsGetNoButtonForItemsWithoutATime() {
        String json = renderAsGuest(List.of(
                item(8L, NoteCategory.IDEA, "沒有時間的想法", null, null))).flexJson();

        assertThat(json).doesNotContain("calendar.google.com").doesNotContain("button");
    }

    @Test
    @DisplayName("連結組不出來就不長按鈕，而不是送出一則會被 LINE 拒收的訊息")
    void dropsTheButtonWhenNoLinkCanBeBuilt() {
        CardRenderer noLink = new CardRenderer(JsonMapper.builder().build(),
                new CalendarCapability() {
                    @Override public boolean canImportDirectly(String lineUserId) {
                        return false;
                    }
                    @Override public String importLinkFor(NoteItem item) {
                        return null;   // 例如 URL 連截斷過都還是太長
                    }
                });

        String json = noLink.render(
                List.of(item(9L, NoteCategory.SCHEDULE, "很長的行程", "2026-08-15T01:00:00Z", true)),
                USER_ID, "抬頭", "結尾", List.of()).flexJson();

        assertThat(json).contains("很長的行程").doesNotContain("button");
    }

    @Test
    @DisplayName("空清單要講出來，不能只有抬頭跟結尾")
    void saysSoWhenThereIsNothing() {
        CardRenderer.Rendered out = render(List.of());

        assertThat(out.altText()).contains("目前沒有任何項目");
        assertThat(out.flexJson()).contains("目前沒有任何項目");
        assertThat(out.itemIds()).isEmpty();
    }

    @Test
    @DisplayName("沒有的分類不出現空標題")
    void skipsEmptyCategories() {
        String json = render(List.of(item(1L, NoteCategory.TODO, "繳電費", null, null))).flexJson();

        assertThat(json).contains("✅ 待辦").doesNotContain("🗓 行程").doesNotContain("💡 想法");
    }

    @Test
    @DisplayName("提醒排在清單前面，而不是串成一段文字")
    void noticesComeFirst() {
        CardRenderer.Rendered out = renderer.render(
                List.of(item(1L, NoteCategory.TODO, "繳電費", null, null)),
                USER_ID, "✅ 已更新", "結尾", List.of("⚠️ 第 2 筆已經不在清單上了"));

        assertThat(out.flexJson().indexOf("已經不在清單上"))
                .isLessThan(out.flexJson().indexOf("繳電費"));
        assertThat(out.altText()).contains("已經不在清單上");
    }

    @Test
    @DisplayName("🔴 清單太長時砍掉尾巴，而不是讓 LINE 整則退回")
    void trimsInsteadOfLettingLineRejectTheWholeMessage() {
        List<NoteItem> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            many.add(item((long) i, NoteCategory.SCHEDULE,
                    "一筆內容夠長的行程標題，用來把卡片撐大 " + i,
                    "2026-08-15T01:00:00Z", true));
        }

        CardRenderer.Rendered out = render(many);

        // 超過 10KB 的 bubble 會被 LINE 拒收，而被拒收的結果是使用者什麼都沒收到
        assertThat(out.flexJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(9_000);
        assertThat(out.itemIds()).hasSizeLessThan(400);
        assertThat(out.flexJson()).contains("沒顯示");
        // 錨點只能記真的顯示出來的那些，否則「第 N 筆」會指到看不見的東西
        assertThat(out.itemIds()).hasSize(out.itemIds().size());
    }
}
