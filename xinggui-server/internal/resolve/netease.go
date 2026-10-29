package resolve

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// TrackMeta 是跨平台统一的歌曲元数据。
type TrackMeta struct {
	Platform string            `json:"platform"`
	ID       string            `json:"id"`
	Name     string            `json:"name"`
	Artist   string            `json:"artist"`
	Album    string            `json:"album"`
	Duration int               `json:"duration"`
	Cover    string            `json:"cover,omitempty"`
	Extra    map[string]string `json:"-"`
}

func newReq(ctx context.Context, rawURL, referer, ua string) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	if ua != "" {
		req.Header.Set("User-Agent", ua)
	}
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	return req, nil
}

// ---------- 网易云 ----------

type wySearchResp struct {
	Result struct {
		Songs []struct {
			ID       int64  `json:"id"`
			Name     string `json:"name"`
			Duration int    `json:"duration"`
			Artists  []struct {
				Name string `json:"name"`
			} `json:"artists"`
			Album struct {
				Name    string `json:"name"`
				PicID   int64  `json:"picId"`
				PicURL  string `json:"picUrl"`
			} `json:"album"`
		} `json:"songs"`
	} `json:"result"`
	Code int `json:"code"`
}

// SearchNetease 走 music.163.com 免签 web 搜索接口。
func (r *Resolver) SearchNetease(ctx context.Context, keyword string, limit int) ([]TrackMeta, error) {
	if limit <= 0 || limit > 30 {
		limit = 20
	}
	u := "https://music.163.com/api/search/get/web?s=" + url.QueryEscape(keyword) +
		"&type=1&offset=0&limit=" + strconv.Itoa(limit)
	code, body, err := httpGet(ctx, r.client, u, "https://music.163.com/", uaDesktop, 2<<20)
	if err != nil {
		return nil, err
	}
	if code != 200 {
		return nil, fmt.Errorf("netease search status %d", code)
	}
	var resp wySearchResp
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, ErrBadPayload
	}
	out := make([]TrackMeta, 0, len(resp.Result.Songs))
	for _, s := range resp.Result.Songs {
		names := make([]string, 0, len(s.Artists))
		for _, a := range s.Artists {
			names = append(names, a.Name)
		}
		out = append(out, TrackMeta{
			Platform: "wy",
			ID:       strconv.FormatInt(s.ID, 10),
			Name:     s.Name,
			Artist:   strings.Join(names, "/"),
			Album:    s.Album.Name,
			Duration: s.Duration / 1000,
			Cover:    s.Album.PicURL,
		})
	}
	return out, nil
}

// ResolveNetease 用外链跳转接口取直链。
// 关键实测：该接口对无版权曲返回 302 → /404，跟随后 content-type 变成 text/html，
// 必须跟随并检查最终 content-type，不能只看 302。
func (r *Resolver) ResolveNetease(ctx context.Context, id string, q Quality) (*Result, error) {
	start := time.Now()
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, ErrNoSource
	}
	outer := "https://music.163.com/song/media/outer/url?id=" + url.QueryEscape(id) + ".mp3"
	req, err := newReq(ctx, outer, "https://music.163.com/", uaDesktop)
	if err != nil {
		return nil, err
	}
	resp, err := r.probe.Do(req)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return nil, ErrTimeout
		}
		return nil, err
	}
	defer resp.Body.Close()
	ct := strings.ToLower(resp.Header.Get("Content-Type"))
	finalURL := resp.Request.URL.String()
	if strings.Contains(finalURL, "/404") || strings.HasPrefix(ct, "text/html") {
		return nil, ErrPaid
	}
	if resp.StatusCode != 200 || !strings.HasPrefix(ct, "audio/") {
		return nil, ErrNoSource
	}
	res := &Result{
		URL:       finalURL,
		Level:     "standard",
		Bitrate:   128,
		ExpiresAt: time.Now().Add(2 * time.Hour).Unix(),
		ElapsedMS: time.Since(start).Milliseconds(),
	}
	if n, e := strconv.ParseInt(resp.Header.Get("Content-Length"), 10, 64); e == nil {
		res.Size = n
	}
	if p, perr := r.probeAny(ctx, finalURL, res.DurationS); perr == nil {
		if p.size > 0 {
			res.Size = p.size
		}
		if p.bitrate > 0 {
			res.Bitrate = p.bitrate
			res.Level = levelOfBitrate(p.bitrate)
		}
		if p.durationS > 0 {
			res.DurationS = p.durationS
		}
	}
	if res.Size > 0 && res.Size < 300*1024 {
		return res, ErrPlaceholder
	}
	return res, nil
}

type wyPlaylistResp struct {
	Result struct {
		Name       string `json:"name"`
		TrackCount int    `json:"trackCount"`
		Tracks     []struct {
			ID       int64  `json:"id"`
			Name     string `json:"name"`
			Duration int    `json:"duration"`
			Artists  []struct {
				Name string `json:"name"`
			} `json:"artists"`
			Album struct {
				Name   string `json:"name"`
				PicURL string `json:"picUrl"`
			} `json:"album"`
		} `json:"tracks"`
	} `json:"result"`
	Code int `json:"code"`
}

// NeteasePlaylist 取歌单详情（最多 1000 首）。
func (r *Resolver) NeteasePlaylist(ctx context.Context, id string, offset, limit int) (string, int, []TrackMeta, error) {
	u := "https://music.163.com/api/playlist/detail?id=" + url.QueryEscape(id)
	code, body, err := httpGet(ctx, r.client, u, "https://music.163.com/", uaDesktop, 8<<20)
	if err != nil {
		return "", 0, nil, err
	}
	if code != 200 {
		return "", 0, nil, fmt.Errorf("netease playlist status %d", code)
	}
	var resp wyPlaylistResp
	if err := json.Unmarshal(body, &resp); err != nil {
		return "", 0, nil, ErrBadPayload
	}
	if resp.Code != 200 {
		return "", 0, nil, ErrNoSource
	}
	if limit <= 0 {
		limit = 100
	}
	all := resp.Result.Tracks
	from := offset
	if from < 0 {
		from = 0
	}
	if from > len(all) {
		from = len(all)
	}
	to := from + limit
	if to > len(all) {
		to = len(all)
	}
	songs := make([]TrackMeta, 0, to-from)
	for _, s := range all[from:to] {
		names := make([]string, 0, len(s.Artists))
		for _, a := range s.Artists {
			names = append(names, a.Name)
		}
		songs = append(songs, TrackMeta{
			Platform: "wy", ID: strconv.FormatInt(s.ID, 10), Name: s.Name,
			Artist: strings.Join(names, "/"), Album: s.Album.Name,
			Duration: s.Duration / 1000, Cover: s.Album.PicURL,
		})
	}
	return resp.Result.Name, resp.Result.TrackCount, songs, nil
}