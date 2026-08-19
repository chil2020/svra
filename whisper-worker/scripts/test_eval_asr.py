"""量尺自己的測試。

eval_asr.py 是用來判斷「換模型是變好還是變壞」的工具。它算錯的話，
產出的是**有自信的錯誤數字**——那比沒有數字更危險，因為沒有人會去質疑它。

不用 pytest：worker 的映像檔目前只有 faster-whisper 與 pika 兩個相依，
為了六個 assert 多裝一套測試框架不划算。CI 直接 `python test_eval_asr.py`。

    python whisper-worker/scripts/test_eval_asr.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from eval_asr import cer, missing_terms, normalize, warn_unverified  # noqa: E402


def check(label, actual, expected):
    assert actual == expected, f"{label}：預期 {expected!r}，實得 {actual!r}"
    print(f"  ✔ {label}")


def approx(label, actual, expected, tolerance=1e-9):
    assert abs(actual - expected) < tolerance, f"{label}：預期 {expected}，實得 {actual}"
    print(f"  ✔ {label}")


print("正規化")
check("標點與空白不算錯誤",
      normalize("明天早上，要去阿里山。"), normalize("明天早上要去阿里山"))
check("全形英數轉半形",
      normalize("ＫＫｄａｙ"), normalize("KKday"))
check("英文大小寫不算錯誤",
      normalize("QR Code"), normalize("qr code"))
check("英文詞前後的空白不算錯誤",
      normalize("在 KKday 買的"), normalize("在KKday買的"))
# 這一條是刻意的：簡繁差異是辨識錯誤，不該被正規化掉
assert normalize("台灣") != normalize("台湾"), "簡繁不可以被視為相同"
print("  ✔ 簡繁差異保留（那是辨識錯誤，不是格式差異）")

print("\nCER")
approx("完全相同 → 0%", cer("明天要去阿里山", "明天要去阿里山"), 0.0)
approx("只差標點 → 0%", cer("明天要去阿里山", "明天，要去阿里山。"), 0.0)
# 「奮起湖」→「正啟湖」：三個字錯兩個（奮→正、起→啟），參考「要去奮起湖玩」共 6 字
approx("替換兩字 / 六字 → 2/6", cer("要去奮起湖玩", "要去正啟湖玩"), 2 / 6)
approx("整句漏掉 → 100%", cer("明天要去阿里山", ""), 1.0)
approx("參考為空但有輸出 → 100%", cer("", "無中生有"), 1.0)
approx("兩邊都空 → 0%", cer("", ""), 0.0)

print("\n專有名詞")
check("有轉出來就算命中",
      missing_terms("那是在 KKday 買的", ["KKday"]), [])
check("大小寫與空白不影響命中",
      missing_terms("那是在kkday買的", ["KK day"]), [])
check("聽錯就是沒命中",
      missing_terms("要去正啟湖吃便當", ["奮起湖"]), ["奮起湖"])
check("多個詞只回報漏掉的",
      missing_terms("在 KKday 買了阿里山的票", ["KKday", "阿里山", "奮起湖"]), ["奮起湖"])

print("\n未校對閘門")
check("verified 缺席視為未校對",
      warn_unverified([{"id": "a"}]), ["a"])
check("verified=false 視為未校對",
      warn_unverified([{"id": "a", "verified": False}]), ["a"])
check("verified=true 才放行",
      warn_unverified([{"id": "a", "verified": True}]), [])

print("\n全部通過")
