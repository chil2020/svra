-- 讓「這個使用者做過什麼」查得回來。
--
-- 起因是一次盤點：107 筆 outbox 事件裡有 47 筆 join 不回任何 note
-- （COMMAND 28、PUSH_TEXT 14、CALENDAR_SYNC 5），而 command_executions 11 筆
-- **一筆都對不到**。因為文字指令不建 note，postback 用的是 webhookEventId——
-- 兩者都不是 notes.source_message_id。
--
-- 使用者資訊其實一直都在，只是躺在 payload 的 JSON 字串裡：沒有索引、
-- 要全表掃描、而且 command_executions 連 payload 都沒有。
--
-- 「這個人的筆記」查得到，「這個人做過什麼操作」查不到——而出事時要查的
-- 通常是後者。等到那天再補就來不及了，因為歷史資料補不回來。
--
-- 可為 NULL：舊資料無法一一還原（payload 裡有的可以，command_executions 沒有），
-- 而回填猜測值就是在資料庫裡寫下沒有人說過的事實。
ALTER TABLE outbox_events      ADD COLUMN line_user_id VARCHAR(64);
ALTER TABLE command_executions ADD COLUMN line_user_id VARCHAR(64);

-- 回填 outbox：payload 是 JSON，而每一種 payload 都有 lineUserId。
-- 只回填解析得出來的，解析不出來的留 NULL——那才是實情。
UPDATE outbox_events
   SET line_user_id = payload::jsonb ->> 'lineUserId'
 WHERE line_user_id IS NULL
   AND payload ~ '^\s*\{'
   AND (payload::jsonb ->> 'lineUserId') IS NOT NULL;

-- command_executions 沒有任何可以回填的來源：它只存過 message id。
-- 舊資料就是查不到了，這一欄從現在開始才有意義。

-- 查詢形態是「某個使用者最近做了什麼」，所以時間要跟著進索引。
CREATE INDEX idx_outbox_user_created
    ON outbox_events (line_user_id, created_at DESC) WHERE line_user_id IS NOT NULL;
CREATE INDEX idx_command_executions_user
    ON command_executions (line_user_id, executed_at DESC) WHERE line_user_id IS NOT NULL;
