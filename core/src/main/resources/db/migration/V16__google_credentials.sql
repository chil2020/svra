-- 每個使用者自己的 Google 憑證。
--
-- 🔴 這張表修正的是一個**分類錯誤**，不是加功能。原本四個 Google 設定全在
-- 環境變數裡，但它們不是同一種東西：
--
--   client_id / client_secret  → **應用程式**的身分，一份，跟使用者數量無關
--   refresh_token / calendar_id → **使用者**的憑證，每人一份
--
-- 前者留在環境變數是對的。後者放在那裡，就是為什麼白名單
-- （CALENDAR_OAUTH_USER_IDS）實務上只能放一個人：名單放兩個，
-- 第二個人的行程會寫進第一個人的行事曆。**那個限制不是產品決定，
-- 是「token 存在一個只能有一份的地方」造成的。**
--
-- 為什麼獨立一張表而不是掛在 users 上：
--   1. 沒授權的人**沒有那一列**，而不是一整排 NULL——「誰授權過」變成
--      count(*) 而不是「數哪幾欄不是 null」
--   2. refresh token 是機密。獨立出來，「哪些程式碼讀得到它」查得出來；
--      混在 users 裡的話，任何讀使用者資料的地方都順手拿到了
CREATE TABLE google_credentials (
    line_user_id VARCHAR(64) PRIMARY KEY
        REFERENCES users(line_user_id) ON DELETE CASCADE,

    -- 🔴 加密後才存。密文是 base64(iv ‖ ciphertext ‖ tag)，長度不固定，所以用 TEXT。
    --
    -- 加密擋得住什麼，要講清楚：資料庫備份外洩、唯讀副本、以及**開發者自己跑 psql**。
    -- 擋不住應用程式被攻破——金鑰就在那裡。所以這不是萬靈丹，
    -- 而是讓「DB 的內容外流」不等於「所有人的行事曆寫入權外流」。
    --
    -- 而最後那個情境是真的：這個專案的開發過程中 psql 跑過幾十次，
    -- 明文 token 會直接留在終端機捲軸裡。
    refresh_token_encrypted TEXT NOT NULL,

    -- 這個人的行程要寫進哪一本行事曆。專用子行事曆的 id 長得像 email。
    calendar_id  VARCHAR(255) NOT NULL,

    -- 當初授權了什麼。存下來才知道**這個 token 能做什麼**——
    -- scope 是會變的（calendar → calendar.app.created 就換過一次），
    -- 而換過之後舊 token 仍然有效但權限不同。沒記的話，
    -- 「為什麼這個人的匯入失敗」就只能猜。
    scope        VARCHAR(255) NOT NULL,

    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL = 仍然有效。撤銷不刪列，理由跟 blocked_at 一樣：
    -- 「他曾經授權過、後來失效了」跟「他從來沒授權過」是兩件不同的事，
    -- 而只有前者需要推播一則「請重新授權」。
    revoked_at   TIMESTAMPTZ
);
