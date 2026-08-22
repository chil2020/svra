# 服務啟動與確認

README 的[快速開始](README.md#快速開始)是**第一次安裝**用的（裝 Ollama、拉模型、填憑證）。
這一份是**平常怎麼開、怎麼確認活著、壞了怎麼分辨**。

---

## 一分鐘版

九成的情況是：容器與常駐服務都還在，只有 core 沒跑。而 **core 有兩種跑法，
差別不是方便，是「會不會安靜地漏訊息」**：

```bash
cd ~/專案/02-個人/SVRA

# A. 平常用這個 —— core 也進容器，有 restart policy，關終端機／重開機都活著
docker compose --profile full up -d

# B. 改程式時用這個 —— 前景執行，改一行不用重建映像檔，但終端機關掉它就沒了
./run-core.sh
```

確認——**兩種模式的查法不一樣**，因為容器版只把 8443 發布出來，
8444 刻意留在容器內（決策 20：actuator 不對外）：

```bash
# A 容器版
docker compose ps core                    # 期望 Up ... (healthy)

# B 本機版
curl -s localhost:8444/actuator/health    # 期望 {"status":"UP"}
```

拿 B 的指令去查 A 會連不上，那**不代表 core 壞了**，只代表那個埠沒發布。

> ⚠️ **兩個不能同時開**，都綁 8443。切換時先停掉另一邊：
> `docker compose --profile full stop core` 或在 run-core.sh 的終端機按 `Ctrl-C`。
>
> **先確認現在是誰在服務**，再決定要停哪一邊：
>
> ```bash
> lsof -nP -iTCP:8443 -sTCP:LISTEN
> ```
>
> 看到 `java` 而 `docker compose ps` 沒有 core，就是本機版在跑。

### 為什麼預設是 A 而不是 B

這正是 [決策 15](README.md#15-全部服務進-compose但-ollama-留在宿主機) 的內容：
core 用 `./run-core.sh` 跑在終端機裡時，**關掉視窗或重開機它就沒了——
而其他四個服務都還活著**。`docker compose ps` 全綠、Ollama 在跑、ngrok 在跑，
看起來一切正常，實際上**沒有人在接 webhook**。

而 LINE 不會把那段時間的訊息補送。**那些語音就是永久遺失**，
outbox 也救不回來——它能重試的前提是「事件已經寫進資料庫」，
而那些訊息根本沒進到門口。

B 只在你正在改 core 的程式碼時才划算。改完記得切回 A。

---

## 完整的圖：什麼跑在哪裡

| 元件 | 跑在哪 | 怎麼起 | 開機自動 |
|---|---|---|---|
| PostgreSQL | 容器 | `docker compose up -d` | ✅ `restart: unless-stopped` |
| RabbitMQ | 容器 | 同上 | ✅ |
| Redis | 容器 | 同上 | ✅ |
| whisper-worker | 容器 | 同上 | ✅ |
| **Ollama** | **宿主機** | `brew services start ollama` | ✅ LaunchAgent |
| **ngrok** | **宿主機** | `ngrok start svra` | ✅ LaunchAgent |
| **core** | 容器 *或* 宿主機 | `docker compose --profile full up -d`<br>或 `./run-core.sh` | ⚠️ 看你用哪一種，見上面 |

### 為什麼有三個東西不在容器裡

- **Ollama**：模型權重 6.6 GB，而且容器裡吃不到 macOS 的 Metal 加速，
  純 CPU 推論會慢好幾倍（[決策 15](README.md#15-全部服務進-compose但-ollama-留在宿主機)）。
- **ngrok**：它是對外的入口，本來就該在網路邊界那一側。
- **core**：開發時放宿主機是為了**改一行不用重建映像檔**。
  它也可以跑在容器裡，見最下面「全部進容器」。

> Ollama 與 ngrok 都已經裝成 LaunchAgent，開機自動起、掛掉自動重啟，
> 平常不用管。**要手動的只有 core。**

### 埠

| 埠 | 誰 | 對外？ |
|---|---|---|
| 8443 | core / webhook | ✅ ngrok 轉進來的就是這個 |
| 8444 | core / actuator | ❌ 刻意不對外（[決策 20](README.md#20-對外只留一個入口)） |
| 5432 / 5672 / 6379 | Postgres / RabbitMQ / Redis | ❌ 只綁本機 |
| 15672 | RabbitMQ 管理介面 | ❌ http://localhost:15672 |
| 11434 | Ollama | ❌ |
| 4040 | ngrok 本機介面 | ❌ http://localhost:4040 |

---

## 冷啟動（整台機器重開之後）

```bash
cd ~/專案/02-個人/SVRA

# Ollama 與 ngrok 的 LaunchAgent 已經自己起來了，容器連 core 一起拉
docker compose --profile full up -d
```

一行就好——`--profile full` 是 `docker compose up -d` 的超集，
連 core 一起帶起來。若 LaunchAgent 沒生效，手動補：

```bash
brew services start ollama
launchctl load ~/Library/LaunchAgents/io.svra.ngrok.plist
```

---

## 確認每一層真的活著

**照這個順序查**，由下往上——上層壞掉多半是下層造成的。

```bash
# 1. 容器：四個都要 healthy（whisper-worker 沒有 healthcheck，看 Up 就好）
docker compose ps

# 2. Ollama：要看得到 qwen3.5:9b
ollama list

# 3. ngrok：要看得到公開網址指向 localhost:8443
curl -s localhost:4040/api/tunnels | python3 -m json.tool | grep public_url

# 4. core：整體健康（本機版；容器版改用 docker compose ps core 看 healthy）
curl -s localhost:8444/actuator/health

# 5. 佇列：積壓與死信都該是 0
docker compose exec rabbitmq rabbitmqctl list_queues name messages
```

第 5 項是最值得養成習慣的一項。`transcribe.jobs` 一直不降代表 worker 沒在消化；
`*.dlq` 不是 0 代表有任務被放棄過（[決策 16](README.md#16-dlq-裡有訊息不等於有補償)——
使用者會收到通知，但你會想知道為什麼）。

### 對外那條路通不通（不用傳語音）

打自己的公開網址，**期望是 401**：

```bash
url=$(curl -s localhost:4040/api/tunnels \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['tunnels'][0]['public_url'])")
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$url/webhook" \
  -H 'Content-Type: application/json' -d '{}'
```

| 回什麼 | 意思 |
|---|---|
| **401** | ✅ 整條通了：網際網路 → ngrok → 8443 → 驗簽擋下沒簽章的請求 |
| 404 / ngrok 錯誤頁 | ngrok 活著但 core 沒起來 |
| 連不上 | ngrok 斷了 |
| 200 | ⚠️ 不該發生——沒帶簽章卻放行，驗簽出問題了 |

**401 才是對的**。這一招的好處是它同時驗了「打得進來」與「擋得住」，
而且不用真的傳語音、不用等三十秒。

順便確認 actuator 沒有跟著曝光（[決策 20](README.md#20-對外只留一個入口)）：

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8443/actuator/health   # 期望 403
curl -s -o /dev/null -w '%{http_code}\n' localhost:8444/actuator/health   # 本機版期望 200
```

第二行只適用本機版。容器版的 8444 根本沒發布到宿主機，
**連不上才是對的**——那是比「回 403」更徹底的隔離。

### 端到端

對 LINE 傳一則語音，然後看 log：

```bash
docker compose logs -f whisper-worker     # 應該看到 job=... done in ...s
```

core 那邊會依序出現「已推送訊息」。整條路徑是
webhook → outbox → RabbitMQ → whisper → 回寫 → LLM 抽取 → 推播。

---

## 停止

```bash
# core：在它的終端機按 Ctrl-C

# 容器（資料保留在 volume 裡）
docker compose stop

# 連同資料一起清掉（會刪 Postgres 與 RabbitMQ 的 volume，很少需要）
docker compose down -v
```

Ollama 與 ngrok 是 LaunchAgent，平常不用停。真要停：

```bash
brew services stop ollama
launchctl unload ~/Library/LaunchAgents/io.svra.ngrok.plist
```

---

## 壞掉的時候怎麼分辨

### core 起不來，說埠被佔

```bash
lsof -nP -iTCP:8443 -sTCP:LISTEN
```

最常見的原因是**容器版的 core 還開著**（`--profile full` 起過）。
兩邊都綁 8443，不能同時跑：

```bash
docker compose --profile full stop core
```

### whisper-worker 一直重啟

```bash
docker compose ps          # 看到 restarting
docker compose logs whisper-worker --tail 20
```

若錯誤是 `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'`，
那是**佇列參數改過而舊佇列還在**——AMQP 的佇列參數建立後不能改。
處理方式見 [README 的升級說明](README.md#從舊版升級要先刪掉一個佇列)。

> 這個症狀不是「啟動失敗一次」，是**無限重啟**：
> 行程結束 → `restart: unless-stopped` 拉起來 → 再撞同一堵牆。
> `docker compose exec` 這時會直接拒絕連進去。

### core 啟動就失敗，抱怨 `svra.line.channel-secret` 或 `svra.calendar.*`

`.env` 裡的憑證沒填。這是**刻意讓它在啟動時失敗**的
（[決策 22](README.md#22-驗簽進-spring-security而-body-得自己包一層)）——
不擋的話會變成每一則訊息都回 500、LINE 無限重送，而應用看起來一切正常。

行事曆那四個鍵（`GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` /
`GOOGLE_REFRESH_TOKEN` / `GOOGLE_CALENDAR_ID`）跑一次
`python3 deploy/google-calendar-auth.py` 就會拿到，見
[決策 26](README.md#26-匯入行事曆讓外部系統自己擋掉重複)。

> 🔴 **但它們是有條件必填的。** `CALENDAR_OAUTH_USER_IDS` 空的話所有人走預填連結，
> 那四個留白是正確狀態（[決策 27](README.md#27-讓別人也能用擋路的不是使用者是-google-的審核)）。
> **白名單有人卻沒填齊才會啟動失敗**——因為那些人會按到一顆註定失敗的按鈕。
> 錯誤訊息會直接說怎麼修。

### 匯入行事曆的按鈕按了沒反應

**先分清楚是哪一種按鈕。** 白名單裡的人按的是 postback（後端直接寫入），
其他人按的是一條開 Google 預填頁的連結——兩者的排查完全不同。

**連結那種**：點下去應該**跳出 LINE**、開系統瀏覽器。如果它留在 LINE 的內建瀏覽器裡
而且顯示 `403 disallowed_useragent`，就是 `openExternalBrowser=1` 沒帶到——
Google 自 2021 起拒絕在內嵌 webview 完成登入，而且**沒有 fallback**。
檢查推播出去的 Flex JSON 裡 `action.uri` 有沒有那個參數。

**postback 那種**：先看啟動時那一行 `已換發 Google access token`。沒有它、而是一行
`Google 行事曆的授權目前是壞的`，就是 refresh token 失效了。
若 log 出現 `不在白名單的使用者送來匯入請求，已拒絕`，那是舊卡片——
那個人當時在名單裡，現在不在了（卡片是會過期的訊息，見決策 27）。

**最常見的原因是 GCP 的 consent screen 還停在 `Testing`**——
Google 會在發出 refresh token 的**七天後**撤銷它。到 GCP console 按
`Publish app` 切到 `In Production`，再重跑一次授權腳本。

按下去之後兩秒沒有回覆，但聊天室裡有你自己那則「匯入行事曆」——
那是 outbox 還沒輪到，看 `outbox_events` 裡 `CALENDAR_SYNC_REQUESTED` 那幾筆的
`status` 與 `last_error`。`FAILED` 且 `attempts = 1` 代表被判死了
（授權失效、行事曆被刪、權限不足），那種不會重試，log 裡有原因。

### 語音進得來，但一直沒有回覆

由後往前查：

| 症狀 | 多半是 |
|---|---|
| worker log 沒有任何 job | outbox 沒送出去 → 看 core log 與 `outbox_events` 的 `last_error` |
| worker 轉完了但沒有推播 | LLM 抽取失敗 → **先看 `ollama list` 是不是活著** |
| 什麼 log 都沒有 | ngrok 斷了，LINE 根本打不進來 |

最後一項最陰險：**ngrok 斷線不會有任何提示**，只會安靜地收不到訊息，
而 LINE 不會補送。所以它才被裝成 LaunchAgent。

```bash
curl -s localhost:4040/api/tunnels | grep -c public_url    # 0 就是斷了
```

### Ollama 沒起來

抽取會失敗，但**不會遺失**——outbox 會退避重試五次
（[決策 3](README.md#3-transactional-outbox先寫意圖再送訊息)）。
在重試耗盡之前把 Ollama 拉起來就會自己接上：

```bash
brew services start ollama
```

---

## 開發時：把 core 換成本機跑

要改 core 的程式碼時，容器版每改一行都要重建映像檔，很痛。切成本機跑：

```bash
docker compose --profile full stop core   # 先讓出 8443
./run-core.sh                             # 前景，Ctrl-C 停
```

改完切回去：

```bash
# 在 run-core.sh 的終端機按 Ctrl-C，然後
docker compose --profile full up -d core
```

⚠️ **兩邊都綁 8443，不能同時開。** 而且兩種撞法的症狀不一樣：

| 情況 | 症狀 |
|---|---|
| 容器版還開著，跑 `./run-core.sh` | 啟動失敗，明講埠被佔 —— 好查 |
| 本機版還開著，跑 `--profile full` | **容器的埠發布不出去，而且不報錯** —— 難查 |

第二種特別陰險：`docker compose ps` 會顯示 core 是 Up，但它其實收不到任何請求。

---

## 附註：`.env` 目前落後 `.env.example`

`.env` 裡還留著已經用不到的 `ANTHROPIC_API_KEY`（早就改成地端 Ollama 了），
而 `.env.example` 新增的這幾個鍵它沒有：

```
AUDIO_DIR  MANAGEMENT_PORT  OLLAMA_BASE_URL  OLLAMA_MODEL  SERVER_PORT  WHISPER_DELETE_AUDIO
CALENDAR_DEFAULT_DURATION_MINUTES
```

**大部分不影響運作**——這些鍵在 `application.yml` 與 `docker-compose.yml` 裡都有預設值。
但 `GOOGLE_*` 那四個是例外：**它們沒有預設值，沒填就啟動失敗**（見上面那一節）。
其餘的要改埠或換模型時會找不到地方改，值得一起補齊：

```bash
# 對照一下缺什麼
diff <(grep -oE '^[A-Z_]+=' .env | sort) <(grep -oE '^[A-Z_]+=' .env.example | sort)
```
