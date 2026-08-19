package io.svra.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交給模型的 JSON Schema 不可以把選填欄位列成必填。
 *
 * <p>這不是形式上的潔癖。schema 會原封不動接在 prompt 後面送給模型，
 * 還附著一句「output must adhere to ... without deviation」——所以欄位一旦必填，
 * 模型要嘛編一個值、要嘛整句拒絕，而<b>手寫的規則說什麼都沒有用</b>：
 * 那是同一份 prompt 裡的兩段話在打架，而機器產的那段語氣更硬。
 *
 * <p>實測就是這樣抓到的：「幫我加一筆待辦：驗證端到端流程」沒講時間，
 * 模型回了一段話解釋 schema 逼它一定要有 occursAt，所以它做不到。
 * 在那之前已經改過兩次手寫規則，都沒有用——改錯了半份。
 *
 * <p>而機器產的那半份不會出現在任何一個字串常數裡。少標一個註解沒有立即症狀，
 * 下一個加欄位的人只會看到模型又開始編日期。
 */
class CommandSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("每個動作只有 action 必填——itemIndex／title／occursAt／category 都是選填")
    void onlyActionIsRequiredWithinAnOp() {
        JsonNode required = schema().path("properties").path("ops").path("items").path("required");

        assertThat(required.valueStream().map(JsonNode::asString).toList())
                .as("多一個必填欄位，模型就得在用不到它的動作上編一個值出來")
                .containsExactly("action");
    }

    @Test
    @DisplayName("最外層只有 ops 必填——reason 與 unhandled 平常本來就該是空的")
    void onlyOpsIsRequiredAtTheTop() {
        assertThat(schema().path("required").valueStream().map(JsonNode::asString).toList())
                .containsExactly("ops");
    }

    private JsonNode schema() {
        return JSON.readTree(new BeanOutputConverter<>(NoteCommand.class).getJsonSchema());
    }
}
