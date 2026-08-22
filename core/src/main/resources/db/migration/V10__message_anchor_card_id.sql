-- 卡片 id：我們自己給那張 Flex 卡的識別碼。
--
-- 起因是一個先有雞還是先有蛋的問題。卡片上每顆「加入行事曆」按鈕都要回答
-- 「這張卡列了哪幾筆」，那份資料就是 message_anchors——但錨點是用
-- **LINE 給的 message id** 當主鍵，而那個 id 要等訊息推出去之後才拿得到，
-- 卡片卻是在推出去**之前**就組好的。
--
-- LINE 的 postback 事件也不帶「按鈕所在的訊息」是哪一則（只有 data 字串），
-- 所以沒有辦法在事後補上這個關聯。
--
-- 解法是換一個我們自己控制得了時機的 id：組卡片時就先生成，寫進按鈕的 data，
-- 推播成功後跟 LINE 的 message id 一起存進同一列。
--
-- 為什麼不把項目 id 直接塞進按鈕的 data：postback 的 data 有 300 字元上限，
-- 而「現在有什麼行程」的清單可以很長。塞得下不代表塞得穩——
-- 那是一個會隨資料成長而突然壞掉的設計，而且壞的時候是靜悄悄地壞。
ALTER TABLE message_anchors ADD COLUMN card_id VARCHAR(32);

-- 🔴 **刻意不是唯一索引**，而這跟決策 2「防線要由資料庫強制」不矛盾。
--
-- 唯一索引在這裡會製造一個新的故障模式：推播是 at-least-once（決策 3），
-- poller 送出訊息後、標記 SENT 前掛掉，同一筆 PUSH_TEXT 事件會再送一次。
-- 第二次會拿到**新的 LINE message id**、但 payload 裡是**同一個 card_id**，
-- 於是 INSERT 撞唯一鍵 → 例外 → 又重試 → 永遠好不了。
--
-- 更關鍵的是：唯一性在這裡不是真正的不變量。真正的不變量是
-- 「一個 card_id 對應一份項目順序」，而重送出去的那張卡列的是同一份，
-- 兩列資料並不衝突。**把一個不是不變量的東西寫成約束，擋掉的是正確的寫入。**
--
-- 查詢因此取最新的一列（見 MessageAnchorRepository）。
CREATE INDEX idx_message_anchors_card_id
    ON message_anchors (card_id) WHERE card_id IS NOT NULL;

-- 不回填。舊的推播是純文字，上面根本沒有按鈕，給它們一個卡片 id
-- 只是憑空製造一批永遠不會被查詢的資料。
