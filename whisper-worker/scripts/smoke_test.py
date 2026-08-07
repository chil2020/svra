"""端到端煙霧測試：產生 1 秒測試音檔 → 發布轉錄任務 → 等待結果。

用法（先 `docker compose up -d` 讓 worker 在跑）:
    docker compose run --rm whisper-worker python scripts/smoke_test.py

首次執行 worker 需下載模型權重，建議先在 .env 設 WHISPER_MODEL=tiny 加速。
注意：本腳本會直接從 transcribe.results 取結果，僅供 core 尚未存在的
開發初期驗證管線用；core 上線後改由它消費結果佇列。
"""

import json
import math
import os
import struct
import sys
import time
import uuid
import wave

import pika

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from main import EXCHANGE, JOB_ROUTING_KEY, RABBITMQ_URL, RESULT_QUEUE, setup_topology

AUDIO_DIR = os.getenv("AUDIO_DIR", "/data/audio")
TIMEOUT_SEC = int(os.getenv("SMOKE_TIMEOUT_SEC", "600"))  # 含首次模型下載時間


def make_test_wav(path, seconds=1.0, freq=440.0, rate=16000):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        for i in range(int(seconds * rate)):
            sample = int(12000 * math.sin(2 * math.pi * freq * i / rate))
            w.writeframes(struct.pack("<h", sample))


def main():
    job_id = f"smoke-{uuid.uuid4().hex[:8]}"
    audio_path = os.path.join(AUDIO_DIR, f"{job_id}.wav")
    os.makedirs(AUDIO_DIR, exist_ok=True)
    make_test_wav(audio_path)

    conn = pika.BlockingConnection(pika.URLParameters(RABBITMQ_URL))
    ch = conn.channel()
    setup_topology(ch)

    ch.basic_publish(
        exchange=EXCHANGE,
        routing_key=JOB_ROUTING_KEY,
        body=json.dumps({"job_id": job_id, "audio_path": audio_path}),
        properties=pika.BasicProperties(delivery_mode=2, content_type="application/json"),
    )
    print(f"[smoke] published job {job_id}, waiting for result (timeout {TIMEOUT_SEC}s) ...")

    deadline = time.monotonic() + TIMEOUT_SEC
    while time.monotonic() < deadline:
        method, _props, body = ch.basic_get(RESULT_QUEUE, auto_ack=False)
        if method is None:
            time.sleep(2)
            continue
        result = json.loads(body)
        if result.get("job_id") != job_id:
            # 不是我們的結果（例如殘留訊息），放回佇列
            ch.basic_reject(method.delivery_tag, requeue=True)
            time.sleep(1)
            continue
        ch.basic_ack(method.delivery_tag)
        print("[smoke] PASS — pipeline works end to end:")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        os.remove(audio_path)
        return 0

    print("[smoke] FAIL — no result before timeout (worker 有起來嗎？模型還在下載？)")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
