# 星轨 管理后台（xinggui-admin）

零构建、零外部依赖的原生 HTML/CSS/JS 管理后台，对接聚合层 `/admin/api`。

## 启动

```powershell
cd C:\Users\wangxiaoming\Documents\Codex\Music\xinggui-admin
node server.js 8787
# 打开 http://127.0.0.1:8787/
```

不想用 Node 时，任意静态服务器均可（因为全是相对路径）：

```powershell
python -m http.server 8787
```

也可以直接双击 `index.html`（`file://` 打开）。此时若未指定 `?api=`，默认请求 `http://127.0.0.1:8443/admin/api`，连不上会自动降级到 mock。

## 模式切换

| 目标 | 地址 |
|---|---|
| Mock 演示（免登录、内置 32 用户与全套数据） | `http://127.0.0.1:8787/?mock=1` |
| 真实后端，同源 | `http://127.0.0.1:8787/`（把本目录挂到网关 `/admin` 下） |
| 真实后端，跨域 | `http://127.0.0.1:8787/?api=https://music.wangxiaoming.vip/admin/api` |
| 禁用自动降级（连不上就报错，便于排障） | `http://127.0.0.1:8787/?mock=0` |
| 直接指定主题 | 追加 `&theme=dark` 或 `&theme=light` |

自动降级规则：`?mock=1` 强制 mock；`?mock=0` 强制真实；不带参数时，真实请求网络失败会自动切到 mock，并在顶栏显示 `MOCK` 徽标。**Mock 模式下不发起任何网络请求。**

Mock 登录：密码 `admin12345`，动态码任意 6 位（`?mock=1` 会跳过登录直接进后台，便于验收）。

## 鉴权

- 登录：`POST /admin/api/login`，body `{ "password": "...", "totp": "123456" }`，返回 `{ token, expiresIn, username, role }`。
- 之后所有请求带 `X-Admin-Token: <token>`；token 存 `sessionStorage`，关标签页即失效。
- 收到 401 自动清 token 并跳回登录页。
- ⚠️ **该 `/login` 端点不在原契约中**（契约 §6 只写了 `X-Admin-Token` 的签发前提，没定义端点）。服务端需按上述形状实现；若后端签名不同，只改 `assets/js/api.js` 的 `login()` 一处。

## 目录结构

```
index.html                 入口，按顺序加载 5 个核心脚本 + 5 个页面模块
server.js                  零依赖静态服务器（node server.js 8787）
assets/css/app.css         全部样式（亮/暗双主题、响应式）
assets/js/mock.js          内置演示数据 + 假路由（不联网）
assets/js/api.js           唯一网络出口，所有端点集中在此
assets/js/ui.js            DOM/图标/Toast/Modal/表格/分页/图表/路由
assets/js/app.js           外壳：登录、侧栏、主题、路由注册
assets/js/pages/*.js       5 个页面模块
_selftest.html             浏览器内自测页（49 项断言）
```

## 页面清单（7 个路由，全部已实现）

| 路由 | 页面 | 覆盖契约端点 |
|---|---|---|
| `#/dashboard` | 监控面板：4 指标卡 + 30 天趋势图 + 系统健康 + 平台成功率/P50/P95 + 热门搜索 + 最近错误 | `stats/overview`、`stats/resolve`、`health`、`stats/errors`、`stats/searches` |
| `#/users` | 用户列表：搜索、状态筛选、分页、行内快捷操作 | `users`、`users/:u/{approve,ban,unban}` |
| `#/users/:username` | 用户详情：基本信息、6 个操作、设备列表、5 类数据浏览 Tab | `users/:u`、`approve/reject/ban/unban/reset-password/unbind/expire`、`favorites/playlists/history/downloads/searches`、`DELETE users/:u` |
| `#/approvals` | 待审核队列，通过/拒绝 | `users?status=pending`、`approve`、`reject` |
| `#/config` | 系统配置：3 个运行开关、设备上限/缓存/限流/下载目录、解析顺序拖拽排序、4 平台启停、公告 | `GET/PUT config`、`config/platforms/:name/disable` |
| `#/version` | 版本管理：版本号、下载地址、SHA-256、更新说明、强制更新、发布前 4 项检查、弹窗预览 | `GET/PUT version` |
| `#/errors` | 错误日志（级别/平台/关键词筛选）+ 热门搜索 Top 50 | `stats/errors`、`stats/searches` |

## 验收方式

```powershell
# 1. 启动
node server.js 8787

# 2. 自测（49 项断言，需先启动服务）
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --headless=new --disable-gpu `
  --virtual-time-budget=40000 --dump-dom "http://127.0.0.1:8787/_selftest.html?mock=1"
```

预期输出首行 `== 49 passed, 0 failed ==`。

## 已知限制

- 未做 i18n，仅中文。
- 图表为内联 SVG，无缩放/悬浮 tooltip。
- 解析顺序仅支持鼠标拖拽 + 上下移按钮，未做键盘无障碍排序。
- 未实现 `setup/status`、`setup/init` 初始化向导 UI（`api.js` 已备好方法，页面未做）。
- 未实现登录页 TOTP 二维码绑定（假设管理员已持有 TOTP 密钥）。
- 无 WebSocket，数据靠手动刷新/切页刷新，不自动轮询。


---

## 接入真实后端（联调已验证 51/51）

server.js 会把 /admin/api/* 反向代理到 XG_BACKEND（默认 http://127.0.0.1:9443）。聚合层不下发 CORS 头，跨域直连会被浏览器拦掉，必须走同源代理。

`powershell
cd ..\xinggui-server
='...\xinggui-server\data-it'
='9443'
.\xinggui-it.exe
`

首次初始化管理员（返回 totpSecret，用 	otpgen.exe '<secret>' 生成 6 位码）：

`powershell
 = @{ username='admin'; password='<你的管理员密码>' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:9443/admin/api/setup/init -Method POST -ContentType application/json -Body 
`

默认模式已改为**真实且不降级**：后端连不上会直接报错，不再伪装成 mock。只有 ?mock=1 强制演示、?mock=auto 允许降级。
