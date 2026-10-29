# 星轨 实施验收日志

> 角色：主控仅负责调度与验收，具体执行由子代理完成。
> 验收标准：真实命令输出 + 可复现 + 可证伪。未执行记「未执行」。

## 验收项 A1：P0 空重建（关键地基）

| 项 | 结果 | 证据 |
|---|---|---|
| `java -jar apktool.jar b apktool-out -o rebuilt-test.apk` | **EXIT=0** | build.log |
| 三 dex 重建 | 成功 | build.log: "Smaling smali folder into classes.dex" x3 |
| 资源编译 | 成功 | build.log: "Building resources..." |
| 产物大小 | 8,740,248 B | 实测 |
| 原版大小 | 8,668,412 B | 实测 |
| 大小偏差 | **+0.83%** | 阈值 <5%，通过 |
| 结论 | **通过** | 路线 B 的资产复用前提成立 |

## 验收项 A2：五份文档完整性

| 文件 | 行数 | 状态 |
|---|---|---|
| 10-p0-preflight-checklist.md | 164 | 已交付 |
| 11-layout-id-map.md | 182 | 已交付 |
| 12-server-api-contract.md | 226 | 已交付 |
| 13-data-layer-migration.md | 232 | 已交付 |
| 14-task-breakdown.md | 196 | 已交付 |

## 验收项 A3：布局 ID 数据核验（主控独立复核）

| 指标 | 复核值 | 文档值 | 一致性 |
|---|---|---|---|
| layout 总数 | 156 | 156 | 一致 |
| 业务布局数 | 42 | 42 | 一致 |
| 业务布局 ID 定义 | 333 | 333 | 一致 |

## 待验收（子代理进行中）

| 项 | 负责 | 状态 |
|---|---|---|
| P0 剩余 5 项检查 | Mencius | 进行中 |
| 服务端骨架 + 酷我解析 | Hilbert | 进行中 |
| 酷狗签名还原 | Gauss | 进行中 |

## 环境缺口

| 项 | 状态 |
|---|---|
| Go | **未安装**（已告知子代理自行安装） |
| gradle | 未安装（P2 阶段需处理） |
| JDK 21 | 已就绪 |
| Node 24 | 已就绪 |
| Android SDK build-tools 34 | 已就绪 |
| adb + 模拟器 | 已就绪 |

## 验收项 A4：P0 剩余 5 项（Mencius）

| 项 | 结果 | 证据 |
|---|---|---|
| 检查 2 布局 ID | **零缺失** | 484 引用 / ids.xml 583 + public.xml 780 并入 |
| 检查 3 字体 | 通过 | res/font 不存在 |
| 检查 4 素材 | 有条件通过 | 228 PNG 全 <100KB，29 个需替换 |
| 检查 5 .so 许可 | 通过（需补 LICENSE） | 4 ABI x 2 齐全，含 2021-03-27 |
| 检查 6 依赖 | 有条件通过 | 47 个 version，40 确定 / 7 为 AGP 占位串 |
| 签名验证 | **VERIFIED=true** | V1/V2/V3 全绿 |
| res 条目对比 | **1019/1019 零差异** | 实体条目一致 |

**总体判定：有条件通过。P6 从 9 天调整为 9.7 天，总工期 108 人天。**

主控复核：子代理修正了 ids.xml 单表统计的误报，需并入 public.xml，该修正成立。

## 验收项 A5：酷狗签名（Gauss）

**主控亲自运行 selftest.mjs 复验，PASS。**

| 测试 | 结果 |
|---|---|
| 正确签名 info_v2 | HTTP 200, errcode 0 |
| 正确签名 get_other_list_file | HTTP 200, error_code 0, 返回真实 hash |
| 伪造 32 个 0 | errcode 1002 签名错误 |
| 不带签名 | errcode 1001 |
| 用 & 连接 | errcode 1002 |

签名规则（实测确认）：
- 盐 = NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt
- sig = md5Hex(salt + Σ排序后 k=v + salt)
- k=v 之间无分隔符
- token= 空值也要保留
- mid = md5(uuid)，dfid = "-"

额外发现：mobilecdnbj.kugou.com/api/v5/special/song 完全免签（实测 117 首）。

## 当前验收汇总

| 项 | 状态 |
|---|---|
| A1 P0 空重建 | 通过 (+0.83%) |
| A2 五份文档 | 通过 |
| A3 ID 数据核验 | 通过 (42/333) |
| A4 P0 五项检查 | 有条件通过 |
| A5 酷狗签名 | 通过（四条对照闭环） |
| A6 服务端骨架 | 待验收 |

## 验收项 A6：服务端骨架 + 酷我解析（Hilbert）

**主控复核 selftest-output.txt，28/28 PASS 属实。**

| 验收点 | 实际证据 |
|---|---|
| go build ./... | EXIT=0 |
| go vet ./... | EXIT=0 |
| go test ./... | 全绿 |
| selftest 端到端 | PASS=28 FAIL=0 |
| 解析成功 | resolvedBy=kw, duration=203.8s, size=3260672 |
| 非片段验证 | PASS: 时长 > 100s |
| 试听占位拦截 | rid=228908 -> 404 kw:228908:placeholder |
| 跨源 ID 保护 | wy id -> id_ignored:wy，改走搜索成功 |
| 缓存 | 二次请求 cached=true, wall=45ms |
| 音质如实回报 | 请求 320k 实得 128k -> actualBitrate=128 |

### 子代理实测推翻的两条原假设（主控已核对源码）

1. **br 参数被酷我忽略**：128k/320k/flac 返回同一文件。代码用 MP3 帧头实测真实码率，如实回报。
   - 证据：kuwo.go 有 actualBitrate 计算；selftest 输出 actualBitrate=128 而请求是 320k。

2. **存在 181,521 B 试听占位文件**：多个不同 rid 返回同一 MD5，只有约 4 秒。
   - 证据：kuwo.go:23 `const placeholderSize int64 = 181521`，注释写明"实测 181521 B / 约 4.5s"。

3. **/anti.s 路径段是签发时间而非过期时间**：按过期算会得到负 TTL，已改为签发+6h。

### 遗留问题（需后续处理）

| 问题 | 证据 | 影响 |
|---|---|---|
| 跨源搜索可能命中非原版 | selftest 输出 warnings:["suspect_not_original:晴天 (KTV版伴奏)"] | 用户搜"晴天"可能听到 KTV 版 |
| /admin/api/* 未实现 | 子代理自述 | 管理页无法对接真实后端 |
| 其余三路解析器未做 | 子代理自述 | 只有酷我可用 |
| playlist/sync/version 未做 | 子代理自述 | 歌单导入、数据同步缺失 |

## 验收项 A7：管理页（Kuhn）

| 项 | 结果 |
|---|---|
| 页面数 | 8（7 路由 + 登录） |
| 浏览器自测 | 49 passed / 0 failed |
| 截图核对 | 12 张，修复 6 个真实缺陷 |
| 零构建 | 原生 HTML/CSS/JS，无外部依赖 |
| mock 模式 | 32 用户全套数据，完全不联网 |
| 入口 | xinggui-admin/index.html |
| 启动 | node server.js 8787 -> ?mock=1 |

未实现：初始化向导 UI、TOTP 绑定、WebSocket 推送、批量操作、CSV 导出、审计日志。

## 当前验收汇总（更新）

| 项 | 状态 | 备注 |
|---|---|---|
| A1 P0 空重建 | 通过 | +0.83% |
| A2 五份文档 | 通过 | — |
| A3 ID 数据核验 | 通过 | 42 布局 / 333 ID |
| A4 P0 五项检查 | 有条件通过 | 29 素材待替换 |
| A5 酷狗签名 | 通过 | 四条对照闭环 |
| A6 服务端酷我 | 通过 | 28/28 |
| A7 管理页 | 通过 | 49/49 |
| A8 Android 骨架 | 待验收 | Dirac 进行中 |

## 验收项 A8：Android 工程骨架（Dirac）—— 主控独立复验

| 验收点 | 主控实测结果 |
|---|---|
| APK 存在 | 7,136,457 B |
| adb install | **Success** |
| 启动 | mCurrentFocus = vip.wangxiaoming.music.debug/MainActivity |
| logcat -b crash | **空**（无崩溃） |
| 界面截图 | 4 tab + mini player + 占位文案，布局正确 |
| 包名 | vip.wangxiaoming.music.debug |
| minSdk/targetSdk | 24 / 34 |

**子代理自述的构建输出（未由主控重跑，采信其日志）：**
- BUILD SUCCESSFUL in 3m 21s（首次）
- BUILD SUCCESSFUL in 14s, EXITCODE=0（复验）
- 唯一警告：Type.kt scaledDensity deprecated

### 子代理发现并修复的两个真实问题

1. **机器级 JVM 阻塞**：%TEMP% 解析成 8.3 短名 C:\Users\WANGXI~1\... 导致 JDK 21+ PipeImpl 的 AF_UNIX connect 返回 EINVAL，JVM 无法启动。
   - 修复：构建前设 TEMP/TMP 到 C:\Users\wangxiaoming\gtmp
   - 影响：**后续每个构建会话都要先设这两个变量**
2. **暗色状态栏图标**：windowLightStatusBar=true 写死，暗色下仍为深色。
   - 修复：新增 values-night/themes.xml 覆盖为 false，实机复验通过

### 资源策略（优于原计划）

未整包拷贝 res，而是从 26 个业务布局算依赖闭包：29 色 / 9 dimen / 73 drawable / 6 style。
114 个库布局零引入，这是首轮编译通过的原因。

activity_main 从 32 KB 压到 11 KB（删播放器/home/批量下载/MV），
原版完整副本存档在 docs/layout-reference/activity_main.full.xml。

### 遗留

| 项 | 状态 |
|---|---|
| 真机测试 | 未执行（无物理设备） |
| Android 24/25 低版本 | 未验证 |
| release 签名 | 未做 |
| lintDebug | 未跑 |

## 当前验收汇总

| 项 | 状态 |
|---|---|
| A1 P0 空重建 | 通过 |
| A2 五份文档 | 通过 |
| A3 ID 数据核验 | 通过 |
| A4 P0 五项检查 | 有条件通过 |
| A5 酷狗签名 | 通过 |
| A6 服务端酷我 | 通过 28/28 |
| A7 管理页 | 通过 49/49 |
| A8 Android 骨架 | **通过（实机）** |
| A9 服务端补齐 | 进行中（Aquinas） |

## 验收项 A9：服务端补齐（Aquinas）

### 主控独立复验（非采信自述）

1. 服务端可启动：`spawn xinggui.exe` -> `health HTTP 200 {"cache":0,"status":"ok"}`
2. 注册/审核/登录链路：
   - `register 201` -> `{"status":"pending"}` 待审核态正确
   - `dbadmin.exe <db> <user> active` -> `updated=1 status=active`
   - `login 200` -> accessToken + refreshToken 正常签发
3. 四路解析器独立验证（`go run ./tools/probe4`）：

| 平台 | 结果 | 大小 | 时长 | 耗时 | URL 域名 |
|---|---|---|---|---|---|
| kg | 128k standard | 2,829,338 B | 176.0s | 333ms | sharefs.kugou.com |
| wy | 128k standard | 3,706,924 B | 231.7s | 309ms | m701.music.126.net |
| tx | FLAC 691kbps | 17,628,042 B | 204.0s | 2022ms | ws.stream.qqmusic.qq.com |
| kw | 128k standard | 3,260,672 B | 203.8s | 176ms | kw-er.kuwo.cn |

四个域名不同，证明数据来自四个不同上游。

### 主控发现的一处不一致

`/v1/resolve` 实测四路都返回 `resolvedBy=kw`，即使请求不同 platform。

查代码确认：
- `internal/resolve/aggregate.go` 的 `ResolveAny` **已实现**四路并行竞速
- 但 `internal/router/router.go:633` 调用的是 `s.res.ResolveCandidates(...)`（酷我专用），未调用 `ResolveAny`
- `router.go:665` 的 `ResolvedBy` 硬编码为 "kw"

**结论：四路能力已具备，但 HTTP 入口未接线。** 属接线问题，非能力缺失。修复约 30 行。

### 未通过 / 遗留

| 项 | 状态 | 说明 |
|---|---|---|
| 酷狗 gcid_* / collection_* 歌单 | 未通过 | 需先 GET m.kugou.com/songlist/<gcid>/ 反查 specialid |
| /v1/resolve 四路接线 | 未完成 | 见上 |
| 管理页 -> 真实后端 | 未联调 | — |
| TLS | 未做 | 交给反代 |

## 验收汇总（更新）

| 项 | 状态 |
|---|---|
| A1 P0 空重建 | 通过 |
| A2 五份文档 | 通过 |
| A3 ID 数据核验 | 通过 |
| A4 P0 五项检查 | 有条件通过 |
| A5 酷狗签名 | 通过 |
| A6 服务端酷我 | 通过 28/28 |
| A7 管理页 | 通过 49/49 |
| A8 Android 骨架 | 通过（实机） |
| A9 服务端补齐 | 部分通过：四路能力已具备，HTTP 入口未接线 |
| A10 数据层 | 进行中（James） |
| A11 四平台元数据 | 进行中（Carver） |

## 验收项 A9b：四路接线修复（Volta）—— 主控独立复验

**主控重新编译并启动服务端，独立请求三次：**

| 请求 | resolvedBy | 时长 | 码率 | trace 前 3 条 |
|---|---|---|---|---|
| kg / 晴天·蓝心羽 | **kg** | 237s | 128k | kg:cc1634c26262:ok(128k,107ms), wy:1500569811:ok(128k,119ms), tx:321461032:err |
| tx / 遇见·纯音乐 | **tx** | 204s | 691k | tx:479281:ok(691k,455ms), wy:230697:paid, wy:1993211038:ok(128k,146ms) |
| kw / 稻香·周杰伦 | **kw** | 203.8s | 128k | kw:474680496:ok(128k,132ms), wy:3381542797:err, wy:-:no_source |

**关键证据**：单次请求的 trace 里同时出现 kg / wy / tx 三个平台，证明四路确实并行竞速，
且 resolvedBy 取自真实命中平台（不再是硬编码 kw）。

### 主控先前的判断有误，此处修正

我之前读 `router.go:633` 时判断「HTTP 入口未接线」。实际 `/v1/resolve` 早就指向 `handleResolveMulti`，
`handleResolve`（555 行）是**未挂载的死代码**。Volta 删除了死代码，把逻辑迁到 `resolve_multi.go`。
我看到的 `ResolvedBy: "kw"` 硬编码确实存在于死代码中，但不在实际执行路径上。

### Volta 修掉的 3 个真实缺陷（主控采信其 trace 证据）

1. 艺术家不符的候选会抢答（别人唱的《晴天》压掉蓝心羽版）
2. 「稻香(治愈版) 120s」靠歌手串含「周杰伦.」冒充 269s 原版
3. 缓存键不含平台开关，禁用酷我后仍返回酷我旧结果

改法：Order 真正决定优先级、双向时长闸门 ±12%、缓存键加 enabledSignature、resolvedBy 取自 out.Platform。

### 验收结论

**A9 / A9b 全部通过。四平台解析能力完整可用，且优先级、禁用、缓存、时长闸门均生效。**

## 验收项 A10：数据层（James）

| 项 | 结果 |
|---|---|
| Room 4 表 | 全部落地，schema 导出 v1 |
| DAO 方法 | 48 个（37 @Query + 10 写 + 1 @Transaction） |
| 外键 | playlist_songs 有 CASCADE |
| DataStore | UserPrefs 9 字段 + AuthCache 6 字段 |
| Token | EncryptedSharedPreferences (AES256-GCM) |
| Repository | 5 个，全 Flow |
| 编译 | BUILD SUCCESSFUL |
| 测试 | **47 通过 / 0 失败** |
| APK | 8,861,926 B |

## 验收项 A11：四平台元数据（Carver）

| 平台 | HTTP | 条数 | 首条 |
|---|---|---|---|
| 网易 wy | 200 | 5 | 想你就写信 (Live) |
| 酷我 kw | 200 | 10 | 晴天 |
| 酷狗 kg | 200 | 10 | 晴天 |
| QQ tx | 200 | 10 | 晴天 |

聚合 searchAll 去重后 31 首。测试 9/9 通过，真实联网。

### Carver 推翻的 3 条原分析结论

1. 酷我 search.kuwo.cn 现在返回严格 JSON（非原报告说的非标格式）
2. 酷我歌词 http 只返回 127 字节空壳，必须用 https（同路径 63 行）
3. 酷狗歌单条目没有 timelen 字段，时长在 duration

## 验收项 A12：播放层 Media3（Bernoulli）—— 主控独立复验

**这是本项目最关键的对照验收：原版 MediaSession position 恒为 0。**

主控实测（emulator-5554，安装后点击 mini player）：

```
S1  state=PLAYING(3) position=0      buffered=87379  active item id=0
S2  state=PLAYING(3) position=14961  buffered=87379  active item id=0   (+14.96 秒)
```

**结论：position 正常递增，active item id=0（非 -1）。原版缺陷已修复。**

### Bernoulli 自测的另外两组（主控未复验）

- 5970 -> 27006（+15s）
- 3779 -> 12798（后台 +10s）

### 其他验收

| 项 | 结果 |
|---|---|
| assembleDebug | BUILD SUCCESSFUL |
| 测试 | **57/57**（P5 新增 10/10） |
| MediaStyle 通知 | 标题/艺术家/进度正常 |
| 来电处理 | LOSS_TRANSIENT -> PAUSED -> 挂断恢复 PLAYING |
| 三档播放模式 | 勾选态正确 |
| logcat -b crash | 空 |

### Bernoulli 修掉的 4 个真实缺陷

1. layoutMiniInfo 默认 gone 导致 mini 播放器文字不显示
2. 模式映射三处重复，抽成 PlayMode 单一入口
3. 随机档复用顺序图标，补 ic_play_mode_shuffle
4. ic_player_pause 缺失，补线稿图标

## 总验收汇总

| 项 | 状态 | 关键证据 |
|---|---|---|
| A1 P0 空重建 | 通过 | EXIT=0, +0.83% |
| A2 五份文档 | 通过 | 1000+ 行 |
| A3 ID 数据核验 | 通过 | 42 布局/333 ID |
| A4 P0 五项检查 | 有条件通过 | ID 零缺失/签名 VERIFIED |
| A5 酷狗签名 | 通过 | 四条对照闭环 |
| A6 服务端酷我 | 通过 | 28/28 |
| A7 管理页 | 通过 | 49/49, 8 页面 |
| A8 Android 骨架 | 通过 | 实机安装运行 |
| A9 服务端补齐 | 通过 | 118/118 |
| A9b 四路接线 | 通过 | 主控实测 by=kg/tx/kw |
| A10 数据层 | 通过 | 47/47 |
| A11 四平台元数据 | 通过 | 9/9 真实联网 |
| A12 播放层 Media3 | **通过** | position 0->14961 |

## 尚未完成（P6/P7/P8/P9）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P6 | 8 个 Fragment UI 页面 | 未开始 |
| P7 | 下载与本地扫描 | 未开始 |
| P8 | 认证 Activity + 注册/登录页 | 未开始 |
| P9 | 测试打磨 | 未开始 |

## 验收项 A13：管理页 × 真实后端联调（Noether）

**51/51 断言通过。管理页首次真正连上后端，不再是 mock。**

### 根因（两个）

1. 聚合层无 CORS 响应头（withCommon 只设了 X-Content-Type-Options / Cache-Control）
2. api.js 的 autoMock 默认开启，静默降级掩盖了故障

### 修法

未改后端 CORS，而是在 server.js 加 /admin/api/* 同源反向代理；autoMock 改为默认不降级。

### 8 处字段不一致，3 处静默失效

| 问题 | 后果 |
|---|---|
| 前端 netease/qq/kugou/kuwo vs 后端 wy/tx/kg/kw | 平台开关返 200 但完全无效 |
| config.platformDisabled 按长名取键 | 四家全 undefined |
| 错误日志筛选用长名 | 筛选永远 0 条 |
| minVersion 用 1.0. + code 拼 | 设 1.0.0 回显 1.0.10000 |
| 用户详情 resolveCount 取全站总数 | 每个用户显示同一个值 |
| lastActiveAt 两处口径不同 | 同一用户两处显示不同 |

### 关键实测证据（主控采信）

```
kw 启用  -> POST /v1/resolve source=kw -> resolvedBy=kw
kw 禁用  -> POST /v1/resolve source=kw -> resolvedBy=wy
```

版本链路：PUT version 1.3.0 -> GET version 回显 1.3.0 -> GET /v1/version/latest versionCode:10300 -> GET /v1/config forceUpdate:true

监控真实数据：todayResolves 3->7，wy p50=446/p95=2265，热门搜索 [{晴天,2},{稻香,1}]

### TOTP

RFC 6238 / SHA-1 / 6 位 / 30 秒，窗口 ±1 周期。setup/init 返回 totpSecret，用 totpgen.exe 出码。

### 未执行

- 浏览器 UI 截图核对（Chrome 无头被本机策略拦截），改为 Node 加载同一份 api.js
- 初始化向导 UI、TOTP 绑定页仍无前端入口

## 验收项 A14：P6 UI 页面（Huygens）—— 主控实机验收

| 页面 | 主控实测结果 |
|---|---|
| 首页 HomeFragment | **通过**：真实榜单（飙升榜=宝船、热歌榜=我不难过）+ 热门歌单（3793.0万/3295.8万/3183.5万）+ 新歌速递（宝船/一张相片，带网易云标签） |
| 歌单 PlaylistFragment | **通过**：标题 + 链接输入框 + 解析搜索按钮 + 空态提示 |
| 播放器 PlayerFragment | **通过**：黑胶唱片 + 《晴天》EPX Music 4 Life + 00:17/03:57 进度条推进 |
| BottomNav | **通过**：4 tab 可见可切换 |
| mini player | **通过**：显示当前曲目 + 播控按钮 |
| 下载 / 我的 / 搜索 | 待细验 |

APK：15,195,747 B（从 8.9 MB 增至 15.2 MB，说明新增了大量页面代码）
进程：pid 9806，MainActivity 前台，无 FATAL

### 注意

首次 am start 后 mCurrentFocus 曾短暂回到 launcher，二次 am start -W 后正常。
属启动延迟，非崩溃（logcat 无 FATAL）。

## 验收项 A14 补充：P6 完整结论（Huygens）

| 页面 | 完成度 | 数据来源 |
|---|---|---|
| 首页 | 完成 | 真实榜单 + 热门歌单 + 新歌速递 |
| 搜索 | 完成 | 实测 81 首 |
| 歌单 | 完成 | 链接解析 + Room |
| 下载 | 完成 | Room 计数 |
| 我的 | 完成 | 账号 + 深色模式 |
| 设置 | 完成 | DataStore 三写 |
| 播放器 | 完成 | 晴天 PLAYING position=12037 |
| 导航 | 完成 | 4 tab + 3 二级页 + 播放器层 |

编译：`assembleDebug` BUILD SUCCESSFUL，EXITCODE=0
APK：15,195,747 B

### 修掉的 7 个真缺陷（其中 2 个致命）

1. **OkHttp 同步请求跑在主线程**，被 runCatching 吞掉（NetworkOnMainThreadException）
2. **网易 302 的 http:// Location 被明文策略拦截**
   - 这两个叠加导致「点歌完全不播」，日志里 15 条解析全 null

其余 5 个：布局可见性问题、模式映射重复、缺失图标等。

### P6 未完成项（转入后续阶段）

- 下载器本体（P7 进行中）
- 登录/解绑（P8 进行中）
- 歌词全屏、队列面板、收藏/评论/定时
- 酷狗 gcid_* 歌单
- 歌手页
- 物理真机与 Android 24/25

## 验收项 A15：P7 下载与本地扫描（Kierkegaard）—— 主控独立复验

### 主控实测证据（不是采信自述）

```
$ adb -s emulator-5554 shell ls -l /sdcard/Download/星轨/
total 3720
-rwxrwx--- 1 u0_a223 media_rw 3802413 2026-10-28 18:29 EPX Music 4 Life - 晴天.mp3
```

```
$ content query --uri content://media/external/audio/media
Row: 1 _id=224, _display_name=EPX Music 4 Life - 晴天.mp3, duration=237624
```

**文件真实落盘 3,802,413 B，MediaStore 已索引，duration=237624ms（约 3:58）与界面 03:57 一致。**

### Kierkegaard 实测推翻的两条假设

1. **MediaStore.Audio 拒绝 Download 目录**（只允许 Music/Ringtones 等），报 `Primary directory Download not allowed`
   - 改用 `MediaStore.Downloads` + `IS_PENDING`
2. **同一文件在 Downloads 与 Audio 双集合各有一条**
   - 首版按 name|artist|duration 去重导致计数翻倍成 2 首，改按 name|artist 后回到 1 首

### 未执行

- 进度条百分比运行中时序（代码已实现）
- 暂停/继续/重试的运行时验证
- API 24-28 legacy 路径

### ⚠️ Kierkegaard 报告的两个环境问题

1. **`build.gradle.kts` 的 `buildConfig = true` 被并行阶段回退两次**
   - 原因：多个代理同时改同一工程
   - 教训：**后续必须严格串行或严格隔离写作用域**
2. **模拟器存在 `UserInfo{10:p8auth}`**（Copernicus 创建的），验证前须 `am switch-user 0`
   - 主控已确认当前有 2 个模拟器：emulator-5554 / emulator-5558

## 当前 APK 状态

| 项 | 值 |
|---|---|
| 大小 | 15,206,473 B |
| 包名 | vip.wangxiaoming.music.debug |
| 已验证功能 | 首页/搜索/歌单/下载/我的/设置/播放器/导航 + 真实下载落盘 |

## 验收项 A16：P8 认证（Copernicus）—— 主控独立复验

### 主控实测

```
adb install   -> Success
pm clear      -> Success
am start      -> mCurrentFocus = vip.wangxiaoming.music.debug/ui.auth.AuthActivity
```

界面截图确认：星轨音乐 Logo + 「登录后同步歌单、收藏与播放记录」+ 用户名/密码框 + 登录按钮 + 注册入口 + 服务器地址 http://10.0.2.2:9443

### Copernicus 端到端实测（主控采信其报告）

| 场景 | 结果 |
|---|---|
| register | 201 pending |
| pending 登录 | 403 pending_approval + 等待对话框 |
| dbadmin 改 active 后登录 | 进入首页 |
| 我的页 | p8user / 普通用户 / 1/2 |
| 错误密码 | 401 |
| 第 5 次错误 | 429 account_locked |
| 第 3 台设备 | 409 device_limit |
| 解绑 | 2 -> 1 |
| 封禁 | 403 banned |
| 冷启动带会话 | 直接进主页 |

### 安全加固（超出原版）

- Manifest 把 launcher 改为 AuthActivity
- MainActivity 降为 exported=false，实测外部启动抛 SecurityException 被拦
- token 走加密存储 + Mutex 串行静默 refresh
- 401 强制登出广播

### 修掉的 3 个真实缺陷

1. CLEARTEXT not permitted（debug 专用 network-security-config）
2. vipType 显示 FREE（大小写比较漏判）
3. 基址覆盖文件被 pm clear 清掉（改两级兜底 + FLAG_DEBUGGABLE 判定）

### 未实测

- expired 分支
- 生产 HTTPS 域名（当前用 10.0.2.2:9443 明文）
- 真机
- API 24/25

## 阶段性总结：P0-P8 全部完成

| 阶段 | 状态 | 关键交付 |
|---|---|---|
| P0 预检 | 通过 | 空重建 EXIT=0，ID 零缺失 |
| P1 服务端 | 通过 | 118 自测 + 四路解析实测 |
| P2 骨架 | 通过 | APK 可运行 |
| P3 数据层 | 通过 | Room 4 表 + 47 测试 |
| P4 网络层 | 通过 | 四平台元数据 9 测试 |
| P5 播放层 | 通过 | position 0->14961 |
| P6 UI | 通过 | 8/8 页面 |
| P7 下载 | 通过 | 3,802,413 B 真实落盘 |
| P8 认证 | 通过 | 端到端 10 场景 |
| 管理页 | 通过 | 8 页面 + 51 联调断言 |

## 剩余工作

| 项 | 说明 |
|---|---|
| P9 测试打磨 | 边界/弱网/多设备/长稳 |
| 生产部署 | 8443 + DDNS + 反代 + TLS |
| 29 个素材替换 | launcher/splash 等 |
| 客户端接聚合层 | 当前用本地解析，需切到 /v1/resolve |
| 酷狗 gcid_* | 歌单反查未移植 |
| 歌词全屏/队列/收藏/评论/定时 | P6 遗留 |

## 验收项 A17：P9 测试打磨（Sartre）

覆盖 19 项测试，全程 `logcat -b crash` **0 条 FATAL**。

### 发现 2 个真实缺陷（均未修复，已在报告给出方案）

| 编号 | 缺陷 | 影响 | 修复方案 |
|---|---|---|---|
| D1 | 断网搜索显示「没有找到…换个关键词」 | 把网络故障说成无此歌 | SearchFragment.kt:208，约 6 行，判断 anyOk |
| D2 | 首页首屏最长 10 秒空窗 | 无骨架屏，用户以为卡住 | 加 skeleton 占位 |

### Sartre 纠正的 3 条假阳性（重要，主控采信）

1. 「暗色首页不加载」= 截图早于 20 秒，等满后数据完整
2. 「点新歌无反应」= 播放器有 1-3 秒打开延迟，media_session 证明 position 递增
3. 「封面空圆」= DEMO 曲无封面

另：`Undefined` 曲名是网易真实歌名（id=405144983），非缺陷。

### 未执行

- 横屏（模拟器拒绝旋转，且 res/ 无 layout-land/，真机极可能拉伸）
- 中文/emoji 搜索（adb 传参限制）
- API 24/25

## 验收项 A18：客户端接入聚合层（Euclid）—— 主控核验代码

### 实现

`PlayLauncher.kt` 从 5,945 B 增至 13,125 B，三处日志占位已确认存在：

```
127: Log.d(TAG, "resolve cache hit ...")
133: Log.d(TAG, "resolve via server ...")
137: Log.d(TAG, "resolve via local fallback ...")
```

### Euclid 实测（主控采信其 logcat 证据）

| 场景 | 日志 | 播放状态 |
|---|---|---|
| 服务端在线 | resolve via server | PLAYING(3) |
| 服务端停止 | resolve via local fallback | **仍在出声**，position 18801 增长 |
| 缓存命中 | resolve cache hit | 未发 HTTP 请求 |

### Euclid 发现的两条服务端事实

1. 服务端可能返回**另一个平台**的直链（请求 kg 返回 wy，trace 有 id_ignored:kg）
2. 网易直链是 http://m*.music.126.net，需在客户端升级为 https；但**不能全局升级**（酷狗 CDN 证书 CN 不匹配）

### ⚠️ 新发现的 P1 缺陷（未修，超出写作用域）

**服务端停止后冷启动会卡在登录页。**

- 原因：`AuthActivity.runStartupGate` 把 `syncProfile` 的网络失败当成不可进入
- 后果：「服务端挂了还能用」只在进程内成立，冷启动就废了
- 这与你的「无兜底」决策有冲突：你选择了无兜底，但播放层实现的是「有兜底」
- 建议：要么修 startupGate（改 5 行，网络失败时允许进入离线态），要么明确接受这个限制

# 最终交付清单

## 三个可运行工程

| 工程 | 路径 | 产物 | 规模 |
|---|---|---|---|
| Android 客户端 | `xinggui-android` | `app-debug.apk` 14.50 MB | 273 文件 / 78 kt / 133 xml |
| 聚合层服务端 | `xinggui-server` | `xinggui.exe` 16.68 MB | 32 go 文件 |
| 管理后台 | `xinggui-admin` | `index.html` + 零构建 | 16 文件 / 10 js / 3 html |

## 28 份报告（共约 9,300 行）

### A 组：原版逆向分析（7 份，2,300+ 行）
- 00-master-report.md（总报告）
- function-analysis.md（993 行）
- ui-analysis.md（1,372 行）
- web-hybrid-analysis.md（840 行）
- static-deepdive.md（525 行）
- fact-check.md（224 行）
- runtime-verification.md / 05-runtime-sampling.md

### B 组：实施设计（6 份）
- 10-p0-preflight-checklist.md / 10b-p0-results.md
- 11-layout-id-map.md
- 12-server-api-contract.md
- 13-data-layer-migration.md
- 14-task-breakdown.md

### C 组：实施报告（15 份）
- 15-kugou-signature.md（434 行，签名实测通过）
- 16-p2-skeleton-report.md
- 17-admin-report.md / 24-admin-integration.md
- 18-server-phase2.md / 21-resolve-wiring.md
- 19-p3-datalayer.md / 20-metadata-clients.md
- 22-player-media3.md
- 23-p6-ui.md
- 25-p7-download.md
- 26-p8-auth.md
- 27-client-aggregation.md
- 28-p9-testing.md

## 关键实测成果（全部由主控或子代理亲测）

| 能力 | 证据 |
|---|---|
| 四平台解析 | probe4: kg/wy/tx(FLAC 691k)/kw 四个不同域名 |
| 酷狗签名 | 四条对照闭环（正确/伪造/无签名/错分隔符） |
| MediaSession position | 原版恒 0 → 新版 0→14961 |
| 真实下载落盘 | 3,802,413 B，MediaStore _id=224 |
| 端到端认证 | 10 个场景（pending/banned/device_limit/locked） |
| 管理页联调 | 51/51 断言 |
| 服务端自测 | 118/118 |
| 客户端测试 | 57/57 |

## 尚未完成（需后续处理）

| 项 | 严重度 | 说明 |
|---|---|---|
| 服务端挂后冷启动卡登录页 | P1 | AuthActivity.runStartupGate 把网络失败当不可进入 |
| 断网搜索文案错误 | P2 | 显示「没有找到」而非「网络异常」 |
| 首页首屏 10 秒空窗 | P2 | 无骨架屏 |
| 酷狗 gcid_* 歌单 | P2 | 需 HTML 反查 specialid |
| 29 个素材未替换 | P2 | launcher/splash 仍是原版 |
| 生产部署 | P0 | 8443 + DDNS + TLS + 反代 |
| 横屏布局 | P3 | 无 layout-land/ |
| API 24/25 | P3 | 未验证 |
| 歌词全屏/队列/收藏/评论/定时 | P2 | P6 遗留 |

## ⚠️ 调度事故记录：单设备并发冲突（2026-10-29 02:00）

### 事故

主控同时把 Gauss（缺陷修复）和 Newton（图标替换）两个子代理指定到 **同一个模拟器 emulator-5554**，
而 emulator-5558 已不存在。导致：

- 两个代理交替 `adb install -r` 覆盖同一个包
- 证据：`dumpsys package` 显示 `lastUpdateTime=01:51:32`，`firstInstallTime` 出现两个值（15:42 与 18:13）
- 设备最终停在 `com.android.settings`，两边的实机验证结果都不可信

### 根因

分派任务时只写了 `-s emulator-5554`，没有为每个代理分配独立设备，
也没有在任务书里明确「禁止使用模拟器」的替代方案。

### 纠正措施（已执行）

1. `interrupt=true` 中断两个代理的实机操作，要求只做编译 + 写报告
2. 主控执行 `am force-stop com.android.settings` + `HOME`，设备回到桌面
3. **后续规则**：
   - 一台设备同一时间只给一个代理
   - 若只有一个设备，则**串行**：前一个代理完全结束后再启动下一个
   - 纯代码/资源任务（不改实机）可以并行
   - 任务书里必须写明「使用哪台设备」或「禁止使用设备」

### 当前活跃代理

| 代理 | 任务 | 设备 |
|---|---|---|
| Gauss | 冷启动缺陷 + 搜索文案 | 已禁止实机，仅编译 |
| Newton | 图标与素材 | 已禁止实机，仅编译 |

## 验收项 A19：缺陷修复（Gauss）—— 代码已验，实机待补

### D1 冷启动离线（AuthActivity.kt，+12 行）

判据设计严谨：**「令牌还在」等价于「只是连不上」**。

- 续期失败且 refreshToken 仍在  -> goMainOffline()，进离线态
- 续期失败且 refreshToken 被清  -> 停在登录页，提示重新登录
- syncProfile 返回 AuthFailure.Network 且 token 在 -> goMainOffline()
- 其他失败（pending/banned/401）-> showFailure()，停在登录页

精妙处：401/403 时 refreshTokens() 会 forceSignOut() 清掉令牌，
所以「令牌还在」必然意味着「只是网络不通」，不会把认证失败误判成可离线。

### D2 搜索网络文案（SearchFragment.kt，+4 行）

判据：`networkDown = songsRes == null || !songsRes.anyOk`

- 四家全挂 -> 「网络异常，请检查网络后重试」
- 有平台通了但没这首歌 -> 「没有找到「kw」…」

### 编译

BUILD SUCCESSFUL in 16s, EXITCODE=0, APK 13,117,025 B @ 09:50

### 实机验证：未验证（设备冲突，待主控重跑）

Gauss 主动作废了 3 条受污染观察，并停掉它起的服务端（PID 15528）。

## 验收项 A20：图标重做（Newton）—— 主控已独立验收

### Newton 的诊断（重要）

原图标是 **P6 阶段的临时占位**，文件里写着 `TODO(P6): 临时占位 foreground`。
占位版 5 处缺陷：

1. 背景深藏青 #0B1020 to #1B2A5E，深色壁纸上糊成一块
2. 7 条实心矩形，端点生硬
3. 额外画星轨圆环，圆形遮罩下裁出不规则白边
4. monochrome 复用彩色前景，Android 13 主题图标变灰块
5. mipmap-*dpi 为空，minSdk 24/25 没有位图图标

### 替换 16 项

- 重写 4：background / foreground / anydpi-v26 ic_launcher 与 ic_launcher_round
- 新增 10：5 档密度 x 方/圆 PNG
- 删除 2：mipmap-anydpi 下的两份 vector

### 删除 mipmap-anydpi 的原因（兼容性修复）

这两份 vector 被 AAPT 编译进 res/mipmap-anydpi-v21/，v21 限定符优先级高于无后缀，
在 API 24/25 上会抢占 PNG，而旧桌面不一定能渲染 vector mipmap。

### 主控核验 APK 内条目

只有 mipmap-anydpi-v26 的 2 个 xml（548 B）+ 5 档 x 2 共 10 个 PNG，
**没有 mipmap-anydpi-v21/** —— 删除确实生效。

### 主控实机验收（App Info 页）

截图 evidence/verify-appinfo.png 显示：
- 蓝色渐变圆底
- 5 条白色竖线声波，圆头端点，圆形遮罩下无裁切
- 名称「星轨音乐」

aapt dump badging 确认 application-label 为「星轨音乐」。

### 遗留项（需决策）

**首页顶部标题仍是「极光音乐」** —— 来自 Kotlin 布局绑定，不在 Newton 写作用域内。
必须改，否则 App 内显示「极光音乐」而桌面显示「星轨音乐」，品牌不一致。

## 验收项 A21：品牌一致性 + 4 条实机验收（Godel）—— 主控已看图核验

### 品牌修复：4 处残留全部替换

| 文件 | 原 | 新 |
|---|---|---|
| view_home_sticky_bar.xml:5 | 极光音乐 | 星轨 |
| view_global_auth_gate.xml:8 | 极光音乐 · 会员登录 | 星轨音乐 · 会员登录 |
| view_settings_page.xml:14 | /Download/极光音乐 | /Download/星轨 |
| view_tab_mine_page.xml:75 | 关于极光音乐 | 关于星轨音乐 |

主控复核：`app/src` 全量 .kt/.xml 中「极光音乐」命中 0 处。
（docs/layout-reference/ 保留原版存档，不参与构建）

编译：BUILD SUCCESSFUL，APK 14,675,327 B @ 10:38:36

### T1 冷启动离线 ✅（主控已看图）

截图 `xinggui-android/evidence/verify-t1-offline.png`：
- 顶部标题「星轨」
- **直接进入首页**，没有卡在登录页
- 榜单显示「暂无数据」（断网预期行为）
- 深色模式

结论：D1 修复生效，「服务端挂了还能用」不再只限于进程内。

### T3 断网搜索文案 ✅（主控已看图）

截图 `xinggui-android/evidence/verify-t3-network-error.png`：
- 搜索 `qingtian`
- 四平台 chip 全灰（网易云/酷我/酷狗/QQ音乐）
- 明确显示「**网络异常，请检查网络后重试**」
- 没有误报「没有找到」

结论：D2 修复生效。

### T2 / T4（Godel 自述，主控未逐张看图）

- T2 在线冷启动：无「离线模式」Toast，我的页读到 itest27 / 普通用户 / 设备数 2/2，宿主 9443 有 Established
- T4 首页标题节点 text="星轨"

### Godel 报告的边界

`SettingsFragment.kt:54` 显式把 `itemSettingDownloadPath` 设为 GONE
（MediaStore 不支持自定义目录，留着是死项）。
所以 view_settings_page.xml:14 那处文案用户看不到，改它只为源码语义一致。

### 收尾状态

- 设备 wifi=1 data=1 已恢复
- 9443 服务端已停止
- 仅用 -s emulator-5554，未启停模拟器
