# 服務啟動與確認

README 的[快速開始](README.md#快速開始)是**第一次安裝**用的（裝 Ollama、拉模型、填憑證）。
這一份是**平常怎麼開、怎麼確認活著、壞了怎麼分辨**。

---

## 一分鐘版

九成的情況是：容器與常駐服務都還在，只有 core 沒跑。

```bash
cd ~/專案/02-個人/SVRA
./run-core.sh
```

這支會佔住終端機（前景執行，`Ctrl-C` 停止）。跑起來之後：

```bash
curl -s localhost:8444/actuator/health   # 另開一個終端機
```

看到 `"status":"UP"` 就好了。

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
| **core** | **宿主機** | `./run-core.sh` | ❌ **只有這個要手動** |

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

# 1. 容器（Ollama 與 ngrok 的 LaunchAgent 已經自己起來了）
docker compose up -d

# 2. core
./run-core.sh
```

就這樣。若 LaunchAgent 沒生效，手動補：

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

# 4. core：整體健康
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
curl -s -o /dev/null -w '%{http_code}\n' localhost:8444/actuator/health   # 期望 200
```

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

### core 啟動就失敗，抱怨 `svra.line.channel-secret`

`.env` 裡的 LINE 憑證沒填。這是**刻意讓它在啟動時失敗**的
（[決策 22](README.md#22-驗簽進-spring-security而-body-得自己包一層)）——
不擋的話會變成每一則訊息都回 500、LINE 無限重送，而應用看起來一切正常。

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

## 另一種跑法：全部進容器

部署或驗證用，core 也跑在容器裡：

```bash
docker compose --profile full up -d
```

⚠️ **不能跟 `./run-core.sh` 同時開。** 兩邊都綁 8443：
容器版沒停乾淨的話本機這支會啟動失敗；反過來本機那支沒關就 `--profile full`，
**容器的埠會發布不出去而且不報錯**——後者比較難發現。

---

## 附註：`.env` 目前落後 `.env.example`

`.env` 裡還留著已經用不到的 `ANTHROPIC_API_KEY`（早就改成地端 Ollama 了），
而 `.env.example` 新增的這幾個鍵它沒有：

```
AUDIO_DIR  MANAGEMENT_PORT  OLLAMA_BASE_URL  OLLAMA_MODEL  SERVER_PORT  WHISPER_DELETE_AUDIO
```

**現在不影響運作**——這些鍵在 `application.yml` 與 `docker-compose.yml` 裡都有預設值。
但要改埠或換模型時會找不到地方改，值得補齊：

```bash
# 對照一下缺什麼
diff <(grep -oE '^[A-Z_]+=' .env | sort) <(grep -oE '^[A-Z_]+=' .env.example | sort)
```
