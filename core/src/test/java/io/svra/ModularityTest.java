package io.svra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 把「不要跨模組亂呼叫」從慣例變成會失敗的測試。
 *
 * <p>package-private 擋得住同 package 內的細節外流，但擋不住兩個模組的 public
 * 型別互相依賴。這個測試補上那一段——決策 7 宣稱的邊界，到這裡才真的有人在守。
 */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(Application.class);

    @Test
    @DisplayName("模組之間沒有非法相依（例如環狀依賴、繞過模組入口）")
    void verifiesModularStructure() {
        MODULES.verify();
    }

    @Test
    @DisplayName("產出模組關係圖，讓結構的變化在 review 時看得見")
    void writesDocumentation() {
        new Documenter(MODULES).writeDocumentation();
    }
}
