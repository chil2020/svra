-- LLM 抽取結果。
--
-- 分兩層的理由：換模型或改 prompt 時新增一筆 extraction，舊的原封不動，
-- 使用者可以比較後選一個。若把 model 放在項目上，每個項目都要重複記，
-- 而且切換版本會變成更新 N 筆。

CREATE TABLE note_extractions (
    id             BIGSERIAL    PRIMARY KEY,
    note_id        BIGINT       NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
    model          VARCHAR(64)  NOT NULL,
    prompt_version VARCHAR(32)  NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 一則 note 同時只能有一個生效版本。這條規則交給資料庫守，
-- 不放在應用層的約定裡（與 notes.source_message_id 的冪等同一個判斷）。
-- 部分索引讓已停用的舊版本不受限制。
CREATE UNIQUE INDEX uk_active_extraction
    ON note_extractions (note_id) WHERE is_active;

CREATE INDEX idx_extractions_note ON note_extractions (note_id, created_at DESC);

CREATE TABLE note_items (
    id            BIGSERIAL    PRIMARY KEY,
    extraction_id BIGINT       NOT NULL REFERENCES note_extractions(id) ON DELETE CASCADE,
    category      VARCHAR(16)  NOT NULL,
    title         TEXT         NOT NULL,
    occurs_at     TIMESTAMPTZ,
    detail        TEXT,
    tags          TEXT[]
);

-- 主要查詢是「某段期間有哪些行程／待辦」
CREATE INDEX idx_items_category_occurs ON note_items (category, occurs_at);
CREATE INDEX idx_items_extraction ON note_items (extraction_id);
