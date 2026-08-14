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
import time

import pika

logging.basicConfig(
    level=os.getenv("LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("whisper-worker")

RABBITMQ_URL = os.getenv("RABBITMQ_URL", "amqp://svra:svra-dev-only@localhost:5672/")
EXCHANGE = os.getenv("SVRA_EXCHANGE", "svra.direct")
DLX = os.getenv("SVRA_DLX", "svra.dlx")
JOB_QUEUE = os.getenv("JOB_QUEUE", "transcribe.jobs")
JOB_ROUTING_KEY = os.getenv("JOB_ROUTING_KEY", "transcribe.job")
RESULT_QUEUE = os.getenv("RESULT_QUEUE", "transcribe.results")
RESULT_ROUTING_KEY = os.getenv("RESULT_ROUTING_KEY", "transcribe.result")

AUDIO_DIR = os.getenv("AUDIO_DIR", "/data/audio")

MODEL_NAME = os.getenv("WHISPER_MODEL", "small")
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

    ch.queue_declare(RESULT_QUEUE, durable=True)
    ch.queue_bind(RESULT_QUEUE, EXCHANGE, RESULT_ROUTING_KEY)


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
            language=job.get("language_hint"),
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
    except Exception:
        log.exception("job=%s failed, sending to DLQ", job_id)
        ch.basic_reject(delivery_tag=method.delivery_tag, requeue=False)


def main():
    while True:
        try:
            conn = pika.BlockingConnection(pika.URLParameters(RABBITMQ_URL))
            ch = conn.channel()
            setup_topology(ch)
            # 轉錄是重活：一次只取一件，讓多個 worker 能公平分工（水平擴展點）
            ch.basic_qos(prefetch_count=1)
            ch.basic_consume(queue=JOB_QUEUE, on_message_callback=handle_job)
            log.info("worker ready, consuming %s", JOB_QUEUE)
            ch.start_consuming()
        except pika.exceptions.AMQPConnectionError:
            log.warning("rabbitmq unavailable, retrying in 5s ...")
            time.sleep(5)
        except KeyboardInterrupt:
            log.info("shutting down")
            return


if __name__ == "__main__":
    main()
