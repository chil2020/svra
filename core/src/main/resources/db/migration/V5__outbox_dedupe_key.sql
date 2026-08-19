-- Outbox 的冪等鍵。
--
-- 起因：`NoteCommandService.recordCommand()` 一直寫著
-- `catch (DataIntegrityViolationException)`，但 outbox_events 上沒有任何唯一約束，
-- 那段 catch 是永遠不會執行的死碼。語音有 notes.source_message_id 擋著重複投遞，
-- 文字指令這條路一道防線都沒有——LINE webhook 逾時重送，指令就執行兩次。
-- 跟 V1 的判斷一樣：防線要由資料庫強制，不能只寫在應用層。
--
-- 為什麼是獨立欄位，而不是 UNIQUE (aggregate_id, event_type)：
-- 不是每種事件都只該發生一次。決策 9 明講了「換模型重跑抽取、兩版並存」是預期的用法，
-- 整張表的唯一約束會把那條路永久堵死。用可為 NULL 的欄位，
-- 由產生事件的那一方決定「這件事只該做一次」——PostgreSQL 的唯一索引不管 NULL，
-- 沒填的事件依然可以重複發。
ALTER TABLE outbox_events ADD COLUMN dedupe_key VARCHAR(160);

-- 部分索引：只約束有填鍵的列
CREATE UNIQUE INDEX uk_outbox_dedupe_key
    ON outbox_events (dedupe_key) WHERE dedupe_key IS NOT NULL;

-- 既有資料回填。同一組 (event_type, aggregate_id) 只回填最早那筆，
-- 其餘留 NULL——歷史上真的發生過的重複就讓它留著，不假裝當初有擋。
UPDATE outbox_events e
   SET dedupe_key = e.event_type || ':' || e.aggregate_id
  FROM (SELECT id,
               row_number() OVER (PARTITION BY event_type, aggregate_id ORDER BY id) AS rn
          FROM outbox_events) d
 WHERE d.id = e.id AND d.rn = 1;
