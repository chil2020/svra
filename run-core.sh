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
echo "LINE_CHANNEL_SECRET = ${LINE_CHANNEL_SECRET:+已設定}${LINE_CHANNEL_SECRET:-⚠️ 未設定}"
echo ""

exec mvn -B -ntp spring-boot:run -f core/pom.xml
