"""SVRA Whisper Worker

消費 RabbitMQ 上的語音轉錄任務，用 faster-whisper 轉出文字後發布結果。

任務訊息（transcribe.jobs）:
    {"job_id": "...", "audio_file": "xxx.m4a", "language_hint": "zh"}

    audio_file 只帶檔名，實際路徑由本端的 AUDIO_DIR 組出來——
    core 與 worker 對同一個共享目錄的掛載點不同，把對方的檔案系統配置
    寫進訊息契約會讓部署方式綁死在協定上。

結果訊息（transcribe.results）:
    {"job_id": "...", "status": "completed", "text": "...", "language": "zh",
     "audio_duration_sec": 3.2, "elapsed_sec": 1.8, "model": "small"}

失敗處理：任何例外 → basic_reject(requeue=False) → 進 DLQ（transcribe.jobs.dlq），
由 core 端負責監控與補償。重複投遞的去重（冪等）也是 core 的責任，
worker 保持無狀態。
"""

import json
import logging
import os
import threading
import time

import pika

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("whisper-worker")

# 連不上 rabbitmq 時，pika 自己會用 ERROR 印四行加一整段 traceback，每重試一次印一遍。
# 等 rabbitmq 起來的那半分鐘就能刷掉幾十屏，而裡面沒有下面重連迴圈沒講到的資訊
# （那行 log 已經帶上 host:port 與例外本文）。連線狀態統一由重連迴圈報告。
for _noisy in (
    "pika.adapters.utils.connection_workflow",
    "pika.adapters.utils.selector_ioloop_adapter",
    "pika.adapters.blocking_connection",
):
    logging.getLogger(_noisy).setLevel(logging.CRITICAL)

RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://svra:svra-dev-only@localhost:5672/")
EXCHANGE = os.getenv("SVRA_EXCHANGE", "svra.direct")
DLX = os.getenv("SVRA_DLX", "svra.dlx")
JOB_QUEUE = os.getenv("JOB_QUEUE", "transcribe.jobs")
JOB_ROUTING_KEY = os.getenv("JOB_ROUTING_KEY", "transcribe.job")
RESULT_QUEUE = os.getenv("RESULT_QUEUE", "transcribe.results")
RESULT_ROUTING_KEY = os.getenv("RESULT_ROUTING_KEY", "transcribe.result")

AUDIO_DIR = os.getenv("AUDIO_DIR", "/data/audio")

RECONNECT_DELAY_SEC = 5
# 重開機時前幾次連不上是正常的（rabbitmq 還沒起來），那是 warning。
# 但「還在等」跟「永遠等不到」是兩回事：worker 沒有 healthcheck，連不上時
# 容器狀態仍然是 Up——一個打錯的 host 名字會變成一個看起來很健康的無限迴圈。
# 超過一分鐘就升級成 error，讓它在 log 裡自己浮出來。
RECONNECT_ERROR_AFTER = 60 // RECONNECT_DELAY_SEC

HEALTH_FILE = os.getenv("HEALTH_FILE", "/tmp/whisper-worker-health.json")
HEALTH_BEAT_SEC = 10

# 目前狀態：主迴圈更新，心跳執行緒寫出去，healthcheck.py 讀。
# connected 才是重點——行程活著不等於它連得上 rabbitmq。
_health = {"connected": False, "detail": "starting"}

# 轉錄成功後刪掉音檔。逐字稿已經寫進資料庫，音檔留著只有壞處：
# 目錄無上限成長，而且那是語音隱私資料——「不離開自己的機器」講的是不外傳，
# 沒說要永久保存。設成 false 可以留著除錯。
DELETE_AUDIO_AFTER_TRANSCRIBE = os.getenv("WHISPER_DELETE_AUDIO", "true").lower() == "true"


# 聯發科的台灣調校版（Whisper large-v2 為底，已轉成 CTranslate2）。
# 實測同一段音檔對照 small 與 large-v3：
#   奮起湖  small ✖ 正啟湖  ／ large-v3 ✔ ／ Breeze ✔
#   KKday   small ✖ KKM    ／ large-v3 ✖ 認不出 ／ Breeze ✔
# 第二列是關鍵——中英夾雜是 large-v3 也修不好、只有台灣調校版能處理的。
MODEL_NAME = os.getenv("WHISPER_MODEL", "phate334/Breeze-ASR-25-ct2")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")

_model = None


def get_model():
    # 延遲載入：容器先上線接佇列，收到第一筆任務才載模型（首次會下載權重）
    global _model
    if _model is None:
        log.info("loading whisper model=%s device=%s compute=%s ...", MODEL_NAME, DEVICE, COMPUTE_TYPE)
        from faster_whisper import WhisperModel

        t0 = time.monotonic()
        _model = WhisperModel(MODEL_NAME, device=DEVICE, compute_type=COMPUTE_TYPE)
        log.info("model loaded in %.1fs", time.monotonic() - t0)
    return _model


def _health_writer():
    """每 HEALTH_BEAT_SEC 秒把 _health 寫進 HEALTH_FILE，給 docker healthcheck 讀。

    🔴 為什麼是執行緒，而不是 pika 的 call_later：call_later 的回呼跟訊息處理
    共用同一個事件迴圈，而 handle_job() 會在轉錄裡整段阻塞好幾分鐘——心跳會
    跟著停，healthcheck 就把「正在做事」誤判成「掛了」。這個誤判是這件事
    最難的部分，不是寫檔。

    這跟 main() 裡 heartbeat=0 那段「用不到執行緒」的結論不衝突：那裡要的是
    跨執行緒 ack，得處理 add_callback_threadsafe；這裡的執行緒只讀一個 dict、
    寫一個檔，完全不碰 AMQP。

    轉錄期間真的輪得到嗎——實測 16.3 秒的轉錄，這個執行緒 tick 了 320 次
    （理想 326 次），最長一次沒輪到 0.06 秒。CTranslate2 計算時會放開 GIL，
    而且 segments 是逐段產生的，不是一次算完。
    """
    while True:
        try:
            tmp = HEALTH_FILE + ".tmp"
            with open(tmp, "w") as f:
                json.dump(dict(_health, ts=time.time()), f)
            # 換名是原子的：probe 不會讀到寫到一半的檔
            os.replace(tmp, HEALTH_FILE)
        except OSError as exc:
            log.warning("健康狀態寫不進 %s：%s", HEALTH_FILE, exc)
        time.sleep(HEALTH_BEAT_SEC)


def setup_topology(ch):
    """宣告 exchange / queue / DLQ。core（Java 端）需使用相同名稱。"""
    ch.exchange_declare(EXCHANGE, exchange_type="direct", durable=True)
    ch.exchange_declare(DLX, exchange_type="direct", durable=True)

    ch.queue_declare(
        JOB_QUEUE,
        durable=True,
        arguments={
            "x-dead-letter-exchange": DLX,
            "x-dead-letter-routing-key": JOB_ROUTING_KEY,
        },
    )
    ch.queue_bind(JOB_QUEUE, EXCHANGE, JOB_ROUTING_KEY)

    dlq = f"{JOB_QUEUE}.dlq"
    ch.queue_declare(dlq, durable=True)
    ch.queue_bind(dlq, DLX, JOB_ROUTING_KEY)

    # 結果佇列也掛死信。core 端宣告的參數必須跟這裡一字不差，
    # 否則 channel 會 PRECONDITION_FAILED。
    ch.queue_declare(
        RESULT_QUEUE,
        durable=True,
        arguments={
            "x-dead-letter-exchange": DLX,
            "x-dead-letter-routing-key": RESULT_ROUTING_KEY,
        },
    )
    ch.queue_bind(RESULT_QUEUE, EXCHANGE, RESULT_ROUTING_KEY)

    result_dlq = f"{RESULT_QUEUE}.dlq"
    ch.queue_declare(result_dlq, durable=True)
    ch.queue_bind(result_dlq, DLX, RESULT_ROUTING_KEY)


def handle_job(ch, method, properties, body):
    try:
        job = json.loads(body)
        job_id = job["job_id"]
        audio_path = os.path.join(AUDIO_DIR, job["audio_file"])
    except (json.JSONDecodeError, KeyError):
        log.exception("malformed job message, sending to DLQ: %r", body[:500])
        ch.basic_reject(delivery_tag=method.delivery_tag, requeue=False)
        return

    log.info("job=%s transcribing %s", job_id, audio_path)
    started = time.monotonic()
    try:
        segments, info = get_model().transcribe(
            audio_path,
            beam_size=5,
            # 沒指定就當華語。自動偵測要多跑一段音訊，而這個服務的輸入
            # 幾乎都是中文——猜錯的代價比省下的那點時間大。
            language=job.get("language_hint") or "zh",
            # 沒開 VAD：實測同一段（有背景音的）錄音，開了反而丟字——
            # 「奮起湖」在 VAD 開的時候消失。這段是連續獨白、幾乎沒有靜默，
            # VAD 沒東西可切，只在語音邊界削掉內容。
            # 之後若出現大量靜默或幻覺迴圈的錄音，再回頭量一次。
        )
        text = "".join(seg.text for seg in segments).strip()
        result = {
            "job_id": job_id,
            "status": "completed",
            "text": text,
            "language": info.language,
            "audio_duration_sec": round(info.duration, 2),
            "elapsed_sec": round(time.monotonic() - started, 2),
            "model": MODEL_NAME,
        }
        ch.basic_publish(
            exchange=EXCHANGE,
            routing_key=RESULT_ROUTING_KEY,
            body=json.dumps(result, ensure_ascii=False),
            properties=pika.BasicProperties(delivery_mode=2, content_type="application/json"),
        )
        ch.basic_ack(delivery_tag=method.delivery_tag)
        log.info("job=%s done in %.1fs (audio %.1fs, %d chars)",
                 job_id, result["elapsed_sec"], info.duration, len(text))

        # ack 之後才刪：先刪的話，ack 失敗導致重送時就沒有音檔可以重轉了。
        if DELETE_AUDIO_AFTER_TRANSCRIBE:
            try:
                os.remove(audio_path)
            except OSError:
                # 刪不掉不該讓任務失敗——它已經做完了
                log.warning("job=%s 音檔刪不掉：%s", job_id, audio_path)
    except Exception:
        log.exception("job=%s failed, sending to DLQ", job_id)
        ch.basic_reject(delivery_tag=method.delivery_tag, requeue=False)


def main():
    # daemon=True：主迴圈結束時它跟著走，不用另外收尾
    threading.Thread(target=_health_writer, name="health", daemon=True).start()

    # URL 在迴圈外解析：它不會在兩次重試之間改變，而解析失敗是設定寫錯，
    # 該在啟動時就炸掉，不是混進重連迴圈裡假裝成「連不上」。
    # 順帶讓 except 分支永遠拿得到 params.host/port 可以印。
    params = pika.URLParameters(RABBITMQ_URL)

    # 🔴 heartbeat=0（關閉心跳），改靠 TCP keepalive 偵測斷線。
    #
    # BlockingConnection 只有在「回到事件迴圈」時才回應心跳，而
    # handle_job() 會在 model.transcribe() 裡整段阻塞——Breeze-ASR-25
    # 實測短音檔 33 秒還安全，但一則幾分鐘的語音留言就會超過預設協商的
    # 60 秒 ×2。RabbitMQ 一斷線，做完之後的 basic_ack 就失敗，
    # 任務回到佇列被重新轉錄——**越慢的任務越會被重跑，而重跑只會更慢**。
    #
    # 另一個做法是把轉錄丟到 thread、主迴圈週期性 process_data_events()。
    # 那樣保得住心跳的偵測能力，但要處理跨執行緒 ack（add_callback_threadsafe）。
    # 這裡的 worker 一次只做一件事、掛掉由 restart policy 接手，
    # 用不到那個複雜度。
    params.heartbeat = 0

    attempt = 0
    failing_since = None
    while True:
        try:
            conn = pika.BlockingConnection(params)
            ch = conn.channel()
            setup_topology(ch)
            # 轉錄是重活：一次只取一件，讓多個 worker 能公平分工（水平擴展點）
            ch.basic_qos(prefetch_count=1)
            ch.basic_consume(queue=JOB_QUEUE, on_message_callback=handle_job)
            log.info("worker ready, consuming %s", JOB_QUEUE)
            _health.update(connected=True, detail=f"consuming {JOB_QUEUE}")
            attempt, failing_since = 0, None   # 連上了就歸零，下次斷線重新從 warning 數起
            ch.start_consuming()
        # OSError 不是多餘的：rabbitmq 容器還沒起來時，DNS 查不到那個名字，
        # pika 的 _reap_last_connection_workflow_error() 會把底層的
        # socket.gaierror 原封不動再拋出來，不包成 AMQPConnectionError——
        # 只接後者的話，這個迴圈接不住，行程直接死給 restart policy 收。
        # 重開機時 rabbitmq 一定比 worker 晚一步，走的就是這條路徑。
        except (pika.exceptions.AMQPConnectionError, OSError) as exc:
            _health.update(connected=False, detail=f"{type(exc).__name__}: {exc}"[:200])
            attempt += 1
            if failing_since is None:
                failing_since = time.monotonic()
            # 印 host:port 而不是 RABBITMQ_URL——URL 裡有密碼。
            log.log(
                logging.WARNING if attempt <= RECONNECT_ERROR_AFTER else logging.ERROR,
                "rabbitmq 連不上（%s:%d，第 %d 次，已等 %ds）：%s — %ds 後重試",
                params.host, params.port, attempt,
                int(time.monotonic() - failing_since), exc, RECONNECT_DELAY_SEC,
            )
            time.sleep(RECONNECT_DELAY_SEC)
        except KeyboardInterrupt:
            log.info("shutting down")
            return


if __name__ == "__main__":
    main()
