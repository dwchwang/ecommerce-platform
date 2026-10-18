# Baseline Metrics

> Phase 0 — 0.5. Số đo "trước" để so sánh ở Phase 1 (image, build) và Phase 9 (bảng trước/sau).
> Đo ngày 2026-10-12 và 2026-10-14. Ô **⏳ bạn đo** là số liệu chưa có (chỉ còn RAM profile `full`, tùy chọn).

## Điều cần nắm

1. Image service **420–467MB**, chỉ amd64, chạy root → mục tiêu Phase 1: nhỏ hơn rõ rệt, arm64, non-root.
2. Startup **~46s** cho phần lớn service — bị đội lên do chạy **giả lập amd64 trên M2**; so sánh công bằng phải đo lại sau khi có image arm64.
3. Profile `core` (11 container) dùng **~4.1GB RAM** → vừa với máy 16GB, còn chỗ cho k3d ở Phase 2.
4. Build Maven trên M2 rất nhanh (**5–6s/service, 13s toàn repo** khi cache dependency đã có) → thời gian CI sẽ bị chi phối bởi tải dependency và build image, không phải compile.
5. **Test gần như không có:** 34 test, tất cả pass, nhưng chỉ nằm ở `auth-service` và `common-lib` — **12/13 service có 0 test**. Pipeline "xanh" hiện tại không chứng minh được gì cho core.
6. **gitleaks: 42 finding** toàn lịch sử, nhưng rule mặc định **bỏ sót** `.env` và `k8s/secrets.yaml` — số finding thấp không có nghĩa là sạch.

## 1. Điều kiện đo

| Mục | Giá trị |
|---|---|
| Ngày đo | 2026-10-12 |
| Commit | tag `baseline-original` (+ `docker-compose.override.yml`, PR #14) |
| Máy | MacBook Apple M2, 16GB RAM |
| Docker | Docker Desktop 29.3.1, cấp 10.4GB RAM, 8 CPU |
| Java / Maven | JDK 21.0.8, Maven 3.10.0 |
| Image | `ghcr.io/hoangtien2k3/*:latest`, **linux/amd64 chạy giả lập** |

## 2. Image

| Service | Kích thước | Digest |
|---|---|---|
| auth-service | 429MB | `sha256:05e10168ab462bf57e3915e3ab90e8a574c1cf79160a118df9a9bb8e0e5ceff6` |
| product-service | 429MB | `sha256:66a4e7d3922f4667649cfba4c55140067b14ff69086fbb0e83900b966212bc7b` |
| inventory-service | 420MB | `sha256:f92d7108fca91fa2d5b98c5bd05a822e43e615b6f8f15c532e961ffb729d4419` |
| payment-service | 467MB | `sha256:dd7b5a794ec3ed41ef3595275f4f8e2575d0351a2d139b8aeb17ff80127ec65e` |
| order-service | 429MB | `sha256:81db22866bac6748e70ac421b44de73eb41c138f519d9cf0a3689d745999211e` |
| notification-service | 452MB | `sha256:f46ce4be5f35f44f86bc736bc72e694592fc8f1e2882083420abeaccd2bb3e27` |

Digest ghi lại để biết chính xác baseline chạy image nào (tag `latest` không đủ).

## 3. Runtime (profile `core`)

### Thời gian khởi động
Lấy từ dòng `Started ... in X seconds` trong log.

| Service | Startup |
|---|---|
| auth-service | 46.8s |
| product-service | 47.0s |
| inventory-service | 45.9s |
| order-service | 45.6s |
| payment-service | 20.8s |
| notification-service | 18.8s |

payment/notification khởi động lại riêng sau khi sửa override (máy ít tải hơn) nên nhanh hơn.

**Khởi động cả profile cùng lúc** (`up -d` từ trạng thái dừng, 2026-10-14) — đây là số dùng để so sánh ở Phase 1:

| Service | Startup (cùng lúc) |
|---|---|
| auth-service | 72.2s |
| product-service | 77.6s |
| inventory-service | 75.7s |
| order-service | 87.2s |
| payment-service | 89.7s |
| notification-service | 87.3s |

6 JVM giả lập amd64 tranh CPU cùng lúc → chậm gần gấp đôi so với khởi động lẻ.

### RAM

| Container | RAM |
|---|---|
| keycloak | 513MB |
| notification-service | 492MB |
| payment-service | 491MB |
| inventory-service | 468MB |
| auth-service | 467MB |
| order-service | 461MB |
| product-service | 459MB |
| kafka | 418MB |
| apisix | 228MB |
| postgres | 156MB |
| redis | 9MB |
| **Tổng profile core** | **~4.1GB** |
| Profile `full` | ⏳ bạn đo |

## 4. Build & test

| Hạng mục | Kết quả |
|---|---|
| Build 1 service (`clean package -pl <svc> -am`, cache dependency ấm) | auth 6s · product 5s · inventory 5s · payment 6s · order 5s · notification 6s |
| Build toàn repo (`clean package`, cache ấm) | 13s |
| Unit test toàn repo (`mvn -B test --fail-at-end`) | **~40s** (lần đầu 40s, chạy lại 38s), **34 test, 0 fail** |
| Phân bố test | `common-lib` core 7 · `common-lib` spring 3 · `auth-service` 24 · **12 service còn lại: 0** |
| Thời gian CI trên GitHub | Không đo được — workflow gốc `startup_failure` trên fork (xem `production-readiness.md` §2.2) |

## 5. Còn thiếu — lệnh để bạn tự đo

Chạy trong thư mục `ecommerce-platform`, điền kết quả vào các ô ⏳ ở trên.

**Unit test:**
```bash
time mvn -B test --fail-at-end | tail -40
```

**Secret trong lịch sử Git** (cài: `brew install gitleaks`; báo cáo lưu **ngoài** repo):
```bash
gitleaks git --report-path ../notes/gitleaks-baseline.json
```
```bash
jq length ../notes/gitleaks-baseline.json
```

| Hạng mục | Kết quả |
|---|---|
| Số finding gitleaks (toàn lịch sử) | **42** trên 294 commit, 100% rule `generic-api-key` |
| Số finding ở file đang track tại HEAD | **12** (`test-realm.json` của tax-service, search-service — fixture test) |

Kết quả đo (gitleaks 8.30.1, rule mặc định), báo cáo ở `../notes/gitleaks-baseline.json`:

| File | Finding |
|---|---|
| `test-realm.json` trong `src/test`, `src/it` của tax, search, rating, promotion, common-lib | 38 |
| `application.properties` của user-service (đã xóa), payment-service, order-service | 3 |
| `.env` | 1 |
| `k8s/secrets.yaml` | **0 — bị bỏ sót** |

`.env` có hơn 10 biến mật khẩu/secret và `k8s/secrets.yaml` có 10 khóa `stringData` plaintext, nhưng rule mặc định chỉ bắt 1 dòng: giá trị entropy thấp (kiểu mật khẩu dev) không bị coi là secret. Hệ quả cho Phase 1: cần `.gitleaks.toml` với rule riêng, và bước rotate (D5) phải dựa vào danh sách khóa trong 2 file này, không dựa vào báo cáo gitleaks.

> **Bạn tự kiểm tra:** `MAIL_USERNAME`/`MAIL_PASSWORD` và `KEYCLOAK_CLIENT_SECRET` trong `.env` có phải giá trị thật của tác giả gốc không (Claude không đọc giá trị secret). Nếu là thật → ghi vào postmortem 001 là credential của bên thứ ba bị lộ; không dùng lại.

**RAM profile full** (tùy chọn, máy có thể chậm):
```bash
docker compose --profile full up -d
```
```bash
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}'
```

## 6. Điểm production readiness

8 / 27 — chi tiết ở [`production-readiness.md`](production-readiness.md).

## 7. Sau Phase 1 (đo 2026-10-18)

Image tự build từ `main` bằng Dockerfile chung (PR #20) + thư viện đã vá CVE (PR #21). Cùng máy, cùng Docker Desktop như mục 1.

**Image** — arm64 native, user `10001`, label `org.opencontainers.image.revision` = commit:

| Service | Trước (amd64, root) | Sau (arm64, non-root) |
|---|---|---|
| auth-service | 429MB | 578MB |
| product-service | 429MB | 578MB |
| order-service | 429MB | 578MB |
| payment-service | 467MB | 619MB |
| inventory-service | 420MB | 569MB |
| notification-service | 452MB | 602MB |

Image **lớn hơn** khoảng 150MB: base Ubuntu noble (JRE ~165MB + thư viện ~79MB chiếm phần lớn). Đã thử 3 base cho order-service:
- Ubuntu noble (chọn): 577MB, nén 161MB. Tầng OS **0 CRITICAL / 0 HIGH**. JRE 21.0.12. Có bash/wget để debug.
- Alpine: 437MB, nén 137MB. Tầng OS 1 HIGH (libexpat). Không có bash.
- Distroless: 422MB, nén 126MB. Tầng OS **1 CRITICAL / 15 HIGH**. JRE cũ hơn (21.0.10). Không có shell.

Kết luận: đổi base không làm image nhỏ đi rõ rệt; ưu tiên tầng OS sạch CVE. Muốn nhỏ thật thì cắt JRE bằng `jlink` (để làm sau, có số trước/sau). Tiêu chí Phase 1 đổi từ "nhỏ hơn" thành "0 CRITICAL/HIGH ở tầng OS + arm64 + non-root".

**CVE (Trivy 0.58.1, CRITICAL + HIGH):**
- Trước khi vá: 5 CRITICAL + 8 HIGH ở thư viện Java (Tomcat, spring-webmvc, Jackson, httpcore5, PostgreSQL driver), thêm 1 HIGH lz4-java ở payment, notification.
- Sau PR #21: chỉ còn 2 CRITICAL của `spring-webmvc` 6.2.19, bản vá chỉ có ở Spring 7 → TD-22.

**Thời gian khởi động** (dòng `Started ... in X seconds`):

| Trường hợp | Trước | Sau |
|---|---|---|
| Cả profile cùng lúc (6 JVM tranh CPU) | 72–90s | 20–28s |
| Khởi động lẻ một service | 19–47s | 5–7s |

Phần lớn khác biệt đến từ việc bỏ giả lập amd64.

**RAM** mỗi service core: 460–490MB → 390–440MB. Compose chưa đặt giới hạn RAM nên `MaxRAMPercentage=75` đang tính trên 9.7GB của Docker Desktop; số thật sẽ đo lại khi có `resources.limits` ở Phase 2.

**Test:** 34 → **48 test**, 0 fail; `mvn -B clean test` toàn repo ~75s. Đã có test ở order, payment, product, inventory (trước: 0). Notification vẫn chưa có test (TD-14).

**e2e** (`plan/phase-1-e2e-test.md`, commit `fd637a1`): 9/9 bước đạt.
- Lần chạy đầu phát hiện thêm một lỗi: payment cho order không tồn tại vẫn trả 500. Nguyên nhân là order-service ném exception riêng mà handler chung không nhận ra. Đã sửa cùng PR với số đo này.
- Category gốc tạo được. Đọc lại order có thông tin product.
- Payment: lần đầu 200, gửi lại 409, order không tồn tại 404.
- notification nhận event qua Kafka.
- Inventory không tăng bản ghi sau restart.
- `/actuator/info` = label image = `git rev-parse --short HEAD`.

