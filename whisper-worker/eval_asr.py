"""ASR 層的 eval。

抽取層有 eval（`eval/cases.jsonl`），上游這一層一直沒有——而 README 決策 13
自己寫著「抽取層的 eval 是 8/8，但實際用起來還是有錯，**錯在上游**」。
當時選模型的依據是一段錄音、兩個詞、肉眼比對。這支程式是要把那個判斷
換成可以重跑的數字。

用法
----
產出待校對的草稿（先用現有模型轉一遍，人只要改錯字，不用從零聽打）：

    docker compose exec -T whisper-worker python -u eval_asr.py draft > eval/asr-cases.jsonl

評分：

    docker compose exec -T whisper-worker python -u eval_asr.py score

比較不同後端／模型（backend 與 model 都可覆寫）：

    python -u eval_asr.py score --backend mlx --model BRlin/Breeze-ASR-25-mlx-fp16

為什麼 runner 在這裡而不是 core
------------------------------
ASR 跑在 Python 這一側。要在 Java 那邊評分就得繞一趟 RabbitMQ，
把「量模型準不準」變成「量整條管線通不通」——兩件不同的事。
決策 5 說語言邊界就是服務邊界，eval 也在同一條邊界上。
"""

import argparse
import json
import os
import re
import sys
import time
import unicodedata
from pathlib import Path

AUDIO_DIR = Path(os.getenv("AUDIO_DIR", "/data/audio"))
CASES_PATH = Path(os.getenv("ASR_CASES", "/eval/asr-cases.jsonl"))


# ── 正規化 ────────────────────────────────────────────────────────────
#
# CER 的數字完全取決於比較前做了什麼正規化，所以這裡的每一條都要說得出理由。
# 換句話說：**這幾行決定了分數的意義**，改它們等於改量尺，
# 舊分數就不能再拿來跟新分數比。

_PUNCT = re.compile(r"[\s，。、！？；：「」『』（）〈〉《》…—～·,.!?;:\"'()\[\]{}<>/\\|@#$%^&*+=~`_-]+")


def normalize(text: str) -> str:
    """把「不算辨識錯誤」的差異抹掉。

    - **標點與空白全部去掉**：Whisper 會自己加標點，而人工校對的參考稿標點習慣
      不可能一致。把標點算進 CER，量到的會是「標點習慣的差異」而不是聽錯了什麼。
      中文本來就沒有詞邊界，空白同理——Whisper 在英文詞前後加不加空白是排版問題。
    - **全形轉半形**：`ＫＫｄａｙ` 與 `KKday` 是同一個東西。
    - **英文一律小寫**：大小寫不是聽出來的。

    中文字元本身不做任何轉換——簡繁、異體字的差異<b>是</b>辨識錯誤，
    這個專案的輸出就該是繁體。
    """
    text = unicodedata.normalize("NFKC", text)  # 全形英數 → 半形
    text = text.lower()
    return _PUNCT.sub("", text)


def cer(reference: str, hypothesis: str) -> float:
    """字元錯誤率 = 編輯距離 / 參考字數。

    中文沒有詞邊界，所以用 CER 而不是 WER——這是中文 ASR 的慣例，
    Breeze 自己的 benchmark 也是這樣算的。

    自己寫而不是引入 jiwer：這裡真正有爭議的是上面那段正規化，不是編輯距離本身
    （十行、教科書寫法、沒有模糊空間）。為了它讓 worker 的映像檔多一個相依不划算。

    參考稿為空時回傳 0.0（沒有東西可以錯）或 1.0（有輸出卻不該有）。
    """
    ref, hyp = normalize(reference), normalize(hypothesis)
    if not ref:
        return 0.0 if not hyp else 1.0

    # 只留兩列的 Levenshtein：逐字稿可能上千字，開完整矩陣沒有必要
    previous = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, start=1):
        current = [i]
        for j, h in enumerate(hyp, start=1):
            current.append(min(
                previous[j] + 1,          # 刪除
                current[j - 1] + 1,       # 插入
                previous[j - 1] + (r != h),  # 替換
            ))
        previous = current
    return previous[len(hyp)] / len(ref)


def missing_terms(hypothesis: str, must_contain: list[str]) -> list[str]:
    """沒有轉出來的專有名詞。

    **為什麼 CER 之外還要這個**：CER 是平均，而專有名詞是少數字元。
    「奮起湖」轉成「正啟湖」在一段 200 字的逐字稿裡只貢獻約 1% 的 CER，
    但那則筆記的價值幾乎全在那三個字——地點錯了，行程就是廢的。
    決策 13 換模型的真正理由是這個，不是整體 CER。
    """
    normalized = normalize(hypothesis)
    return [t for t in must_contain if normalize(t) not in normalized]


# ── 後端 ──────────────────────────────────────────────────────────────
#
# 抽成介面是為了同一把尺能量不同的執行方式。目前的 CTranslate2 是基準線，
# MLX 那條路的加速倍數沒有任何公開數據（作者只比過 transformers），
# 所以「值不值得換」只能自己量。


class CTranslate2Backend:
    """正式環境目前用的：faster-whisper ＋ CTranslate2。"""

    def __init__(self, model_name: str, device: str, compute_type: str):
        from faster_whisper import WhisperModel

        self.label = f"ct2/{compute_type} {model_name}"
        self._model = WhisperModel(model_name, device=device, compute_type=compute_type)

    def transcribe(self, path: Path, language: str) -> tuple[str, float]:
        # 參數要跟 main.py 的正式路徑一致，否則量到的不是線上的行為
        segments, info = self._model.transcribe(path.as_posix(), beam_size=5, language=language)
        return "".join(s.text for s in segments).strip(), info.duration


class MlxBackend:
    """Apple Silicon 的 GPU 路徑：mlx-audio。

    <b>只能跑在宿主機</b>。容器裡沒有 Metal——這正是決策 15 讓 Ollama 留在
    宿主機的理由，而同一句話對 whisper-worker 一字不改地成立，當時沒人注意到。
    CTranslate2 支援的 Apple 加速是 Accelerate（CPU/AMX），<b>不是 Metal</b>，
    所以目前容器裡那條路連 CPU 上的 Apple 加速都吃不到。

    ⚠️ <b>量過了，2026-08 的結論是不能用在正式環境</b>（見 eval/README.md 的對照表）。
    真實音檔的內容它解得出來、甚至有幾個專有名詞比 CT2 準，但<b>音檔結束之後
    會繼續往補滿 30 秒視窗的那段靜音解下去</b>，接一長串幻覺。
    試過 chunk_duration、condition_on_previous_text、hallucination_silence_threshold、
    temperature 各種組合都壓不住；segment 的時間戳又全是 None，
    連事後依長度裁掉都做不到。

    這支留著是為了<b>下次 mlx-audio 更新時能一行指令重測</b>——
    當初「該不該換」花了一整輪才問清楚，下次不該再花第二輪。
    """

    def __init__(self, model_name: str):
        from mlx_audio.stt.utils import load_model

        self.label = f"mlx/fp16 {model_name}"
        self._model = load_model(model_name)

    def transcribe(self, path: Path, language: str) -> tuple[str, float]:
        samples, duration = decode_pcm(path)
        # 🔴 <b>不要傳 temperature=0.0</b>。
        #
        # model card 建議這樣做以求可重現，而那個建議會<b>關掉重複迴圈的逃生機制</b>。
        # 預設的 temperature 是一串遞增值 (0.0, 0.2, … 1.0)：先用 0.0 解，
        # 若 compression_ratio 超過門檻（＝輸出在自我重複）就升溫重解。
        # 傳單一個 0.0 等於把那串砍成一個值，模型一旦掉進迴圈就再也出不來。
        #
        # 實測代價極大：3.2 秒的音檔轉出「我會帶你去看電影」重複 16 次，
        # 那一則的 CER 是 1175%。用預設值就正常。
        #
        # 換句話說，可重現與正確性在這裡是衝突的，而<b>正確性優先</b>——
        # 只在偵測到自我重複時才升溫，一般情況下仍是 0.0，依然穩定。
        result = self._model.generate(samples, language=language)
        return (result.text or "").strip(), duration


SAMPLE_RATE = 16_000


def decode_pcm(path: Path):
    """把音檔解成 16 kHz 單聲道 float32，並回報長度。

    <b>為什麼不直接把檔案路徑丟給 mlx-audio</b>：它遇到 m4a 會去呼叫系統的
    {@code ffmpeg} 執行檔，而 faster-whisper 內建 PyAV、不需要。
    只為了跑另一個後端就要求機器上裝 ffmpeg，是個不必要的部署相依。

    更重要的是<b>公平性</b>：解碼器與重取樣器不同，本身就會造成辨識結果的差異。
    兩個後端走同一條解碼路徑，量到的差異才是模型的差異。
    """
    import av
    import numpy as np

    resampler = av.audio.resampler.AudioResampler(format="flt", layout="mono", rate=SAMPLE_RATE)
    chunks = []
    with av.open(path.as_posix()) as container:
        duration = float(container.duration) / 1_000_000  # AV_TIME_BASE 是微秒
        for frame in container.decode(audio=0):
            for resampled in resampler.resample(frame):
                chunks.append(resampled.to_ndarray().reshape(-1))
        for resampled in resampler.resample(None):  # 沖掉重取樣器裡剩下的
            chunks.append(resampled.to_ndarray().reshape(-1))

    samples = np.concatenate(chunks) if chunks else np.zeros(0, dtype=np.float32)
    return samples.astype(np.float32), duration


def build_backend(args):
    if args.backend == "ctranslate2":
        return CTranslate2Backend(args.model, args.device, args.compute_type)
    if args.backend == "mlx":
        return MlxBackend(args.model)
    raise SystemExit(f"還沒有實作這個後端：{args.backend}")


# ── 模式 ──────────────────────────────────────────────────────────────


def load_cases(path: Path) -> list[dict]:
    if not path.exists():
        raise SystemExit(
            f"找不到案例檔 {path}。先跑 `draft` 產出草稿，人工校對後再存成這個檔案。")
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def warn_unverified(cases: list[dict]) -> list[str]:
    """還沒人工校對的案例。

    🔴 這道閘門是必要的：`draft` 產出的 reference 就是模型自己的輸出，
    拿它當標準答案去評分，CER 會是漂亮的 0% ——**而那個 0% 什麼都沒證明**，
    它只證明了模型跟自己一致。一個會給出假綠燈的量尺比沒有量尺更危險。
    """
    return [c["id"] for c in cases if not c.get("verified")]


def run_draft(backend, args) -> None:
    """把音檔轉一遍，輸出待校對的案例骨架。

    參考稿先填模型的輸出——人只要改錯的地方，不用從零聽打。
    `mustContain` 留空，由人填「這幾個詞一定要對」。
    """
    files = sorted(p for p in AUDIO_DIR.iterdir() if p.suffix.lower() in {".m4a", ".mp3", ".wav", ".ogg"})
    if not files:
        raise SystemExit(f"{AUDIO_DIR} 裡沒有音檔")

    print(f"# 待校對草稿。reference 目前是「{backend.label}」的輸出，不是正確答案。",
          file=sys.stderr)
    print(f"# 共 {len(files)} 個音檔", file=sys.stderr)

    for path in files:
        started = time.monotonic()
        text, duration = backend.transcribe(path, args.language)
        elapsed = time.monotonic() - started
        print(f"  {path.name}  {duration:.1f}s 音檔 / {elapsed:.1f}s 轉錄", file=sys.stderr)

        print(json.dumps({
            "id": path.stem,
            "audio": path.name,
            "note": "TODO：這段在測什麼（中英夾雜／背景音／地名／連續獨白…）",
            "reference": text,
            "mustContain": [],
        }, ensure_ascii=False))


def run_score(backend, args) -> None:
    cases = load_cases(args.cases)

    unverified = warn_unverified(cases)
    if unverified and not args.allow_unverified:
        # 跳過而不是整批拒跑：校對是一筆一筆做的，不該因為還有一筆沒校完
        # 就讓已經校好的五筆也量不了。跳掉幾筆一定要講，否則整體 CER 會
        # 悄悄地只代表一部分案例。
        print(f"⚠ 跳過 {len(unverified)} 筆未校對的案例："
              f"{'、'.join(unverified)}", file=sys.stderr)
        cases = [c for c in cases if c.get("verified")]

    if not cases:
        raise SystemExit(
            "沒有任何已校對的案例。先跑 `draft` 產出草稿，人工校對後把 "
            "\"verified\" 改成 true。\n"
            "未校對的 reference 存的是模型自己的輸出，拿來評分只會得到假的 0% CER。")

    print(f"\n後端：{backend.label}")
    print(f"案例：{len(cases)} 筆"
          + (f"（另有 {len(unverified)} 筆未校對，未計入）" if unverified and not args.allow_unverified else "")
          + "\n")
    header = f"{'案例':<22}{'CER':>8}{'專有名詞':>10}{'RTF':>7}  說明"
    print(header)
    print("-" * (len(header) + 12))

    total_ref_chars = 0
    total_errors = 0.0
    per_case_rates = []
    total_terms = 0
    hit_terms = 0
    empty = 0
    audio_seconds = 0.0
    wall_seconds = 0.0
    rows = []

    for case in cases:
        path = AUDIO_DIR / case["audio"]
        if not path.exists():
            print(f"{case['id']:<22}{'—':>8}{'—':>10}{'—':>7}  ⚠ 找不到音檔 {case['audio']}")
            continue

        started = time.monotonic()
        text, duration = backend.transcribe(path, args.language)
        elapsed = time.monotonic() - started

        reference = case["reference"]
        rate = cer(reference, text)
        must = case.get("mustContain", [])
        missing = missing_terms(text, must)

        # 用參考字數加權：長逐字稿的錯誤不該跟短的一樣重
        ref_len = len(normalize(reference))
        total_ref_chars += ref_len
        total_errors += rate * ref_len
        per_case_rates.append(rate)
        total_terms += len(must)
        hit_terms += len(must) - len(missing)
        audio_seconds += duration
        wall_seconds += elapsed
        if not text.strip():
            empty += 1

        term_cell = "—" if not must else f"{len(must) - len(missing)}/{len(must)}"
        flag = "✔" if rate < 0.05 and not missing else "✖"
        print(f"{case['id']:<22}{rate:>7.1%}{term_cell:>10}{elapsed / duration:>7.2f}  "
              f"{flag} {case.get('note', '')}")
        if missing:
            print(f"{'':<22}└─ 沒轉出來：{'、'.join(missing)}")
            rows.append((case["id"], missing, text))

    print("-" * (len(header) + 12))
    overall = total_errors / total_ref_chars if total_ref_chars else 0.0
    unweighted = sum(per_case_rates) / len(per_case_rates) if per_case_rates else 0.0
    print(f"整體 CER（依字數加權）      {overall:.2%}")
    # 兩個一起看才有意義：加權是「總共錯幾個字」，未加權是「平均每則錯多少」。
    # 未加權明顯高於加權，就代表**短的那幾則特別糟**，而長段落把平均拉漂亮了。
    # 這個專案的輸入大多是短語音，所以未加權那個數字更貼近實際體感。
    print(f"平均 CER（每則等重）        {unweighted:.2%}")
    if total_terms:
        print(f"專有名詞命中          {hit_terms}/{total_terms}（{hit_terms / total_terms:.0%}）")
    print(f"空輸出                {empty}/{len(cases)}")
    if audio_seconds:
        print(f"整體 RTF              {wall_seconds / audio_seconds:.2f}"
              f"（音檔共 {audio_seconds:.0f}s，轉錄共 {wall_seconds:.0f}s）")

    # 沒有 assert，理由同抽取層的 eval：「幾分算通過」取決於當下的目標。
    # 剛換後端時 CER 略升但快三倍可能是划算的，穩定之後同樣的數字就是退步。
    if rows and args.show_output:
        print("\n有專有名詞漏掉的案例，完整輸出：")
        for case_id, missing, text in rows:
            print(f"\n[{case_id}] 缺 {'、'.join(missing)}\n{text}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mode", choices=["draft", "score"])
    parser.add_argument("--backend", default="ctranslate2", choices=["ctranslate2", "mlx"])
    parser.add_argument("--model", default=os.getenv("WHISPER_MODEL", "phate334/Breeze-ASR-25-ct2"))
    parser.add_argument("--device", default=os.getenv("WHISPER_DEVICE", "cpu"))
    parser.add_argument("--compute-type", default=os.getenv("WHISPER_COMPUTE_TYPE", "int8"))
    parser.add_argument("--language", default="zh")
    parser.add_argument("--cases", type=Path, default=CASES_PATH)
    parser.add_argument("--show-output", action="store_true",
                        help="印出有專有名詞漏掉的案例的完整逐字稿")
    parser.add_argument("--allow-unverified", action="store_true",
                        help="容許 reference 還沒人工校對就評分（數字不可信，僅供除錯）")
    args = parser.parse_args()

    backend = build_backend(args)
    (run_draft if args.mode == "draft" else run_score)(backend, args)


if __name__ == "__main__":
    main()
