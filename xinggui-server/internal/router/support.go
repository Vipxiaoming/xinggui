package router

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"time"

	"xinggui/internal/db"
	"xinggui/internal/resolve"
)

// ---------- 登录/请求限流 ----------

type rateLimiter struct {
	mu   sync.Mutex
	hits map[string][]time.Time
}

func newRateLimiter() *rateLimiter {
	return &rateLimiter{hits: make(map[string][]time.Time)}
}

// allow 在 window 内最多放行 limit 次；超限返回 false。
func (l *rateLimiter) allow(key string, limit int, window time.Duration) bool {
	if limit <= 0 {
		return true
	}
	nowT := time.Now()
	cut := nowT.Add(-window)
	l.mu.Lock()
	defer l.mu.Unlock()
	list := l.hits[key]
	kept := list[:0]
	for _, t := range list {
		if t.After(cut) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= limit {
		l.hits[key] = kept
		return false
	}
	l.hits[key] = append(kept, nowT)
	return true
}

// sweep 清理长期不用的键，避免内存无界增长。
func (l *rateLimiter) sweep() {
	cut := time.Now().Add(-2 * time.Hour)
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, v := range l.hits {
		if len(v) == 0 || v[len(v)-1].Before(cut) {
			delete(l.hits, k)
		}
	}
}

// ---------- 管理员上下文 ----------

type adminCtxKey struct{}

func withAdmin(ctx context.Context, username, role string) context.Context {
	return context.WithValue(ctx, adminCtxKey{}, [2]string{username, role})
}

// ---------- 系统配置 ----------

// defaultSysConfig 返回默认全局配置（与契约 §4.1 对齐）。
func defaultSysConfig() map[string]any {
	return map[string]any{
		"appEnabled":        true,
		"registrationOpen":  true,
		"requireApproval":   true,
		"maxDevices":        2,
		"resolveOrder":      []string{"wy", "tx", "kg", "kw"},
		"platformDisabled":  map[string]bool{"wy": false, "tx": false, "kg": false, "kw": false},
		"cacheTtlSeconds":   1800,
		"rateLimitPerMinute": 60,
		"downloadDir":       "Download/星轨",
		"allowedQualities":  []string{"128k", "320k", "flac"},
		"forceUpdate":       false,
		"announcement":      "",
		"stopNotice":        "",
	}
}

// sysConfigSnapshot 读取库里的覆盖值并合并默认值。
func (s *Server) sysConfigSnapshot() map[string]any {
	cfg := defaultSysConfig()
	raw, ok, err := s.db.GetSetting("sys_config")
	if err != nil || !ok || strings.TrimSpace(raw) == "" {
		return cfg
	}
	var over map[string]any
	if err := json.Unmarshal([]byte(raw), &over); err != nil {
		return cfg
	}
	for k, v := range over {
		cfg[k] = v
	}
	// 平台禁用表必须补齐四家，避免前端 undefined
	if m, ok := cfg["platformDisabled"].(map[string]any); ok {
		fixed := map[string]bool{}
		for _, p := range []string{"wy", "tx", "kg", "kw"} {
			if v, exist := m[p]; exist {
				if b, ok := v.(bool); ok {
					fixed[p] = b
				}
			}
		}
		cfg["platformDisabled"] = fixed
	}
	return cfg
}

// applySysConfig 把配置落到解析器与管理页需要的运行态。
func (s *Server) applySysConfig(cfg map[string]any) {
	if v, ok := cfg["resolveOrder"].([]any); ok {
		order := make([]string, 0, len(v))
		for _, x := range v {
			if str, ok := x.(string); ok {
				order = append(order, str)
			}
		}
		resolve.SetPlatformOrder(order)
	} else if v, ok := cfg["resolveOrder"].([]string); ok {
		resolve.SetPlatformOrder(v)
	}
	s.disabledMu.Lock()
	s.disabledPlatforms = map[string]bool{}
	if m, ok := cfg["platformDisabled"].(map[string]bool); ok {
		for k, v := range m {
			s.disabledPlatforms[k] = v
		}
	} else if m, ok := cfg["platformDisabled"].(map[string]any); ok {
		for k, v := range m {
			if b, ok := v.(bool); ok {
				s.disabledPlatforms[k] = b
			}
		}
	}
	s.disabledMu.Unlock()
	if n, ok := numOf(cfg["maxDevices"]); ok && n > 0 {
		s.cfgMaxDevices = n
	}
}

func numOf(v any) (int, bool) {
	switch t := v.(type) {
	case float64:
		return int(t), true
	case int:
		return t, true
	case int64:
		return int(t), true
	case json.Number:
		n, err := t.Int64()
		return int(n), err == nil
	}
	return 0, false
}

func (s *Server) isPlatformDisabled(p string) bool {
	s.disabledMu.RLock()
	defer s.disabledMu.RUnlock()
	return s.disabledPlatforms[p]
}

func (s *Server) maxDevices() int {
	if s.cfgMaxDevices > 0 {
		return s.cfgMaxDevices
	}
	return 2
}

// reloadConfigFromDB 启动时调用一次。
func (s *Server) reloadConfigFromDB() {
	s.applySysConfig(s.sysConfigSnapshot())
}

// ---------- 用户数据写入（供 sync 使用） ----------

func (s *Server) applySyncChange(username, deviceID string, ch syncChangeReq) error {
	payload, _ := json.Marshal(ch)
	switch ch.Type {
	case "favorite":
		if ch.Op == "remove" || ch.Op == "delete" {
			_ = s.db.DeleteFavorite(username, ch.SongID)
		} else {
			_ = s.db.UpsertFavorite(username, map[string]any{
				"songId": ch.SongID, "title": ch.Title, "artist": ch.Artist,
				"album": ch.Album, "duration": ch.Duration, "platform": ch.Platform,
			})
		}
	case "playlist":
		if ch.Op == "remove" || ch.Op == "delete" {
			_ = s.db.UpsertPlaylist(username, ch.ID, "", false, []map[string]any{})
		} else {
			songs := make([]map[string]any, 0, len(ch.Songs))
			for _, sg := range ch.Songs {
				songs = append(songs, map[string]any{
					"songId": sg.SongID, "title": sg.Title, "artist": sg.Artist,
					"album": sg.Album, "duration": sg.Duration, "platform": sg.Platform,
				})
			}
			_ = s.db.UpsertPlaylist(username, ch.ID, ch.Name, ch.IsPublic, songs)
		}
	case "history":
		_ = s.db.AppendHistory(username, map[string]any{
			"songId": ch.SongID, "title": ch.Title, "artist": ch.Artist,
			"platform": ch.Platform, "duration": ch.Duration,
			"playedSeconds": ch.PlayedSeconds, "completed": ch.Completed,
		})
	}
	updated := ch.UpdatedAt
	if updated == 0 {
		updated = time.Now().Unix()
	}
	return s.db.AppendSyncChange(username, deviceID, ch.Type, ch.Op, firstNonEmpty(ch.ID, ch.SongID), string(payload), updated)
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if strings.TrimSpace(s) != "" {
			return s
		}
	}
	return ""
}

// ensureUser 用于管理端「查看某用户数据」时的存在性校验别名。
func userExists(d *db.DB, username string) bool {
	_, err := d.GetUserFull(username)
	return err == nil
}