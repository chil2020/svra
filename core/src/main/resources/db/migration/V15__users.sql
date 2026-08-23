-- 使用者表。V11 的註解說「真的需要使用者表的那天（多租戶 OAuth）再建，
-- 那時它會有更多欄位要放」——就是今天。
--
-- 🔴 建它的理由不是「應該要有一張使用者表」，是**關於一個使用者的事實
-- 現在散在四個地方**：blocked_users（表）、CALENDAR_OAUTH_USER_IDS、
-- GOOGLE_REFRESH_TOKEN、GOOGLE_CALENDAR_ID（三個環境變數）。
-- 而環境變數天生只能有一份——「只有一個人能直接匯入」這個限制不是產品決定，
-- 是儲存方式決定的。
--
-- 主鍵直接用 line_user_id 而不另發內部 id：所有現有的表都已經存著它，
-- 加外鍵就完工、一列資料都不用搬。代價是「同一個人換 LINE 帳號」要搬資料——
-- 而這個 bot 不做 App、只活在 LINE 上，那天不會來。
CREATE TABLE users (
    line_user_id  VARCHAR(64) PRIMARY KEY,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL = 沒被封鎖。取代 blocked_users 整張表。
    -- 用時間而不是 boolean：「他什麼時候封鎖的」是查得到才有意義的事實，
    -- 而 boolean 一旦翻回 false 就永遠不知道發生過。
    blocked_at    TIMESTAMPTZ
);

-- 回填。**不能只看 notes**：一個人可能傳過指令、收過推播，卻沒有任何一則語音
-- （例如加了好友、打了「目前還有什麼」就走了）。漏掉他，下面的外鍵就建不起來，
-- 而錯誤訊息會是一個看不出所以然的約束違反。
INSERT INTO users (line_user_id, first_seen_at)
SELECT line_user_id, min(seen_at)
FROM (
    SELECT line_user_id, created_at   AS seen_at FROM notes
    UNION ALL SELECT line_user_id, created_at    FROM message_anchors
    UNION ALL SELECT line_user_id, created_at    FROM outbox_events      WHERE line_user_id IS NOT NULL
    UNION ALL SELECT line_user_id, delivered_at  FROM outbox_deliveries
    UNION ALL SELECT line_user_id, executed_at   FROM command_executions WHERE line_user_id IS NOT NULL
    UNION ALL SELECT line_user_id, blocked_at    FROM blocked_users
) seen
GROUP BY line_user_id;

-- blocked_users 併進來後就沒有存在的必要了
UPDATE users u SET blocked_at = b.blocked_at
  FROM blocked_users b WHERE b.line_user_id = u.line_user_id;

DROP TABLE blocked_users;

-- 外鍵。全部 ON DELETE CASCADE——**這就是建這張表最實際的回報**：
-- 「刪掉這個使用者的所有資料」從「記得掃五張表、而且要照對的順序」
-- 變成 DELETE FROM users WHERE line_user_id = ?。
--
-- 漏掉一張表的後果不是報錯，是**留下一堆查不到主人的孤兒列**——
-- 而那正是「使用者要求刪除資料」時最不想發生的事。
ALTER TABLE notes              ADD CONSTRAINT fk_notes_user
    FOREIGN KEY (line_user_id) REFERENCES users(line_user_id) ON DELETE CASCADE;
ALTER TABLE message_anchors    ADD CONSTRAINT fk_message_anchors_user
    FOREIGN KEY (line_user_id) REFERENCES users(line_user_id) ON DELETE CASCADE;
ALTER TABLE outbox_deliveries  ADD CONSTRAINT fk_outbox_deliveries_user
    FOREIGN KEY (line_user_id) REFERENCES users(line_user_id) ON DELETE CASCADE;

-- 這兩張的欄位可以是 NULL（V12 之前的舊列沒有這個資訊）。
-- 外鍵對 NULL 一律放行，所以舊資料不會擋住遷移。
ALTER TABLE outbox_events      ADD CONSTRAINT fk_outbox_events_user
    FOREIGN KEY (line_user_id) REFERENCES users(line_user_id) ON DELETE CASCADE;
ALTER TABLE command_executions ADD CONSTRAINT fk_command_executions_user
    FOREIGN KEY (line_user_id) REFERENCES users(line_user_id) ON DELETE CASCADE;

-- 外鍵不會自動建索引，而 CASCADE 刪除會對每一張子表查一次。
-- 但**這裡只需要補一個**：其餘四張表已經有以 line_user_id 開頭的複合索引
-- （idx_notes_user_created、idx_outbox_user_created、idx_deliveries_user、
-- idx_command_executions_user），複合索引的前導欄位就足以服務 `WHERE line_user_id = ?`。
--
-- 其中兩個是 partial index（WHERE line_user_id IS NOT NULL）——一樣可用，
-- 因為 `line_user_id = ?` 本身就蘊含 NOT NULL，planner 推得出來。
--
-- 🔴 差點多建五個同名索引。`idx_command_executions_user` 這個名字 V12 已經用掉了，
-- 直接 CREATE 會撞名讓整個遷移失敗——**而失敗的原因跟這次要做的事毫無關係**。
CREATE INDEX idx_message_anchors_user ON message_anchors(line_user_id);
