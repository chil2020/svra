package io.svra.calendar;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 啟動完成後，用 refresh token 換一次 access token。
 *
 * <p>{@code @NotBlank} 擋得住「沒填」，擋不住「填了但是錯的」。而錯的 refresh token
 * 的症狀是「按鈕按下去，幾分鐘後收到一則失敗通知」——那時你已經在用了。
 *
 * <p>放在 {@code ApplicationReadyEvent} 而不是建構子：換 token 是一次網路呼叫，
 * 塞在 bean 初始化裡會讓「Google 暫時連不上」變成「應用起不來」，
 * 而那兩件事的嚴重程度差很多。
 */
@Component
class CalendarStartupCheck {

    private final GoogleTokenProvider tokenProvider;
    private final CalendarProperties properties;

    CalendarStartupCheck(GoogleTokenProvider tokenProvider, CalendarProperties properties) {
        this.tokenProvider = tokenProvider;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void verify() {
        // 白名單是空的＝所有人走連結（決策 27），根本不會用到 OAuth。
        // 還是去換 token 的話，每次啟動都會印一行紅色的「授權是壞的」——
        // 而那個部署一切正常。**會一直誤報的檢查，等於沒有檢查。**
        if (properties.oauthUserIds().isEmpty()) {
            return;
        }
        tokenProvider.verifyOnStartup();
    }
}
