package router

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"xinggui/internal/config"
	"xinggui/internal/db"
)

func newFixServer(t *testing.T) *Server {
	t.Helper()
	dir := t.TempDir()
	cfg := &config.Config{
		Port:      0,
		DBPath:    filepath.Join(dir, "fix.db"),
		JWTSecret: []byte("test-secret-for-xinggui-selftest-0123456789"),
		BaseURL:   "http://127.0.0.1:8443",
		DataDir:   dir,
	}
	database, err := db.Open(cfg.DBPath)
	if err != nil {
		t.Fatalf("db open: %v", err)
	}
	t.Cleanup(func() { database.Close() })
	return New(cfg, database)
}

// TestAdminCtxKeyUnified 验证 P1：withAdmin 写入的身份必须能被 adminOf 读出。
func TestAdminCtxKeyUnified(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/admin/api/users", nil)
	r = r.WithContext(withAdmin(r.Context(), "alice", "superadmin"))
	user, role := adminOf(r)
	if user != "alice" || role != "superadmin" {
		t.Fatalf("adminOf=(%q,%q) want (alice,superadmin)", user, role)
	}
	if v, _ := r.Context().Value(adminCtxKey{}).([2]string); v[0] != "alice" {
		t.Fatalf("ctx value=%v", v)
	}
	// 无上下文时不应 panic，返回空串。
	user, role = adminOf(httptest.NewRequest(http.MethodGet, "/admin/api/users", nil))
	if user != "" || role != "" {
		t.Fatalf("empty ctx adminOf=(%q,%q) want empty", user, role)
	}
}

// TestAdminTokenExtract 验证 P2a：X-Admin-Token 与 Authorization: Bearer 都能解析。
func TestAdminTokenExtract(t *testing.T) {
	cases := []struct {
		name  string
		setup func(*http.Request)
		want  string
	}{
		{"x-admin-token", func(r *http.Request) { r.Header.Set("X-Admin-Token", "abc") }, "abc"},
		{"bearer", func(r *http.Request) { r.Header.Set("Authorization", "Bearer xyz") }, "xyz"},
		{"bearer-spaces", func(r *http.Request) { r.Header.Set("Authorization", "  Bearer   spaced  ") }, "spaced"},
		{"x-admin-token-wins", func(r *http.Request) {
			r.Header.Set("X-Admin-Token", "abc")
			r.Header.Set("Authorization", "Bearer xyz")
		}, "abc"},
		{"empty", func(r *http.Request) {}, ""},
		{
			"bearer-empty-token",
			func(r *http.Request) { r.Header.Set("Authorization", "Bearer   ") },
			"",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPost, "/admin/api/logout", nil)
			tc.setup(r)
			if got := adminToken(r); got != tc.want {
				t.Fatalf("adminToken=%q want %q", got, tc.want)
			}
		})
	}
}

// TestAdminLogoutAcceptsBearer 验证 P2a 端到端：Bearer 令牌登出后必须被吊销。
func TestAdminLogoutAcceptsBearer(t *testing.T) {
	s := newFixServer(t)
	tok, _, _, err := s.jwt.IssueAdmin("alice", "superadmin")
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	claims, err := s.jwt.ParseAdmin(tok)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if err := s.db.AddAdminToken(claims.ID, "alice", 0, 9999999999); err != nil {
		t.Fatalf("add token: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, "/admin/api/logout", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	rec := httptest.NewRecorder()
	s.handleAdminLogout(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("logout status=%d want 200", rec.Code)
	}
	if ok, err := s.db.AdminTokenValid(claims.ID); err != nil || ok {
		t.Fatalf("token still valid after Bearer logout: ok=%v err=%v", ok, err)
	}

	// 无令牌也必须 200 + ok。
	rec2 := httptest.NewRecorder()
	s.handleAdminLogout(rec2, httptest.NewRequest(http.MethodPost, "/admin/api/logout", nil))
	if rec2.Code != http.StatusOK {
		t.Fatalf("empty logout status=%d want 200", rec2.Code)
	}
	var body map[string]bool
	if err := json.Unmarshal(rec2.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !body["ok"] {
		t.Fatalf("body=%v want ok=true", body)
	}
}

// TestClientIP 验证 P2b：默认不信任 XFF；仅显式开启且 RemoteAddr 为内网时采信。
func TestClientIP(t *testing.T) {
	t.Run("untrusted-by-default", func(t *testing.T) {
		t.Setenv("XG_TRUST_PROXY", "")
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.RemoteAddr = "203.0.113.9:54321"
		r.Header.Set("X-Forwarded-For", "8.8.8.8")
		r.Header.Set("X-Real-IP", "8.8.4.4")
		if got := clientIP(r); got != "203.0.113.9" {
			t.Fatalf("clientIP=%q want 203.0.113.9", got)
		}
	})
	t.Run("proxy-internal-trusts-xff", func(t *testing.T) {
		t.Setenv("XG_TRUST_PROXY", "1")
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.RemoteAddr = "10.1.2.3:54321"
		r.Header.Set("X-Forwarded-For", "8.8.8.8, 10.0.0.1")
		if got := clientIP(r); got != "8.8.8.8" {
			t.Fatalf("clientIP=%q want 8.8.8.8", got)
		}
	})
	t.Run("proxy-public-ignores-xff", func(t *testing.T) {
		t.Setenv("XG_TRUST_PROXY", "true")
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.RemoteAddr = "203.0.113.9:54321"
		r.Header.Set("X-Forwarded-For", "8.8.8.8")
		if got := clientIP(r); got != "203.0.113.9" {
			t.Fatalf("clientIP=%q want 203.0.113.9", got)
		}
	})
	t.Run("loopback-trusts-x-real-ip", func(t *testing.T) {
		t.Setenv("XG_TRUST_PROXY", "1")
		r := httptest.NewRequest(http.MethodGet, "/", nil)
		r.RemoteAddr = "127.0.0.1:54321"
		r.Header.Set("X-Real-IP", "198.51.100.7")
		if got := clientIP(r); got != "198.51.100.7" {
			t.Fatalf("clientIP=%q want 198.51.100.7", got)
		}
	})
}

// TestAdminGetVersionFallbackHasVersionCode 验证 P3：无数据分支也返回 versionCode。
func TestAdminGetVersionFallbackHasVersionCode(t *testing.T) {
	s := newFixServer(t)
	rec := httptest.NewRecorder()
	s.handleAdminGetVersion(rec, httptest.NewRequest(http.MethodGet, "/admin/api/version", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d want 200", rec.Code)
	}
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if _, ok := got["versionCode"]; !ok {
		t.Fatalf("fallback missing versionCode: %v", got)
	}
	for _, k := range []string{"latestVersion", "minVersion", "updateUrl", "updateSha256", "updateNotes", "forceUpdate", "publishedAt", "versionCode"} {
		if _, ok := got[k]; !ok {
			t.Fatalf("fallback missing field %q: %v", k, got)
		}
	}
	if len(got) != 8 {
		t.Fatalf("fallback field count=%d want 8: %v", len(got), got)
	}
}

// TestAdminGetVersionDataBranchHasVersionCode 验证 P3：有数据分支字段集合与回退分支一致。
func TestAdminGetVersionDataBranchHasVersionCode(t *testing.T) {
	s := newFixServer(t)
	if err := s.db.UpsertVersion(db.Version{
		VersionCode: 12, VersionName: "1.2.0", URL: "https://example.com/a.apk",
		SHA256: "abc", Notes: "n", Force: true, MinVersionCode: 3, Published: true,
	}); err != nil {
		t.Fatalf("upsert: %v", err)
	}
	rec := httptest.NewRecorder()
	s.handleAdminGetVersion(rec, httptest.NewRequest(http.MethodGet, "/admin/api/version", nil))
	var got map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(got) != 8 {
		t.Fatalf("data branch field count=%d want 8: %v", len(got), got)
	}
	if v, _ := got["versionCode"].(float64); int(v) != 12 {
		t.Fatalf("versionCode=%v want 12", got["versionCode"])
	}
}
