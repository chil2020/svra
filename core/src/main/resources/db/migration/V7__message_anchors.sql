-- 訊息錨點：某一則推播出去的訊息，當時秀了哪些項目、依什麼順序。
--
-- 取代 note_extractions.notify_message_id。舊機制只記「這則訊息屬於哪一批抽取」，
-- 編號則在使用者下指令時，從那批的<現況>重算。兩個問題：
--
--   1. 編號會漂。那批若已經刪過東西，重算出來的「第三筆」就不是使用者
--      眼前那則訊息上的第三筆了——而他看著舊訊息說「第三筆」。
--   2. 跨語音的清單錨不住。「現在有什麼行程」的回覆橫跨多批抽取，
--      沒有單一一批可以掛，於是引用那則訊息永遠對不上。
--
-- 記下當時的 id 順序就同時解決兩個：解析編號不再重算，而是直接查當時的快照。
--
-- item_ids 用陣列而不是另開一張明細表：它<整組>才有意義，
-- 沒有任何查詢會問「這個項目出現在哪些訊息裡」。順序就是編號，
-- 而 PostgreSQL 的陣列是有序的。專案裡已經有 note_items.tags 這個先例。
CREATE TABLE message_anchors (
    line_message_id VARCHAR(64)  PRIMARY KEY,
    line_user_id    VARCHAR(64)  NOT NULL,
    item_ids        BIGINT[]     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 既有的推播訊息還在使用者的對話紀錄裡，往上滑就引用得到。
-- 不回填的話，這次改動會讓「以前推播過的那些」全部變成引用不到——
-- 而它們現在是可以的。
--
-- 順序要跟 NoteCategory.itemOrder() 一致：分類（SCHEDULE→TODO→IDEA）、
-- 時間（無時間排後）、id。這是唯一一處在 SQL 裡重複那個順序的地方，
-- 而它只跑一次；之後的錨點都由應用程式寫入，順序來自同一個 Comparator。
INSERT INTO message_anchors (line_message_id, line_user_id, item_ids, created_at)
SELECT e.notify_message_id,
       n.line_user_id,
       ARRAY(SELECT i.id
               FROM note_items i
              WHERE i.extraction_id = e.id
              ORDER BY CASE i.category
                           WHEN 'SCHEDULE' THEN 0
                           WHEN 'TODO'     THEN 1
                           ELSE 2
                       END,
                       i.occurs_at NULLS LAST,
                       i.id),
       e.created_at
  FROM note_extractions e
  JOIN notes n ON n.id = e.note_id
 WHERE e.notify_message_id IS NOT NULL
   -- 一筆項目都不剩的就不回填。空錨點會讓「第一筆」失敗在「超出範圍」，
   -- 而沒有錨點會失敗在「這則我對不上」——後者才是實情：那則訊息上的東西全沒了。
   -- 執行期的 MessageAnchors.record 也是跳過空清單，兩邊要一致。
   AND EXISTS (SELECT 1 FROM note_items i WHERE i.extraction_id = e.id)
ON CONFLICT (line_message_id) DO NOTHING;

DROP INDEX IF EXISTS idx_extractions_notify_message;
ALTER TABLE note_extractions DROP COLUMN notify_message_id;
