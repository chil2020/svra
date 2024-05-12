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
    ReplyMessageRequest,
    TextMessage
)
from linebot.v3.webhooks import (
    MessageEvent,
    AudioMessageContent
)
from faster_whisper import WhisperModel
import os

# Line Bot設定
configuration = Configuration('3JdeHbL579QeSsUP7zNsATAIxcNr+cRTiYWdocmCdBmmCLahoeWNll0VH/OmJRLESZEwvSuo+P7q2hJQ31oPfR5uYFbmjDVMFsPq9YEaNdtyJt+9qafAuR+h6O7aviy9dOVWK0D/aQkXji6QnM/ilQdB04t89/1O/w1cDnyilFU=')
handler = WebhookHandler('a7d76f222e027c0ab65d418c7c995904')

# Whisper設定
model_size = "large-v2" # tiny, base, small, medium, large, large-v2, large-v3
mode = "normal" # normal 一般, timeline 加入時間軸, subtitle 產生成字幕檔格式

# Run on GPU with FP16
# model = WhisperModel(model_size, device="cuda", compute_type="float16")
# or run on GPU with INT8
# model = WhisperModel(model_size, device="cuda", compute_type="int8_float16")
# or run on CPU with INT8
model = WhisperModel(model_size, device="cpu", compute_type="int8")

# 處理LineBot訊息
@handler.add(MessageEvent, message=AudioMessageContent)
def handle_audio(event):
    with ApiClient(configuration) as api_client:
        line_bot_api = MessagingApi(api_client)

    # 取得語音檔案
    message_content = line_bot_api.get_message_content(event.message.id)
    
    # 儲存語音檔案
    audio_path = 'temp_audio.wav'
    with open(audio_path, 'wb') as fw:
        fw.write(message_content.content)
    
    # 使用Whisper轉換語音為文字
    segments, info = model.transcribe(audio_path, beam_size=5, initial_prompt="繁體")
    transcription_segments = [segment.text for segment in segments]
    transcription = "，".join(transcription_segments)


    # 回覆文字訊息
    line_bot_api.reply_message(
        event.reply_token,
        TextMessage(text=transcription)
    )
    
    # 刪除暫存的語音檔案
    os.remove(audio_path)

# 運行LineBot
import flask
app = flask.Flask(__name__)

@app.route("/callback", methods=['POST'])
def callback():
    # 處理LineBot的webhook請求
    signature = flask.request.headers['X-Line-Signature']
    body = flask.request.get_data(as_text=True)
    handler.handle(body, signature)
    return 'OK'

if __name__ == "__main__":
    app.run("0.0.0.0", port=6000, debug=True)