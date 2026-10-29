# syntax=docker/dockerfile:1
# ============================================================
# 星轨音乐服务端 (xinggui) 多阶段构建
#   构建上下文 = 仓库根（同时含 xinggui-server/ 与 xinggui-admin/）
#   本地: docker build -f xinggui-server/Dockerfile -t xinggui-server:local .
# 管理页由 Go 直接托管：xinggui-admin/ -> /app/admin，配合 XG_ADMIN_DIR。
# ============================================================

# ---------- 构建阶段 ----------
# go.mod 要求 go 1.26.0，故使用 golang 1.26 系列镜像
FROM golang:1.26-alpine AS build

WORKDIR /src

# 先只拷依赖清单，命中 Docker 层缓存
COPY xinggui-server/go.mod xinggui-server/go.sum ./
RUN go mod download

# 再拷服务端源码（仓库根 .dockerignore 保证不含数据/密钥/二进制/Android）
COPY xinggui-server/ ./

ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64

RUN go build -trimpath -ldflags="-s -w" -o /out/xinggui ./cmd/server \
 && go build -trimpath -ldflags="-s -w" -o /out/dbadmin ./tools/dbadmin \
 && go build -trimpath -ldflags="-s -w" -o /out/totpgen ./tools/totpgen

# ---------- 运行阶段 ----------
FROM alpine:3.20

# ca-certificates: 出站 HTTPS 解析音乐平台所需
# tzdata: 容器内 TZ=Asia/Shanghai 生效所需
# curl: HEALTHCHECK 探测（-k 跳过校验，因为容器内按 127.0.0.1 访问，证书 CN 是域名）
RUN apk add --no-cache ca-certificates tzdata curl wget \
 && addgroup -g 10001 -S xinggui \
 && adduser -u 10001 -S -G xinggui -h /app -s /sbin/nologin xinggui

WORKDIR /app

COPY --from=build /out/xinggui /app/xinggui
COPY --from=build /out/dbadmin /app/dbadmin
COPY --from=build /out/totpgen /app/totpgen

# 管理页静态文件（来自仓库根的 xinggui-admin/；仓库根 .dockerignore 已排除调试产物与凭据）
COPY xinggui-admin/ /app/admin/

# 数据目录：SQLite 库 + jwt.secret 都落在这里，必须持久化
RUN mkdir -p /app/data /app/certs \
 && chown -R 10001:10001 /app

USER 10001:10001

ENV XG_PORT=8443 \
    XG_DATA_DIR=/app/data \
    XG_ADMIN_DIR=/app/admin \
    TZ=Asia/Shanghai

EXPOSE 8443
VOLUME ["/app/data"]

# 服务端本身默认明文 HTTP；若 main.go 依据 XG_TLS_CERT/XG_TLS_KEY 启用了 TLS，
# 这里先试 https 再退回 http，两种模式都能通过。
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS -k -o /dev/null "https://127.0.0.1:${XG_PORT:-8443}/v1/health" || curl -fsS -o /dev/null "http://127.0.0.1:${XG_PORT:-8443}/v1/health"

ENTRYPOINT ["/app/xinggui"]
