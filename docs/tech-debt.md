# Tech debt

> Danh sách lỗi và nợ kỹ thuật phát hiện được, thay cho GitHub Issues (bỏ từ 2026-10-17).
> Mỗi mục có mã `TD-xx` để nhắc trong commit/PR (`Fixes TD-03`). Sửa xong thì đổi trạng thái, ghi PR, **không xóa dòng** — lịch sử này là bằng chứng cho portfolio.
>
> Trạng thái: `mở` · `đang làm` · `xong (code)` (đã sửa code, chờ image tự build để chạy thật) · `xong` · `giữ cho lab` (cố ý chưa sửa để làm bài trước/sau ở phase sau).
> Người làm: **Claude** = sửa code Java/pom/test · **Bạn** = phần DevOps (Docker, Compose, CI, K8s…).

## Tổng quan

| Mã | Vấn đề | Phase | Người làm | Trạng thái |
|---|---|---|---|---|
| TD-01 | Không tạo được category gốc qua API | 1 | Claude | xong |
| TD-02 | Payment: trùng trả 500, order không tồn tại vẫn tạo được | 1 | Claude | xong |
| TD-03 | `DataLoader` inventory chèn trùng mỗi lần khởi động | 1 | Claude | xong |
| TD-04 | Thiếu property `kafka.bootstrap.servers` (payment, notification) | 1 | Claude | xong |
| TD-05 | Notification đọc nhầm `payment.kafka.consumer-group-id` | 1 | Claude | xong |
| TD-06 | URL gọi giữa service viết cứng và sai | 1 | Claude | xong |
| TD-07 | `/actuator/info` không có commit | 1 | Claude | xong |
| TD-08 | Image upstream: `latest`, amd64, Jib, chạy root | 1 | Bạn | xong |
| TD-09 | Keycloak issuer split-horizon (`localhost` vs `keycloak`) | 1 | Bạn | xong |
| TD-10 | Makefile tham chiếu service không tồn tại | 1 | Bạn | xong |
| TD-11 | Secret dev mặc định commit trong `.env`, `k8s/secrets.yaml` | 1 | Bạn (+ Claude viết postmortem) | xong |
| TD-12 | gitleaks rule mặc định bỏ sót `.env` / `kind: Secret` | 1 | Bạn | đang làm |
| TD-13 | CI gốc `startup_failure` trên fork, tag `latest`, có `kubectl apply` | 1 | Bạn | mở |
| TD-14 | 12/13 service không có test | 1 | Claude | mở |
| TD-15 | Liquibase placeholder, schema dựa vào `ddl-auto=update` | 3 | Claude + Bạn | giữ cho lab |
| TD-16 | Lỗi gọi sync bị nuốt, API vẫn trả 200 | 8 | Claude | giữ cho lab |
| TD-17 | Payment dual-write DB + Kafka, không outbox | 5 | Claude | giữ cho lab |
| TD-18 | Notification mất event khi gửi mail lỗi (commit offset, không retry/DLQ) | 5 | Claude | giữ cho lab |
| TD-19 | Gọi sync không timeout / retry / circuit breaker | 5 | Claude | giữ cho lab |
| TD-20 | 13 route `/v3/api-docs` public qua gateway | 6 | Bạn | mở |
| TD-21 | Email thanh toán gửi tới địa chỉ cứng | 5 | Claude | mở |
| TD-22 | Spring Boot 3.5 đã hết hỗ trợ OSS (30/06/2026) → cân nhắc lên 4.1 | 1–2 | Claude | đã chốt: lên 4.1 sau CI |
| TD-23 | Health tổng hợp gồm dependency ngoài (SMTP) → unhealthy dù service chạy được | 2 | Claude + Bạn | mở (tạm tắt ở Compose) |
| TD-24 | Frontend suy địa chỉ API từ subdomain (`web.x` → `api.x`), không chạy khi truy cập bằng IP | 2 | Bạn | mở (dùng sslip.io) |

---

## Chi tiết

### TD-01 — Không tạo được category gốc qua API
- **Phát hiện:** e2e Phase 0, bước 2 (`POST /api/categories` → 500 `TransientPropertyValueException`).
- **Nguyên nhân:** `product-service/.../helper/CategoryMappingHelper.java` — `map(CategoryDto)` luôn tạo một `Category` cha rỗng (`categoryId = null`) khi DTO không có `parentCategory` → Hibernate từ chối lưu vì tham chiếu entity chưa persist.
- **Tạm thời:** chèn category gốc bằng SQL rồi tạo category con qua API.
- **Hướng sửa:** chỉ gán `parentCategory` khi DTO có `parentCategory.categoryId`; ngược lại để `null`.

### TD-02 — Payment không kiểm tra order, trùng trả 500
- **Phát hiện:** e2e bước 6.
- Payment trùng cho cùng order → chặn đúng nhưng trả **500** (`PaymentNotFoundException` bị map thành lỗi chung) thay vì **409**.
- Payment cho `orderId = 999` (không tồn tại) → **200**, vẫn phát event `SUCCESSFUL` dù `paymentStatus = NOT_STARTED`.
- **Hướng sửa:** exception riêng cho "đã thanh toán" → 409; kiểm tra order tồn tại qua order-service (`/api/orders/existOrderId`, phụ thuộc TD-06) → 404. Idempotency key đầy đủ để Phase 5.
- **Kiểm chứng e2e Phase 1 (2026-10-18):** lần đầu order không tồn tại vẫn ra **500** — order-service ném `OrderNotFoundException`, handler chung của common-lib không biết lớp này → 500, payment không nhận được 404. Unit test của PR #16 mock order-service trả 404 nên không bắt được. Sửa: các chỗ tra theo id ở order/cart/payment/product/category dùng `BusinessException.of(ErrorCode.X_NOT_FOUND)`. Chạy lại: 404 `ORD-4001`. Bài học: unit test mock ranh giới service không thay được e2e.

### TD-03 — `DataLoader` inventory chèn trùng
- **Phát hiện:** e2e bước 8 — bảng `inventory` có 2 bản `Iphone 13`, 2 bản `Macbook Pro 13.3` sau 2 lần khởi động; API trả kết quả trùng.
- **Nguyên nhân:** `inventory-service/.../util/DataLoader.java` (`CommandLineRunner`) `save()` mỗi lần start, không kiểm tra; trùng với `db/scripts/sql/table-inventory-init.sql`.
- **Hướng sửa:** chỉ seed khi bảng rỗng (hoặc bỏ hẳn, dùng một nguồn seed duy nhất). Khi lên nhiều replica ở Phase 2, seed trong code chạy ở mọi pod — càng phải bỏ.

### TD-04 — Thiếu `kafka.bootstrap.servers`
- **Phát hiện:** Phase 0.2 (trước là issue #10). `ReactiveKafkaAppProperties` của payment/notification đọc `${kafka.bootstrap.servers}` không default, yml chỉ có `spring.kafka.bootstrap-servers`, Compose/K8s chỉ truyền `KAFKA_SERVERS` → crash khi start.
- **Tạm thời:** override env `KAFKA_BOOTSTRAP_SERVERS` trong `docker-compose.override.yml`.
- **Đã sửa:** `@Value` đọc `spring.kafka.bootstrap-servers` (một nguồn duy nhất). Phần vá trong override **chỉ xóa được khi Compose chạy image tự build** (Phase 1.2) — image upstream vẫn chứa lỗi.

### TD-05 — Notification đọc nhầm consumer group của payment
- **Phát hiện:** Phase 0.2 (trước là issue #11). `ReactiveKafkaAppProperties`, `EventConsumer` của notification dùng `${payment.kafka.consumer-group-id}`, yml khai báo `notification.kafka.consumer-group-id`.
- **Rủi ro nếu sửa sai:** thêm property `payment.*` → notification và payment chung group, chia nhau partition.
- **Tạm thời:** override env `PAYMENT_KAFKA_CONSUMER_GROUP_ID=notification-groupId`.
- **Đã sửa:** placeholder → `notification.kafka.consumer-group-id`. Override xóa cùng lúc với TD-04.

### TD-06 — URL gọi giữa service sai
- **Phát hiện:** phân tích code + e2e bước 5, 6 (chi tiết `current-architecture.md` §2).
- order → product: thiếu `/product` → 404. order → auth: `/api/manager/user` không tồn tại.
- payment → order / auth: `http://ORDER-SERVICE` — Docker DNS vẫn resolve (không phân biệt hoa thường) nhưng không có port → cổng 80 → connection refused; thiếu context-path.
- **Hướng sửa:** base URL đọc từ property/env (`services.product.url`…), mặc định đúng port + context-path; endpoint user trỏ đúng API của auth-service. Không thêm retry/CB ở đây (TD-19).
- **Đã sửa (nhánh `fix/phase-1-core-bugs`):** env `AUTH_SERVICE_URL`, `PRODUCT_SERVICE_URL`, `ORDER_SERVICE_URL`; user lấy từ `/auth/api/v1/users/{id}` (bóc `ApiResponse`). Khi sửa phát hiện thêm 2 lỗi cùng chỗ, đã sửa luôn:
  - `JwtTokenFilter.getTokenFromRequest()` đã trả `"Bearer …"`, `CallAPI` lại thêm `"Bearer "` → header `Bearer Bearer …` → 401 kể cả khi URL đúng.
  - `CallAPI` gọi `baseUrl()` trên **một** `RestClient.Builder` dùng chung cho mọi đích → request đồng thời có thể bị gán nhầm base URL. Giờ mỗi đích một `RestClient` tạo sẵn.

### TD-07 — `/actuator/info` không có commit
- **Hướng sửa:** `git-commit-id-maven-plugin` + `spring-boot-maven-plugin` goal `build-info`; expose `info.git`. Là điều kiện "từ image truy ngược được commit" của Phase 1.
- **Đã sửa:** PR #17 (jar có `git.properties` + `build-info.properties`). Chờ image tự build (TD-08) để kiểm `/actuator/info` khi chạy thật.

### TD-08 — Image upstream
- **Phát hiện:** Phase 0.2 (trước là issue #12). `ghcr.io/hoangtien2k3/*:latest`, `pull_policy: always`, chỉ `linux/amd64` (giả lập trên M2, startup 72–90s khi chạy cả profile), build bằng Jib, chạy root, 420–467MB.
- **Hướng sửa:** Dockerfile multi-stage arm64, non-root UID 10001, layered jar, tag = git SHA, push GHCR của fork. So sánh với `baseline-metrics.md`.
- **Đã làm (chờ build + đo):** `Dockerfile` dùng chung ở gốc repo (`ARG SERVICE`), jar build ngoài Docker, tách layer, `eclipse-temurin:21-jre-noble`, UID 10001, OCI label `revision`/`source`. Compose: 6 service core build image `ghcr.io/dwchwang/<svc>:dev`, healthcheck actuator cổng 9000. Gỡ Jib và 12 Dockerfile cũ.

### TD-09 — Keycloak issuer split-horizon
- **Phát hiện:** e2e bước 2. Token lấy qua `localhost:8080` có `iss = http://localhost:8080/realms/ecommerce`, service kỳ vọng `http://keycloak:8080/...` → 401 `The iss claim is not valid`.
- **Tạm thời:** `curl --resolve keycloak:8080:127.0.0.1 http://keycloak:8080/...` hoặc thêm `127.0.0.1 keycloak` vào `/etc/hosts`.
- **Hướng sửa:** cố định hostname Keycloak (`KC_HOSTNAME`) để issuer không phụ thuộc client gọi từ đâu. Trên K8s vấn đề lặp lại dưới dạng ingress hostname khác service DNS → ghi quyết định vào ADR ở Phase 2.
- **Đã làm (chờ e2e):** `KC_HOSTNAME=http://localhost:8080` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`; 13 service kiểm `iss` = localhost, tải JWKS qua `keycloak:8080` (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`).
- **Kiểm chứng (2026-10-18):** token lấy qua `localhost:8080` có `iss` = issuer trong discovery; gọi `/api/carts`, `/api/orders` qua gateway → 200 (không token → 401).
- **Vướng thêm khi kiểm:** Docker Desktop (mạng gvisor) đưa request từ host vào container với IP công khai (`172.66.x.x`, thấy trong log `HttpLoggingFilter`) → Keycloak `sslRequired=external` trả 403 `HTTPS required` cho mọi request từ host. Dev: realm `ecommerce` đặt `sslRequired: none` trong file import, realm `master` đặt bằng `make keycloak-http`. K8s/AWS có TLS ở ingress nên không dùng cách này.

### TD-10 — Makefile lệch compose
- **Phát hiện:** Phase 0.2 (trước là issue #13). `make infra-up` gọi `mongodb`, `minio` không còn trong compose.
- **Hướng sửa:** viết lại target theo profile `core`/`full`.
- **Đã sửa:** Makefile mới chỉ gồm Compose (profile `core`/`full`), Maven, setup (`env`, `hooks`), `secrets-scan`. Bỏ các target kind/K8s/ArgoCD/ARC (tham chiếu file và service không còn, `kubectl apply` trái nguyên tắc GitOps) — Phase 2 làm lại qua GitOps repo.

### TD-11 — Secret dev commit vào Git
- `.env`: mọi mật khẩu là giá trị dev mặc định (`admin`), `KEYCLOAK_CLIENT_SECRET` rỗng, mail dùng hộp thư công khai yopmail → **không có credential thật bị lộ**.
- `k8s/secrets.yaml`: 10 khóa `stringData` plaintext.
- `docker/keycloak/import/ecommerce-realm.json`: mật khẩu user test.
- **Hướng sửa (D5):** giữ lịch sử, xóa khỏi HEAD, thêm `.env.example`, `.gitleaksignore` cho finding lịch sử kèm lý do, postmortem `docs/incidents/001-secrets-in-git-history.md` (vấn đề là **quy trình cho phép commit secret**, không phải lộ credential thật).
- **Đã sửa:** gỡ `.env`, `k8s/secrets.yaml` khỏi HEAD, `.env.example`, `.gitleaksignore` 89 fingerprint có lý do, `k3d-setup.sh` tạo secret từ `.env`. Postmortem: `docs/incidents/001-secrets-in-git-history.md`.

### TD-12 — gitleaks bỏ sót
- 42 finding / 294 commit nhưng `.env` chỉ bắt 1 dòng, `k8s/secrets.yaml` 0 (giá trị entropy thấp).
- **Hướng sửa:** `.gitleaks.toml` mở rộng rule mặc định: chặn file `.env*` (trừ `.env.example`), chặn manifest `kind: Secret` có `data`/`stringData`. Kiểm chứng: PR chứa secret giả bị chặn.
- **Đã làm (local):** `.gitleaks.toml` thêm rule `dotenv-secret` và `k8s-plain-secret`, allowlist ciphertext Sealed Secrets; hook `.githooks/pre-commit`. Quét lịch sử: 42 → 89 finding (`.env` 1 → 34, `k8s/secrets.yaml` 0 → 13, thêm placeholder trong `k8s/github-runner/runner-scale-set.yaml`). Còn: gitleaks trong CI (Phase 1 mục D).

### TD-24 — Frontend cần hostname có subdomain
- `frontend/packages/lib/src/api.ts` suy API là `api.<domain gốc>` từ `window.location` (bỏ subdomain đầu). Truy cập bằng IP (`13.250.1.2`) → suy ra `api.250.1.2`, sai. Ưu điểm: một image chạy mọi môi trường, không đóng cứng `NEXT_PUBLIC_API_URL` lúc build.
- **Hướng xử lý (Phase 2, D16):** cluster RKE2 không có domain → dùng `web.<ip-gạch-ngang>.sslip.io` (DNS công cộng tự trỏ về IP), APISIX định tuyến theo host. Không cần sửa code.

### TD-13 — CI gốc
- `startup_failure` trên fork (`_build-service.yml` xin `packages: write`, `ci.yml` không khai báo `permissions`), tag `latest`, `cd.yml` có `kubectl apply`/`argocd app sync`, runner ARC dùng PAT.
- **Hướng sửa:** `pr.yml` + `main.yml` mới theo roadmap Phase 1, xóa workflow cũ.

### TD-14 — Thiếu test
- 34 test, tất cả ở `common-lib` (10) và `auth-service` (24). product, order, payment, inventory, notification: 0.
- **Hướng sửa:** unit test cho 6 service core; Testcontainers (Postgres, Kafka) cho order, payment, inventory; JaCoCo.

### TD-15 — Liquibase placeholder *(giữ cho lab Phase 3)*
- Trước là issue #9. Changelog order/payment/product/inventory/shipping/favourite chỉ có `SELECT 1;`, schema do Hibernate `ddl-auto=update` sinh. Tạm thời override `DDL_AUTO=update`.
- **Phase 3:** sinh changelog thật, `ddl-auto: validate`, migration chạy như Job PreSync; Phase 5 dùng cho expand/contract.

### TD-16 — Lỗi bị nuốt *(giữ cho lab Phase 8)*
- `OrderServiceImpl`, `PaymentServiceImpl` `catch (Exception)` khi gọi sync, chỉ log → API trả 200 dữ liệu thiếu. SLI chỉ dựa trên HTTP status sẽ báo 100% thành công.
- **Phase 8:** dùng làm ví dụ "SLI sai", rồi sửa (trả lỗi rõ ràng hoặc metric riêng cho degraded response).

### TD-17 — Dual-write *(giữ cho lab Phase 5)*
- `PaymentServiceImpl.save` lưu DB rồi publish Kafka, không outbox. Kafka lỗi → event mất.
- Tên topic `SUCCESSFUL` lấy nhầm hằng số trạng thái; event phát cả khi chưa thanh toán.

### TD-18 — Mất event ở notification *(giữ cho lab Phase 5)*
- Gửi mail lỗi `Authentication failed`, offset vẫn commit (lag 0) → không retry, không DLQ.

### TD-19 — Không timeout / retry / CB *(giữ cho lab Phase 5)*
- Lab trên Order → Product, Payment → Order, và lời gọi mới Order → Inventory.

### TD-20 — OpenAPI public qua gateway
- `deploy/apisix/apisix.yaml`: 13 route `/<service>/v3/api-docs` không auth.

### TD-21 — Email cứng người nhận
- `notification-service/.../EventConsumer.java` gửi mọi email thanh toán tới một địa chỉ cố định → lấy email từ user của payment.

### TD-22 — Spring Boot 3.5 hết hỗ trợ OSS
- **Phát hiện:** khi nâng cấp ở Phase 1 (ADR 014). D8 chốt "lên 3.5.x" với giả định 3.5 còn hỗ trợ, nhưng 3.5 đã hết hỗ trợ OSS từ 30/06/2026; 3.5.16 là bản cuối. 4.0 hết 31/12/2026, 4.1 hết 31/07/2027.
- **Hiện tại:** đã lên 3.5.16 (bước 1 của ADR 014) để không chặn Phase 1.
- **Hướng xử lý:** sau khi CI chạy được, lên 4.1 trong PR riêng, dùng CI + test + Trivy làm bằng chứng; viết ADR mới. Trong lúc chờ, CVE của Spring không có bản vá → ngoại lệ có thời hạn trong `.trivyignore`.

### TD-23 — Health tổng hợp phụ thuộc SMTP
- **Phát hiện:** Phase 1 (2026-10-18), lần đầu chạy image tự build có healthcheck. `notification-service` luôn `unhealthy`: `/actuator/health` = DOWN chỉ vì `mail` DOWN (`smtp.gmail.com:587` từ chối đăng nhập — dev không có tài khoản mail thật). DB, Kafka, xử lý event vẫn bình thường.
- **Vì sao quan trọng:** trên K8s nếu readiness dùng health tổng hợp → pod không bao giờ nhận traffic khi nhà cung cấp mail lỗi; nếu liveness dùng → restart liên tục (vi phạm nguyên tắc "liveness không kiểm DB/Kafka", ở đây còn là dịch vụ bên ngoài).
- **Tạm thời:** Compose đặt `MANAGEMENT_HEALTH_MAIL_ENABLED=false` cho notification-service.
- **Hướng sửa (Phase 2):** bật `management.endpoint.health.probes.enabled`; nhóm `liveness` chỉ `livenessState`, nhóm `readiness` gồm `readinessState` + DB (không gồm mail, Kafka xem xét riêng); probe K8s và healthcheck Compose trỏ vào nhóm tương ứng.

