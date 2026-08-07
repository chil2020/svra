-- SVRA 初始 schema
--
-- 設計重點：
--   1. source_message_id 的 UNIQUE 約束是「冪等」的實作核心。
--      LINE webhook 逾時會重送同一則訊息（at-least-once），重送時第二筆 INSERT
--      會被資料庫擋下並拋出 DataIntegrityViolationException，程式視為「已處理過」。
--      為什麼不用「先 SELECT 再 INSERT」：兩個執行緒可能同時查到「不存在」然後都插入
--      （race condition）。唯一約束是唯一由資料庫層強制、跨多實例都有效的原子保證。
--   2. status 讓一則筆記有明確的生命週期：webhook 收到時建 PENDING，
--      轉錄結果回來才補上 transcript 並轉 COMPLETED。
--      這樣冪等檢查發生在最早的時間點（webhook），而不是等轉錄完才發現重複。

CREATE TABLE notes (
    id                 BIGSERIAL    PRIMARY KEY,
    line_user_id       VARCHAR(64)  NOT NULL,
    source_message_id  VARCHAR(64)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    transcript         TEXT,
    language           VARCHAR(16),
    audio_duration_sec REAL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_notes_source_message_id UNIQUE (source_message_id)
);

-- 查某使用者的筆記（時間新到舊）是主要查詢路徑
CREATE INDEX idx_notes_user_created ON notes (line_user_id, created_at DESC);
