# syntax=docker/dockerfile:1
# =============================================================================
# Dockerfile dùng chung cho mọi service Spring Boot (TD-08, ADR 012)
#
# Jar được build NGOÀI Docker (Maven trên máy dev / runner CI) — Dockerfile chỉ đóng gói.
# Lý do: jar đã chạy test chính là jar được ship; jar có sẵn git.properties nên
# /actuator/info có commit (.git không vào build context).
#
#   mvn -B package -DskipTests -pl order-service -am
#   docker build --build-arg SERVICE=order-service --build-arg GIT_COMMIT=$(git rev-parse --short HEAD) -t order-service:dev .
#
# Context là thư mục gốc repo; .dockerignore chỉ cho */target/*.jar đi vào.
# =============================================================================

ARG JRE_IMAGE=eclipse-temurin:21-jre-noble

# --- Stage 1: tách fat jar thành các layer --------------------------------------
# Thư viện bên ngoài (~76MB) ít đổi → layer riêng, được cache giữa các lần build;
# code app + common-lib (~400KB) đổi thường xuyên → layer cuối.
FROM ${JRE_IMAGE} AS extract
ARG SERVICE
RUN test -n "${SERVICE}" || (echo "Thiếu --build-arg SERVICE=<tên-service>" >&2 && exit 1)
WORKDIR /build
COPY ${SERVICE}/target/${SERVICE}-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Stage 2: runtime ------------------------------------------------------------
FROM ${JRE_IMAGE}

# User non-root cố định UID/GID 10001 để khớp securityContext (runAsUser) ở K8s.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
# File do root sở hữu, user app chỉ đọc → app không tự sửa được code của mình.
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./

USER 10001:10001

# Heap theo giới hạn RAM của container; OOM thì thoát hẳn để orchestrator restart,
# không để JVM chạy tiếp ở trạng thái hỏng.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

# Metadata để truy ngược image → commit. Đặt cuối để đổi commit không làm mất cache các layer trên.
ARG SERVICE
ARG GIT_COMMIT=unknown
ARG SOURCE_URL=https://github.com/dwchwang/ecommerce-platform
LABEL org.opencontainers.image.title="${SERVICE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.licenses="MIT"
