# Postmortem 001: Secret nằm trong lịch sử Git

- **Mức độ:** SEV4 (không có credential thật bị lộ, không ảnh hưởng người dùng)
- **Phát hiện:** 2026-10-14, Phase 0 (đánh giá baseline) · **Khắc phục:** 2026-10-17, Phase 1 mục B
- **Liên quan:** TD-11, TD-12 (`docs/tech-debt.md`), quyết định D5 (`roadmap.md`)
- **Kiểu postmortem:** blameless. Repo kế thừa từ upstream; mục tiêu là sửa **quy trình**, không truy lỗi người.

## Tóm tắt

Repo fork từ upstream đã commit secret vào Git ở 3 dạng: file `.env` (12 commit từ 2025-01-10), manifest `k8s/secrets.yaml` (8 commit từ 2026-06-06), và `jwt.secret` trong `application.properties` (2024). Tất cả giá trị trong `.env`/`k8s/secrets.yaml` là mật khẩu dev mặc định nên **không có credential thật bị lộ**. Vấn đề thật là **không có lớp nào chặn việc commit secret**, và công cụ quét với cấu hình mặc định còn **báo sạch giả**: gitleaks chỉ bắt 1 dòng trong `.env` và 0 dòng trong `k8s/secrets.yaml`.

## Tác động

- Người dùng: không có (môi trường dev, không có hệ thống production).
- Credential: không có giá trị thật.
  - `.env`: mật khẩu Postgres/Redis/Keycloak/RustFS là giá trị dev mặc định; `KEYCLOAK_CLIENT_SECRET` rỗng; `MAIL_USERNAME` là hộp thư công khai yopmail.
  - `k8s/secrets.yaml`: 10 khóa `stringData` cùng giá trị dev.
  - `jwt.secret` (3 finding, commit 2024): khóa HMAC thời trước Keycloak, file đã xóa từ 2026-02-09, không dùng ở đâu trong hệ thống hiện tại. Không phải của mình nên không rotate được; coi như đã lộ và không bao giờ dùng lại.
- Rủi ro nếu không sửa: thói quen "commit `.env` cho tiện" sẽ đưa secret thật (AWS, GHCR token, mật khẩu RDS) vào Git ở Phase 4–8.

## Timeline (UTC+7)

- **2024-01-02** — upstream commit `jwt.secret` vào `application.properties` (order/user/payment).
- **2025-01-10** — upstream thêm `.env` vào Git (`5d765f37`).
- **2026-06-06** — upstream thêm `k8s/secrets.yaml` (`7d4b971c`) và `k8s/github-runner/runner-scale-set.yaml` có `github_token` placeholder.
- **2026-10-14** — Phase 0: chạy gitleaks rule mặc định trên toàn lịch sử → 42 finding, nhưng đọc tay thấy `.env` có hơn 10 biến mật khẩu mà chỉ 1 dòng bị bắt, `k8s/secrets.yaml` bị bỏ sót hoàn toàn. Ghi vào `baseline-metrics.md`, TD-11, TD-12.
- **2026-10-17** — chủ repo xác nhận mọi giá trị trong `.env` là mặc định dev → không cần rotate khẩn cấp.
- **2026-10-17** — thêm `.gitleaks.toml` (2 rule riêng) + pre-commit hook (PR #18). Quét lại: 42 → 89 finding.
- **2026-10-17** — gỡ `.env`, `k8s/secrets.yaml` khỏi HEAD, thêm `.env.example`, `.gitleaksignore` 89 fingerprint có lý do. Quét lịch sử: 0 finding chưa xét.

## Root cause (5 Whys)

1. **Vì sao secret vào Git?** `.env` và `k8s/secrets.yaml` được commit như file cấu hình bình thường.
2. **Vì sao commit được?** `.gitignore` không có `.env`; không có pre-commit hook; CI không quét secret.
3. **Vì sao không ai nhận ra?** Giá trị là mật khẩu dev, trông "vô hại", nên commit cho người clone chạy được ngay — không có cơ chế thay thế (`.env.example`).
4. **Vì sao công cụ quét cũng không bắt?** Rule mặc định của gitleaks dựa vào entropy và mẫu định dạng của provider (AWS, GitHub...). Mật khẩu ngắn kiểu `admin` có entropy thấp, `stringData` của K8s không khớp mẫu nào.
5. **Vì sao đó là vấn đề quy trình?** Không có định nghĩa "secret là gì trong repo này" → không có rule theo **ngữ cảnh** (file `.env`, `kind: Secret`) thay vì theo **giá trị**.

**Root cause:** thiếu guardrail ở cả 3 tầng (gitignore, pre-commit, CI) và công cụ quét chỉ dùng rule chung, không có rule theo ngữ cảnh của repo.

## Đã khắc phục

- `.gitleaks.toml`: giữ rule mặc định, thêm
  - `dotenv-secret` — biến có tên PASSWORD/SECRET/TOKEN/KEY... có giá trị trong `.env*` (trừ `.env.example`);
  - `k8s-plain-secret` — `kind: Secret` có `data`/`stringData` (bỏ qua template Helm);
  - allowlist ciphertext Sealed Secrets để Phase 2 không bị chặn nhầm.
- `.githooks/pre-commit` chạy `gitleaks git --staged`; bật bằng `make hooks`.
- Gỡ khỏi HEAD theo D5, **không viết lại lịch sử**: `.env` (`git rm --cached`, thêm vào `.gitignore`), `k8s/secrets.yaml` (xóa; `k3d-setup.sh` tạo secret từ `.env` local).
- `.env.example` đủ 55 biến, secret thay bằng `changeme-dev`; `make env` tạo `.env` cho người mới clone.
- `.gitleaksignore`: 89 fingerprint chia 5 nhóm, mỗi nhóm có lý do (lịch sử `.env`, lịch sử `k8s/secrets.yaml`, `jwt.secret` upstream, fixture `test-realm.json`, placeholder runner ARC). Ignore theo fingerprint `commit:file:rule:line` chứ không theo file → secret mới trong cùng file vẫn bị chặn.

Kiểm chứng:
- Quét lịch sử (`make secrets-scan`): 0 finding chưa xét.
- Commit thử qua hook: chặn `.env` có mật khẩu, `kind: Secret`, AWS key trong file Java; cho qua `.env` rỗng, `.env.example`, template Helm, SealedSecret.

## Vì sao không viết lại lịch sử (D5)

`git filter-repo` xóa được secret khỏi lịch sử nhưng: đổi SHA của toàn bộ commit (phá liên kết PR/fork), không thu hồi được bản đã bị clone/cache, và tạo cảm giác an toàn giả. Với secret thật, cách đúng là **rotate**; xóa lịch sử chỉ là bước phụ. Ở đây không có secret thật nên giữ lịch sử và ghi nhận bằng `.gitleaksignore`.

## Điều đã làm tốt / chưa tốt / may mắn

- **Tốt:** không tin con số "42 finding" của rule mặc định — đọc tay hai file đáng ngờ nên phát hiện bỏ sót.
- **Chưa tốt:** Phase 0 dừng ở mức ghi nhận; nếu đã có secret thật thì 3 ngày từ phát hiện đến khắc phục là quá lâu.
- **May mắn:** mọi giá trị là mặc định dev. Nếu tác giả gốc đã commit mật khẩu mail thật, đó là credential của bên thứ ba và chỉ chủ của nó mới rotate được.

## Còn tồn đọng

- `docker/keycloak/import/ecommerce-realm.json` chứa mật khẩu của user test (`adminuser`, `testuser`) dùng cho e2e. Không bị gitleaks bắt (mật khẩu trong realm import, entropy thấp). Chấp nhận cho dev; Phase 4+ không import realm này vào môi trường có dữ liệu thật.
- `frontend/apps/*/.env` vẫn được track, chỉ chứa `NEXT_PUBLIC_API_URL`, `PORT` (công khai theo thiết kế của Next.js) → giữ.

## Action items

- [x] `.gitleaks.toml` + pre-commit hook — TD-12 (PR #18)
- [x] Gỡ `.env`, `k8s/secrets.yaml` khỏi HEAD; `.env.example`; `.gitleaksignore` — TD-11
- [ ] gitleaks trong `pr.yml`, chạy `gitleaks git` (cả lịch sử của PR), bắt buộc pass — Phase 1 mục D
- [ ] Sealed Secrets cho K8s local/EC2 — Phase 2
- [ ] External Secrets Operator + Secrets Manager (IRSA) trên AWS; CI vào AWS bằng OIDC, không có access key dài hạn — Phase 7
