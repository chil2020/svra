#!/usr/bin/env python3
"""一次性取得 Google 行事曆的 refresh token，並建立專用的子行事曆。

跑法：
    export GOOGLE_CLIENT_ID=...  GOOGLE_CLIENT_SECRET=...
    python3 deploy/google-calendar-auth.py

它會開一個瀏覽器分頁讓你授權，然後把要貼進 .env 的兩個值印出來。
只有換 client、撤銷授權、或 refresh token 失效時才需要再跑一次。

為什麼是一支獨立腳本，而不是在 core 裡開一個 /oauth/callback 端點：
那個端點必須 permitAll（OAuth 的 callback 沒有 HMAC 可驗），等於在唯一那個
對外入口旁邊開一個沒有驗簽的洞——而 README 決策 20 講的就是這件事。
授權是「設定」，不是「執行期功能」，它不需要一直掛在網路上。

只用標準函式庫：這支腳本一年跑不到一次，為它裝 google-auth-oauthlib
是讓一個一次性的工具長出需要維護的相依。
"""

import http.server
import json
import os
import secrets
import socket
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
CALENDAR_API = "https://www.googleapis.com/calendar/v3"

# 🔴 這一行是整個產品能不能公開的關鍵，不是隨手挑的。
#
#   calendar              全權，「查看、編輯、分享並永久刪除你所有的日曆」
#   calendar.events       「查看及編輯你所有日曆上的所有活動」  → **機密範圍**
#   calendar.app.created  「建立次要日曆，並管理其中的活動」    → **非機密範圍**
#
# 而 Google 說：「If your app utilizes only non-sensitive scopes, it is not
# mandatory for your app to complete the app verification process.」
#
# 只用非機密範圍 → 不需要驗證 → 不會出現未驗證警告畫面 → **沒有 100 人終身上限**，
# 也不用 4-6 週人工審核。換成 calendar 或 calendar.events，那三樣全部回來。
#
# 代價是這個 scope **寫不進 primary**：它只碰得到這支腳本自己建的那本行事曆。
# 那不是限制，那正是它便宜的原因——而且它同時是最好的隱私保證：
# 就算伺服器被打下來，攻擊者也讀不到使用者的其他行程。
SCOPE = "https://www.googleapis.com/auth/calendar.app.created"

CALENDAR_NAME = "SVRA"


def fail(message):
    print(f"\n✗ {message}", file=sys.stderr)
    sys.exit(1)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class CallbackHandler(http.server.BaseHTTPRequestHandler):
    """收 Google 轉回來的 authorization code。"""

    result = {}

    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        CallbackHandler.result = {k: v[0] for k, v in query.items()}
        ok = "code" in CallbackHandler.result
        body = ("授權完成，可以關掉這個分頁了。" if ok
                else "授權失敗：" + CallbackHandler.result.get("error", "未知原因"))
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(f"<html><body><h3>{body}</h3></body></html>".encode())
        threading.Thread(target=self.server.shutdown, daemon=True).start()

    def log_message(self, *args):
        pass  # 不要把 HTTP log 混進輸出裡


def post_form(url, fields):
    data = urllib.parse.urlencode(fields).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data)) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        fail(f"{url} 回應 {e.code}：{e.read().decode(errors='replace')}")


def api(method, path, token, body=None, allow_failure=False):
    request = urllib.request.Request(
        CALENDAR_API + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {token}",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        if allow_failure:
            return None
        fail(f"Calendar API {method} {path} 回應 {e.code}："
             f"{e.read().decode(errors='replace')}")


def authorize(client_id, client_secret):
    port = free_port()
    redirect_uri = f"http://127.0.0.1:{port}"
    state = secrets.token_urlsafe(16)

    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPE,
        # access_type=offline 才會發 refresh token；
        # prompt=consent 強制重新同意——同一個帳號第二次授權時，
        # 少了它 Google 只回 access token，而我們要的正是 refresh token。
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
    }
    url = f"{AUTH_URL}?{urllib.parse.urlencode(params)}"

    print("在瀏覽器完成授權（若沒有自動開啟，複製下面這個網址）：\n")
    print(f"  {url}\n")
    print(f"⏳ 等待授權中… 這個視窗會停在這裡不動，那是正常的。")
    print(f"   授權完成後它會自己繼續。**不要按 Ctrl+C**——")
    print(f"   中斷之後本機這個接收埠（{port}）就沒了，")
    print(f"   Google 導回來只會看到「拒絕連線」，得整個重跑。")
    print("\nℹ️ 若出現「Google hasn't verified this app」：")
    print("   發布狀態還是「測試中」的話這是正常的，點「進階」→「前往…（不安全）」通過。")
    print("   已經切到 In Production 卻還出現，才代表 scope 被改動過")
    print("   ——非機密範圍不該有這個畫面，先回頭看 SCOPE 那一行。\n")
    webbrowser.open(url)

    server = http.server.HTTPServer(("127.0.0.1", port), CallbackHandler)
    # 五分鐘還沒等到就自己收攤，而不是無限期佔著一個埠。
    server.timeout = 300
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        fail("被中斷了。授權還沒完成——重跑一次，這次讓它停在「等待授權中」別動。")

    result = CallbackHandler.result
    if not result:
        fail("等了五分鐘沒有等到授權回呼。可能是瀏覽器沒開起來，"
             "或授權完成後導回的網址被別的東西攔走了。")
    if "code" not in result:
        fail("沒有拿到 authorization code：" + result.get("error", "使用者取消？"))
    if result.get("state") != state:
        fail("state 對不上，可能不是這次的授權回應")

    tokens = post_form(TOKEN_URL, {
        "code": result["code"],
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect_uri,
        "grant_type": "authorization_code",
    })
    if "refresh_token" not in tokens:
        fail("Google 沒有回 refresh token。到 Google 帳號的「第三方應用程式」"
             "把這個 app 的存取權移除後再跑一次。")
    return tokens


def ensure_calendar(token, existing_id):
    """沿用既有的那本，或建一本新的。

    🔴 **不能用列出所有行事曆的方式去找**。calendar.app.created 只授權
    「這支 app 建的那本」，`GET /users/me/calendarList` 會直接 403——
    而那正是這個 scope 便宜的原因：它看不到使用者的其他行事曆。

    所以「已經建過了嗎」只能靠我們自己記著 id。重跑這支腳本時，
    把舊的 GOOGLE_CALENDAR_ID 留在環境變數裡，它就會沿用而不是再建一本。
    """
    if existing_id:
        # 確認那本還在、而且這次的授權碰得到它。碰不到就當作沒有，重新建一本——
        # 使用者可能換了 Google 帳號，而舊 id 屬於另一個帳號。
        try:
            calendar = api("GET", "/calendars/" + urllib.parse.quote(existing_id, safe=""),
                           token, allow_failure=True)
        except SystemExit:
            calendar = None
        if calendar:
            print(f"沿用既有的行事曆「{calendar.get('summary', existing_id)}」。")
            return existing_id
        print("⚠️ 環境變數裡的 GOOGLE_CALENDAR_ID 這次碰不到（換帳號了？），改建一本新的。")

    created = api("POST", "/calendars", token, {
        "summary": CALENDAR_NAME,
        "description": "由 SVRA 從 LINE 語音筆記匯入的行程。可以整層關掉或整批刪除。",
        "timeZone": "Asia/Taipei",
    })
    print(f"已建立行事曆「{CALENDAR_NAME}」。")
    return created["id"]


def main():
    client_id = os.environ.get("GOOGLE_CLIENT_ID", "").strip()
    client_secret = os.environ.get("GOOGLE_CLIENT_SECRET", "").strip()
    if not client_id or not client_secret:
        fail("請先設定 GOOGLE_CLIENT_ID 與 GOOGLE_CLIENT_SECRET"
             "（GCP → Credentials → OAuth client ID → Desktop app）")

    tokens = authorize(client_id, client_secret)
    calendar_id = ensure_calendar(tokens["access_token"],
                                  os.environ.get("GOOGLE_CALENDAR_ID", "").strip())

    print("\n" + "=" * 64)
    print("把下面兩行貼進 .env：\n")
    print(f"GOOGLE_REFRESH_TOKEN={tokens['refresh_token']}")
    print(f"GOOGLE_CALENDAR_ID={calendar_id}")
    print("\n" + "=" * 64)
    print("⚠️ 最後確認一次：GCP 的 OAuth consent screen 必須是 In Production。")
    print("   還停在 Testing 的話，這個 refresh token 七天後就會失效。")
    print("   （只用非機密範圍時，Publish 不需要通過任何審核，按下去就好。）")
    print("⚠️ 別忘了把自己的 LINE userId 填進 CALENDAR_OAUTH_USER_IDS，")
    print("   不然卡片上還是會給你連結而不是一鍵匯入的按鈕。")
    print("⚠️ 手機的 Google 行事曆 app 要手動把「SVRA」這個行事曆勾起來才看得到。")


if __name__ == "__main__":
    main()
