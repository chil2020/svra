package io.svra.extract;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;

import io.svra.note.NoteCategory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快取存得進去、也要讀得回來。
 *
 * <p>為什麼值得為這件事寫一個測試：{@code ExtractedNote} 是<b>package-private 的
 * record</b>，而快取的錯誤處理是<b>刻意設計成「失敗就當沒快取，照常呼叫模型」</b>
 * （見 {@code LlmCacheConfig} 的 CacheErrorHandler）。兩件事加起來的後果是——
 * 序列化只要壞掉，命中率就是 0，而應用<b>完全正常運作、只是變慢</b>，
 * log 裡除了幾行 warn 什麼都不會發生。
 *
 * <p>刻意設計成「安靜地降級」的東西，就需要有人在別的地方大聲檢查。
 */
class ExtractionCacheSerializationTest {

    @Test
    @DisplayName("抽取結果經 Redis 序列化器往返後，內容完全一致")
    void extractedNoteSurvivesRoundTrip() {
        JacksonJsonRedisSerializer<ExtractedNote> serializer =
                new JacksonJsonRedisSerializer<>(ExtractedNote.class);

        ExtractedNote original = new ExtractedNote(List.of(
                new ExtractedNote.Item(NoteCategory.SCHEDULE, "前往奮起湖",
                        "2026-08-16T09:00:00+08:00", "跟家人一起", List.of("旅遊")),
                // 沒有時間、沒有補充、沒有標籤的那種——null 與空清單也要能往返
                new ExtractedNote.Item(NoteCategory.IDEA, "履歷可以用佇列深度當指標",
                        null, null, List.of())));

        Object restored = serializer.deserialize(serializer.serialize(original));

        assertThat(restored)
                .as("反序列化要還原成同一個型別，不是 LinkedHashMap")
                .isInstanceOf(ExtractedNote.class)
                .isEqualTo(original);
    }
}
