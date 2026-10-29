# 星轨音乐（XingGui）— Android 客户端

P2 骨架阶段。包名 `vip.wangxiaoming.music`。

## 构建前必读（本机特有）

本机 `%TEMP%` 解析为 8.3 短名路径，导致 JDK 21+ 的 AF_UNIX `connect` 返回 `EINVAL`，
Gradle 会直接报 `Unable to establish loopback connection`。**每次构建前先执行**：

```powershell
$env:TEMP = "C:\Users\wangxiaoming\gtmp"
$env:TMP  = "C:\Users\wangxiaoming\gtmp"
```

## 构建

```powershell
$env:TEMP="C:\Users\wangxiaoming\gtmp"; $env:TMP=$env:TEMP
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 安装运行

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n vip.wangxiaoming.music.debug/vip.wangxiaoming.music.MainActivity
```

## 当前状态（P2）

- 可启动的空壳：底部 4 tab + mini 播放器 + 内容占位区
- 设计 token 已提取：`ui/theme/{Color,Type,Shapes,Spacing}.kt`
- 26 个业务布局已迁入（`res/layout`），资源闭包无断链
- 未接线：播放、网络、登录、下载、评论、MV

详见 `aurora-music-analysis/reports/16-p2-skeleton-report.md`。
