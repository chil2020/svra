FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# -u：log 即時輸出，不被 buffer 卡住
CMD ["python", "-u", "main.py"]
