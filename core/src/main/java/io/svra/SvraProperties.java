package io.svra;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param audioDir core 寫入音檔的位置。與 worker 的掛載對應（見 docker-compose）。
 */
@ConfigurationProperties(prefix = "svra")
public record SvraProperties(String audioDir) {

    private static final Logger log = LoggerFactory.getLogger(SvraProperties.class);

    public SvraProperties {
        // 相對路徑會依「app 從哪裡啟動」而變——mvn spring-boot:run 的工作目錄是 core/，
        // 但 compose 掛載的是 repo 根目錄的 data/audio。啟動時印出解析後的絕對路徑，
        // 讓這種錯配一眼看得出來，而不是等 worker 找不到檔案才發現。
        log.info("音檔目錄：{} → {}", audioDir, Path.of(audioDir).toAbsolutePath().normalize());
    }
}
