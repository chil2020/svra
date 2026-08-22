-- 「這筆 outbox 事件的訊息已經送出去了」。
--
-- outbox 是 at-least-once（決策 3），而**送訊息沒有辦法冪等**：
-- 行事曆那條可以靠決定性 event id 讓 Google 自己回 409，但 LINE 沒有對應的東西——
-- 同一則訊息送兩次就是兩則訊息。
--
-- 而重跑不是理論風險：poller 的 dispatch() 是**整批一個交易**，markSent 要等
-- 整批跑完才提交，而同一批裡的 EXTRACT 事件實測要跑 17 秒。在那 17 秒內重啟，
-- 同批已經推播出去的事件會全部重跑 → 使用者收到重複的卡片。
--
-- 🔴 **為什麼是獨立的表，而不是 outbox_events 上的一個欄位。**
-- poller 的外層交易用 FOR UPDATE SKIP LOCKED 鎖著那一列，而 handler 跑在交易外
-- （決策 18 讓開的那一層）。handler 去更新同一列會等一個**永遠不會釋放的鎖**：
-- 外層在等 handler，handler 在等外層的鎖。獨立的表沒有這個問題。
--
-- 順帶成為推播的稽核軌跡：誰、什麼時候、LINE 給的訊息 id 是什麼。
-- 那是 V12 那兩欄之外，唯一能回答「他到底收到了什麼」的地方。
CREATE TABLE outbox_deliveries (
    outbox_event_id BIGINT      PRIMARY KEY,
    line_user_id    VARCHAR(64) NOT NULL,
    line_message_id VARCHAR(64),
    delivered_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 不設 FK 指向 outbox_events：那張表會被保留期清掉（見 OutboxRetention），
-- 而投遞紀錄的保留期不必跟它一樣。加了 FK 就等於把兩者的生命週期綁死。
CREATE INDEX idx_deliveries_user ON outbox_deliveries (line_user_id, delivered_at DESC);
