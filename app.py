from flask import Flask, request, abort
from linebot.v3 import (
    WebhookHandler
)
from linebot.v3.exceptions import (
    InvalidSignatureError
)
from linebot.v3.messaging import (
    Configuration,
    ApiClient,
    MessagingApi,
    MessagingApiBlob,
    ReplyMessageRequest,
    TextMessage,
    AudioMessage
)
from linebot.v3.webhooks import (
    MessageEvent,
    TextMessageContent,
    AudioMessageContent
)

from faster_whisper import WhisperModel
import os

import requests

# 運行LineBot
app = Flask(__name__)

# Line Bot設定
configuration = Configuration(access_token='3JdeHbL579QeSsUP7zNsATAIxcNr+cRTiYWdocmCdBmmCLahoeWNll0VH/OmJRLESZEwvSuo+P7q2hJQ31oPfR5uYFbmjDVMFsPq9YEaNdtyJt+9qafAuR+h6O7aviy9dOVWK0D/aQkXji6QnM/ilQdB04t89/1O/w1cDnyilFU=')
handler = WebhookHandler('a7d76f222e027c0ab65d418c7c995904')

# Whisper設定
model_size = "large-v3" # tiny, base, small, medium, large, large-v2, large-v3
mode = "normal" # normal 一般, timeline 加入時間軸, subtitle 產生成字幕檔格式

# Run on GPU with FP16
model = WhisperModel(model_size, device="cuda", compute_type="float16")
# or run on GPU with INT8
# model = WhisperModel(model_size, device="cuda", compute_type="int8_float16")
# or run on CPU with INT8
# model = WhisperModel(model_size, device="cpu", compute_type="int8")

@app.route("/callback", methods=['POST'])
def callback():
    # get X-Line-Signature header value
    signature = request.headers['X-Line-Signature']

    # get request body as text
    body = request.get_data(as_text=True)
    app.logger.info("Request body: " + body)

    # handle webhook body
    try:
        handler.handle(body, signature)
    except InvalidSignatureError:
        app.logger.info("Invalid signature. Please check your channel access token/channel secret.")
        abort(400)

    return 'OK'


# 處理LineBot文字訊息
@handler.add(MessageEvent, message=TextMessageContent)
def handle_message(event):
    print("Text message received")
    with ApiClient(configuration) as api_client:
        line_bot_api = MessagingApi(api_client)
        line_bot_api.reply_message_with_http_info(
            ReplyMessageRequest(
                reply_token=event.reply_token,
                messages=[TextMessage(text=event.message.text)]
            )
        )


# 處理LineBot訊息
@handler.add(MessageEvent, message=AudioMessageContent)
def handle_audio(event):
    print("Audio message received")
    with ApiClient(configuration) as api_client:
        line_bot_blob_api  = MessagingApiBlob(api_client)

    # 取得語音檔案
    message_content = line_bot_blob_api.get_message_content(message_id=event.message.id)
    
    # 儲存語音檔案
    audio_path = 'temp_audio.wav'
    with open(audio_path, 'wb') as fw:
        fw.write(message_content)
    
    # 使用Whisper轉換語音為文字
    segments, info = model.transcribe(audio_path, beam_size=5, initial_prompt="繁體")
    transcription_segments = [segment.text for segment in segments]
    transcription = "，".join(transcription_segments)


    # 回覆文字訊息
    with ApiClient(configuration) as api_client:
        line_bot_api = MessagingApi(api_client)
        # line_bot_api.reply_message(
        #     event.reply_token,
        #     TextMessage(text=transcription)
        # )
        messages = ''
        # line_bot_api.reply_message_with_http_info(
        #     ReplyMessageRequest(
        #         reply_token=event.reply_token,
        #         messages=[TextMessage(text=transcription)]
        #     )
        # )
        messages=[TextMessage(text=transcription)]
        print(messages[0].text)
    
        url = "https://memos-archie.fly.dev/api/v1/memo"

        headers = {
            "Authorization": "Bearer eyJhbGciOiJIUzI1NiIsImtpZCI6InYxIiwidHlwIjoiSldUIn0.eyJuYW1lIjoiIiwiaXNzIjoibWVtb3MiLCJzdWIiOiIxIiwiYXVkIjpbInVzZXIuYWNjZXNzLXRva2VuIl0sImV4cCI6NDg2NjQwMzI1OCwiaWF0IjoxNzEyODAzMjU4fQ.wBF1WMj3Lizwktcrn5Vvc9YTRRiq-V6S7AQcf17IZjg"
        }

        payload = {
            "content": messages[0].text,
            "visibility": "PUBLIC",
            "resourceIdList": []
        }
        response = requests.post(url, headers=headers, json=payload)
        if response.status_code == 200:
            print("Memo pushed successfully")
        else:
            print(f"Error: {response.status_code} - {response.text}")
    # 刪除暫存的語音檔案
    os.remove(audio_path)
    
def handle_error(event, exception):
    print(exception)
    
if __name__ == "__main__":
    app.run()