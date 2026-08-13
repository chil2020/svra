# K8s 部署（Future Work，尚未實作）

目前的部署方式是根目錄的 `docker-compose.yml`，單機起 core、whisper-worker、
PostgreSQL、RabbitMQ、Redis。以現在的規模這樣就夠了。

K8s 這層**刻意先不做**。它要解決的是水平擴展與滾動更新，
而目前既沒有需要擴展的流量，也還沒量出瓶頸在哪。
等佇列層與 LLM 層完成、確認 worker 真的是瓶頸之後再做才有意義。

## 真的要做的話，順序會是

1. core / whisper-worker 的 Deployment + Service
2. ConfigMap / Secret 分離（延續 12-factor，憑證不落地）
3. 資料層（PostgreSQL、RabbitMQ、Redis）自管 vs 託管的取捨
   —— 自管省錢，但備份、升級、HA 都得自己扛
4. worker 依佇列深度做水平擴展

## 第 4 項是這裡唯一有技術含量的部分

**CPU 使用率不是轉錄服務的正確擴展指標。**

whisper worker 大部分時間在等 I/O（下載音檔、寫結果），
這時候 CPU 是低的，但佇列可能已經積了一堆待處理的任務。
照 CPU 擴展會擴不動；照佇列深度擴展才對得上實際負載。

K8s 原生的 HPA 只看得到 CPU 與記憶體，看不到 RabbitMQ 的佇列深度，
所以需要 KEDA 這類 metrics adapter 把外部指標接進來。
