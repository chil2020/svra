-- Transactional Outbox
--
-- 問題：note 寫進 PostgreSQL、任務發到 RabbitMQ，是兩個不同的系統。
-- 沒有辦法讓它們一起成功或一起失敗——note 存好了但發送失敗的話，
-- 那筆 note 會永遠停在 PENDING，而且因為冪等，重送也救不回來。
--
-- 解法：把「要發送」這件事跟 note 寫在同一個交易裡。交易成功 = 意圖已持久化，
-- 之後由 poller 負責真的送出去。RabbitMQ 掛掉只會延遲，不會遺失。

CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

-- 部分索引：poller 只查 PENDING，已送出的資料再多也不影響查詢成本
CREATE INDEX idx_outbox_pending ON outbox_events (next_attempt_at)
    WHERE status = 'PENDING';
