package router_test

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"xinggui/internal/config"
	"xinggui/internal/db"
	"xinggui/internal/router"
)

type env struct {
	srv  *httptest.Server
	db   *db.DB
	cfg  *config.Config
	h    http.Handler
}

func newEnv(t *testing.T) *env {
	t.Helper()
	dir := t.TempDir()
	cfg := &config.Config{
		Port:      0,
		DBPath:    filepath.Join(dir, "test.db"),
		JWTSecret: []byte("test-secret-for-xinggui-selftest-0123456789"),
		BaseURL:   "http://127.0.0.1:8443",
		DataDir:   dir,
	}
	database, err := db.Open(cfg.DBPath)
	if err != nil {
		t.Fatalf("db open: %v", err)
	}
	t.Cleanup(func() { database.Close() })
	h := router.New(cfg, database).Handler()
	ts := httptest.NewServer(h)
	t.Cleanup(ts.Close)
	return &env{srv: ts, db: database, cfg: cfg, h: h}
}

func (e *env) do(t *testing.T, method, path, token string, body any) (int, map[string]any, string) {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("encode: %v", err)
		}
	}
	req, err := http.NewRequest(method, e.srv.URL+path, &buf)
	if err != nil {
		t.Fatalf("new req: %v", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do: %v", err)
	}
	defer resp.Body.Close()
	raw := new(bytes.Buffer)
	raw.ReadFrom(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw.Bytes(), &out)
	return resp.StatusCode, out, raw.String()
}

func TestHealthAndConfig(t *testing.T) {
	e := newEnv(t)
	code, body, raw := e.do(t, "GET", "/v1/health", "", nil)
	if code != 200 || body["status"] != "ok" {
		t.Fatalf("health: %d %s", code, raw)
	}
	code, body, raw = e.do(t, "GET", "/v1/config", "", nil)
	if code != 200 {
		t.Fatalf("config: %d %s", code, raw)
	}
	if body["maxDevices"] == nil || body["resolveEndpoint"] == nil {
		t.Fatalf("config 缺字段: %s", raw)
	}
}

func TestAuthFlow(t *testing.T) {
	e := newEnv(t)

	// 1. 注册 -> pending
	code, body, raw := e.do(t, "POST", "/v1/auth/register", "", map[string]string{
		"username": "alice", "password": "Passw0rd123", "deviceId": "dev-a", "deviceName": "Pixel 8",
	})
	if code != 201 || body["status"] != "pending" {
		t.Fatalf("register 期望 201/pending，实际 %d %s", code, raw)
	}

	// 2. 重复注册 -> 409 username_taken
	code, body, raw = e.do(t, "POST", "/v1/auth/register", "", map[string]string{
		"username": "Alice", "password": "Passw0rd123", "deviceId": "dev-x",
	})
	if code != 409 || body["error"] != "username_taken" {
		t.Fatalf("重复注册期望 409 username_taken，实际 %d %s", code, raw)
	}

	// 3. 弱密码 -> 400 weak_password
	code, body, _ = e.do(t, "POST", "/v1/auth/register", "", map[string]string{
		"username": "carol", "password": "short",
	})
	if code != 400 || body["error"] != "weak_password" {
		t.Fatalf("弱密码期望 400 weak_password，实际 %d", code)
	}

	// 4. pending 登录 -> 403 pending_approval
	code, body, raw = e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "alice", "password": "Passw0rd123", "deviceId": "dev-a",
	})
	if code != 403 || body["error"] != "pending_approval" {
		t.Fatalf("pending 登录期望 403 pending_approval，实际 %d %s", code, raw)
	}

	// 5. 错误密码 -> 401 bad_credentials
	code, body, _ = e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "alice", "password": "WrongPass999", "deviceId": "dev-a",
	})
	if code != 401 || body["error"] != "bad_credentials" {
		t.Fatalf("错误密码期望 401 bad_credentials，实际 %d", code)
	}

	// 6. 管理员放行（模拟改库）
	if err := e.db.SetStatus("alice", "active"); err != nil {
		t.Fatalf("set status: %v", err)
	}

	// 7. 登录成功
	code, body, raw = e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "alice", "password": "Passw0rd123", "deviceId": "dev-a", "deviceName": "Pixel 8",
	})
	if code != 200 {
		t.Fatalf("登录期望 200，实际 %d %s", code, raw)
	}
	access, _ := body["accessToken"].(string)
	refresh, _ := body["refreshToken"].(string)
	if access == "" || refresh == "" {
		t.Fatalf("登录未返回 token: %s", raw)
	}
	if exp, _ := body["expiresIn"].(float64); int(exp) != 7200 {
		t.Fatalf("expiresIn 期望 7200，实际 %v", body["expiresIn"])
	}

	// 8. me
	code, body, raw = e.do(t, "GET", "/v1/auth/me", access, nil)
	if code != 200 || body["username"] != "alice" {
		t.Fatalf("me 期望 200/alice，实际 %d %s", code, raw)
	}

	// 9. 无 token 访问 me -> 401
	code, body, _ = e.do(t, "GET", "/v1/auth/me", "", nil)
	if code != 401 || body["error"] != "token_expired" {
		t.Fatalf("无 token 期望 401 token_expired，实际 %d", code)
	}

	// 10. refresh
	code, body, raw = e.do(t, "POST", "/v1/auth/refresh", "", map[string]string{"refreshToken": refresh})
	if code != 200 {
		t.Fatalf("refresh 期望 200，实际 %d %s", code, raw)
	}
	if s, _ := body["accessToken"].(string); s == "" {
		t.Fatalf("refresh 未返回 accessToken: %s", raw)
	}

	// 11. 解绑当前设备 -> 400 forbidden
	code, body, _ = e.do(t, "POST", "/v1/auth/unbind", access, map[string]string{"deviceId": "dev-a"})
	if code != 400 || body["error"] != "forbidden" {
		t.Fatalf("解绑当前设备期望 400 forbidden，实际 %d", code)
	}

	// 12. 设备上限：第二台可登录，第三台 409 device_limit
	code, _, raw = e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "alice", "password": "Passw0rd123", "deviceId": "dev-b", "deviceName": "Mi 14",
	})
	if code != 200 {
		t.Fatalf("第二台设备期望 200，实际 %d %s", code, raw)
	}
	code, body, _ = e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "alice", "password": "Passw0rd123", "deviceId": "dev-c", "deviceName": "iPad",
	})
	if code != 409 || body["error"] != "device_limit" {
		t.Fatalf("第三台设备期望 409 device_limit，实际 %d", code)
	}

	// 13. 解绑 dev-b 后第三台可登录
	code, _, raw = e.do(t, "POST", "/v1/auth/unbind", access, map[string]string{"deviceId": "dev-b"})
	if code != 200 {
		t.Fatalf("解绑 dev-b 期望 200，实际 %d %s", code, raw)
	}
	code, _, raw = e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "alice", "password": "Passw0rd123", "deviceId": "dev-c",
	})
	if code != 200 {
		t.Fatalf("解绑后新设备期望 200，实际 %d %s", code, raw)
	}
}

func TestResolveRealKuwo(t *testing.T) {
	if testing.Short() {
		t.Skip("short mode")
	}
	e := newEnv(t)
	if _, _, raw := e.do(t, "POST", "/v1/auth/register", "", map[string]string{
		"username": "kwuser", "password": "Passw0rd123", "deviceId": "dev-kw",
	}); raw == "" {
		t.Fatal("register empty")
	}
	if err := e.db.SetStatus("kwuser", "active"); err != nil {
		t.Fatalf("set status: %v", err)
	}
	code, body, raw := e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "kwuser", "password": "Passw0rd123", "deviceId": "dev-kw",
	})
	if code != 200 {
		t.Fatalf("login: %d %s", code, raw)
	}
	token, _ := body["accessToken"].(string)

	// 首次解析：真实请求酷我（474680496 = 稻香，完整曲目约 203s）
	code, body, raw = e.do(t, "POST", "/v1/resolve", token, map[string]any{
		"source": "kw", "id": "474680496", "name": "稻香", "artist": "周杰伦", "duration": 203, "quality": "128k",
	})
	if code != 200 {
		t.Fatalf("resolve 期望 200，实际 %d %s", code, raw)
	}
	url, _ := body["url"].(string)
	if len(url) < 12 || (url[:7] != "http://" && url[:8] != "https://") {
		t.Fatalf("resolve 未返回合法 URL: %s", raw)
	}
	if body["resolvedBy"] != "kw" {
		t.Fatalf("resolvedBy 期望 kw: %s", raw)
	}
	if cached, _ := body["cached"].(bool); cached {
		t.Fatalf("首次解析不应命中缓存: %s", raw)
	}
	if dur, _ := body["duration"].(float64); dur < 100 {
		t.Fatalf("实测时长异常（疑似片段）: %s", raw)
	}
	if size, _ := body["size"].(float64); size < 1000000 {
		t.Fatalf("实测文件过小（疑似片段）: %s", raw)
	}
	t.Logf("首次解析 bitrate=%v dur=%v size=%v trace=%v", body["actualBitrate"], body["duration"], body["size"], body["trace"])

	// 二次解析（同参数）：应命中缓存
	code, body, raw = e.do(t, "POST", "/v1/resolve", token, map[string]any{
		"source": "kw", "id": "474680496", "name": "稻香", "artist": "周杰伦", "duration": 203, "quality": "128k",
	})
	if code != 200 {
		t.Fatalf("二次 resolve 期望 200，实际 %d %s", code, raw)
	}
	if cached, _ := body["cached"].(bool); !cached {
		t.Fatalf("二次解析应命中缓存: %s", raw)
	}

	// 占位片段 rid（228908 返回固定 181521 B 试听文件）：无歌名兜底时必须 404
	code, body, raw = e.do(t, "POST", "/v1/resolve", token, map[string]any{
		"source": "kw", "id": "228908", "quality": "320k",
	})
	if code != 404 || body["error"] != "no_source" {
		t.Fatalf("占位片段期望 404 no_source，实际 %d %s", code, raw)
	}
	// 无 id 且无 name -> 404 no_source
	code, body, _ = e.do(t, "POST", "/v1/resolve", token, map[string]any{"source": "kw"})
	if code != 404 || body["error"] != "no_source" {
		t.Fatalf("空请求期望 404 no_source，实际 %d", code)
	}

	// 非法音质 -> 400 bad_quality
	code, body, _ = e.do(t, "POST", "/v1/resolve", token, map[string]any{"source": "kw", "id": "228908", "quality": "999k"})
	if code != 400 || body["error"] != "bad_quality" {
		t.Fatalf("非法音质期望 400 bad_quality，实际 %d", code)
	}
}

func TestResolveByNameSearch(t *testing.T) {
	if testing.Short() {
		t.Skip("short mode")
	}
	e := newEnv(t)
	e.do(t, "POST", "/v1/auth/register", "", map[string]string{
		"username": "kwsearch", "password": "Passw0rd123", "deviceId": "dev-s",
	})
	if err := e.db.SetStatus("kwsearch", "active"); err != nil {
		t.Fatalf("set status: %v", err)
	}
	code, body, raw := e.do(t, "POST", "/v1/auth/login", "", map[string]string{
		"username": "kwsearch", "password": "Passw0rd123", "deviceId": "dev-s",
	})
	if code != 200 {
		t.Fatalf("login: %d %s", code, raw)
	}
	token, _ := body["accessToken"].(string)

	code, body, raw = e.do(t, "POST", "/v1/resolve", token, map[string]any{
		"source": "kw", "name": "晴天", "artist": "周杰伦", "quality": "128k",
	})
	if code != 200 {
		t.Fatalf("按名解析期望 200，实际 %d %s", code, raw)
	}
	url, _ := body["url"].(string)
	if len(url) < 12 {
		t.Fatalf("按名解析未返回 URL: %s", raw)
	}
	t.Logf("按名解析 URL=%s trace=%v", url, body["trace"])
}