# ADR-012: CI/CD bằng GitHub Actions — đóng gói image và pipeline PR/main

- Trạng thái: Accepted
- Ngày: 2026-10-18
- Liên quan: D6, D7 trong `roadmap.md`; TD-08, TD-12, TD-13, TD-22, TD-24 trong `docs/tech-debt.md`; PR #20, #21

## Context

Repo gốc có `ci.yml`, `cd.yml`, `_build-service.yml` (TD-13):
- Trên fork luôn `startup_failure`: workflow con xin `packages: write`, workflow cha không khai báo `permissions`.
- Image tag `latest`, chỉ amd64, build bằng Jib, chạy root (TD-08).
- `cd.yml` gọi `kubectl apply` / `argocd app sync` từ CI, nghĩa là CI giữ credential cluster.
- Không quét secret, không quét CVE.

Ràng buộc:
- Chỉ dùng GitHub Actions (D6). Image arm64 build native trên `ubuntu-24.04-arm` (D7), runner này miễn phí cho repo public.
- Không có tag `latest`. Tag là git short SHA, phải truy ngược được image về commit.
- CI không có credential cluster (Phase 3 chỉ commit tag vào GitOps repo).

## Decision

### 1. Đóng gói image

- **Jar build ngoài Docker** (Maven trên runner), Dockerfile chỉ đóng gói. Jar đã qua test chính là jar được ship. Jar có sẵn `git.properties`, nên `/actuator/info` có commit mà không cần đưa `.git` vào build context.
- **Một Dockerfile dùng chung** ở gốc repo, chọn service bằng `--build-arg SERVICE`. 13 Dockerfile riêng và plugin Jib đã bị gỡ.
- Spring Boot **layered jar** (`-Djarmode=tools extract --layers`): thư viện (~79MB) nằm ở layer riêng và được cache, code app (~400KB) ở layer cuối.
- Base image `eclipse-temurin:21-jre-noble`. Chạy bằng user `10001:10001`. Có `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`.
- Frontend (`web`, `admin`) dùng Dockerfile sẵn có của repo, sửa lại:
  - Stage runtime chỉ còn node, đã gỡ npm, npx, corepack và pnpm.
  - User dạng số `1001:1001`, để K8s kiểm được `runAsNonRoot`.
  - Không đóng cứng `NEXT_PUBLIC_API_URL`. App tự suy ra địa chỉ API lúc chạy (TD-24), nên một image dùng được cho mọi môi trường.

**Chọn base image (đo 2026-10-18, order-service):**
- Ubuntu noble: 577MB, tầng OS 0 CRITICAL/HIGH, JRE 21.0.12, có shell.
- Alpine: 437MB, tầng OS 1 HIGH, không có bash.
- Distroless: 422MB, tầng OS 1 CRITICAL + 15 HIGH, JRE 21.0.10, không có shell.

Kích thước gần như không đổi theo base, vì JRE và thư viện chiếm phần lớn. Vì vậy chọn bản sạch CVE nhất và dễ vận hành nhất. Muốn nhỏ thật thì cắt JRE bằng `jlink` (cải tiến sau).

### 2. `pr.yml` — mọi PR vào `main`

- `changes`: dùng `dorny/paths-filter` để biết module nào đổi. Đổi `pom.xml` gốc hoặc `common-lib/**` thì build tất cả. PR chỉ sửa docs thì bỏ qua test (job bị skip vẫn được tính là đạt).
- `test`: `mvn package -pl <modules> -am` (compile, unit test, đóng jar), sau đó Trivy `rootfs` quét thư viện trong jar, có CRITICAL thì fail.
- `gitleaks`: cài binary 8.30.1 có kiểm checksum, chỉ quét các commit của PR (`origin/main..HEAD`), dùng `.gitleaks.toml` và `.gitleaksignore` (D5).
- Quyền: `contents: read`; riêng `changes` thêm `pull-requests: read`.

### 3. `main.yml` — mỗi commit lên `main`

- Chỉ build các service core (D1) và frontend có thay đổi. Chạy tay (`workflow_dispatch`) thì build tất cả.
- Chạy trên `ubuntu-24.04-arm`:
  1. `mvn package` (chạy lại test của module).
  2. `docker build` với tag `ghcr.io/dwchwang/<svc>:<sha7>` và label OCI `revision`, `source`.
  3. Trivy chặn CRITICAL.
  4. Push lên GHCR bằng `GITHUB_TOKEN`.
- `packages: write` chỉ có ở job build. Không có bước deploy.

### 4. Quy tắc chung

- **Pin mọi action theo commit SHA**, kèm comment phiên bản. Trivy chạy bằng image pin theo digest. Gitleaks tải binary và kiểm checksum. Không dùng `aquasecurity/trivy-action`: action tham chiếu bằng tag có thể bị đổi nội dung, chuỗi cung ứng công cụ quét cũng là bề mặt tấn công.
- **Ngoại lệ Trivy** đặt trong `.trivyignore.yaml`, mỗi mục có `statement` (lý do) và `expired_at`. Hiện có `spring-webmvc` 6.2.19 (2 CRITICAL, chỉ vá ở Spring 7), hết hạn 2027-01-31, gắn với TD-22.
- **Thư viện có bản vá thì nâng ngay** bằng property override của Spring Boot (PR #21: Tomcat, Jackson, PostgreSQL, httpcore5, lz4-java). Frontend nâng Next.js 16.2.7 lên 16.3.8 (2 CRITICAL).

## Consequences

**Tốt:**
- Từ một image truy ngược được commit theo ba cách: tag, label `org.opencontainers.image.revision`, và `/actuator/info`.
- Không còn `latest`, không còn `kubectl apply` trong CI.
- Thêm thư viện có CVE CRITICAL thì PR bị chặn ngay.
- PR chỉ sửa một service thì chỉ build service đó.
- Startup khi chạy cả 6 service cùng lúc giảm từ 72–90s xuống 20–28s (do bỏ giả lập amd64). Số đo ở `baseline-metrics.md` mục 7.

**Đánh đổi:**
- Image Java lớn hơn upstream khoảng 150MB (Ubuntu so với base cũ).
- Test chạy hai lần: một lần ở PR, một lần ở main (mỗi service khoảng 1 phút). Đổi lại, jar được ship chắc chắn đã qua test trên đúng commit đó.
- Trivy 0.58.1 pin cứng, phải tự cập nhật digest. Phase 6 sẽ thêm Dependabot/Renovate cho việc này.
- Gate hiện chỉ chặn CRITICAL. Chính sách "HIGH có bản vá cũng chặn" làm ở Phase 6.
- Chưa có integration test (Testcontainers), SonarCloud, JaCoCo (TD-14, Phase 6).

## Alternatives đã cân nhắc

- **Build jar trong Docker (multi-stage có Maven):** đúng mẫu "multi-stage" quen thuộc, nhưng jar build lại trong Docker không phải jar đã chạy test, cache Maven trong BuildKit khó dùng lại giữa các runner, và `.git` không có trong build context nên mất commit.
- **Jib (repo gốc dùng):** không cần Docker, nhưng khó tùy biến user, layer, label; nhóm đã chọn học Dockerfile chuẩn.
- **Distroless:** nhỏ nhất, nhưng tầng OS đang có nhiều CVE hơn (Debian 12) và không debug được.
- **Build multi-arch (amd64 + arm64):** không cần. Máy dev, EC2 Graviton (RKE2) và EKS Graviton đều là arm64 (D7).
