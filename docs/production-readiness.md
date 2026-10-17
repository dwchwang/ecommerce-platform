# Production Readiness — Gap Analysis (baseline)

> Phase 0 — 0.4. Đánh giá tại tag `baseline-original`. Chấm lại ở Phase 9 để có bảng trước/sau.
> Thang điểm: **0** = không có · **1** = có nhưng sai/thiếu nghiêm trọng · **2** = đúng cơ bản · **3** = production-grade.
> Chi tiết kiến trúc: [`architecture/current-architecture.md`](architecture/current-architecture.md). Số đo: [`baseline-metrics.md`](baseline-metrics.md).

## Điều cần nắm

1. **Repo "có đủ thứ" nhưng hầu hết ở mức 1:** có CI, K8s manifest, ArgoCD, metrics, Resilience4j — nhưng đều có lỗi khiến chúng không dùng được đúng nghĩa production.
2. **Chạy local không được nếu không vá:** Compose thiếu biến môi trường so với K8s, schema DB phụ thuộc `ddl-auto` → phải dùng `docker-compose.override.yml` (TD-15, TD-04, TD-05 trong `tech-debt.md`).
3. **Không gì truy vết được:** image tag `latest`, `pull_policy: always`, build bằng Jib chỉ cho amd64, ArgoCD theo dõi `main` của **repo tác giả gốc**.
4. **Secret nằm trong Git** ở ít nhất 4 nơi (`.env`, `k8s/secrets.yaml`, realm Keycloak, runner secret) → xử lý ở Phase 1 theo D5.
5. **Reliability gần như bằng 0:** mọi lời gọi sync không timeout và đang trỏ sai URL; Kafka dual-write không outbox.
6. Điểm tổng **8 / 27** → mục tiêu cuối project ≥ 24 / 27.

---

## 1. Bảng gap

| Mảng | Điểm | Bằng chứng chính | Mục tiêu | Phase |
|---|---|---|---|---|
| Container | **1** | Jib, chỉ amd64, chạy **root**, tag `latest`, ~430–470MB; không Dockerfile | Multi-stage arm64, non-root, tag SHA, nhỏ hơn rõ rệt | 1 |
| CI/CD | **1** | Có paths-filter + reusable workflow; nhưng tag `latest`, không scan, chỉ unit test, `startup_failure` trên fork, CD có nhánh `kubectl apply` | PR gate + main pipeline + GitOps | 1, 3 |
| Kubernetes | **1** | Có startup/readiness, resources, seccomp; **không liveness**, không ServiceAccount, không HPA/PDB/NetworkPolicy, `imagePullPolicy: Always` + `latest`, chạy root | Chart chung chuẩn restricted | 2 |
| GitOps | **1** | Có ArgoCD Application + auto-sync; nhưng trỏ **repo tác giả gốc**, sync cả `k8s/secrets.yaml`, CD còn `kubectl` + `argocd app sync --force` từ CI | GitOps repo riêng, ApplicationSet, promotion qua PR | 2, 3 |
| Observability | **1** | Actuator + Prometheus registry (cổng 9000), APISIX có plugin prometheus; **không histogram**, log text, không traceId, không tracing, không dashboard, không alert | Metrics + logs + traces liên kết, alert có runbook | 4 |
| Security | **1** | JWT 2 lớp (APISIX + service), seccomp + drop ALL; nhưng secret trong Git, OpenAPI public qua gateway, runner trong cluster, không scan nào | Pipeline security + secrets management + admission policy | 1, 6 |
| Reliability | **0** | Không timeout/retry/CB trên lời gọi sync; URL gọi sai; dual-write Kafka; email cứng người nhận | Timeout budget, CB, outbox, graceful degradation, chaos lab | 5 |
| IaC | **1** | Có `k3d-config.yaml` + `k3d-setup.sh` cho local; không có gì cho cloud | Terraform VPC/EKS/RDS | 4, 7 |
| DR | **1** | Stateful dùng StatefulSet + PVC; không backup, không restore | Backup + restore kiểm chứng, RPO/RTO | 8 |
| **Tổng** | **8 / 27** | | **≥ 24 / 27** | |

## 2. Chi tiết theo mảng

### 2.1 Container

| Hạng mục | Hiện trạng | Bằng chứng |
|---|---|---|
| Cách build | Jib (`jib-maven-plugin`), không Dockerfile | `pom.xml` (jib), `.github/workflows/_build-service.yml:74` |
| Kiến trúc | Chỉ `linux/amd64` | `docker buildx imagetools inspect` → một manifest amd64 |
| User | **root** (Config.User rỗng) | `docker image inspect` |
| Tag | `latest` trên main, tên nhánh cho nhánh khác | `_build-service.yml:51` |
| Kích thước | 420–467MB / service | `baseline-metrics.md` |
| JVM trong container | Compose: không giới hạn; K8s: `-Xmx200m` + `MaxRAMPercentage=75` (mâu thuẫn nhau) | `k8s/configmap.yaml` |
| Compose healthcheck | Có cho hạ tầng; **không có cho service ứng dụng** | `docker-compose.yml` |

### 2.2 CI/CD

Pipeline hiện tại:

```text
PR / push main ─► detect-changes (dorny/paths-filter) ─► mvn test (unit, toàn repo)
   └─► 13 job build-<service> gọi _build-service.yml ─► jib:build ─► push ghcr.io/<owner>/<svc>:latest
workflow_dispatch ─► cd.yml ─► [argocd] argocd app sync --force trên runner self-hosted trong cluster
                             └► [kubectl] kubectl apply -f k8s/... + rollout restart
```

| Câu hỏi | Trả lời |
|---|---|
| Image tag immutable? | Không — `latest` |
| SAST / SCA / secret scan / container scan? | Không có. Chỉ Renovate cập nhật dependency, `.whitesource` (Mend) |
| Integration test? | Không, chỉ `mvn test` |
| Cache? | Cache Maven (`setup-java cache: maven`); không cache Docker layer (Jib tự xử lý layer) |
| Chỉ build service thay đổi? | Có (paths-filter) — điểm tốt, giữ lại ý tưởng |
| Traceability commit → image → deploy? | Không |
| Rollback? | Không có quy trình; `latest` khiến rollback về bản cũ là không thể |
| Trạng thái trên fork | `startup_failure` sau 1 giây: job trong `_build-service.yml` xin quyền `packages: write`, nhưng `ci.yml` không khai báo `permissions` và repo fork mặc định chỉ cấp `read` → GitHub từ chối khởi chạy workflow |

Đánh giá `cd.yml`: chứa đúng anti-pattern roadmap muốn loại bỏ — CI cầm credential cluster (`kubectl apply`, `rollout restart`) và CI ép ArgoCD sync (`--force`) thay vì để ArgoCD tự reconcile; các bước lỗi được nuốt bằng `|| true`.

### 2.3 Kubernetes (`k8s/backend/*.yaml`, 13 Deployment)

| Hạng mục | Có / 13 | Ghi chú |
|---|---|---|
| startupProbe | 13 | `/actuator/health` cổng 9000 — health gồm cả DB |
| readinessProbe | 13 | Cùng endpoint startup — readiness phụ thuộc DB |
| livenessProbe | **0** | Treo/deadlock sẽ không tự restart |
| resources | 13 | request = limit memory 512Mi, CPU limit 1000m (throttling) |
| securityContext | 13 | seccomp RuntimeDefault, drop ALL, no privilege escalation; **không** `runAsNonRoot`, không `readOnlyRootFilesystem` |
| ServiceAccount riêng | **0** | Dùng `default` |
| HPA / PDB / NetworkPolicy | **0** | Không có trong toàn bộ `k8s/` |
| Image | `latest` + `imagePullPolicy: Always` | Mỗi lần restart có thể ra bản khác |
| initContainer | busybox chờ postgres/kafka bằng `nc` | busybox không pin version |

Hạ tầng (`k8s/infra/`): Postgres, Kafka, Elasticsearch, Redis dạng StatefulSet + PVC; Keycloak, APISIX, RustFS dạng Deployment. Một Postgres duy nhất cho mọi service.

Thư mục khác:
- `k8s/argocd/application.yaml`: 2 Application (`ecommerce`, `ecommerce-prod`) trỏ `https://github.com/hoangtien2k3/ecommerce-microservices.git`, path `k8s` (gồm cả `secrets.yaml`), `prune + selfHeal`. **Nếu cài nguyên trạng, cluster của bạn sẽ chạy theo commit của người khác.**
- `k8s/github-runner/runner-scale-set.yaml`: Actions Runner Controller trong cluster, xác thực bằng GitHub PAT trong Secret. Runner này chạy job CD có quyền vào cluster → ai merge được vào repo là có quyền vào cluster.
- `k3d-config.yaml` / `k3d-setup.sh`: script một lệnh dựng k3d, tự cài k3d/kubectl nếu thiếu, `kubectl apply` toàn bộ `k8s/`.

### 2.4 Observability

| Câu hỏi | Hiện trạng |
|---|---|
| `/actuator/prometheus` | Có, cổng 9000 (`common-spring`), 25 series http/jvm mẫu trên order-service |
| Percentile histogram | **Không** — không tính được P95/P99 |
| Log | Text pattern, không JSON, không traceId |
| Tracing | Không có Micrometer Tracing / OpenTelemetry |
| Dashboard / alert | Không có trong repo |
| Gateway | APISIX global rule bật `prometheus` |

### 2.5 Security

| Vị trí | Nội dung |
|---|---|
| `.env` (commit) | 15 biến nhạy cảm: mật khẩu Postgres, Redis, Keycloak admin, storage access/secret key, mail password… |
| `k8s/secrets.yaml` (commit) | 10 khóa (Postgres, Keycloak admin, Redis, storage, Elastic, mail) dạng **`stringData` — plaintext**, không phải base64 |
| `docker/keycloak/import/ecommerce-realm.json` | Mật khẩu user test plaintext |
| `k8s/github-runner/runner-scale-set.yaml` | Secret chứa trường token |
| `deploy/apisix/apisix.yaml` | 13 route `/v3/api-docs` public |
| notification `EventConsumer.java` | Email thanh toán gửi tới địa chỉ cứng bên ngoài |

gitleaks 8.30.1 (rule mặc định): **42 finding / 294 commit** toàn lịch sử, 12 finding ở file đang track tại HEAD (chi tiết `baseline-metrics.md` §5). Điểm quan trọng: **rule mặc định không bắt được `.env` và `k8s/secrets.yaml`** — mật khẩu dạng `KEY=value` entropy thấp lọt qua. Phase 1 cần thêm rule tùy chỉnh trong `.gitleaks.toml` (chặn commit file `.env`, chặn `kind: Secret` có `data`/`stringData`), không thể chỉ dựa vào rule mặc định.

### 2.6 Reliability

- Lời gọi sync: không timeout, không retry, không CB, URL sai (bảng ở `current-architecture.md` §2).
- Kafka: publish sau khi commit DB, không outbox; tên topic sai ngữ nghĩa; consumer group nhầm.
- **Lỗi bị nuốt:** order/payment `catch` lỗi gọi sync, log rồi vẫn trả 200 với dữ liệu thiếu → client và monitoring không thấy lỗi (đã quan sát khi chạy e2e).
- **Mất event không dấu vết:** notification gửi mail lỗi nhưng offset Kafka vẫn commit (lag = 0) — không retry, không DLQ.
- **Không idempotent ở tầng HTTP:** payment trùng trả 500 thay vì 409; payment cho order không tồn tại vẫn được tạo và vẫn phát event `SUCCESSFUL`.
- Không có idempotency cho payment (chỉ chặn trùng bằng `existsByOrderIdAndIsPayed`, không có idempotency key).
- Redis chạy nhưng không dùng → không có cache để fallback.

## 3. Phân loại: Giữ / Refactor / Thay thế / Build mới

| Nhóm | Thành phần | Lý do |
|---|---|---|
| **Giữ** | Business logic, ranh giới 13 service, database-per-service, Keycloak + realm import, APISIX standalone YAML + rewrite theo context-path, paths-filter trong CI, startup/readiness probe + seccomp/drop ALL, Actuator trên cổng quản trị riêng | Đúng hướng, sửa nhỏ là dùng được |
| **Refactor** | URL gọi giữa service (TD-06), property Kafka (TD-04, TD-05), Liquibase thật (TD-15), probes (thêm liveness, tách readiness/liveness group), resources (bỏ CPU limit theo ADR 008), securityContext (non-root, read-only FS), logging JSON, Makefile (TD-10) | Có sẵn nhưng sai |
| **Thay thế** (cần ADR) | Jib amd64 `latest` → Dockerfile multi-stage arm64 tag SHA (ADR 012, 014) · manifest viết tay → Helm chart chung (ADR 002) · `cd.yml` kubectl/argocd-sync → GitOps repo + ApplicationSet (ADR 001) · ARC runner trong cluster → GitHub-hosted runner (ADR 012) · secret trong Git → Sealed Secrets / ESO (ADR 007) | Kiến trúc hiện tại đi ngược nguyên tắc roadmap |
| **Build mới** | Security pipeline, Testcontainers, NetworkPolicy/HPA/PDB/KEDA, observability stack, Argo Rollouts canary, resilience + outbox, Terraform, SLO, backup/DR, runbook, postmortem | Chưa tồn tại |

## 4. Kế hoạch theo phase

| Phase | Gap xử lý | Issue |
|---|---|---|
| 1 | Dockerfile arm64 non-root, CI PR + main, tag SHA, gitleaks, secret cũ (D5), Spring Boot 3.5, sửa property Kafka + consumer group, Makefile, URL gọi giữa service | TD-01…TD-14 |
| 2 | Helm chart chung, probes đúng ngữ nghĩa, ServiceAccount, PSA restricted, HPA/KEDA, PDB, NetworkPolicy, Prometheus tối thiểu | — |
| 3 | GitOps repo riêng, ArgoCD trỏ đúng repo, bỏ `cd.yml`/ARC runner, Liquibase thật + migration Job | TD-15 |
| 4 | Histogram, log JSON + traceId, OTel + Tempo, dashboard, alert | — |
| 5 | Timeout/CB cho Order → Product, Payment → Order; outbox cho payment; idempotency key | — |
| 6 | SAST/SCA/container/IaC scan, ký image, Kyverno, ẩn OpenAPI khỏi gateway | — |
| 7–8 | Terraform, RDS, backup/restore, SLO | — |

### Lưu ý cho roadmap
Phase 5 trong roadmap giả định có lời gọi sync **Order → Payment** và **Order → Inventory**. Repo thật không có hai lời gọi này. Đề xuất khi tới Phase 5: làm circuit breaker trên **Order → Product** và **Payment → Order** (đã có sẵn), và thêm lời gọi **Order → Inventory** (kiểm tồn kho khi đặt hàng) như một thay đổi nghiệp vụ có chủ đích.
