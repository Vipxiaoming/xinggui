# 星轨音乐服务端部署手册（Docker）

镜像：`ghcr.io/vipxiaoming/xinggui-server:latest`（GitHub Actions 构建，linux/amd64）
源码目录：本仓库 `xinggui-server/`
部署目录（服务器上的一个普通文件夹，下文记作 `~/xinggui`）：

```
~/xinggui/
├── docker-compose.yml      # 来自本仓库 xinggui-server/docker-compose.yml
├── certs/
│   ├── fullchain.pem       # 证书链（叶子 + 中间），权限 600
│   └── privkey.pem         # 私钥，权限 600
└── data/                   # 首次启动自动生成；SQLite 库 + jwt.secret
```

---

## 0. 前置条件

- 服务器已装 Docker Engine + Docker Compose v2（`docker compose version` 有输出）
- 架构为 amd64（`uname -m` 返回 `x86_64`）。镜像只发布 `linux/amd64`。
- 公网入口已把外部端口映射到本机 `8443`（NAT/防火墙侧配置，不在本文范围）
- 出站可访问 `ghcr.io`（拉镜像）与各音乐平台（解析功能需要）

未在本文覆盖：DNS、NAT 端口映射、防火墙规则。

---

## 1. 准备证书

从证书签发方拿到的是合并 PEM（例如 `one.pem`，3 段 CERTIFICATE + 1 段 PKCS#8 PRIVATE KEY）。
需要拆成两个文件。两种方式任选其一。

### 1a. 用 awk（推荐，Linux 服务器上直接可用）

```bash
mkdir -p ~/xinggui/certs && cd ~/xinggui/certs

# 证书链：所有 CERTIFICATE 块
awk '/-----BEGIN CERTIFICATE-----/{f=1} f{print} /-----END CERTIFICATE-----/{f=0}' ~/one.pem > fullchain.pem

# 私钥：所有 PRIVATE KEY 块
awk '/-----BEGIN PRIVATE KEY-----/{f=1} f{print} /-----END PRIVATE KEY-----/{f=0}' ~/one.pem > privkey.pem

chmod 600 fullchain.pem privkey.pem
```

### 1b. 用 openssl（如果签发方只给了 key/crt 分离文件）

```bash
cat cert.crt intermediate.crt > fullchain.pem   # 顺序：叶子在前，中间在后
cp cert.key privkey.pem                          # 若是 PKCS#1，先转换：
# openssl pkcs8 -topk8 -nocrypt -in cert.key -out privkey.pem
chmod 600 fullchain.pem privkey.pem
```

### 校验（必做）

```bash
# 证书链段数（正常 2~3）
grep -c 'BEGIN CERTIFICATE' fullchain.pem

# 私钥段数（必须 1）
grep -c 'BEGIN PRIVATE KEY' privkey.pem

# 私钥与证书是否配对：两行输出的公钥哈希必须完全一致
openssl x509 -in fullchain.pem -noout -pubkey | openssl sha256
openssl pkey -in privkey.pem -pubout | openssl sha256

# 有效期
openssl x509 -in fullchain.pem -noout -dates -subject
```

> 本机（Windows）上已完成拆分，产物为 `xinggui-server/certs/fullchain.pem` 与 `xinggui-server/certs/privkey.pem`，
> 仅本地存在、已被 `.gitignore`（`**/certs/`）排除。部署时请通过 `scp` 或密码管理器传输，不要走 git。

---

## 2. 准备 compose 文件与数据目录

```bash
mkdir -p ~/xinggui/data
cd ~/xinggui
# 把仓库里的 xinggui-server/docker-compose.yml 放到这里
```

`docker-compose.yml` 关键点：

| 项 | 值 | 说明 |
| --- | --- | --- |
| `ports` | `8443:8443` | 外部 20001 由 NAT 转到本机 8443 |
| `./data:/app/data` | 持久化 | 含 `xinggui.db` 与 `jwt.secret`，**不能删** |
| `./certs:/app/certs:ro` | 只读挂载 | 证书不写回 |
| `XG_TLS_CERT` / `XG_TLS_KEY` | `/app/certs/fullchain.pem` / `privkey.pem` | 两者同时非空才启用 HTTPS |
| `XG_BASE_URL` / `XG_CORS_ORIGINS` | `https://music.wangxiaoming.vip:20001` | 返回给客户端与管理端的地址 |

如果公网端口不是 20001，或者容器要直接监听 20001，把 `ports` 改成 `"20001:8443"` 并同步 `XG_BASE_URL` / `XG_CORS_ORIGINS`。

**权限提醒**：容器以 UID 10001 运行。若 `data/` 由 root 创建，需要放权，否则 SQLite 无法写入：

```bash
sudo chown -R 10001:10001 ~/xinggui/data
sudo chmod 700 ~/xinggui/data
```

---

## 3. 拉取镜像并启动

```bash
docker pull ghcr.io/vipxiaoming/xinggui-server:latest
cd ~/xinggui
docker compose up -d
```

首次启动会：创建 `data/xinggui.db`（SQLite，WAL 模式）、生成 `data/jwt.secret`（32 字节 hex，权限 600）。
**`jwt.secret` 一旦生成就不能更换**，否则所有已签发的 access/refresh token 立即失效，全部用户需要重新登录。

固定版本启动（回滚时用）：

```bash
docker pull ghcr.io/vipxiaoming/xinggui-server:<短哈希>
# 编辑 compose 里 image: 为 :<短哈希>
docker compose up -d
```

---

## 4. 验证

```bash
# 1) 容器状态与健康检查
docker ps --filter name=xinggui
docker inspect --format '{{.State.Health.Status}}' xinggui

# 2) 启动日志（应看到 "TLS 模式:" 与 "监听 :8443"）
docker logs xinggui

# 3) 容器内自测（HTTPS）
docker exec xinggui wget -qO- --no-check-certificate https://127.0.0.1:8443/v1/health

# 4) 宿主机回环
curl -k https://127.0.0.1:8443/v1/health

# 5) 公网（在服务器外执行）
curl https://music.wangxiaoming.vip:20001/v1/health
```

`/v1/health` 返回 200 且 body 含健康标记即视为服务可用。

---

## 5. 数据持久化

- `./data` 挂载到容器 `/app/data`，容器重建不影响数据。
- 关键文件：`xinggui.db`（用户 / 歌单 / 日志）、`xinggui.db-wal`、`xinggui.db-shm`（SQLite WAL 模式）、`jwt.secret`（JWT 签名密钥，32 字节 hex）。
- 容器自带 `dbadmin`，但它只用于改用户状态（`dbadmin <db路径> <用户名> [status]`），**不做备份**。

### 备份

最稳妥的做法是停服冷备（SQLite WAL 在运行时直接 cp 可能拿到不一致快照）：

```bash
mkdir -p "$HOME/backup" && chmod 700 "$HOME/backup"
cd ~/xinggui
docker compose stop xinggui
tar -czf "$HOME/backup/xinggui-$(date +%F).tar.gz" data/
docker compose start xinggui
chmod 600 "$HOME"/backup/*
```

需要不停服时，用容器内 sqlite 做在线备份（镜像里没有 sqlite3，用一次性容器挂载同一数据目录）：

```bash
docker run --rm -v "$HOME/xinggui/data:/d:ro" -v "$HOME/backup:/b" alpine:3.20 \
  sh -c 'apk add -q sqlite && sqlite3 /d/xinggui.db ".backup /b/xinggui-online-$(date +%F).db"'
chmod 600 "$HOME"/backup/*
```

> 在线备份期间服务仍在写库，`.backup` 会自动处理锁；但一致性要求高的场景请用冷备。

### 恢复

```bash
cd ~/xinggui
docker compose down
cp data/xinggui.db "data/xinggui.db.broken-$(date +%s)"
tar -xzf "$HOME/backup/xinggui-2026-10-29.tar.gz"    # 或 cp 备份的 .db
rm -f data/xinggui.db-wal data/xinggui.db-shm
docker compose up -d
```

备份中含用户数据与 `jwt.secret`，**不要提交到仓库，也不要发给无关人员**。

---
## 6. 证书续期

```bash
# 1) 覆盖 ~/xinggui/certs/ 下的两个文件（保持 600）
# 2) 服务端只在启动时读证书，必须重启
docker compose restart xinggui

# 3) 确认生效
curl -k --resolve music.wangxiaoming.vip:20001:127.0.0.1 https://music.wangxiaoming.vip:20001/v1/health
openssl s_client -connect 127.0.0.1:8443 -servername music.wangxiaoming.vip </dev/null 2>/dev/null | openssl x509 -noout -dates
```

不需要重建容器，`restart` 即可（证书是只读挂载）。`docker compose up -d --force-recreate` 也可以但没必要。

---

## 7. 常见故障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 日志 `TLS 预检失败` / 容器反复重启 | 证书与私钥不配对，或 `privkey.pem` 里混了证书段 | 按 §1 校验公钥哈希；重新拆分 |
| `unable to open database file` | `./data` 归 root，容器 UID 10001 无写权限 | `sudo chown -R 10001:10001 ~/xinggui/data` |
| `database is locked` | 多个实例同时挂了同一个 db | 只保留一个 `xinggui` 容器 |
| 浏览器报 CORS 被拒 | `XG_CORS_ORIGINS` 没含实际页面 Origin（协议/端口/大小写都要一致） | 改 compose 里的值后 `docker compose up -d` |
| 用户全被判未登录 | `jwt.secret` 被换掉或丢了 | 从备份恢复 `data/jwt.secret`，不要重新生成 |
| 公网 curl 超时，容器内正常 | NAT/防火墙未转发 20001→8443 | 检查端口映射；`ss -ltnp \| grep 8443` |
| `docker pull` 401/403 | GHCR 包可见性为 private | 在 GitHub 包的 Settings 里改为 Public，或 `docker login ghcr.io` |
| 平台解析全部失败 | 容器出站 443 不通 / 缺 CA | `docker exec xinggui wget -qO- https://www.qq.com` |

---

## 8. 回滚

### 回滚镜像（推荐，秒级）

```bash
cd ~/xinggui
docker compose down
# 把 image 改回上一个短哈希标签
sed -i 's|:latest|:<上一个短哈希>|' docker-compose.yml
docker compose up -d
```

可用标签：`docker run --rm ghcr.io/vipxiaoming/xinggui-server:latest true` 看不到列表；
在 GitHub 仓库 → Packages → `xinggui-server` → Versions 里查所有短哈希标签。

### 回滚数据（仅在数据损坏时）

```bash
docker compose down
cp ~/xinggui/data/xinggui.db ~/xinggui/data/xinggui.db.broken-$(date +%s)
cp ~/backup/xinggui-2026-10-29.db ~/xinggui/data/xinggui.db
rm -f ~/xinggui/data/xinggui.db-wal ~/xinggui/data/xinggui.db-shm
docker compose up -d
```

### 彻底下线

```bash
cd ~/xinggui && docker compose down
docker rmi ghcr.io/vipxiaoming/xinggui-server:latest
# 数据目录自行保留或删除（删前先备份）
```

---

## 9. 本地构建（无 GHCR 权限时）

```bash
cd xinggui-server
docker build -t xinggui-server:local .
docker run --rm -p 8443:8443 \
  -v "$PWD/data:/app/data" \
  -v "$PWD/certs:/app/certs:ro" \
  -e XG_PORT=8443 -e XG_DATA_DIR=/app/data \
  -e XG_BASE_URL=https://music.wangxiaoming.vip:20001 \
  -e XG_TLS_CERT=/app/certs/fullchain.pem \
  -e XG_TLS_KEY=/app/certs/privkey.pem \
  xinggui-server:local
```

`.dockerignore` 保证构建上下文里不含 `data*/`、`*.exe`、`*.db`、证书，因此不会把本地数据打进镜像层。