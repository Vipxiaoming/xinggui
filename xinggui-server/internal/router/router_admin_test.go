package router_test

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"xinggui/internal/config"
	"xinggui/internal/db"
	"xinggui/internal/router"
)

// newAdminEnv 构造一个把 AdminDir 指向临时静态目录的完整 Server（真实 db + 真实路由表），
// 用来验证 /admin 静态托管与 /admin/api/*、/v1/* 的优先级。
func newAdminEnv(t *testing.T) *httptest.Server {
	t.Helper()
	dir := t.TempDir()
	adminDir := filepath.Join(dir, "admin")
	if err := os.MkdirAll(filepath.Join(adminDir, "assets", "js"), 0o755); err != nil {
		t.Fatalf("mkdir admin: %v", err)
	}
	if err := os.WriteFile(filepath.Join(adminDir, "index.html"), []byte("<!doctype html><title>admin-index</title>"), 0o644); err != nil {
		t.Fatalf("write index: %v", err)
	}
	if err := os.WriteFile(filepath.Join(adminDir, "assets", "js", "api.js"), []byte("window.API=1;"), 0o644); err != nil {
		t.Fatalf("write api.js: %v", err)
	}
	cfg := &config.Config{
		Port:      0,
		DBPath:    filepath.Join(dir, "test.db"),
		JWTSecret: []byte("test-secret-for-xinggui-selftest-0123456789"),
		BaseURL:   "http://127.0.0.1:8443",
		DataDir:   dir,
		AdminDir:  adminDir,
	}
	database, err := db.Open(cfg.DBPath)
	if err != nil {
		t.Fatalf("db open: %v", err)
	}
	t.Cleanup(func() { database.Close() })
	ts := httptest.NewServer(router.New(cfg, database).Handler())
	t.Cleanup(ts.Close)
	return ts
}

func doRaw(t *testing.T, ts *httptest.Server, method, path, body string, noRedirect bool) (*http.Response, string) {
	t.Helper()
	var rdr *strings.Reader
	if body == "" {
		rdr = strings.NewReader("")
	} else {
		rdr = strings.NewReader(body)
	}
	req, err := http.NewRequest(method, ts.URL+path, rdr)
	if err != nil {
		t.Fatalf("new req: %v", err)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	client := *http.DefaultClient
	if noRedirect {
		client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("do %s %s: %v", method, path, err)
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	return resp, string(raw)
}

// TestAdminStaticRouting 覆盖路径3的 4 条核心断言。
func TestAdminStaticRouting(t *testing.T) {
	ts := newAdminEnv(t)

	t.Run("GET /admin -> 301 /admin/", func(t *testing.T) {
		resp, _ := doRaw(t, ts, "GET", "/admin", "", true)
		if resp.StatusCode != http.StatusMovedPermanently {
			t.Fatalf("status=%d want 301", resp.StatusCode)
		}
		if loc := resp.Header.Get("Location"); loc != "/admin/" {
			t.Fatalf("Location=%q want /admin/", loc)
		}
	})

	t.Run("GET /admin/ -> 200 index.html", func(t *testing.T) {
		resp, body := doRaw(t, ts, "GET", "/admin/", "", false)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("status=%d want 200", resp.StatusCode)
		}
		if !strings.Contains(body, "admin-index") {
			t.Fatalf("body 不是 index.html: %q", body)
		}
		if cc := resp.Header.Get("Cache-Control"); cc != "public, max-age=300" {
			t.Fatalf("Cache-Control=%q want public, max-age=300", cc)
		}
	})

	t.Run("GET /admin/assets/js/api.js -> 200 static", func(t *testing.T) {
		resp, body := doRaw(t, ts, "GET", "/admin/assets/js/api.js", "", false)
		if resp.StatusCode != http.StatusOK || !strings.Contains(body, "window.API=1") {
			t.Fatalf("static asset: status=%d body=%q", resp.StatusCode, body)
		}
	})

	t.Run("POST /admin/api/login -> API handler (not static)", func(t *testing.T) {
		// 非法请求体：静态服务会返回 405/404 或 index.html；API handler 返回 401 JSON。
		resp, body := doRaw(t, ts, "POST", "/admin/api/login", "{", false)
		if resp.StatusCode != http.StatusUnauthorized {
			t.Fatalf("status=%d want 401 (API handler), body=%q", resp.StatusCode, body)
		}
		if !strings.Contains(body, "bad_credentials") {
			t.Fatalf("body 不是 API JSON: %q", body)
		}
		if cc := resp.Header.Get("Cache-Control"); cc != "no-store" {
			t.Fatalf("API Cache-Control=%q want no-store", cc)
		}
		if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
			t.Fatalf("Content-Type=%q want application/json", ct)
		}
	})

	t.Run("GET /admin/api/setup/status -> API handler", func(t *testing.T) {
		resp, body := doRaw(t, ts, "GET", "/admin/api/setup/status", "", false)
		if resp.StatusCode != http.StatusOK || !strings.Contains(resp.Header.Get("Content-Type"), "application/json") {
			t.Fatalf("setup/status: status=%d ct=%q body=%q", resp.StatusCode, resp.Header.Get("Content-Type"), body)
		}
	})

	t.Run("GET /v1/health -> 200", func(t *testing.T) {
		resp, body := doRaw(t, ts, "GET", "/v1/health", "", false)
		if resp.StatusCode != http.StatusOK || !strings.Contains(body, "\"status\"") {
			t.Fatalf("health: status=%d body=%q", resp.StatusCode, body)
		}
		if cc := resp.Header.Get("Cache-Control"); cc != "no-store" {
			t.Fatalf("health Cache-Control=%q want no-store", cc)
		}
	})

	t.Run("POST /admin/ (非 GET) 不被静态服务处理", func(t *testing.T) {
		resp, _ := doRaw(t, ts, "POST", "/admin/index.html", "{}", false)
		if resp.StatusCode == http.StatusOK {
			t.Fatalf("POST 静态路径不应返回 200")
		}
	})
}
