# 使用官方的 Python 基礎镜像
FROM python:3.9

# 設置工作目錄
WORKDIR /app

# 複製當前目錄中的所有文件到工作目錄
COPY . .

# 安裝 requirements.txt 中列出的依賴項
RUN pip install --no-cache-dir -r requirements.txt

# 設置環境變量 (如果需要的話)
ENV FLASK_APP=app.py

# 暴露端口 (如果需要的話)
EXPOSE 5000

# 運行應用程序
CMD ["python", "-m", "flask", "run", "--host=0.0.0.0"]
