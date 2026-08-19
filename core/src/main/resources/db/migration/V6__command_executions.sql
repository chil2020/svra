-- 指令的執行紀錄。
--
-- 起因：V5 的 dedupe_key 擋住了 LINE 的重送——同一則指令訊息只會寫下一筆 outbox 事件。
-- 但 outbox 自己是 at-least-once：處理器成功提交、poller 的 markSent 卻失敗時，
-- 同一筆事件會再跑一次（見 OutboxPoller.runOutsideOwnTransaction 的說明）。
--
-- 對「插入」而言重跑是無害的，唯一鍵擋著。對指令不是：「刪掉第一筆」是<位置性>的，
-- 重跑時清單已經少了一筆，同樣的「第一筆」指向的是另一個項目。
-- 它會成功、會回覆「已刪除」，而刪掉的是別筆。沒有任何錯誤訊息。
--
-- 所以指令要的不是「不要重複記錄」，而是「不要重複執行」。
-- 一則指令訊息只能有一次執行，鍵就是它的 messageId。
--
-- 為什麼不塞進 outbox_events.dedupe_key：那張表記的是「要送什麼」，不是「做過什麼」。
-- 同一個鍵在兩張表代表不同的事，混在一起之後，「這筆事件送出去了沒」
-- 與「這個指令執行過沒」就再也分不開了。
CREATE TABLE command_executions (
    command_message_id VARCHAR(64)  PRIMARY KEY,
    -- 只給人看的欄位。它不會拿去跟任何時刻比大小，所以用資料庫的 now()
    -- 沒有決策 19 那個「兩個時鐘」的問題。
    executed_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
