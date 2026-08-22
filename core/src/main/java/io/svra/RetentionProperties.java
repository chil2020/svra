package io.svra;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 舊資料留多久。
 *
 * <p>這個系統有三張<b>只增不減</b>的表：outbox 事件、訊息錨點、投遞紀錄。
 * 個人規模下要很久才會有感，但「很久」不是「不會」——而且它跟一個更直接的問題
 * 是同一件事：<b>舊資料沒有人負責</b>。
 *
 * <p>兩個保留期不一樣，因為它們過期的理由不同：
 *
 * <ul>
 * <li><b>outbox 事件</b>——SENT 的已經做完了，payload 在標記 SENT 時就清掉了
 * （見 {@code OutboxEvent.markSent}），剩下的只是「這件事發生過」的痕跡。
 * FAILED 的<b>不刪</b>：它們是還沒有人去看的問題</li>
 * <li><b>訊息錨點與投遞紀錄</b>——錨點決定使用者能不能引用一則<b>舊訊息</b>來下指令，
 * 而 LINE 的聊天紀錄是永久的。刪太快的結果是「往上滑找到那則卡片，回覆它，
 * 卻被告訴『這則我對不上』」。所以它該比 outbox 長得多</li>
 * </ul>
 *
 * @param sentEvents     SENT 的 outbox 事件留多久
 * @param messageAnchors 訊息錨點與投遞紀錄留多久
 */
@ConfigurationProperties(prefix = "svra.retention")
public record RetentionProperties(Duration sentEvents, Duration messageAnchors) {

    public RetentionProperties {
        sentEvents = sentEvents == null ? Duration.ofDays(30) : sentEvents;
        messageAnchors = messageAnchors == null ? Duration.ofDays(180) : messageAnchors;
    }
}
