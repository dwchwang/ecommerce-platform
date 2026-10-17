# ADR-014: Nâng Spring Boot 3.3.5 → 3.5.16

- Trạng thái: Accepted (bước 1) — bước 2 (lên 4.x) chờ quyết định, xem TD-22
- Ngày: 2026-10-17
- Liên quan: D8 trong `roadmap.md`, TD-07, TD-22 trong `docs/tech-debt.md`

## Context

Repo gốc dùng Spring Boot **3.3.5**. Lịch hỗ trợ OSS (nguồn: endoflife.date, kiểm tra 2026-10-17):

| Dòng | Phát hành | Hết hỗ trợ OSS | Bản mới nhất |
|---|---|---|---|
| 3.3 | 05/2024 | **30/06/2025** | 3.3.13 |
| 3.5 | 05/2025 | **30/06/2026** | 3.5.16 (bản cuối) |
| 4.0 | 11/2025 | 31/12/2026 | 4.0.8 |
| 4.1 | 06/2026 | 31/07/2027 | 4.1.1 |

Khi chốt D8 ("nâng lên 3.5.x, chưa lên 4.0"), giả định là 3.5 còn được hỗ trợ. **Giả định này đã sai tại thời điểm thực hiện:** 3.5 cũng đã hết hỗ trợ OSS từ 3 tháng trước. Dự án chạy đến khoảng 03/2027.

Ràng buộc:
- 13 service + `common-lib`, test rất ít (TD-14) → nâng cấp lớn khó chứng minh là an toàn.
- Chưa có CI hoạt động (TD-13) và chưa có image tự build (TD-08) → chưa chạy thật được bản nâng cấp trên môi trường nào.
- Phase 1 cần một nền ổn định để viết Dockerfile và CI.

## Decision

Làm theo **hai bước**:

1. **Bây giờ:** nâng lên **3.5.16** — bản cuối của dòng 3.x, cùng major với 3.3 nên thay đổi nhỏ, làm được ngay.
2. **Sau khi CI chạy được** (cuối Phase 1 hoặc đầu Phase 2): đánh giá lên **4.1** (hỗ trợ OSS đến 07/2027, phủ hết thời gian dự án), làm trong PR riêng, có CI + test + Trivy làm bằng chứng. Theo dõi ở TD-22.

## Thay đổi thực tế ở bước 1

| Thành phần | Trước | Sau | Lý do |
|---|---|---|---|
| `spring-boot-starter-parent` | 3.3.5 | 3.5.16 | Bản cuối dòng 3.x |
| `springdoc-openapi` | 2.6.0 | 2.8.17 | springdoc 2.6 không tương thích Spring Framework 6.2 (lỗi khi khởi động). Dòng 2.x dành cho Boot 3; 3.x dành cho Boot 4 |
| elasticsearch-java (Boot quản lý) | 8.13 | 8.18 | Đi theo Boot |

Breaking change gặp phải (chỉ ở `search-service`, do elasticsearch-java đổi API):
- `RangeQuery.Builder.field()/from()/to()` không còn → phải chọn kiểu: `range(r -> r.number(n -> n.field(..).gte(..).lte(..)))`. `from`/`to` cũ mặc định bao gồm hai đầu nên đổi thành `gte`/`lte`.
- `FetchSourceFilter(String[], String[])` → `FetchSourceFilter(Boolean fetchSource, String[], String[])`.

Không cần sửa gì ở 5 service core.

Kèm theo (TD-07): `git-commit-id-maven-plugin` + goal `build-info` → `/actuator/info` trả về commit. Cấu hình `failOnNoGitDirectory=false` để build trong Docker không có `.git` vẫn chạy được (khi đó chỉ thiếu phần `git`).

## Alternatives

1. **Giữ 3.3.5** — loại: hết hỗ trợ hơn một năm, Trivy sẽ báo CVE không có bản vá.
2. **Lên thẳng 4.1 ngay** — hoãn: major version (Jakarta EE 11, Jackson 3, Spring Security 7, Hibernate 7, springdoc 3, bỏ nhiều API deprecated), động vào cả 13 service và `common-lib`. Không có CI và gần như không có test để chứng minh không hỏng. Làm bây giờ sẽ chặn toàn bộ Phase 1.
3. **Lên 4.0** — loại: hết hỗ trợ OSS 31/12/2026, chỉ hơn 2 tháng nữa; tốn công gần bằng 4.1 mà được ít hơn.

## Trade-offs

| | 3.5.16 (chọn) | 4.1 ngay |
|---|---|---|
| Công sức | Nhỏ (2 file code) | Lớn (13 service + common-lib) |
| Rủi ro hỏng | Thấp, cùng major | Cao, chưa có CI/test bảo vệ |
| Còn được vá bảo mật | **Không** (OSS đã hết) | Có, đến 07/2027 |
| Chặn Phase 1 | Không | Có |

## Consequences

- **Tích cực:** từ 3.3 (hết hỗ trợ 06/2025) lên bản vá cuối của 3.x; Phase 1 không bị chặn; có `/actuator/info` chứa commit.
- **Tiêu cực:** vẫn chạy framework hết hỗ trợ OSS. Trivy ở Phase 1/6 có thể báo CVE của Spring mà không có bản vá 3.5 → ghi ngoại lệ có thời hạn trong `.trivyignore`, trỏ về ADR này và TD-22.
- **Chưa kiểm chứng:** service chưa được khởi động thật trên 3.5.16 — test hiện tại không dựng Spring context. Kiểm chứng ở bước Dockerfile (Phase 1.2): cả 6 service core lên được, `/actuator/info` có `git.commit.id`.
- **Việc cần làm tiếp:** TD-22 — lên 4.1 sau khi có CI; khi làm thì viết ADR mới thay thế bước 2 của ADR này.
