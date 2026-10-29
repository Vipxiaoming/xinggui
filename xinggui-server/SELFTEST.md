# SELFTEST · 星轨聚合层服务端骨架（P1 阶段 1-6）

生成时间：2026-10-28 23:10 (Asia/Shanghai)
工程路径：`C:\Users\wangxiaoming\Documents\Codex\Music\xinggui-server`

---

## 0. 环境

| 项 | 值 | 备注 |
|---|---|---|
| Go | go1.26.0 windows/amd64 | winget 静默安装卡住 >3 分钟无输出，改用官方 zip 解压到 `C:\Go` |
| 初始 Go | go1.22.10 | `golang.org/x/crypto v0.58.0` 要求 go >= 1.26，`go mod tidy` 自动切 toolchain 1.26.10 并回写 `go 1.26.0` |
| GOPROXY | https://goproxy.cn,direct | 国内直连 proxy.golang.org 不稳 |
| SQLite 驱动 | modernc.org/sqlite v1.61.0 | 纯 Go，无需 CGO/VS Build Tools |
| JWT | github.com/golang-jwt/jwt/v5 v5.3.1 | HS256 |
| 密码散列 | golang.org/x/crypto/argon2 | argon2id, m=64MB, t=3, p=4 |

安装命令（实际执行）：

```powershell
Invoke-WebRequest -Uri 'https://go.dev/dl/go1.22.10.windows-amd64.zip' -OutFile 'C:\Users\wangxiaoming\Downloads\go122.zip'
Expand-Archive -LiteralPath 'C:\Users\wangxiaoming\Downloads\go122.zip' -DestinationPath 'C:\Users\wangxiaoming\Downloads\go122b' -Force
Move-Item -LiteralPath 'C:\Users\wangxiaoming\Downloads\go122b\go' -Destination 'C:\Go'
& 'C:\Go\bin\go.exe' version
# go version go1.22.10 windows/amd64
```

## 1. 编译

命令：

```powershell
$env:PATH='C:\Go\bin;' + $env:PATH
$env:GOPROXY='https://goproxy.cn,direct'
$env:GOCACHE='C:\Users\wangxiaoming\.gocache-xg'
$env:GOMODCACHE='C:\Users\wangxiaoming\.gomodcache-xg'
Set-Location 'C:\Users\wangxiaoming\Documents\Codex\Music\xinggui-server'
go build ./...
go vet ./...
```

实际输出：

```text
BUILD_EXIT=0
VET_EXIT=0
```

产物：`xinggui.exe` = 16,947,712 B（16.2 MB）。

## 2. 单元/集成测试

命令：`go test ./... -count=1`

实际输出：

```text
ok  	xinggui/internal/router	1.394s
TEST_EXIT=0
```

覆盖 4 个用例（`internal/router/router_test.go`，共 28 条断言）：

| 用例 | 断言点 |
|---|---|
| TestHealthAndConfig | health=ok；config 含 maxDevices/resolveEndpoint |
| TestAuthFlow | 注册 201/pending、重复 409/username_taken、弱密码 400/weak_password、pending 登录 403/pending_approval、错误密码 401/bad_credentials、放行后登录 200、expiresIn=7200、me 200、无 token 401、refresh 200、解绑当前设备 400、设备上限 409/device_limit、解绑后可登 |
| TestResolveRealKuwo | 真实请求酷我 rid=474680496 返回 200；URL 为 http(s)；实测 duration>100s、size>1MB（防占位片段）；resolvedBy=kw；二次 cached=true；占位 rid=228908 无歌名 → 404/no_source；空请求 404；非法音质 400/bad_quality |
| TestResolveByNameSearch | 只给 `name=晴天, artist=周杰伦` 时不带 id 也能拿到真实 URL |

## 3. 启动

命令：

```powershell
Set-Location 'C:\Users\wangxiaoming\Documents\Codex\Music\xinggui-server'
.\xinggui.exe
```

实际输出：

```text
[xinggui] 2026/10/28 23:08:32.456356 监听 :8443 (base=https://music.wangxiaoming.vip:8443, db=data\xinggui.db)
```

监听 `:8443`（HTTP，TLS 交给反代）。DB 自动建表：`users` / `devices` / `search_logs`。

环境变量（全部可选）：

| 变量 | 默认 | 说明 |
|---|---|---|
| XG_PORT | 8443 | 监听端口 |
| XG_DATA_DIR | data | 数据目录 |
| XG_DB_PATH | data/xinggui.db | SQLite 路径 |
| XG_JWT_SECRET | 自动生成 data/jwt.secret | HS256 密钥 |
| XG_BASE_URL | https://music.wangxiaoming.vip:8443 | 对外基址（用于 /v1/config） |
| XG_DEBUG_SEARCH | 空 | 非空时把搜索空响应落盘到 search-debug.txt |

## 4. 端到端自测（真实 HTTP）

脚本：`selftest.ps1`（28 条检查）。运行：

```powershell
Set-Location 'C:\Users\wangxiaoming\Documents\Codex\Music\xinggui-server'
powershell -NoProfile -ExecutionPolicy Bypass -File .\selftest.ps1
```

**实际结果：PASS=28 FAIL=0，退出码 0。** 完整输出见同目录 `selftest-output.txt`。

### 4.1 关键快照（真实响应，非编造）

health：

```json
{"cache":0,"status":"ok","time":1793200129}
```

注册（pending）：

```text
### POST /v1/auth/register (selftest54042) -> HTTP 201
{"message":"注册成功，等待管理员审核","status":"pending"}
```

待审核登录被拒：

```text
### POST /v1/auth/login (pending) -> HTTP 403
{"error":"pending_approval","message":"账号待管理员审核"}
```

手工放行（管理员审批的替身：契约 6.1 approve，本阶段先用 CLI 改库）：

```text
.\dbadmin.exe data\xinggui.db selftest54042 active
updated=1 user=selftest54042 status=active
now status = active
```

登录成功（expiresIn=7200，access 2h / refresh 30d）：

```json
{"accessToken":"eyJhbGciOiJIUzI1NiIs...","expiresIn":7200,"refreshToken":"eyJhbGciOiJIUzI1NiIs...",
 "user":{"deviceCount":1,"expireAt":0,"maxDevices":2,"status":"active","username":"selftest54042","vipType":"FREE"}}
```

酷我解析（真实直链 + 实测元数据）：

```text
### POST /v1/resolve (kw id=474680496, wall=284ms) -> HTTP 200
{"url":"https://kw-er.kuwo.cn/cf54a975121ca9c589141437f3ae7194/6ae21001/resource/30106/trackmedia/M500004Nfj4M2o8m5N.mp3",
 "level":"standard","actualBitrate":128,"duration":203.8,"size":3260672,"resolvedBy":"kw",
 "expiresAt":1793221729,"trace":["kw:search=20/keep=6","kw:filtered=14","kw:474680496:ok(128k)"],"cached":false}
```

缓存命中（wall 45ms）：

```json
{"cached":true,"trace":["kw:search=20/keep=6","kw:filtered=14","kw:cache_hit"], ...}
```

## 5. 实测发现的酷我行为（重要，与需求说明有出入）

### 5.1 `br` 参数被忽略

同一 rid 分别以 `br=128kbps` / `320kbps` / `flac` 请求，返回**同一文件**（MD5 相同）。因此在酷我这路上，`quality` 不能保证码率：

- 请求 320k 实际拿到 128k → 响应里 `actualBitrate=128`、`level="standard"`（以 MP3 帧头实测为准，不虚报）
- 请求 flac → 上游返回 `refuse request!`，自动降级到 320k 并附 `warnings:["flac_unsupported_fallback_320k"]`

### 5.2 存在"试听占位"文件

多个不同 rid（228908 / 351583919 / 349354644 / 488357764）都返回**同一个** 181,521 B 文件（MD5 `43FE5F6460E49AFDF0BC2340012FE7B5`，约 4.5 秒）。

处理：`placeholderSize = 181521` 硬拦 + 时长校验（实测时长 < 请求时长 40% 视为片段）+ 跳过换下一候选。这条不做会导致客户端"点播放只有 4 秒"。

### 5.3 `/anti.s` 路径段是签发时间，不是过期时间

`.../6ae20b04/nf/resource/...` 的 16 进制段解出来 ≈ 当前时间，而非过期时间。按"过期时间"直接减 now 会得到负 TTL，导致缓存永不命中。实际按 **签发 + 6h** 估算，再与 6h 上限取 min。

### 5.4 `source` 不等于酷我时，id 不能当 rid 用

`source="wy", id="186016"` 如果直接查 `anti.s?rid=186016`，会返回一首完全无关的歌。已修：仅 `source=kw` 时才用 id，其余走歌名搜索，并在 trace 里显式写 `id_ignored:wy`。

### 5.5 酷我无周杰伦《晴天》原版版权

搜索"晴天 周杰伦"的 20 条命中里没有原版正片，最高分是 `51685512 = 晴天 (KTV版伴奏)`。服务端仍返回可播 URL，但附 `warnings:["suspect_not_original:晴天 (KTV版伴奏)"]`，让客户端可以决定是否提示用户。这是上游曲库问题，不是解析 bug。

## 6. 排障记录（真实过程，含失败）

| # | 现象 | 定位 | 修复 |
|---|---|---|---|
| 1 | winget 安装 Go 静默运行 3 分钟无输出、进程仍存活 | 无输出无法判定 | 放弃 winget，改用官方 zip 解压到 `C:\Go` |
| 2 | `go mod tidy` 中途要求 go >= 1.26 | x/crypto v0.58.0 的 go.mod 要求 | 让 toolchain 自动升级，回写 `go 1.26.0` |
| 3 | 二次 resolve 未命中缓存 | `parseKwExpiry` 把签发时间当过期时间，TTL≈0 | 改为签发+6h，见 5.3 |
| 4 | 按名搜索 404 | 酷我搜索返回 Python dict 单引号，原正则按双引号匹配 | 改为按 `{'AARTIST'` 分块 + 单引号正则 |
| 5 | 按名搜索命中 KTV 伴奏 / 无关歌 | 无过滤，取第一条 | 加 `badWords` 扣分 + `filterHits` 名称/艺术家强校验 + 时长<60s 扣分 |
| 6 | `wy` 的 id 命中错歌 | id 被无条件当 rid | 加 `source=kw` 才用 id |
| 7 | selftest 里中文搜索恒 404，trace 报 `search_err:no_source` | `powershell.exe` 5.1 按 ANSI 编码发 Body，中文变 `??`；酷我返回 `HITMODE':'nores'` | 脚本改用 `[Text.Encoding]::UTF8.GetBytes()` 发 Body。**服务端无问题** |
| 8 | selftest 读不到 4xx 响应体，全显示空 | 5.1 的 `Invoke-WebRequest` 无 `-SkipHttpErrorCheck`，需从 `ErrorDetails` 取 | 加 try/catch + `.ErrorDetails.Message` |

## 6.5 第二阶段（本轮补齐）实测结果

生成时间：2026-10-28 23:55 (Asia/Shanghai)

### 6.5.1 新增自动化脚本

| 脚本 | 覆盖 | 实测结果 |
|---|---|---|
| `selftest.ps1`（原有，回归） | 注册/登录/刷新/解绑/四路解析/缓存/占位拦截/搜索日志 | **PASS=28 FAIL=0** |
| `selftest2.ps1`（本轮新增） | 管理端全套 + 歌单 + 同步 + 版本 + 限流锁定 | **PASS=90 FAIL=0** |

合计 **118 条断言全绿**。`selftest2.ps1` 会自行启停独立实例（端口 19443、数据目录 `data-selftest`），不与 8443 的生产实例互相影响。

运行方式：

```powershell
Set-Location 'C:\Users\wangxiaoming\Documents\Codex\Music\xinggui-server'
powershell -NoProfile -ExecutionPolicy Bypass -File .\selftest2.ps1
```

### 6.5.2 四路解析器实测（每条均为真实 HTTP 请求 + 真实音频字节探测）

| 平台 | 接口 | 实测样本 | 结果 | 关键数据 |
|---|---|---|---|---|
| 酷我 kw | `antiserver.kuwo.cn/anti.s` | rid=474680496 稻香 | 200 | 128k / 3,260,672 B / 203.8s |
| 网易云 wy | `music.163.com/api/search/get/web` + `/song/media/outer/url` | 稻香（搜索首个免费曲） | 200 | 128k / 3,706,924 B / 231.7s |
| 酷狗 kg | `mobilecdn.kugou.com/api/v3/search/song` + `m.kugou.com/app/i/getSongInfo.php` | 晴天/蓝心羽 | 200 | 128k / 2,829,338 B / 176.0s |
| QQ tx | `smartbox_new.fcg` + `api.vkeys.cn/v2/music/tencent` | 遇见/纯音乐 | 200 | FLAC / 17,628,042 B / 691kbps |

竞速编排：四路并行，单路 7s 超时，某路失败不影响其他路（trace 里逐条记录 `platform:id:ok/paid/no_source/timeout/short`）。

### 6.5.3 本轮实测发现的三个新行为（与需求原文不同）

1. **QQ 音乐 VIP 曲只给试听**：`api.vkeys.cn` 对周杰伦《晴天》返回 `quality="音乐试听"`、`kbps=28`、`size="0.92MB"`。已加 `isQQTrial()` 硬拦并判 `ErrPaid`，让竞速自动切到其他平台；否则客户端点播放只有 30 秒。
2. **QQ 付费器乐曲反而给完整 FLAC**：《遇见（钢琴版）》返回 17,628,042 B 的 FLAC。因此不能「见付费即拒」，只能按试听特征判断。
3. **酷我歌单接口的 rid 在 `id` 字段**：`nplserver.kuwo.cn/pl.svc` 返回的 `MUSICRID` 为空，真实 rid 在 `id`（如 3780531）。首版取 `MUSICRID` 导致 id 全空，已修。

另有两处修正：

- 缓存命中曾把 `resolvedBy` 写成 `"cache"`，破坏契约「resolvedBy = 实际命中平台」；已改为缓存里存平台名并在命中时回填。
- 跨平台 id 忽略标记 `id_ignored:<source>` 之前只在未命中路径写，命中路径丢失；已改为无条件写入 trace 首项。

### 6.5.4 管理端实测

| 项 | 实测 |
|---|---|
| `POST /admin/api/login` | 密码 + TOTP → token，expiresIn=7200；错误密码/错误 TOTP 均 401 |
| TOTP 算法 | RFC 6238 官方向量 6 条全部一致（59→287082、1111111109→081804、20000000000→353130 等） |
| `/admin/api/setup/status` + `init` | 未初始化返回 false；init 201 返回 totpSecret 与 otpauthUrl；二次调用 409 |
| 用户管理 | 列表/搜索/状态筛选、详情、approve/reject/ban/unban、reset-password、unbind、expire、软删除全部 200 |
| 用户数据浏览 | favorites/playlists/history/downloads/searches 均返回真实写入的数据（含歌单内歌曲） |
| 配置 | GET/PUT config、平台禁用开关；`/v1/config` 能同步到 maxDevices/announcement/disabledPlatforms |
| 版本 | PUT 后 `/v1/config` 带出 updateUrl + 64 位 SHA + forceUpdate；填 URL 缺 SHA 返回 400 |
| 监控 | overview（30 天 trend、今日解析数）、stats/resolve（P50/P95）、stats/errors、stats/searches、health（cpu/mem/disk/goroutines） |
| 登出 | `/admin/api/logout` 后同一 token 立即 401 |

### 6.5.5 限流与锁定实测

- 同 IP 每分钟第 6 次**失败**触发 `429 rate_limited`（成功登录不占配额）。
- 同账号连续 5 次失败后锁定 15 分钟，期间即使密码正确也返回 `429 account_locked`。

### 6.5.6 单元测试新增

| 包 | 用例数 | 内容 |
|---|---|---|
| `internal/auth` | 2 | RFC 6238 六组官方向量、±1 时间窗 |
| `internal/db` | 6 | 建表幂等、管理员令牌生命周期、percentile、版本 upsert、用户数据全链路、列表筛选/软删除 |
| `internal/resolve` | 5 | 酷狗签名 V1/V2 向量、平台归一化、音质映射、顺序去重、时长文本解析 |
| `internal/router` | 4（原有） | 健康/配置、认证全流程、真实酷我解析、按名搜索 |

## 7. 已知缺口

### 7.1 已在第二阶段关闭

- ~~审核没有 HTTP API~~ → `/admin/api/*` 全套已实现并通过 90 条断言
- ~~解析只有酷我一路~~ → 四路 + 竞速编排已实现，四路各自实测拿到可播 URL
- ~~无歌单/同步/版本端点~~ → `/v1/playlist/parse|songs`、`/v1/sync/push|pull`、`/v1/version/latest` 均实现
- ~~无登录限流/账号锁定~~ → 同 IP 5 次失败/分钟、同账号 5 次失败锁 15 分钟，实测通过
- ~~`/v1/config` 静态值~~ → 已接数据库（settings 表 + versions 表），PUT 后客户端立即可见
- ~~`updateSha256` 为空~~ → 版本管理强制校验：填下载地址必须同时给 SHA-256

### 7.2 仍未做（明确列出，未宣称完成）

| 项 | 状态 | 说明 |
|---|---|---|
| 酷狗 `gcid_*` / `collection_*` 歌单 | **未通过** | 首版直接返回 404。原因是这两类 ID 需先经 `m.kugou.com/songlist/<gcid>/` HTML 反查 `specialid`（原 app 的 `resolveKugouShareGcid`），本轮未移植。普通数字 specialid 已通过 |
| 酷狗付费曲 | 已知限制 | `pay_type=3` 的曲目返回 `error="需要付费"`，无直链。竞速会自动切到其他平台，故不影响最终成功率，但酷狗这路对热歌命中率较低 |
| TLS | 未启用 | 设计如此，交给反代（8443 由 Nginx/Caddy 终结 TLS） |
| 管理页对接联调 | 未执行 | 管理页 23 条路由已全部实现并通过 curl 实测，但未用浏览器点选真实后端验证（管理页自测跑的是 mock 模式） |
| 真机 / 真客户端联调 | 未执行 | 本阶段仅服务端，无 Android 客户端参与 |
| WebSocket 实时推送 | 未实现 | 管理页靠手动刷新；契约未要求 |
| 审计日志独立页 | 未实现 | 管理操作目前写入 `error_logs`（level=warn），可在错误日志页看到，但无独立审计视图 |
| 单测覆盖 `internal/router` 管理端 | 部分 | 管理端主要靠 `selftest2.ps1` 端到端覆盖（90 条），未逐端点补 Go 单测 |

## 8. 复现步骤

```powershell
# 1) 依赖
$env:PATH = 'C:\Go\bin;' + $env:PATH
$env:GOPROXY = 'https://goproxy.cn,direct'
Set-Location 'C:\Users\wangxiaoming\Documents\Codex\Music\xinggui-server'
go build ./...            # 期望退出码 0

# 2) 测试（会真实访问酷我）
go test ./... -count=1    # 期望 ok xinggui/internal/router

# 3) 起服务
go run ./cmd/server       # 监听 :8443

# 4) 端到端（另开一个终端）
powershell -NoProfile -ExecutionPolicy Bypass -File .\selftest.ps1
# 期望：===== 汇总: PASS=28 FAIL=0 =====
```

## 9. 第二阶段新增端点清单

### 9.1 客户端 API（`/v1`）

| 方法 | 路径 | 状态 |
|---|---|---|
| POST | /v1/auth/logout | 新增（token_version+1，access+refresh 全失效） |
| POST | /v1/resolve | **改造**：由单路酷我改为四路并行竞速 + 服务端缓存 + 真实码率 |
| POST | /v1/playlist/parse | 新增（四平台链接 + 纯数字双候选 + 非法输入明确报错） |
| POST | /v1/playlist/songs | 新增（wy/tx/kg/kw 四平台分页） |
| POST | /v1/sync/push | 新增（favorite/playlist/history，≤500 条/次） |
| GET | /v1/sync/pull | 新增（游标式增量，返回 cursor） |
| GET | /v1/version/latest | 新增 |
| GET | /v1/config | **改造**：接数据库（maxDevices/announcement/disabledPlatforms/版本信息） |

### 9.2 管理 API（`/admin/api`）

| 方法 | 路径 | 状态 |
|---|---|---|
| POST | /login | 新增（密码 + TOTP，2h） |
| POST | /logout | 新增（服务端吊销 jti） |
| GET | /setup/status | 新增（含 internalIP 判定） |
| POST | /setup/init | 新增（仅无管理员 + 内网 IP 或 X-Setup-Token） |
| GET | /users | 新增（分页/搜索/状态筛选/includeDeleted） |
| GET | /users/{u} | 新增 |
| DELETE | /users/{u} | 新增（软删除） |
| POST | /users/{u}/approve|reject|ban|unban|reset-password|unbind|expire | 新增（7 个动作） |
| GET | /users/{u}/favorites|playlists|history|downloads|searches | 新增（5 个数据浏览） |
| GET/PUT | /config | 新增 |
| POST | /config/platforms/{name}/disable | 新增 |
| GET/PUT | /version | 新增 |
| GET | /stats/overview | 新增 |
| GET | /stats/resolve | 新增（24h P50/P95） |
| GET | /stats/errors | 新增（level/platform/q 三重筛选） |
| GET | /stats/searches | 新增（Top 50） |
| GET | /health | 新增（cpu/mem/disk/dbLatency/goroutines） |

### 9.3 新增源文件

```
internal/auth/totp.go            RFC 6238 TOTP
internal/auth/admin_token.go     管理令牌（独立 issuer=admin，带 jti 可吊销）
internal/auth/totp_test.go
internal/db/db_extra.go          12 张扩展表 + 全部管理/数据查询
internal/db/db_test.go
internal/resolve/common.go       平台归一化、通用探测、HTTP helper
internal/resolve/kugou.go        酷狗签名 + 搜索 + 直链 + 歌单
internal/resolve/netease.go      网易云搜索 + 外链跳转 + 歌单
internal/resolve/qq.go           QQ smartbox + vkeys 网关（含试听识别）
internal/resolve/kuwo_playlist.go 酷我歌单
internal/resolve/aggregate.go    四路并行竞速 + 统一打分
internal/resolve/resolve_test.go
internal/router/admin.go         管理端 20 端点
internal/router/playlist.go      歌单解析/歌曲
internal/router/sync.go          同步/版本/登出/限流/四路解析入口
internal/router/support.go       限流器、系统配置、同步写入
internal/router/metrics.go       cpu/disk/db 延迟
internal/router/util.go          时间格式化、平台归一化
selftest2.ps1                    90 条端到端断言
tools/totpgen                    TOTP 生成 CLI（供 selftest 用）
tools/probe4                     四路解析探针（人工验证用）
```

### 9.4 依赖新增

| 模块 | 版本 | 用途 |
|---|---|---|
| github.com/shirou/gopsutil/v4 | v4.26.9 | /admin/api/health 的 cpu / disk |

### 9.5 新增环境变量

| 变量 | 用途 |
|---|---|
| XG_SETUP_TOKEN | 跨公网执行 `/setup/init` 时的一次性令牌（不设则只允许内网 IP） |

---
