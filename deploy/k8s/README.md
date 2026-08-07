# K8s 部署（第 3 週：8/10–8/16）

預計內容：

- [ ] core / whisper-worker 的 Deployment + Service
- [ ] PostgreSQL、RabbitMQ、Redis：本機用 OrbStack 內建 K8s 或 kind，正式論述放 README trade-off（自管 vs 託管）
- [ ] ConfigMap / Secret 分離（延續 12-factor，憑證不落地）
- [ ] HPA 展示點：worker 依佇列深度水平擴展（KEDA 是加分題，可只寫進 Future Work）
