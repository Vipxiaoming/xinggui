package resolve

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// KuwoPlaylist 走 nplserver.kuwo.cn 免签歌单接口。
// 实测该接口对部分歌单返回的 musiclist 长度小于 total（服务端分页不稳定），
// 因此以返回条数为准，total 单独透传。
func (r *Resolver) KuwoPlaylist(ctx context.Context, pid string, offset, limit int) (string, int, []TrackMeta, error) {
	if limit <= 0 || limit > 300 {
		limit = 100
	}
	pn := offset / limit
	u := "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=" + url.QueryEscape(pid) +
		"&pn=" + strconv.Itoa(pn) + "&rn=" + strconv.Itoa(limit) +
		"&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=1"
	code, body, err := httpGet(ctx, r.client, u, "http://www.kuwo.cn/", uaDesktop, 8<<20)
	if err != nil {
		return "", 0, nil, err
	}
	if code != 200 {
		return "", 0, nil, fmt.Errorf("kuwo playlist status %d", code)
	}
	var resp struct {
		Title     string `json:"title"`
		Total     any    `json:"total"`
		MusicList []struct {
			Name     string `json:"NAME"`
			Artist   string `json:"ARTIST"`
			Album    string `json:"ALBUM"`
			Duration any    `json:"DURATION"`
			// 实测该接口用 id 字段承载 rid（MUSICRID 为空）；两者都取，优先非空。
			ID       any    `json:"id"`
			RID      any    `json:"MUSICRID"`
		} `json:"musiclist"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return "", 0, nil, ErrBadPayload
	}
	total := anyToInt(resp.Total)
	songs := make([]TrackMeta, 0, len(resp.MusicList))
	for _, m := range resp.MusicList {
		rid := strings.TrimPrefix(anyToString(m.RID), "MUSIC_")
		if rid == "" {
			rid = anyToString(m.ID)
		}
		songs = append(songs, TrackMeta{
			Platform: "kw", ID: rid,
			Name:     unescapeKw(m.Name), Artist: unescapeKw(m.Artist), Album: unescapeKw(m.Album),
			Duration: anyToInt(m.Duration),
		})
	}
	name := strings.TrimSpace(resp.Title)
	if name == "" {
		name = "酷我歌单"
	}
	return name, total, songs, nil
}

func anyToString(v any) string {
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t)
	case float64:
		return strconv.FormatInt(int64(t), 10)
	case int64:
		return strconv.FormatInt(t, 10)
	}
	return ""
}

func anyToInt(v any) int {
	switch t := v.(type) {
	case float64:
		return int(t)
	case string:
		n, _ := strconv.Atoi(strings.TrimSpace(t))
		return n
	}
	return 0
}