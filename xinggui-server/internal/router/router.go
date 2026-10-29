package router

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"xinggui/internal/auth"
	"xinggui/internal/cache"
	"xinggui/internal/config"
	"xinggui/internal/db"
	"xinggui/internal/resolve"
)

type failEntry struct {
	first time.Time
	count int
}

type Server struct {
	cfg   *config.Config
	db    *db.DB
	jwt   *auth.Manager
	res   *resolve.Resolver
	cache *cache.LRU
	mux   *http.ServeMux

	limiter  *rateLimiter
	failMu   sync.Mutex
	loginFails map[string]*failEntry

	disabledMu       sync.RWMutex
	disabledPlatforms map[string]bool
	cfgMaxDevices    int
}

func New(cfg *config.Config, database *db.DB) *Server {
	s := &Server{
		cfg:               cfg,
		db:                database,
		jwt:               auth.NewManager(cfg.JWTSecret),
		res:               resolve.NewResolver(),
		cache:             cache.New(2048),
		mux:               http.NewServeMux(),
		limiter:           newRateLimiter(),
		loginFails:        map[string]*failEntry{},
		disabledPlatforms: map[string]bool{},
	}
	s.reloadConfigFromDB()
	go func() {
		t := time.NewTicker(30 * time.Minute)
		defer t.Stop()
		for range t.C {
			s.limiter.sweep()
		}
	}()
	s.routes()
	return s
}

func (s *Server) Handler() http.Handler { return s.withCommon(s.mux) }

func (s *Server) routes() {
	s.mux.HandleFunc("GET /v1/health", s.handleHealth)
	s.mux.HandleFunc("GET /v1/config", s.handleConfig)

	s.mux.HandleFunc("POST /v1/auth/register", s.handleRegister)
	s.mux.HandleFunc("POST /v1/auth/login", s.handleLogin)
	s.mux.HandleFunc("POST /v1/auth/refresh", s.handleRefresh)
	s.mux.HandleFunc("POST /v1/auth/logout", s.auth(s.handleLogout))
	s.mux.HandleFunc("GET /v1/auth/me", s.auth(s.handleMe))
	s.mux.HandleFunc("POST /v1/auth/unbind", s.auth(s.handleUnbind))

	// 四路竞速解析（第二阶段的正式实现），旧单路实现保留在 handleResolve 供回退。
	s.mux.HandleFunc("POST /v1/resolve", s.auth(s.handleResolveMulti))
	s.mux.HandleFunc("POST /v1/search/log", s.auth(s.handleSearchLog))

	// 歌单 / 同步 / 版本
	s.mux.HandleFunc("POST /v1/playlist/parse", s.auth(s.handlePlaylistParse))
	s.mux.HandleFunc("POST /v1/playlist/songs", s.auth(s.handlePlaylistSongs))
	s.mux.HandleFunc("POST /v1/sync/push", s.auth(s.handleSyncPush))
	s.mux.HandleFunc("GET /v1/sync/pull", s.auth(s.handleSyncPull))
	s.mux.HandleFunc("GET /v1/version/latest", s.handleVersionLatest)

	// 管理端
	s.mux.HandleFunc("POST /admin/api/login", s.handleAdminLogin)
	s.mux.HandleFunc("POST /admin/api/logout", s.handleAdminLogout)
	s.mux.HandleFunc("GET /admin/api/setup/status", s.handleSetupStatus)
	s.mux.HandleFunc("POST /admin/api/setup/init", s.handleSetupInit)

	s.mux.HandleFunc("GET /admin/api/users", s.adminAuth(s.handleAdminUsers))
	s.mux.HandleFunc("GET /admin/api/users/{username}", s.adminAuth(s.handleAdminUserDetail))
	s.mux.HandleFunc("DELETE /admin/api/users/{username}", s.adminAuth(s.handleAdminDeleteUser))
	s.mux.HandleFunc("POST /admin/api/users/{username}/{action}", s.adminAuth(s.handleAdminUserAction))

	s.mux.HandleFunc("GET /admin/api/users/{username}/favorites", s.adminAuth(s.handleAdminUserData("favorites")))
	s.mux.HandleFunc("GET /admin/api/users/{username}/playlists", s.adminAuth(s.handleAdminUserData("playlists")))
	s.mux.HandleFunc("GET /admin/api/users/{username}/history", s.adminAuth(s.handleAdminUserData("history")))
	s.mux.HandleFunc("GET /admin/api/users/{username}/downloads", s.adminAuth(s.handleAdminUserData("downloads")))
	s.mux.HandleFunc("GET /admin/api/users/{username}/searches", s.adminAuth(s.handleAdminUserData("searches")))

	s.mux.HandleFunc("GET /admin/api/config", s.adminAuth(s.handleAdminGetConfig))
	s.mux.HandleFunc("PUT /admin/api/config", s.adminAuth(s.handleAdminPutConfig))
	s.mux.HandleFunc("POST /admin/api/config/platforms/{name}/disable", s.adminAuth(s.handleAdminPlatformToggle))
	s.mux.HandleFunc("GET /admin/api/version", s.adminAuth(s.handleAdminGetVersion))
	s.mux.HandleFunc("PUT /admin/api/version", s.adminAuth(s.handleAdminPutVersion))

	s.mux.HandleFunc("GET /admin/api/stats/overview", s.adminAuth(s.handleAdminOverview))
	s.mux.HandleFunc("GET /admin/api/stats/resolve", s.adminAuth(s.handleAdminStatsResolve))
	s.mux.HandleFunc("GET /admin/api/stats/errors", s.adminAuth(s.handleAdminStatsErrors))
	s.mux.HandleFunc("GET /admin/api/stats/searches", s.adminAuth(s.handleAdminStatsSearches))
	s.mux.HandleFunc("GET /admin/api/health", s.adminAuth(s.handleAdminHealth))

	// 管理页静态资源：/admin 精确匹配后 301 到 /admin/，/admin/ 前缀交给 FileServer。
	// 方法限定 GET 保证 POST /admin/xxx 不会被静态服务吞掉；
	// /admin/api/* 的模式更具体，按 ServeMux 优先级仍由上面的 API 路由处理。
	s.mux.HandleFunc("GET /admin", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/admin/", http.StatusMovedPermanently)
	})
	s.mux.Handle("GET /admin/", http.StripPrefix("/admin/", http.FileServer(http.Dir(s.cfg.AdminDir))))
}

// ---------- 中间件与通用响应 ----------

// corsAllowOrigin 判断请求 Origin 是否在允许清单内。
// 清单来自 XG_CORS_ORIGINS（逗号分隔）；未配置时只放行本机来源，
// 避免把管理端跨域能力默认开放给任意站点。
func corsAllowOrigin(origin string) bool {
	origin = strings.TrimSpace(origin)
	if origin == "" {
		return false
	}
	if allowed := strings.TrimSpace(os.Getenv("XG_CORS_ORIGINS")); allowed != "" {
		for _, v := range strings.Split(allowed, ",") {
			if strings.EqualFold(strings.TrimSpace(v), origin) {
				return true
			}
		}
		return false
	}
	u, err := url.Parse(origin)
	if err != nil || u.Scheme == "" || u.Hostname() == "" {
		return false
	}
	switch u.Hostname() {
	case "127.0.0.1", "localhost", "::1":
		return true
	}
	return false
}

func (s *Server) withCommon(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		// 管理页静态资源允许浏览器短时缓存；API 与其余接口一律 no-store。
		if strings.HasPrefix(r.URL.Path, "/admin/") && !strings.HasPrefix(r.URL.Path, "/admin/api") {
			h.Set("Cache-Control", "public, max-age=300")
		} else {
			h.Set("Cache-Control", "no-store")
		}
		// CORS：管理页与聚合层常不同端口，浏览器会先发预检。
		// 只有 Origin 命中允许清单才回写 ACAO，并始终带 Vary: Origin。
		if origin := strings.TrimSpace(r.Header.Get("Origin")); origin != "" {
			h.Add("Vary", "Origin")
			if corsAllowOrigin(origin) {
				h.Set("Access-Control-Allow-Origin", origin)
				h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
				h.Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Admin-Token, X-Setup-Token")
				h.Set("Access-Control-Max-Age", "600")
			}
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, code int, errCode, msg string) {
	writeJSON(w, code, map[string]string{"error": errCode, "message": msg})
}

// decodeJSON 宽松解析：忽略客户端多带的字段。
// 契约驱动下客户端可能随版本追加字段，严格模式会造成无谓的 400。
func decodeJSON(r *http.Request, v any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 2<<20))
	return dec.Decode(v)
}

// ---------- 鉴权 ----------

type ctxKey string

const ctxUser ctxKey = "user"

func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		if !strings.HasPrefix(h, "Bearer ") {
			fail(w, http.StatusUnauthorized, "token_expired", "缺少访问令牌")
			return
		}
		claims, err := s.jwt.Parse(strings.TrimSpace(h[len("Bearer "):]), auth.TypeAccess)
		if err != nil {
			fail(w, http.StatusUnauthorized, "token_expired", "访问令牌无效或已过期")
			return
		}
		u, err := s.db.GetUser(claims.Subject)
		if err != nil {
			fail(w, http.StatusUnauthorized, "token_expired", "账号不存在")
			return
		}
		if claims.TokenVersion != u.TokenVersion {
			fail(w, http.StatusUnauthorized, "token_expired", "令牌已失效，请重新登录")
			return
		}
		if u.Status != "active" {
			code := "banned"
			if u.Status == "pending" {
				code = "pending_approval"
			}
			fail(w, http.StatusForbidden, code, "账号状态异常")
			return
		}
		if u.ExpireAt > 0 && u.ExpireAt < time.Now().Unix() {
			fail(w, http.StatusForbidden, "expired", "会员已到期")
			return
		}
		s.db.TouchDevice(u.Username, claims.DeviceID)
		next(w, r.WithContext(context.WithValue(r.Context(), ctxUser, u)))
	}
}

func userOf(r *http.Request) *db.User {
	u, _ := r.Context().Value(ctxUser).(*db.User)
	return u
}

// ---------- 健康检查与配置 ----------

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"time":   time.Now().Unix(),
		"cache":  s.cache.Len(),
	})
}

func (s *Server) handleConfig(w http.ResponseWriter, r *http.Request) {
	cfg := s.sysConfigSnapshot()
	disabled := []string{}
	for _, p := range []string{"wy", "tx", "kg", "kw"} {
		if s.isPlatformDisabled(p) {
			disabled = append(disabled, p)
		}
	}
	appEnabled := true
	if v, ok := cfg["appEnabled"].(bool); ok {
		appEnabled = v
	}
	stopNotice, _ := cfg["stopNotice"].(string)
	announcement, _ := cfg["announcement"].(string)
	forceUpdate := false
	if v, ok := cfg["forceUpdate"].(bool); ok {
		forceUpdate = v
	}
	maxDevices := s.maxDevices()
	if v, ok := numOf(cfg["maxDevices"]); ok && v > 0 {
		maxDevices = v
	}

	minVersion, latestVersion := 1, 1
	updateURL, updateSHA, updateNotes := "", "", ""
	if ver, err := s.db.LatestVersion(); err == nil {
		latestVersion = ver.VersionCode
		minVersion = ver.MinVersionCode
		updateURL, updateSHA, updateNotes = ver.URL, ver.SHA256, ver.Notes
		if ver.Force {
			forceUpdate = true
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"appEnabled":        appEnabled,
		"minVersion":        minVersion,
		"latestVersion":     latestVersion,
		"maxDevices":        maxDevices,
		"stopNotice":        stopNotice,
		"announcement":      announcement,
		"resolveEndpoint":   s.cfg.BaseURL + "/v1/resolve",
		"disabledPlatforms": disabled,
		"forceUpdate":       forceUpdate,
		"updateUrl":         updateURL,
		"updateSha256":      updateSHA,
		"updateNotes":       updateNotes,
	})
}
// ---------- 注册 / 登录 ----------

type registerReq struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
}

func validUsername(v string) bool {
	if len(v) < 3 || len(v) > 32 {
		return false
	}
	for _, r := range v {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '_', r == '-':
		default:
			return false
		}
	}
	return true
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req registerReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_username", "请求体格式错误")
		return
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	if !validUsername(req.Username) {
		fail(w, http.StatusBadRequest, "invalid_username", "用户名需 3-32 位字母、数字、下划线或连字符")
		return
	}
	if len(req.Password) < 8 {
		fail(w, http.StatusBadRequest, "weak_password", "密码至少 8 位")
		return
	}
	if _, err := s.db.GetUser(req.Username); err == nil {
		fail(w, http.StatusConflict, "username_taken", "用户名已被占用")
		return
	} else if !errors.Is(err, db.ErrNotFound) {
		fail(w, http.StatusInternalServerError, "upstream_error", "数据库异常")
		return
	}
	cfg := s.sysConfigSnapshot()
	if v, ok := cfg["registrationOpen"].(bool); ok && !v {
		fail(w, http.StatusServiceUnavailable, "registration_closed", "当前未开放注册")
		return
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		fail(w, http.StatusInternalServerError, "upstream_error", "密码处理失败")
		return
	}
	if err := s.db.CreateUser(req.Username, hash); err != nil {
		fail(w, http.StatusConflict, "username_taken", "用户名已被占用")
		return
	}
	requireApproval := true
	if v, ok := cfg["requireApproval"].(bool); ok {
		requireApproval = v
	}
	if !requireApproval {
		if err := s.db.SetStatus(req.Username, "active"); err != nil {
			fail(w, http.StatusInternalServerError, "upstream_error", "账号初始化失败")
			return
		}
		writeJSON(w, http.StatusCreated, map[string]string{
			"status": "active", "message": "注册成功，可直接登录",
		})
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{
		"status":  "pending",
		"message": "注册成功，等待管理员审核",
	})
}

type loginReq struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusUnauthorized, "bad_credentials", "用户名或密码错误")
		return
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	if !s.loginGuard(w, r, req.Username) {
		return
	}
	u, err := s.db.GetUser(req.Username)
	if err != nil {
		if s.noteLoginFailWithIP(r, req.Username) {
			fail(w, http.StatusTooManyRequests, "rate_limited", "请求过于频繁，请稍后再试")
			return
		}
		fail(w, http.StatusUnauthorized, "bad_credentials", "用户名或密码错误")
		return
	}
	ok, verr := auth.VerifyPassword(req.Password, u.PasswordHash)
	if verr != nil || !ok {
		if s.noteLoginFailWithIP(r, req.Username) {
			fail(w, http.StatusTooManyRequests, "rate_limited", "请求过于频繁，请稍后再试")
			return
		}
		fail(w, http.StatusUnauthorized, "bad_credentials", "用户名或密码错误")
		return
	}
	s.noteLoginOK(req.Username)
	switch u.Status {
	case "pending":
		fail(w, http.StatusForbidden, "pending_approval", "账号待管理员审核")
		return
	case "banned":
		fail(w, http.StatusForbidden, "banned", "账号已被封禁")
		return
	case "rejected":
		fail(w, http.StatusForbidden, "banned", "注册申请已被拒绝")
		return
	case "active":
	default:
		fail(w, http.StatusForbidden, "banned", "账号状态异常")
		return
	}
	if u.ExpireAt > 0 && u.ExpireAt < time.Now().Unix() {
		fail(w, http.StatusForbidden, "expired", "会员已到期")
		return
	}
	deviceID := strings.TrimSpace(req.DeviceID)
	if deviceID == "" {
		deviceID = "unknown"
	}
	if _, err := s.db.FindDevice(u.Username, deviceID); err != nil {
		n, cerr := s.db.CountDevices(u.Username)
		if cerr != nil {
			fail(w, http.StatusInternalServerError, "upstream_error", "数据库异常")
			return
		}
		if n >= s.maxDevices() {
			fail(w, http.StatusConflict, "device_limit", "设备数已达上限，请先解绑其他设备")
			return
		}
		if err := s.db.AddDevice(u.Username, deviceID, strings.TrimSpace(req.DeviceName)); err != nil {
			fail(w, http.StatusInternalServerError, "upstream_error", "设备登记失败")
			return
		}
	} else {
		s.db.TouchDevice(u.Username, deviceID)
	}

	access, refresh, expiresIn, err := s.jwt.Issue(u.Username, deviceID, u.TokenVersion)
	if err != nil {
		fail(w, http.StatusInternalServerError, "upstream_error", "签发令牌失败")
		return
	}
	s.db.TouchUserLogin(u.Username)
	count, _ := s.db.CountDevices(u.Username)
	writeJSON(w, http.StatusOK, map[string]any{
		"accessToken":  access,
		"refreshToken": refresh,
		"expiresIn":    expiresIn,
		"user": map[string]any{
			"username":    u.Username,
			"status":      u.Status,
			"vipType":     u.VipType,
			"expireAt":    u.ExpireAt,
			"deviceCount": count,
			"maxDevices":  u.MaxDevices,
		},
	})
}

type refreshReq struct {
	RefreshToken string `json:"refreshToken"`
}

func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req refreshReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusUnauthorized, "token_expired", "刷新令牌缺失")
		return
	}
	claims, err := s.jwt.Parse(strings.TrimSpace(req.RefreshToken), auth.TypeRefresh)
	if err != nil {
		fail(w, http.StatusUnauthorized, "token_expired", "刷新令牌无效或已过期")
		return
	}
	u, err := s.db.GetUser(claims.Subject)
	if err != nil {
		fail(w, http.StatusUnauthorized, "token_expired", "账号不存在")
		return
	}
	if claims.TokenVersion != u.TokenVersion || u.Status != "active" {
		fail(w, http.StatusUnauthorized, "token_expired", "令牌已失效，请重新登录")
		return
	}
	access, refresh, expiresIn, err := s.jwt.Issue(u.Username, claims.DeviceID, u.TokenVersion)
	if err != nil {
		fail(w, http.StatusInternalServerError, "upstream_error", "签发令牌失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"accessToken":  access,
		"refreshToken": refresh,
		"expiresIn":    expiresIn,
	})
}

// ---------- 用户信息 / 解绑 ----------

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	u := userOf(r)
	claims, _ := s.jwt.Parse(strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")), auth.TypeAccess)
	count, _ := s.db.CountDevices(u.Username)
	devices, _ := s.db.ListDevices(u.Username)
	out := make([]map[string]any, 0, len(devices))
	for _, d := range devices {
		out = append(out, map[string]any{
			"deviceId":   d.DeviceID,
			"deviceName": d.DeviceName,
			"createdAt":  d.CreatedAt,
			"lastSeenAt": d.LastSeenAt,
			"current":    claims != nil && claims.DeviceID == d.DeviceID,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"username":    u.Username,
		"status":      u.Status,
		"vipType":     u.VipType,
		"expireAt":    u.ExpireAt,
		"deviceCount": count,
		"maxDevices":  u.MaxDevices,
		"devices":     out,
	})
}

type unbindReq struct {
	DeviceID string `json:"deviceId"`
}

func (s *Server) handleUnbind(w http.ResponseWriter, r *http.Request) {
	u := userOf(r)
	var req unbindReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_username", "请求体格式错误")
		return
	}
	req.DeviceID = strings.TrimSpace(req.DeviceID)
	if req.DeviceID == "" {
		fail(w, http.StatusBadRequest, "invalid_username", "缺少 deviceId")
		return
	}
	claims, _ := s.jwt.Parse(strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")), auth.TypeAccess)
	if claims != nil && claims.DeviceID == req.DeviceID {
		fail(w, http.StatusBadRequest, "forbidden", "不能解绑当前正在使用的设备")
		return
	}
	if err := s.db.RevokeDevice(u.Username, req.DeviceID); err != nil {
		fail(w, http.StatusNotFound, "forbidden", "设备不存在或已解绑")
		return
	}
	count, _ := s.db.CountDevices(u.Username)
	writeJSON(w, http.StatusOK, map[string]any{"deviceCount": count, "maxDevices": u.MaxDevices})
}


// filterHits 剔除明显不匹配的候选：歌名不含请求名，或艺术家明显不符。
// 目标是不让“晴天”命中别的歌、也不让 A 歌手的版本顶替 B 歌手。
func filterHits(hits []resolve.SearchHit, wantName, wantArtist string) (kept []resolve.SearchHit, filtered int) {
	n := strings.ToLower(normalizeName(wantName))
	a := strings.ToLower(normalizeName(wantArtist))
	for _, h := range hits {
		hn := strings.ToLower(normalizeName(h.Name))
		ha := strings.ToLower(normalizeName(h.Artist))
		if n != "" && !strings.Contains(hn, n) {
			filtered++
			continue
		}
		if a != "" && ha != "" && !strings.Contains(ha, a) && !strings.Contains(a, ha) {
			filtered++
			continue
		}
		kept = append(kept, h)
	}
	return kept, filtered
}

// normalizeName 去掉括号补充说明（如“晴天 (KTV版伴奏)”）、空白与常见修饰。
func normalizeName(s string) string {
	s = strings.TrimSpace(s)
	for _, open := range []string{"(", "（", "[", "【"} {
		if i := strings.Index(s, open); i > 0 {
			s = s[:i]
		}
	}
	return strings.Join(strings.Fields(s), "")
}

var badNameWords = []string{"伴奏", "KTV", "ktv", "翻唱", "cover", "Cover", "DJ版", "dj版", "铃声", "纯音乐", "钢琴版", "吉他版"}

func containsBadWord(s string) bool {
	for _, w := range badNameWords {
		if strings.Contains(s, w) {
			return true
		}
	}
	return false
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func round1(v float64) float64 {
	return float64(int64(v*10+0.5)) / 10
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

type searchLogReq struct {
	Keyword string `json:"keyword"`
}

func (s *Server) handleSearchLog(w http.ResponseWriter, r *http.Request) {
	u := userOf(r)
	var req searchLogReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_username", "请求体格式错误")
		return
	}
	kw := strings.TrimSpace(req.Keyword)
	if kw == "" || len([]rune(kw)) > 64 {
		fail(w, http.StatusBadRequest, "invalid_username", "关键词长度非法")
		return
	}
	if err := s.db.LogSearch(u.Username, kw); err != nil {
		fail(w, http.StatusInternalServerError, "upstream_error", "写入失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}
