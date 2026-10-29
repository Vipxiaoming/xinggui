package router

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"xinggui/internal/resolve"
)

// ---------- 歌单解析 ----------

type playlistParseReq struct {
	Input string `json:"input"`
}

type playlistCandidate struct {
	Platform  string `json:"platform"`
	ID        string `json:"id"`
	Name      string `json:"name"`
	SongCount int    `json:"songCount"`
	Cover     string `json:"cover,omitempty"`
}

// detectPlaylist 识别四平台歌单链接与纯数字 ID。
func detectPlaylist(input string) ([]playlistCandidate, bool) {
	s := strings.TrimSpace(input)
	if s == "" {
		return nil, false
	}
	lower := strings.ToLower(s)

	// 纯数字：网易云 + 酷狗都可能，按契约返回多候选
	if isAllDigits(s) {
		return []playlistCandidate{
			{Platform: "wy", ID: s, Name: "网易云歌单"},
			{Platform: "kg", ID: s, Name: "酷狗歌单"},
		}, true
	}

	var out []playlistCandidate
	// 网易云
	if strings.Contains(lower, "music.163.com") || strings.Contains(lower, "163cn.tv") {
		if id := extractQuery(s, "id"); id != "" {
			out = append(out, playlistCandidate{Platform: "wy", ID: id, Name: "网易云歌单"})
		} else if id := lastPathSegment(s); id != "" && isAllDigits(id) {
			out = append(out, playlistCandidate{Platform: "wy", ID: id, Name: "网易云歌单"})
		}
	}
	// QQ 音乐
	if strings.Contains(lower, "y.qq.com") || strings.Contains(lower, "qq.com") && strings.Contains(lower, "playlist") {
		if id := extractQuery(s, "id"); id != "" {
			out = append(out, playlistCandidate{Platform: "tx", ID: id, Name: "QQ 音乐歌单"})
		} else if id := extractQuery(s, "disstid"); id != "" {
			out = append(out, playlistCandidate{Platform: "tx", ID: id, Name: "QQ 音乐歌单"})
		} else if id := lastPathSegment(s); id != "" && isAllDigits(id) {
			out = append(out, playlistCandidate{Platform: "tx", ID: id, Name: "QQ 音乐歌单"})
		}
	}
	// 酷狗：gcid_xxx / collection_xxx / special/song?specialid=
	if strings.Contains(lower, "kugou.com") {
		if id := extractQuery(s, "specialid"); id != "" {
			out = append(out, playlistCandidate{Platform: "kg", ID: id, Name: "酷狗歌单"})
		} else if i := strings.Index(lower, "gcid_"); i >= 0 {
			id := takeWhile(s[i:], func(r rune) bool {
				return r == '_' || (r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z')
			})
			out = append(out, playlistCandidate{Platform: "kg", ID: id, Name: "酷狗歌单(gcid)"})
		} else if i := strings.Index(lower, "collection_"); i >= 0 {
			id := takeWhile(s[i:], func(r rune) bool {
				return r == '_' || (r >= '0' && r <= '9')
			})
			out = append(out, playlistCandidate{Platform: "kg", ID: id, Name: "酷狗歌单(collection)"})
		}
	}
	// 酷我：pid=
	if strings.Contains(lower, "kuwo.cn") {
		if id := extractQuery(s, "pid"); id != "" {
			out = append(out, playlistCandidate{Platform: "kw", ID: id, Name: "酷我歌单"})
		} else if id := lastPathSegment(s); id != "" && isAllDigits(id) {
			out = append(out, playlistCandidate{Platform: "kw", ID: id, Name: "酷我歌单"})
		}
	}
	if len(out) == 0 {
		return nil, false
	}
	return out, true
}

func isAllDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func extractQuery(raw, key string) string {
	i := strings.Index(raw, "?")
	if i < 0 {
		return ""
	}
	for _, kv := range strings.Split(raw[i+1:], "&") {
		p := strings.SplitN(kv, "=", 2)
		if len(p) == 2 && strings.EqualFold(p[0], key) {
			return strings.TrimSpace(p[1])
		}
	}
	return ""
}

func lastPathSegment(raw string) string {
	if i := strings.IndexAny(raw, "?#"); i >= 0 {
		raw = raw[:i]
	}
	parts := strings.Split(strings.TrimRight(raw, "/"), "/")
	if len(parts) == 0 {
		return ""
	}
	return strings.TrimSpace(parts[len(parts)-1])
}

func takeWhile(s string, ok func(rune) bool) string {
	for i, r := range s {
		if !ok(r) {
			return s[:i]
		}
	}
	return s
}

func (s *Server) handlePlaylistParse(w http.ResponseWriter, r *http.Request) {
	var req playlistParseReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
		return
	}
	cands, ok := detectPlaylist(req.Input)
	if !ok {
		fail(w, http.StatusBadRequest, "unrecognized_link", "无法识别该链接")
		return
	}
	// 单候选时补齐真实名称与曲目数；多候选保留轻量返回（避免串行等待四家）
	if len(cands) == 1 {
		ctx, cancel := contextWithTimeout(r, 8*time.Second)
		defer cancel()
		c := cands[0]
		if name, total, _, err := s.fetchPlaylist(ctx, c.Platform, c.ID, 0, 1); err == nil {
			cands[0].Name = name
			cands[0].SongCount = total
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"candidates": cands})
}

type playlistSongsReq struct {
	Platform string `json:"platform"`
	ID       string `json:"id"`
	Offset   int    `json:"offset"`
	Limit    int    `json:"limit"`
}

func (s *Server) handlePlaylistSongs(w http.ResponseWriter, r *http.Request) {
	var req playlistSongsReq
	if err := decodeJSON(r, &req); err != nil {
		fail(w, http.StatusBadRequest, "invalid_request", "请求体格式错误")
		return
	}
	p := resolve.NormalizePlatform(req.Platform)
	if p == "" {
		fail(w, http.StatusBadRequest, "unrecognized_link", "未知平台")
		return
	}
	if strings.TrimSpace(req.ID) == "" {
		fail(w, http.StatusBadRequest, "unrecognized_link", "缺少歌单 ID")
		return
	}
	if req.Limit <= 0 || req.Limit > 300 {
		req.Limit = 100
	}
	ctx, cancel := contextWithTimeout(r, 15*time.Second)
	defer cancel()

	name, total, songs, err := s.fetchPlaylist(ctx, p, req.ID, req.Offset, req.Limit)
	if err != nil {
		code := http.StatusBadGateway
		errCode := "upstream_error"
		if errors.Is(err, resolve.ErrNoSource) || errors.Is(err, resolve.ErrBadPayload) {
			code = http.StatusNotFound
			errCode = "no_source"
		}
		fail(w, code, errCode, "歌单拉取失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"platform": p, "id": req.ID, "name": name, "total": total,
		"offset": req.Offset, "songs": songs,
	})
}

func (s *Server) fetchPlaylist(ctx context.Context, platform, id string, offset, limit int) (string, int, []resolve.TrackMeta, error) {
	switch platform {
	case "wy":
		return s.res.NeteasePlaylist(ctx, id, offset, limit)
	case "tx":
		return s.res.QQPlaylist(ctx, id, offset, limit)
	case "kg":
		if strings.HasPrefix(id, "collection_") || strings.HasPrefix(id, "gcid_") {
			return "", 0, nil, resolve.ErrNoSource
		}
		return s.res.KugouPlaylist(ctx, id, offset, limit)
	case "kw":
		return s.kwPlaylist(ctx, id, offset, limit)
	}
	return "", 0, nil, resolve.ErrNoSource
}

func (s *Server) kwPlaylist(ctx context.Context, id string, offset, limit int) (string, int, []resolve.TrackMeta, error) {
	name, total, songs, err := s.res.KuwoPlaylist(ctx, id, offset, limit)
	return name, total, songs, err
}

func versionOf(v string) int {
	parts := strings.Split(v, ".")
	code := 0
	for i := 0; i < len(parts) && i < 3; i++ {
		n, _ := strconv.Atoi(parts[i])
		code = code*100 + n
	}
	return code
}