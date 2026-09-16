#!/usr/bin/env python3
"""docker healthcheck：這個 worker 有沒有真的連上 rabbitmq。

退出碼 0 代表健康。檢查兩件事，兩件都必要：

  1. 狀態檔夠新 —— 心跳執行緒還在寫，代表行程活著而且還排得到 CPU
  2. connected 為真 —— 真的連上 rabbitmq 了

🔴 第 2 條才是這支存在的理由。worker 連不上 rabbitmq 時**不會死**，它會
退避重連（那是刻意的：重開機時 rabbitmq 一定比它晚起）。代價是容器狀態
永遠停在 Up——少了這一關，.env 裡打錯一個 host 名字會變成一個看起來很健康
的無限迴圈，而 docker ps 完全看不出來。

只用 python 標準函式庫：映像檔是 python:3.12-slim，裡面沒有 curl。
"""
import json
import os
import sys
import time

HEALTH_FILE = os.getenv("HEALTH_FILE", "/tmp/whisper-worker-health.json")
# 心跳是 10 秒一次，容忍連漏五次。抓的是「卡死」而不是「慢」——
# 轉錄期間心跳照樣在跳（CTranslate2 放得開 GIL），所以不需要為長任務放寬。
MAX_AGE_SEC = int(os.getenv("HEALTH_MAX_AGE_SEC", "60"))

try:
    with open(HEALTH_FILE) as f:
        state = json.load(f)
except (OSError, ValueError) as exc:
    sys.exit(f"讀不到狀態檔 {HEALTH_FILE}：{exc}")

age = time.time() - state.get("ts", 0)
if age > MAX_AGE_SEC:
    sys.exit(f"狀態檔 {age:.0f}s 沒更新（上限 {MAX_AGE_SEC}s）：心跳執行緒卡住了")

if not state.get("connected"):
    sys.exit(f"沒連上 rabbitmq：{state.get('detail', '?')}")

print(f"ok: {state.get('detail', '?')}")
