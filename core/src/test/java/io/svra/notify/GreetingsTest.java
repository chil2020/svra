package io.svra.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import io.svra.outbox.OutboxEventRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 歡迎訊息與「使用說明」<b>必須是同一段文字</b>。
 *
 * <p>🔴 各寫一份的話，改了其中一份就開始漂移，而漂移的症狀是
 * <b>使用者照著說明做、而系統的行為是另一套</b>——那比沒有說明更糟，
 * 因為他會相信那份說明。
 *
 * <p>這件事沒有測試就守不住：兩段文字看起來都對，而「它們不一樣」
 * 只有把兩邊擺在一起才看得出來。
 */
@ExtendWith(MockitoExtension.class)
class GreetingsTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private Greetings greetings;

    @Test
    @DisplayName("🔴 歡迎訊息裡包含說明本文，兩邊共用同一份來源")
    void theWelcomeContainsTheSameHelpText() {
        when(outboxRepository.insertIfAbsent(anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(1);

        greetings.welcome("U4af4980629", "wh-1", "reply-token");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository).insertIfAbsent(any(), any(), any(),
                payload.capture(), any());

        // 逐行比對而不是整段：payload 是 JSON，換行被跳脫過了。
        for (String line : Greetings.helpText().split("\n")) {
            if (!line.isBlank()) {
                assertThat(payload.getValue())
                        .as("說明本文的這一行沒有出現在歡迎訊息裡：%s", line)
                        .contains(line);
            }
        }
    }

    @Test
    @DisplayName("說明本文不含招呼語與「現在就試」——那兩句是為第一次寫的")
    void theHelpTextDropsTheFirstTimeOnlyLines() {
        // 用了三週的人點「使用說明」，收到一句「我是 SVRA」會像認錯人。
        assertThat(Greetings.helpText())
                .doesNotContain("我是 SVRA")
                .doesNotContain("現在就傳一段語音試試看");
    }

    @Test
    @DisplayName("說明要提到選單——選單預設收合，不講就沒有人會發現它")
    void theHelpTextPointsAtTheMenu() {
        assertThat(Greetings.helpText()).contains("選單");
    }
}
