#!/usr/bin/env bash
# 本機跑 core。讀根目錄的 .env，並把 AUDIO_DIR 指到與 worker 共用的絕對路徑。
#
# 為什麼需要這支：.env 是 docker-compose 在讀的，mvn spring-boot:run 不會讀；
# 而 audio-dir 用相對路徑會依啟動目錄變動（mvn 的工作目錄是 core/，
# compose 掛的是 repo 根目錄），兩端就會讀寫不同地方。
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "找不到 .env，請先從 .env.example 複製一份"; exit 1; }
set -a; source .env; set +a

export AUDIO_DIR="$(pwd)/data/audio"
mkdir -p "$AUDIO_DIR"

echo "AUDIO_DIR = $AUDIO_DIR"

# 只印有沒有設定，不印值。
# 注意 ${V:+已設定}${V:-未設定} 這種寫法是錯的：有值時第二段會展開成「值本身」，
# 等於把 secret 印進日誌。先收斂成一個變數再取預設值才安全。
#
# LINE 那兩個是無條件必填——少了 core 會在啟動時因為屬性綁定失敗而炸
# （決策 8 一貫的做法）。與其讓人去讀那串 Spring 的 BindException，
# 不如在啟動之前就講清楚是哪一個。
report() {
  local KEY=$1
  local STATE="${!KEY:+已設定}"
  printf '%-28s = %s\n' "$KEY" "${STATE:-⚠️ 未設定}"
  [ -n "$STATE" ]
}

MISSING=0
for KEY in LINE_CHANNEL_SECRET LINE_CHANNEL_ACCESS_TOKEN; do
  report "$KEY" || MISSING=1
done

# GOOGLE_ 那四個是**有條件**必填：白名單空的話所有人走預填連結（決策 27），
# 完全不需要 OAuth，留白是正確狀態而不是漏填。
# 白名單有人卻沒填齊才是壞的組合——那由 CalendarProperties 的 @AssertTrue 擋。
report CALENDAR_OAUTH_USER_IDS || true
if [ -n "${CALENDAR_OAUTH_USER_IDS:-}" ]; then
  for KEY in GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GOOGLE_REFRESH_TOKEN GOOGLE_CALENDAR_ID; do
    report "$KEY" || MISSING=1
  done
else
  echo "（白名單是空的 → 所有人走 Google 預填連結，不需要 GOOGLE_ 憑證）"
fi

if [ "$MISSING" = 1 ]; then
  echo ""
  echo "⚠️ 有必填項沒設定，core 會在啟動時失敗。"
  echo "   LINE 的兩個看 .env.example；GOOGLE_ 那四個跑一次："
  echo "       export GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=..."
  echo "       python3 deploy/google-calendar-auth.py"
  echo "   或者把 CALENDAR_OAUTH_USER_IDS 清空，讓所有人走連結。"
fi
echo ""

exec mvn -B -ntp spring-boot:run -f core/pom.xml
