package router

import (
	"errors"
	"net/http"
	"math"
	"strconv"
	"strings"
	"time"

	"xinggui/internal/resolve"
)

// ---------- 解析（四路竞速） ----------

type resolveReq2 struct {
	Source   string `json:"source"`
	ID       string `json:"id"`
	Name     string `json:"name"`
	Artist   string `json:"artist"`
	Duration int    `json:"duration"`
	Quality  string `json:"quality"`
}

// resolveOrderFromConfig 读取管理页下发的解析顺序；顺序即优先级，非法值回落到默认四路。
func (s *Server) resolveOrderFromConfig() []string {
	cfg := s.sysConfigSnapshot()
	raw, _ := cfg["resolveOrder"].([]string)
	if len(raw) == 0 {
		if arr, ok := cfg["resolveOrder"].([]any); ok {
			raw = make([]string, 0, len(arr))
			for _, v := range arr {
				if str, ok := v.(string); ok {
					raw = append(raw, str)
				}
			}
		}
	}
	out := make([]string, 0, 4)
	seen := map[string]bool{}
	for _, p := range raw {
		n := resolve.NormalizePlatform(p)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	if len(out) == 0 {
		return []string{"wy", "tx", "kg", "kw"}
	}
	return out
}

// disabledMap 返回当前被管理页关闭的平台。
func (s *Server) disabledMap() map[string]bool {
	disabled := map[string]bool{}
	for _, p := range []string{"wy", "tx", "kg", "kw"} {
		if s.isPlatformDisabled(p) {
			disabled[p] = true
		}
	}
	return disabled
}

// enabledSignature 参与缓存键：平台开关一变，旧缓存立即失效，
// 避免「刚禁用 wy，却还命中 wy 的缓存结果」。
func enabledSignature(order []string, disabled map[string]bool) string {
	parts := make([]string, 0, len(order))
	for _, p := range order {
		if disabled[p] {
			parts = append(parts, p+"=off")
			continue
		}
		parts = append(parts, p+"=on")
	}
	return strings.Join(parts, ",")
}

func (s *Server) handleResolveMulti(w http.ResponseWriter, r *http.Request) {
	u := userOf(r)
	var req resolveReq2
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
		return
	}
	q, err := resolve.MapQuality(req.Quality)
	if err != nil {
		fail(w, http.StatusBadRequest, "bad_quality", "不支持的音质参数")
		return
	}
	source := NormalizePlatform(req.Source)
	if source == "" {
		source = "kw"
	}
	if strings.TrimSpace(req.ID) == "" && strings.TrimSpace(req.Name) == "" && strings.TrimSpace(req.Artist) == "" {
		fail(w, http.StatusNotFound, "no_source", "缺少歌曲 ID 或歌名")
		return
	}

	start := time.Now()
	order := s.resolveOrderFromConfig()
	// 客户端显式指定了平台（source 非空）时，把该平台提到队首：
	// 这既是用户意图，也避免「请求 kg 却先命中 wy」的答非所问。
	if strings.TrimSpace(req.Source) != "" {
		order = pinFirst(order, source)
	}
	disabled := s.disabledMap()

	// 缓存键必须包含 source 与平台启用状态：否则切换 source 或开关平台后，
	// 会把上一个平台的结果当成新请求的答案返回。
	cacheKey := strings.Join([]string{
		"v3", source, strings.TrimSpace(req.ID), strings.TrimSpace(req.Name),
		strings.TrimSpace(req.Artist), strconv.Itoa(req.Duration), q.BR,
		enabledSignature(order, disabled),
	}, "|")
	if v, ok := s.cache.Get(cacheKey); ok {
		if hit, ok := v.(*cachedResult); ok {
			s.db.LogResolve(u.Username, source, hit.Platform, true, "", time.Since(start).Milliseconds(), true)
			warnings := hit.Res.Warnings
			if warnings == nil {
				warnings = []string{}
			}
			writeJSON(w, http.StatusOK, map[string]any{
				"url": hit.Res.URL, "level": hit.Res.Level, "actualBitrate": hit.Res.Bitrate,
				"duration": round1(hit.Res.DurationS), "size": hit.Res.Size,
				// resolvedBy 必须保留真实命中平台，不能写成 "cache"
				"resolvedBy": hit.Platform, "expiresAt": hit.Res.ExpiresAt,
				"trace": []string{hit.Platform + ":cache_hit"}, "cached": true, "warnings": warnings,
			})
			return
		}
	}

	out, rerr := s.res.ResolveAny(r.Context(), resolve.ResolveRequest{
		Source: source, ID: req.ID, Name: req.Name, Artist: req.Artist,
		Duration: req.Duration, Quality: q, Disabled: disabled,
		Order: order, PerTry: 7 * time.Second,
	})
	elapsed := time.Since(start).Milliseconds()

	trace := []string{}
	if out != nil {
		trace = append(trace, out.Trace...)
	}
	// 跨平台 id 不能当本平台 rid 用，显式标记便于客户端排障。
	if source != "kw" && strings.TrimSpace(req.ID) != "" {
		trace = append([]string{"id_ignored:" + source}, trace...)
	}
	if len(trace) == 0 {
		trace = []string{"no_attempt"}
	}

	if rerr != nil {
		errCode := "no_source"
		msg := "四家平台均未返回可播放地址"
		status := http.StatusNotFound
		switch {
		case errors.Is(rerr, resolve.ErrPaid):
			trace = append(trace, "all_paid")
			msg = "四家平台仅返回试听或付费片段"
		case errors.Is(rerr, resolve.ErrTimeout):
			status, errCode, msg = http.StatusBadGateway, "upstream_timeout", "上游平台超时"
		}
		s.db.LogResolve(u.Username, source, "", false, errCode, elapsed, false)
		s.db.LogError("warn", source, errCode, "四路解析失败", u.Username)
		writeJSON(w, status, map[string]any{
			"error": errCode, "message": msg, "trace": trace,
			"tried": order, "disabled": disabled,
		})
		return
	}

	res := out.Result
	// 兜底再校验：空 URL 或时长远短于请求值（试听片段）都不许回给客户端。
	if res == nil || strings.TrimSpace(res.URL) == "" {
		s.db.LogResolve(u.Username, source, "", false, "no_source", elapsed, false)
		writeJSON(w, http.StatusNotFound, map[string]any{
			"error": "no_source", "message": "上游未返回可播放地址", "trace": trace, "tried": order,
		})
		return
	}
	// 双向容差：既拦试听占位，也拦「治愈版/剪辑版」冒充原版（实测 wy「稻香」120s 冒充 269s）。
	if req.Duration >= 60 && res.DurationS > 0 && math.Abs(res.DurationS-float64(req.Duration)) > float64(durationToleranceRouter(req.Duration)) {
		s.db.LogResolve(u.Username, source, out.Platform, false, "no_source", elapsed, false)
		trace = append(trace, out.Platform+":short_guard")
		writeJSON(w, http.StatusNotFound, map[string]any{
			"error": "no_source", "message": "仅取到试听片段，未返回正片", "trace": trace, "tried": order,
		})
		return
	}

	warnings := append([]string{}, res.Warnings...)
	if warnings == nil {
		warnings = []string{}
	}

	if ttl := time.Until(time.Unix(res.ExpiresAt, 0)); ttl > 0 {
		if maxTTL := 6 * time.Hour; ttl > maxTTL {
			ttl = maxTTL
		}
		s.cache.Set(cacheKey, &cachedResult{Res: res, Platform: out.Platform}, ttl)
	}
	s.db.LogResolve(u.Username, source, out.Platform, true, "", elapsed, false)
	writeJSON(w, http.StatusOK, map[string]any{
		"url": res.URL, "level": res.Level, "actualBitrate": res.Bitrate,
		"duration": round1(res.DurationS), "size": res.Size,
		"resolvedBy": out.Platform, "expiresAt": res.ExpiresAt,
		"trace": trace, "cached": false, "warnings": warnings,
	})
}

// cachedResult 让缓存命中仍能如实回报真实命中平台。
type cachedResult struct {
	Res      *resolve.Result
	Platform string
}

// pinFirst 把 p 提到队首（已存在则移动，不存在则插入），其余保持原顺序。
func pinFirst(order []string, p string) []string {
	out := make([]string, 0, len(order)+1)
	out = append(out, p)
	for _, v := range order {
		if v == p {
			continue
		}
		out = append(out, v)
	}
	return out
}

// durationToleranceRouter 与 resolve 包保持一致：12%，最少 15 秒。
func durationToleranceRouter(want int) int {
	t := int(float64(want) * 0.12)
	if t < 15 {
		t = 15
	}
	return t
}
