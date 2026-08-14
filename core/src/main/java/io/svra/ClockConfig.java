package io.svra;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 給需要「現在」的地方用。
 *
 * <p>注意抽取層<b>不</b>用這個：相對日期（「明天」）要以錄音當下為基準，
 * 那是資料本身的屬性，從 {@code notes.created_at} 拿，不是環境的當下時刻。
 * 兩者平常只差幾秒，但重跑舊資料或佇列積壓時會整個錯開。
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
