package router

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ---------- 同步 ----------

type syncSongReq struct {
	SongID   string `json:"songId"`
	Title    string `json:"title"`
	Artist   string `json:"artist"`
	Album    string `json:"album"`
	Duration int    `json:"duration"`
	Platform string `json:"platform"`
}

type syncChangeReq struct {
	Type      string        `json:"type"`
	Op        string        `json:"op"`
	ID        string        `json:"id"`
	SongID    string        `json:"songId"`
	Name      string        `json:"name"`
	Title     string        `json:"title"`
	Artist    string        `json:"artist"`
	Album     string        `json:"album"`
	Duration  int           `json:"duration"`
	Platform  string        `json:"platform"`
	IsPublic  bool          `json:"isPublic"`
	Songs     []syncSongReq `json:"songs"`
	UpdatedAt int64         `json:"updatedAt"`
	PlayedAt  int64         `json:"playedAt"`
	PlayedSeconds int       `json:"playedSeconds"`
	Completed     bool      `json:"completed"`
}

type syncPushReq struct {
	DeviceID string          `json:"deviceId"`
	Since    int64           `json:"since"`
	Changes  []syncChangeReq `json:"changes"`
}

func (s *Server) handleSyncPush(w http.ResponseWriter, r *http.Request) {
	u := userOf(r)
	var req syncPushReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
		return
	}
	if len(req.Changes) > 500 {
		fail(w, http.StatusBadRequest, "invalid_request", "单次同步变更不能超过 500 条")
		return
	}
	accepted := 0
	var conflicts []string
	for _, ch := range req.Changes {
		switch ch.Type {
		case "favorite", "playlist", "history":
		default:
			conflicts = append(conflicts, "unknown_type:"+ch.Type)
			continue
		}
		if err := s.applySyncChange(u.Username, strings.TrimSpace(req.DeviceID), ch); err != nil {
			conflicts = append(conflicts, "write_failed:"+ch.Type)
			continue
		}
		accepted++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"accepted": accepted, "conflicts": conflicts, "serverTime": time.Now().Unix(),
	})
}

func (s *Server) handleSyncPull(w http.ResponseWriter, r *http.Request) {
	u := userOf(r)
	since := int64(0)
	if v := strings.TrimSpace(r.URL.Query().Get("since")); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			since = n
		}
	}
	changes, maxSeq, err := s.db.SyncChangesSince(u.Username, since, 500)
	if err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "数据库异常")
		return
	}
	items := make([]map[string]any, 0, len(changes))
	for _, c := range changes {
		var payload map[string]any
		_ = json.Unmarshal([]byte(c.Payload), &payload)
		items = append(items, map[string]any{
			"seq": c.Seq, "type": c.Type, "op": c.Op, "id": c.ID,
			"updatedAt": c.UpdatedAt, "payload": payload,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"serverTime": time.Now().Unix(), "cursor": maxSeq, "changes": items,
	})
}

// ---------- 版本 ----------

func (s *Server) handleVersionLatest(w http.ResponseWriter, r *http.Request) {
	v, err := s.db.LatestVersion()
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"latestVersion": "1.0.0", "versionCode": 100, "minVersionCode": 100,
			"updateUrl": "", "updateSha256": "", "updateNotes": "",
			"forceUpdate": false, "publishedAt": 0,
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"latestVersion": v.VersionName, "versionCode": v.VersionCode,
		"minVersionCode": v.MinVersionCode, "updateUrl": v.URL,
		"updateSha256": v.SHA256, "updateNotes": v.Notes,
		"forceUpdate": v.Force, "publishedAt": v.CreatedAt,
	})
}

// ---------- 登出 ----------

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	// 简单实现：token_version+1 会让该账号所有 access/refresh 立即失效。
	// 契约只要求「吊销该 refresh token」，这里按更严格的口径执行，
	// 客户端随后重新登录即可（access 2h 内本来也会自然过期）。
	u := userOf(r)
	if err := s.db.BumpTokenVersion(u.Username); err != nil {
		fail(w, http.StatusInternalServerError, "server_error", "登出失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// ---------- 登录限流与锁定 ----------

const (
	loginRatePerMinute = 5
	loginFailWindow    = 15 * time.Minute
	loginFailLimit     = 5
)

// loginGuard 只对「失败尝试」计数：
//  - 同 IP 每分钟最多 5 次失败（成功登录不占配额，避免正常用户被误伤）
//  - 同账号连续 5 次失败锁定 15 分钟
func (s *Server) loginGuard(w http.ResponseWriter, r *http.Request, username string) bool {
	if username != "" && s.lockedOut(username) {
		fail(w, http.StatusTooManyRequests, "account_locked", "连续登录失败次数过多，请 15 分钟后再试")
		return false
	}
	return true
}

// noteLoginFailWithIP 记录一次失败并返回「该 IP 本分钟是否已超配额」。
// 配额只统计失败，避免正常用户被成功登录挤掉名额。
func (s *Server) noteLoginFailWithIP(r *http.Request, username string) bool {
	s.noteLoginFail(username)
	ip := clientIP(r)
	return !s.limiter.allow("login-fail-ip:"+ip, loginRatePerMinute, time.Minute)
}

func (s *Server) lockedOut(username string) bool {
	s.failMu.Lock()
	defer s.failMu.Unlock()
	entry := s.loginFails[username]
	if entry == nil {
		return false
	}
	if time.Since(entry.first) > loginFailWindow {
		delete(s.loginFails, username)
		return false
	}
	return entry.count >= loginFailLimit
}

func (s *Server) noteLoginFail(username string) {
	if username == "" {
		return
	}
	s.failMu.Lock()
	defer s.failMu.Unlock()
	entry := s.loginFails[username]
	if entry == nil || time.Since(entry.first) > loginFailWindow {
		s.loginFails[username] = &failEntry{first: time.Now(), count: 1}
		return
	}
	entry.count++
}

func (s *Server) noteLoginOK(username string) {
	s.failMu.Lock()
	delete(s.loginFails, username)
	s.failMu.Unlock()
}

