-- 推播出去的 LINE 訊息 ID。使用者引用該訊息下指令時，
-- 用 webhook 的 quotedMessageId 反查是哪一批項目。
ALTER TABLE note_extractions ADD COLUMN notify_message_id VARCHAR(64);

CREATE INDEX idx_extractions_notify_message ON note_extractions (notify_message_id)
    WHERE notify_message_id IS NOT NULL;
