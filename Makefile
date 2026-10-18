# =============================================================================
# Makefile — lệnh tắt cho dev local (TD-10)
#
#   make help                 # liệt kê lệnh
#   make up                   # build jar + image, chạy hạ tầng + 6 service core
#   make up PROFILE=full      # toàn bộ hệ thống (nặng, ~16GB RAM không đủ thoải mái)
#   make logs SVC=order-service
#   make test-svc SVC=payment-service
#
# Chỉ phục vụ máy dev. Build/push image do CI làm (tag = git SHA, không có `latest`);
# deploy K8s đi qua GitOps repo + ArgoCD (Phase 2), không `kubectl apply` từ đây.
# =============================================================================

PROFILE ?= core
COMPOSE := docker compose --profile $(PROFILE)
SVC     ?=
CORE    := auth-service,product-service,order-service,payment-service,inventory-service,notification-service

# Truyền vào build arg của Compose → OCI label org.opencontainers.image.revision
export GIT_COMMIT := $(shell git rev-parse --short=7 HEAD 2>/dev/null || echo unknown)

.DEFAULT_GOAL := help

# -----------------------------------------------------------------------------
# Thiết lập một lần sau khi clone
# -----------------------------------------------------------------------------
.PHONY: setup env hooks

setup: env hooks ## Tạo .env + bật pre-commit hook

env: ## Tạo .env từ .env.example (không ghi đè nếu đã có)
	@if [ -f .env ]; then echo ".env đã có — giữ nguyên"; else cp .env.example .env && echo "Đã tạo .env từ .env.example"; fi

hooks: ## Bật pre-commit hook gitleaks (.githooks/)
	git config core.hooksPath .githooks

# -----------------------------------------------------------------------------
# Docker Compose
# -----------------------------------------------------------------------------
.PHONY: jars images up up-full down ps logs restart keycloak-http clean

jars: ## Build jar cho 6 service core (luôn clean: IDE có thể ghi class hỏng vào target/)
	mvn -B -q clean package -DskipTests -pl $(CORE) -am

images: jars ## Build image core: ghcr.io/dwchwang/<svc>:dev (arm64)
	docker compose --profile core build

up: env images ## Build jar + image rồi chạy profile core (đổi bằng PROFILE=full)
	$(COMPOSE) up -d

up-full: ## Chạy toàn bộ hệ thống (profile full)
	@$(MAKE) up PROFILE=full

down: ## Dừng mọi container (cả core lẫn full)
	docker compose --profile full down

ps: ## Trạng thái container
	$(COMPOSE) ps

logs: ## Xem log, ví dụ: make logs SVC=order-service
	docker compose --profile full logs -f --tail=200 $(SVC)

restart: ## Restart một service: make restart SVC=order-service
	@[ -n "$(SVC)" ] || (echo "Thiếu SVC, ví dụ: make restart SVC=order-service" && exit 1)
	docker compose --profile full restart $(SVC)

keycloak-http: ## Cho phép HTTP từ máy host vào Keycloak (dev; chạy lại sau `make clean`)
	@# Docker Desktop (mạng gvisor) đưa request từ host vào container với IP công khai →
	@# Keycloak (sslRequired=external) đòi HTTPS. Realm ecommerce đã đặt trong file import;
	@# realm master không import nên phải đặt bằng kcadm.
	docker compose exec -T keycloak bash -c '/opt/keycloak/bin/kcadm.sh config credentials --server http://127.0.0.1:8080 --realm master --user "$$KEYCLOAK_ADMIN" --password "$$KEYCLOAK_ADMIN_PASSWORD" && for r in master ecommerce; do /opt/keycloak/bin/kcadm.sh update realms/$$r -s sslRequired=NONE; done'

clean: ## Dừng và XÓA volume (mất dữ liệu Postgres/Kafka/Keycloak)
	@printf "Xóa toàn bộ volume dev? [y/N] " && read ans && [ "$$ans" = "y" ]
	docker compose --profile full down -v --remove-orphans

# -----------------------------------------------------------------------------
# Maven
# -----------------------------------------------------------------------------
.PHONY: test test-svc build

test: ## Chạy toàn bộ test
	mvn -B clean test --fail-at-end

test-svc: ## Test một service (+ module phụ thuộc): make test-svc SVC=order-service
	@[ -n "$(SVC)" ] || (echo "Thiếu SVC, ví dụ: make test-svc SVC=order-service" && exit 1)
	mvn -B clean test -pl $(SVC) -am

build: ## Đóng gói jar, bỏ qua test
	mvn -B clean package -DskipTests

# -----------------------------------------------------------------------------
# Bảo mật
# -----------------------------------------------------------------------------
.PHONY: secrets-scan

secrets-scan: ## Quét secret toàn bộ lịch sử Git (dùng .gitleaks.toml + .gitleaksignore)
	gitleaks git --redact --no-banner .

# -----------------------------------------------------------------------------
.PHONY: help
help: ## Liệt kê lệnh
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-14s %s\n", $$1, $$2}'
