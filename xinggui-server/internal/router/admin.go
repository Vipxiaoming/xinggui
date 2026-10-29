package router

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"

	"xinggui/internal/auth"
	"xinggui/internal/db"
)

// ---------- 管理端中间件 ----------

type ctxKeyAdmin string

const ctxAdmin ctxKeyAdmin = "admin"

func (s *Server) adminAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimSpace(r.Header.Get("X-Admin-Token"))
		if tok == "" {
			tok = strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		}
		if tok == "" {
			fail(w, http.StatusUnauthorized, "unauthorized", "缺少管理员令牌")
			return
		}
		claims, err := s.jwt.ParseAdmin(tok)
		if err != nil {
			fail(w, http.StatusUnauthorized, "unauthorized", "管理员令牌无效或已过期")
			return
		}
		ok, err := s.db.AdminTokenValid(claims.ID)
		if err != nil || !ok {
			fail(w, http.StatusUnauthorized, "unauthorized", "管理员令牌已吊销")
			return
		}
		if _, err := s.db.GetAdmin(claims.Subject); err != nil {
			fail(w, http.StatusUnauthorized, "unauthorized", "管理员不存在")
			return
		}
		next(w, r.WithContext(withAdmin(r.Context(), claims.Subject, claims.Role)))
	}
}

func adminOf(r *http.Request) (string, string) {
	v, _ := r.Context().Value(ctxAdmin).([2]string)
	return v[0], v[1]
}

// ---------- 登录 / 登出 ----------

type adminLoginReq struct {
	Password string `json:"password"`
	TOTP     string `json:"totp"`
	Username string `json:"username"`
}

func (s *Server) handleAdminLogin(w http.ResponseWriter, r *http.Request) {
	if !s.limiter.allow("admin-login:"+clientIP(r), 5, time.Minute) {
		fail(w, http.StatusTooManyRequests, "rate_limited", "登录尝试过于频繁，请稍后再试")
		return
	}
	var req adminLoginReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusUnauthorized, "bad_credentials", "请求体格式错误")
		return
	}
	username := strings.TrimSpace(req.Username)
	if username == "" {
		username = "admin"
	}
	a, err := s.db.GetAdmin(username)
	if err != nil {
		fail(w, http.StatusUnauthorized, "bad_credentials", "用户名或密码错误")
		return
	}
	ok, verr := auth.VerifyPassword(req.Password, a.PasswordHash)
	if verr != nil || !ok {
		fail(w, http.StatusUnauthorized, "bad_credentials", "用户名或密码错误")
		return
	}
	if a.TOTPSecret != "" && !auth.VerifyTOTP(a.TOTPSecret, req.TOTP) {
		fail(w, http.StatusUnauthorized, "bad_credentials", "动态验证码错误")
		return
	}
	tok, expiresIn, jti, err := s.jwt.IssueAdmin(a.Username, a.Role)
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "签发令牌失败")
		return
	}
	nowT := time.Now()
	if err := s.db.AddAdminToken(jti, a.Username, nowT.Unix(), nowT.Add(auth.AdminTTL).Unix()); err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "令牌登记失败")
		return
	}
	s.db.TouchAdminLogin(a.Username)
	writeJSON(w, http.StatusOK, map[string]any{
		"token": tok, "expiresIn": expiresIn, "username": a.Username, "role": a.Role,
	})
}

func (s *Server) handleAdminLogout(w http.ResponseWriter, r *http.Request) {
	tok := strings.TrimSpace(r.Header.Get("X-Admin-Token"))
	if claims, err := s.jwt.ParseAdmin(tok); err == nil {
		_ = s.db.RevokeAdminToken(claims.ID)
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---------- 初始化向导 ----------

func (s *Server) handleSetupStatus(w http.ResponseWriter, r *http.Request) {
	n, err := s.db.CountAdmins()
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"initialized": n > 0,
		"internalIP":  isInternalIP(clientIP(r)),
	})
}

type setupInitReq struct {
	Username string `json:"username"`
	Password string `json:"password"`
	// Secret 允许调用方自带 TOTP 密钥；留空则自动生成并随响应返回。
	Secret string `json:"secret"`
}

func (s *Server) handleSetupInit(w http.ResponseWriter, r *http.Request) {
	n, err := s.db.CountAdmins()
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
		return
	}
	if n > 0 {
		fail(w, http.StatusConflict, "already_initialized", "管理员已存在，初始化向导已关闭")
		return
	}
	ip := clientIP(r)
	if !isInternalIP(ip) && !s.setupTokenOK(r) {
		fail(w, http.StatusForbidden, "forbidden", "仅允许内网 IP 或携带一次性初始化令牌")
		return
	}
	var req setupInitReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
		return
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	if req.Username == "" {
		req.Username = "admin"
	}
	if !validUsername(req.Username) {
		fail(w, http.StatusBadRequest, "invalid_username", "管理员用户名不合法")
		return
	}
	if len(req.Password) < 12 {
		fail(w, http.StatusBadRequest, "weak_password", "管理员密码至少 12 位")
		return
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "密码处理失败")
		return
	}
	secret := strings.TrimSpace(req.Secret)
	if secret == "" {
		secret, err = auth.NewTOTPSecret()
		if err != nil {
			fail(w, http.StatusInternalServerError, "server_error", "生成 TOTP 密钥失败")
			return
		}
	}
	if err := s.db.CreateAdmin(req.Username, hash, secret, "superadmin"); err != nil {
		fail(w, http.StatusConflict, "already_initialized", "创建管理员失败（可能已存在）")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"username":   req.Username,
		"totpSecret": secret,
		"otpauthUrl": auth.TOTPURL("星轨", req.Username, secret),
	})
}

// setupTokenOK 支持通过环境变量提供一次性初始化令牌。
func (s *Server) setupTokenOK(r *http.Request) bool {
	want := strings.TrimSpace(os.Getenv("XG_SETUP_TOKEN"))
	if want == "" {
		return false
	}
	got := strings.TrimSpace(r.Header.Get("X-Setup-Token"))
	if got == "" {
		got = strings.TrimSpace(r.URL.Query().Get("token"))
	}
	return got == want
}

// ---------- 用户管理 ----------

func (s *Server) handleAdminUsers(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		q = strings.TrimSpace(r.URL.Query().Get("search"))
	}
	status := strings.TrimSpace(r.URL.Query().Get("status"))
	page := atoiDefault(r.URL.Query().Get("page"), 1)
	size := atoiDefault(r.URL.Query().Get("pageSize"), 20)
	includeDeleted := r.URL.Query().Get("includeDeleted") == "1"

	rows, total, err := s.db.ListUsers(q, status, page, size, includeDeleted)
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
		return
	}
	items := make([]map[string]any, 0, len(rows))
	for _, u := range rows {
		devices, _ := s.db.CountDevices(u.Username)
		items = append(items, map[string]any{
			"username":     u.Username,
			"email":        u.Email,
			"status":       u.Status,
			"vipType":      u.VipType,
			"expireAt":     u.ExpireAt,
			"maxDevices":   u.MaxDevices,
			"devices":      devices,
			"registeredAt": fmtTime(u.CreatedAt),
			"lastActiveAt": fmtTime(u.LastActiveAt),
			"resolveCount": u.ResolveCount,
			"note":         u.Note,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items": items, "total": total, "page": page, "pageSize": size,
	})
}

func (s *Server) handleAdminUserDetail(w http.ResponseWriter, r *http.Request) {
	username := r.PathValue("username")
	u, err := s.db.GetUserFull(username)
	if err != nil {
		fail(w, http.StatusNotFound, "user_not_found", "用户不存在")
		return
	}
	devices, _ := s.db.ListDevices(u.Username)
	devOut := make([]map[string]any, 0, len(devices))
	for _, d := range devices {
		devOut = append(devOut, map[string]any{
			"id": d.DeviceID, "deviceId": d.DeviceID, "model": d.DeviceName,
			"createdAt": fmtTime(d.CreatedAt), "lastSeen": fmtTime(d.LastSeenAt),
		})
	}
	resolves, _ := s.db.CountUserResolves(username)
	writeJSON(w, http.StatusOK, map[string]any{
		"username":     u.Username,
		"email":        u.Email,
		"status":       u.Status,
		"role":         "user",
		"vipType":      u.VipType,
		"expireAt":     u.ExpireAt,
		"maxDevices":   u.MaxDevices,
		"devices":      len(devOut),
		"deviceList":   devOut,
		"registeredAt": fmtTime(u.CreatedAt),
		"lastActiveAt": fmtTime(lastActiveOf(devices, u.LastLoginAt)),
		"resolveCount": resolves,
		"note":         u.Note,
	})
}

func (s *Server) handleAdminUserAction(w http.ResponseWriter, r *http.Request) {
	username := r.PathValue("username")
	action := r.PathValue("action")
	adminName, _ := adminOf(r)

	if _, err := s.db.GetUserFull(username); err != nil {
		fail(w, http.StatusNotFound, "user_not_found", "用户不存在")
		return
	}

	switch action {
	case "approve":
		if err := s.db.SetStatus(username, "active"); err != nil {
			fail(w, http.StatusInternalServerError, "server_error", "更新失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "status": "active"})
	case "reject":
		_ = s.db.SetStatus(username, "rejected")
		_ = s.db.BumpTokenVersion(username)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "status": "rejected"})
	case "ban":
		_ = s.db.SetStatus(username, "banned")
		_ = s.db.BumpTokenVersion(username)
		_ = s.db.RevokeAllDevices(username)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "status": "banned"})
	case "unban":
		if err := s.db.SetStatus(username, "active"); err != nil {
			fail(w, http.StatusInternalServerError, "server_error", "更新失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "status": "active"})
	case "reset-password":
		var req struct {
			Password string `json:"password"`
		}
		_ = decodeJSON(r, &req)
		pwd := strings.TrimSpace(req.Password)
		if pwd == "" {
			pwd = randomPassword()
		}
		if len(pwd) < 8 {
			fail(w, http.StatusBadRequest, "weak_password", "新密码至少 8 位")
			return
		}
		hash, err := auth.HashPassword(pwd)
		if err != nil {
			fail(w, http.StatusInternalServerError, "server_error", "密码处理失败")
			return
		}
		// UpdateUserPassword 内部 token_version+1，等于吊销该用户全部 refresh token
		if err := s.db.UpdateUserPassword(username, hash); err != nil {
			fail(w, http.StatusInternalServerError, "server_error", "更新失败")
			return
		}
		s.db.LogError("warn", "", "admin_reset_password", "管理员 "+adminName+" 重置了 "+username+" 的密码", adminName)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "tempPassword": pwd})
	case "unbind":
		var req struct {
			DeviceID string `json:"deviceId"`
		}
		if err := decodeJSON(r, &req); err != nil || strings.TrimSpace(req.DeviceID) == "" {
			fail(w, http.StatusBadRequest, "invalid_request", "缺少 deviceId")
			return
		}
		if err := s.db.RevokeDevice(username, strings.TrimSpace(req.DeviceID)); err != nil {
			fail(w, http.StatusNotFound, "device_not_found", "设备不存在或已解绑")
			return
		}
		n, _ := s.db.CountDevices(username)
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "devices": n})
	case "expire":
		var req struct {
			ExpireAt any `json:"expireAt"`
		}
		if err := decodeJSON(r, &req); err != nil {
			fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
			return
		}
		ts := parseExpire(req.ExpireAt)
		if err := s.db.SetUserExpire(username, ts); err != nil {
			fail(w, http.StatusInternalServerError, "server_error", "更新失败")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "expireAt": ts})
	default:
		fail(w, http.StatusNotFound, "unknown_action", "未知操作")
	}
}

func (s *Server) handleAdminDeleteUser(w http.ResponseWriter, r *http.Request) {
	username := r.PathValue("username")
	if err := s.db.SoftDeleteUser(username); err != nil {
		fail(w, http.StatusNotFound, "user_not_found", "用户不存在")
		return
	}
	_ = s.db.RevokeAllDevices(username)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---------- 用户数据浏览 ----------

func (s *Server) handleAdminUserData(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		username := r.PathValue("username")
		if _, err := s.db.GetUserFull(username); err != nil {
			fail(w, http.StatusNotFound, "user_not_found", "用户不存在")
			return
		}
		var (
			items []map[string]any
			err   error
		)
		switch kind {
		case "favorites":
			items, err = s.db.Favorites(username)
		case "playlists":
			items, err = s.db.Playlists(username)
		case "history":
			items, err = s.db.History(username, 200)
		case "downloads":
			items, err = s.db.Downloads(username)
		case "searches":
			items, err = s.db.Searches(username, 200)
		}
		if err != nil {
			fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"items": items, "total": len(items)})
	}
}

// ---------- 系统配置 ----------

func (s *Server) handleAdminGetConfig(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.sysConfigSnapshot())
}

func (s *Server) handleAdminPutConfig(w http.ResponseWriter, r *http.Request) {
	var req map[string]any
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
		return
	}
	b, err := json.Marshal(req)
	if err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "序列化失败")
		return
	}
	if err := s.db.SetSetting("sys_config", string(b)); err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "保存失败")
		return
	}
	s.applySysConfig(req)
	writeJSON(w, http.StatusOK, s.sysConfigSnapshot())
}

func (s *Server) handleAdminPlatformToggle(w http.ResponseWriter, r *http.Request) {
	name := NormalizePlatform(r.PathValue("name"))
	if name == "" {
		fail(w, http.StatusBadRequest, "invalid_request", "未知平台")
		return
	}
	var req struct {
		Disabled *bool `json:"disabled"`
	}
	if err := decodeJSON(r, &req); err != nil || req.Disabled == nil {
		fail(w, http.StatusBadRequest, "invalid_request", "缺少 disabled 字段")
		return
	}
	cfg := s.sysConfigSnapshot()
	disabled, _ := cfg["platformDisabled"].(map[string]bool)
	if disabled == nil {
		disabled = map[string]bool{}
	}
	disabled[name] = *req.Disabled
	cfg["platformDisabled"] = disabled
	b, _ := json.Marshal(cfg)
	_ = s.db.SetSetting("sys_config", string(b))
	s.applySysConfig(cfg)
	writeJSON(w, http.StatusOK, map[string]any{"platform": name, "disabled": *req.Disabled})
}

func (s *Server) handleAdminGetVersion(w http.ResponseWriter, r *http.Request) {
	v, err := s.db.LatestVersion()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"latestVersion": "1.0.0", "minVersion": "1.0.0",
			"updateUrl": "", "updateSha256": "", "updateNotes": "",
			"forceUpdate": false, "publishedAt": "",
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"latestVersion": v.VersionName,
		"minVersion":    versionNameOf(v.MinVersionCode),
		"versionCode":   v.VersionCode,
		"updateUrl":     v.URL,
		"updateSha256":  v.SHA256,
		"updateNotes":   v.Notes,
		"forceUpdate":   v.Force,
		"publishedAt":   fmtTime(v.CreatedAt),
	})
}

func (s *Server) handleAdminPutVersion(w http.ResponseWriter, r *http.Request) {
	var req struct {
		LatestVersion string `json:"latestVersion"`
		MinVersion    string `json:"minVersion"`
		VersionCode   int    `json:"versionCode"`
		UpdateURL     string `json:"updateUrl"`
		UpdateSHA256  string `json:"updateSha256"`
		UpdateNotes   string `json:"updateNotes"`
		ForceUpdate   bool   `json:"forceUpdate"`
	}
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
		return
	}
	if strings.TrimSpace(req.LatestVersion) == "" {
		fail(w, http.StatusBadRequest, "invalid_request", "latestVersion 不能为空")
		return
	}
	code := req.VersionCode
	if code == 0 {
		code = versionCodeOf(req.LatestVersion)
	}
	minCode := versionCodeOf(req.MinVersion)
	if minCode == 0 {
		minCode = 1
	}
	if req.UpdateURL != "" && req.UpdateSHA256 == "" {
		fail(w, http.StatusBadRequest, "missing_sha256", "填写下载地址时必须同时提供 SHA-256")
		return
	}
	v := db.Version{
		VersionCode: code, VersionName: req.LatestVersion, URL: req.UpdateURL,
		SHA256: strings.ToLower(strings.TrimSpace(req.UpdateSHA256)), Notes: req.UpdateNotes,
		Force: req.ForceUpdate, MinVersionCode: minCode, Published: true,
	}
	if err := s.db.UpsertVersion(v); err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "保存失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "versionCode": code})
}

// ---------- 监控 ----------

func (s *Server) handleAdminOverview(w http.ResponseWriter, r *http.Request) {
	nowT := time.Now()
	todayStart := time.Date(nowT.Year(), nowT.Month(), nowT.Day(), 0, 0, 0, 0, nowT.Location()).Unix()
	yStart := todayStart - 86400

	totalUsers, _ := s.db.CountUsers("")
	pending, _ := s.db.CountUsers("pending")
	newToday, _ := s.db.CountUsersSince(todayStart)
	activeDevices, _ := s.db.CountActiveDevices(nowT.Add(-5 * time.Minute).Unix())
	todayResolves, _ := s.db.CountResolvesSince(todayStart)
	yResolves, _ := s.db.CountResolvesSince(yStart)
	trend, _ := s.db.ResolveTrend(30)

	delta := 0.0
	if yResolves > 0 {
		delta = float64(todayResolves-yResolves) / float64(yResolves) * 100
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"totalUsers":       totalUsers,
		"onlineUsers":      activeDevices,
		"activeDevices":    activeDevices,
		"todayResolves":    todayResolves,
		"newUsersToday":    newToday,
		"pendingApprovals": pending,
		"trend":            trend,
		"deltas":           map[string]any{"users": 0, "online": 0, "resolves": round1(delta)},
	})
}

func (s *Server) handleAdminStatsResolve(w http.ResponseWriter, r *http.Request) {
	stats, err := s.db.ResolveStats(time.Now().Add(-24 * time.Hour).Unix())
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
		return
	}
	items := make([]map[string]any, 0, len(stats))
	for _, p := range stats {
		rate := 0.0
		if p.Total > 0 {
			rate = float64(p.OK) / float64(p.Total)
		}
		items = append(items, map[string]any{
			"platform": p.Platform, "success": rate, "total": p.Total,
			"p50": p.P50, "p95": p.P95,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (s *Server) handleAdminStatsErrors(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit := atoiDefault(q.Get("limit"), 500)
	offset := atoiDefault(q.Get("offset"), 0)
	rows, total, err := s.db.RecentErrors(limit, offset, q.Get("level"), q.Get("platform"), q.Get("q"))
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
		return
	}
	items := make([]map[string]any, 0, len(rows))
	for _, e := range rows {
		items = append(items, map[string]any{
			"at": fmtTime(e.At), "level": e.Level, "platform": e.Platform,
			"code": e.Code, "message": e.Message, "user": e.Username,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "total": total})
}

func (s *Server) handleAdminStatsSearches(w http.ResponseWriter, r *http.Request) {
	items, err := s.db.TopSearches(50)
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

func (s *Server) handleAdminHealth(w http.ResponseWriter, r *http.Request) {
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	writeJSON(w, http.StatusOK, map[string]any{
		"uptime":      time.Since(startTime).Round(time.Second).String(),
		"cpu":         cpuPercent(),
		"mem":         round1(float64(ms.Alloc) / 1024 / 1024),
		"disk":        diskPercent(),
		"dbLatencyMs": s.dbPingMS(),
		"goroutines":  runtime.NumGoroutine(),
		"cache":       s.cache.Len(),
	})
}

// ---------- 小工具 ----------

// versionNameOf 把 versionCode 反解成可读版本号，供管理页回显。
// code<=1 表示「未设置最低版本」，按 1.0.0 处理。
func versionNameOf(code int) string {
	if code <= 1 {
		return "1.0.0"
	}
	return fmt.Sprintf("%d.%d.%d", code/10000, (code/100)%100, code%100)
}

// lastActiveOf 取设备最后在线与登录时间的较大者，与用户列表列口径保持一致。
func lastActiveOf(devices []db.Device, lastLogin int64) int64 {
	max := lastLogin
	for _, d := range devices {
		if d.LastSeenAt > max {
			max = d.LastSeenAt
		}
	}
	return max
}


func clientIP(r *http.Request) string {
	if v := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); v != "" {
		if i := strings.Index(v, ","); i > 0 {
			v = v[:i]
		}
		return strings.TrimSpace(v)
	}
	if v := strings.TrimSpace(r.Header.Get("X-Real-IP")); v != "" {
		return v
	}
	host, _, err := net.SplitHostPort(strings.TrimSpace(r.RemoteAddr))
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func isInternalIP(ip string) bool {
	parsed := net.ParseIP(strings.TrimSpace(ip))
	if parsed == nil {
		return false
	}
	return parsed.IsLoopback() || parsed.IsPrivate() || parsed.IsLinkLocalUnicast()
}

func atoiDefault(s string, def int) int {
	if n, err := strconv.Atoi(strings.TrimSpace(s)); err == nil {
		return n
	}
	return def
}

func parseExpire(v any) int64 {
	switch t := v.(type) {
	case nil:
		return 0
	case float64:
		return int64(t)
	case string:
		s := strings.TrimSpace(t)
		if s == "" {
			return 0
		}
		for _, layout := range []string{"2006-01-02", "2006-01-02 15:04", time.RFC3339} {
			if d, err := time.ParseInLocation(layout, s, time.Local); err == nil {
				return d.Add(24*time.Hour - time.Second).Unix()
			}
		}
		if n, err := strconv.ParseInt(s, 10, 64); err == nil {
			return n
		}
	}
	return 0
}

func versionCodeOf(name string) int {
	parts := strings.Split(strings.TrimSpace(name), ".")
	code := 0
	for i := 0; i < len(parts) && i < 3; i++ {
		n, _ := strconv.Atoi(parts[i])
		code = code*100 + n
	}
	return code
}

func randomPassword() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
	buf := make([]byte, 14)
	seed := time.Now().UnixNano()
	for i := range buf {
		seed = seed*6364136223846793005 + 1442695040888963407
		buf[i] = alphabet[int(uint64(seed)>>33)%len(alphabet)]
	}
	return string(buf)
}
