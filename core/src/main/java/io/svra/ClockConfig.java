package io.svra;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 正式執行用系統時鐘；測試可以覆蓋成固定時刻。
 *
 * <p>測試要換掉的話，提供一個名稱不同、標了 {@code @Primary} 的 Clock bean——
 * 同名會撞成 BeanDefinitionOverrideException。
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
