# Eval 集（第 2–3 週）

「串 API」和「工程化 LLM」的分水嶺：20–30 條口語輸入 → 期望分類與抽取欄位，
改 prompt 後跑準確率，讓 prompt 迭代有回歸測試可依。

## 案例格式（cases.jsonl，一行一案例）

```json
{"id": "todo-001", "input": "口語轉錄文字", "expected": {"category": "待辦|想法|行程", "fields": {"time": "...", "people": ["..."], "tags": ["..."]}}}
```

`cases.jsonl` 已放 3 條範例示意格式，正式案例建議從你自己的真實語音輸入累積
（真實口語的雜訊——贅字、修正、跳題——才是 eval 有價值的原因）。

## Runner（待做，約一天）

- [ ] 逐案例呼叫 core 的抽取端點（或直接呼叫 LLM 層）
- [ ] 比對 category 完全一致；fields 逐鍵比對（時間類欄位允許正規化後比對）
- [ ] 輸出總準確率＋錯誤案例明細，結果存 `runs/<日期>-<prompt版本>.json`
- [ ] README 放一張「prompt 版本 vs 準確率」表——面試展示迭代過程
