package resolve

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ---------- QQ 音乐 ----------
//
// 实测结论（reports/18-server-phase2.md）：
//  1) smartbox_new.fcg 免签可用，用于「歌名 → songid」。
//  2) api.vkeys.cn/v2/music/tencent 对 VIP 曲只返回 28kbps 试听片段
//     （quality="音乐试听"，size 固定 0.92MB），必须识别并判为 ErrPaid，
//     否则客户端点播放只有 30 秒。
//  3) 对纯音乐/器乐曲，vkeys 会返回 flac 完整文件（Content-Range 正常）。

const vkeysTencent = "https://api.vkeys.cn/v2/music/tencent"

type txSmartbox struct {
	Code int `json:"code"`
	Data struct {
		Song struct {
			Count    int `json:"count"`
			ItemList []struct {
				ID     string `json:"id"`
				Mid    string `json:"mid"`
				Name   string `json:"name"`
				Singer string `json:"singer"`
			} `json:"itemlist"`
		} `json:"song"`
	} `json:"data"`
}

// SearchQQ 走 smartbox 免签联想接口。
func (r *Resolver) SearchQQ(ctx context.Context, keyword string, limit int) ([]TrackMeta, error) {
	if limit <= 0 || limit > 30 {
		limit = 20
	}
	u := "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=" + url.QueryEscape(keyword) + "&format=json"
	code, body, err := httpGet(ctx, r.client, u, "https://y.qq.com/", uaDesktop, 1<<20)
	if err != nil {
		return nil, err
	}
	if code != 200 {
		return nil, fmt.Errorf("qq smartbox status %d", code)
	}
	var resp txSmartbox
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, ErrBadPayload
	}
	if resp.Code != 0 {
		return nil, ErrNoSource
	}
	out := make([]TrackMeta, 0, len(resp.Data.Song.ItemList))
	for i, it := range resp.Data.Song.ItemList {
		if i >= limit {
			break
		}
		out = append(out, TrackMeta{
			Platform: "tx", ID: it.ID, Name: it.Name, Artist: it.Singer,
			Extra: map[string]string{"mid": it.Mid},
		})
	}
	return out, nil
}

type vkeysTencentResp struct {
	Code int    `json:"code"`
	Msg  string `json:"message"`
	Data struct {
		ID      int64  `json:"id"`
		Mid     string `json:"mid"`
		Song    string `json:"song"`
		Album   string `json:"album"`
		Singer  string `json:"singer"`
		Cover   string `json:"cover"`
		Pay     string `json:"pay"`
		Quality string `json:"quality"`
		Interval string `json:"interval"`
		Size    string `json:"size"`
		Kbps    string `json:"kbps"`
		URL     string `json:"url"`
	} `json:"data"`
}

// ResolveQQ 走 vkeys 网关。识别「音乐试听」并判为 ErrPaid。
func (r *Resolver) ResolveQQ(ctx context.Context, id string, q Quality) (*Result, error) {
	start := time.Now()
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, ErrNoSource
	}
	u := vkeysTencent + "?id=" + url.QueryEscape(id)
	code, body, err := httpGet(ctx, r.probe, u, "https://y.qq.com/", uaDesktop, 1<<20)
	if err != nil {
		return nil, err
	}
	if code != 200 {
		return nil, fmt.Errorf("vkeys status %d", code)
	}
	var resp vkeysTencentResp
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, ErrBadPayload
	}
	if resp.Code != 200 || strings.TrimSpace(resp.Data.URL) == "" {
		return nil, ErrNoSource
	}
	if isQQTrial(resp.Data.Quality, resp.Data.Kbps, resp.Data.Size) {
		return nil, ErrPaid
	}
	res := &Result{
		URL:       resp.Data.URL,
		Level:     "high",
		Bitrate:   320,
		ExpiresAt: time.Now().Add(2 * time.Hour).Unix(),
		ElapsedMS: time.Since(start).Milliseconds(),
		Warnings:  []string{"qq:via_vkeys_gateway"},
	}
	if d := parseInterval(resp.Data.Interval); d > 0 {
		res.DurationS = d
	}
	if p, perr := r.probeAny(ctx, resp.Data.URL, res.DurationS); perr == nil {
		if p.size > 0 {
			res.Size = p.size
		}
		if p.bitrate > 0 {
			res.Bitrate = p.bitrate
			res.Level = levelOfBitrate(p.bitrate)
		}
		if res.DurationS == 0 && p.durationS > 0 {
			res.DurationS = p.durationS
		}
	}
	if res.Size > 0 && res.Size < 300*1024 {
		return res, ErrPlaceholder
	}
	return res, nil
}

func parseInterval(s string) float64 {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	var min, sec int
	if _, err := fmt.Sscanf(s, "%d分%d秒", &min, &sec); err == nil {
		return float64(min*60 + sec)
	}
	if n, err := strconv.Atoi(s); err == nil {
		return float64(n)
	}
	return 0
}

// QQPlaylist 走 y.qq.com 歌单详情接口（免签）。
func (r *Resolver) QQPlaylist(ctx context.Context, disstid string, offset, limit int) (string, int, []TrackMeta, error) {
	u := "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0" +
		"&disstid=" + url.QueryEscape(disstid) +
		"&format=json&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
	code, body, err := httpGet(ctx, r.client, u, "https://y.qq.com/", uaDesktop, 8<<20)
	if err != nil {
		return "", 0, nil, err
	}
	if code != 200 {
		return "", 0, nil, fmt.Errorf("qq playlist status %d", code)
	}
	var resp struct {
		Code   int `json:"code"`
		CdList []struct {
			DissName string `json:"dissname"`
			SongNum  int    `json:"songnum"`
			SongList []struct {
				SongID   int64  `json:"songid"`
				SongName string `json:"songname"`
				AlbumName string `json:"albumname"`
				Interval int    `json:"interval"`
				Singer   []struct {
					Name string `json:"name"`
				} `json:"singer"`
			} `json:"songlist"`
		} `json:"cdlist"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return "", 0, nil, ErrBadPayload
	}
	if resp.Code != 0 || len(resp.CdList) == 0 {
		return "", 0, nil, ErrNoSource
	}
	cd := resp.CdList[0]
	if limit <= 0 {
		limit = 100
	}
	all := cd.SongList
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
		names := make([]string, 0, len(s.Singer))
		for _, a := range s.Singer {
			names = append(names, a.Name)
		}
		songs = append(songs, TrackMeta{
			Platform: "tx", ID: strconv.FormatInt(s.SongID, 10), Name: s.SongName,
			Artist: strings.Join(names, "/"), Album: s.AlbumName, Duration: s.Interval,
		})
	}
	return cd.DissName, cd.SongNum, songs, nil
}

// errIsRetryable 判断是否值得换下一个候选（付费/片段不重试同一 id）。
func errIsRetryable(err error) bool {
	return !errors.Is(err, ErrPaid)
}
// isQQTrial 判断 vkeys 返回的是否为"试听片段"。
// 实测：VIP 曲固定返回 quality="音乐试听"、kbps≈23~28、size="0.92MB"；
// 而付费的器乐曲会返回完整 flac（kbps 400+ / size 数 MB），必须放行。
func isQQTrial(quality, kbps, size string) bool {
	if strings.Contains(quality, "试听") {
		return true
	}
	if strings.Contains(size, "0.92MB") {
		return true
	}
	var n int
	if _, err := fmt.Sscanf(strings.TrimSuffix(strings.TrimSpace(kbps), "kbps"), "%d", &n); err == nil && n > 0 && n <= 40 {
		return true
	}
	return false
}