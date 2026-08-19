package io.svra.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型寫的句子會原封不動出現在使用者的聊天視窗裡，所以它不能講開發者的話。
 *
 * <p>這一條是實測抓到的：使用者說「幫我加一筆待辦：驗證端到端流程」，
 * 收到的回覆是「未指定時間（occursAt），根據規則 ADD 動作需填寫 occursAt」。
 * prompt 已經要求它講人話，但 <b>prompt 是請求不是保證</b>——換個模型版本就可能又漏。
 */
class CommandReplyWordingTest {

    private static final String FALLBACK = "看不懂這個指令，可以換個說法嗎？";

    @Test
    @DisplayName("回覆帶著欄位名 → 換成固定說法")
    void replacesFieldNames() {
        assertThat(NoteCommandParser.userFacing(
                "未指定時間（occursAt），根據規則 ADD 動作需填寫 occursAt", FALLBACK))
                .isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("回覆帶著動作代號 → 換成固定說法")
    void replacesActionCodes() {
        assertThat(NoteCommandParser.userFacing("我只能做 DELETE 跟 LIST", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(NoteCommandParser.userFacing("這筆要分到 SCHEDULE 還是 TODO？", FALLBACK))
                .isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("正常的人話 → 原封不動，不要把好句子也吃掉")
    void keepsPlainLanguage() {
        String plain = "你說的那一筆我找不到，可以說編號嗎？";
        assertThat(NoteCommandParser.userFacing(plain, FALLBACK)).isEqualTo(plain);
    }

    @Test
    @DisplayName("空的就是空的——不要無中生有一句話給使用者")
    void keepsEmptyEmpty() {
        assertThat(NoteCommandParser.userFacing(null, FALLBACK)).isNull();
        assertThat(NoteCommandParser.userFacing("   ", FALLBACK)).isNull();
    }
}
