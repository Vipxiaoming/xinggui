package resolve

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ---------- 酷狗 ----------

// kgH5Salt 是 smali 中还原出的 H5 签名盐（reports/15-kugou-signature.md 实测确认）。
const kgH5Salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"

// SignKugouH5 复刻 smali signKugouH5：
// md5Hex(salt + Σ(排序后 k=v，无分隔符) + salt)，小写 32 位。
func SignKugouH5(params map[string]string) string {
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	b.WriteString(kgH5Salt)
	for _, k := range keys {
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(params[k])
	}
	b.WriteString(kgH5Salt)
	sum := md5.Sum([]byte(b.String()))
	return hex.EncodeToString(sum[:])
}

// KugouBaseParams 生成基础参数集（srcappid/clientver/clienttime/mid/uuid/dfid）。
func KugouBaseParams(clienttime, mid string) map[string]string {
	return map[string]string{
		"srcappid":   "2919",
		"clientver":  "20000",
		"clienttime": clienttime,
		"mid":        mid,
		"uuid":       mid,
		"dfid":       "-",
	}
}

// KugouMid 生成 mid = md5(uuid)，与 smali 一致。
func KugouMid() string {
	buf := make([]byte, 16)
	for i := range buf {
		buf[i] = byte(time.Now().UnixNano() >> (i % 8 * 8))
	}
	sum := md5.Sum([]byte(fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		buf[0:4], buf[4:6], buf[6:8], buf[8:10], buf[10:16])))
	return hex.EncodeToString(sum[:])
}

type kgSearchResp struct {
	Status  int    `json:"status"`
	Errcode int    `json:"errcode"`
	Error   string `json:"error"`
	Data    struct {
		Total int `json:"total"`
		Info  []struct {
			SongName   string `json:"songname"`
			SingerName string `json:"singername"`
			Hash       string `json:"hash"`
			AlbumID    string `json:"album_id"`
			AlbumName  string `json:"album_name"`
			Duration   int    `json:"duration"`
			PayType    int    `json:"pay_type"`
			Privilege  int    `json:"privilege"`
			Filesize   int64  `json:"filesize"`
			HQHash     string `json:"320hash"`
			SQHash     string `json:"sqhash"`
			ExtName    string `json:"extname"`
		} `json:"info"`
	} `json:"data"`
}

// SearchKugou 走免签 v3 搜索接口。
func (r *Resolver) SearchKugou(ctx context.Context, keyword string, limit int) ([]TrackMeta, error) {
	if limit <= 0 || limit > 30 {
		limit = 20
	}
	u := "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=" +
		url.QueryEscape(keyword) + "&page=1&pagesize=" + strconv.Itoa(limit) + "&showtype=1"
	code, body, err := httpGet(ctx, r.client, u, "http://m.kugou.com/", uaMobile, 1<<20)
	if err != nil {
		return nil, err
	}
	if code != 200 {
		return nil, fmt.Errorf("kugou search status %d", code)
	}
	var resp kgSearchResp
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, ErrBadPayload
	}
	if resp.Status != 1 {
		return nil, ErrNoSource
	}
	out := make([]TrackMeta, 0, len(resp.Data.Info))
	for _, it := range resp.Data.Info {
		out = append(out, TrackMeta{
			Platform: "kg",
			ID:       it.Hash,
			Name:     it.SongName,
			Artist:   it.SingerName,
			Album:    it.AlbumName,
			Duration: it.Duration,
			Extra: map[string]string{
				"albumId": it.AlbumID,
				"payType": strconv.Itoa(it.PayType),
				"320hash": it.HQHash,
			},
		})
	}
	return out, nil
}

type kgSongInfo struct {
	Status     int    `json:"status"`
	Errcode    int    `json:"errcode"`
	Error      string `json:"error"`
	URL        string `json:"url"`
	BitRate    int    `json:"bitRate"`
	TimeLength int    `json:"timeLength"`
	FileSize   int64  `json:"fileSize"`
	SongName   string `json:"songName"`
	AuthorName string `json:"author_name"`
	PayType    int    `json:"pay_type"`
}

// ResolveKugou 取酷狗直链。免费曲返回 128k mp3；付费曲返回 error=需要付费。
func (r *Resolver) ResolveKugou(ctx context.Context, hash string, q Quality) (*Result, error) {
	start := time.Now()
	hash = strings.TrimSpace(hash)
	if hash == "" {
		return nil, ErrNoSource
	}
	mid := KugouMid()
	u := "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + url.QueryEscape(hash) + "&from=mkugou"
	req, err := newReq(ctx, u, "http://m.kugou.com/", uaIPhone)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Cookie", "kg_mid="+mid+"; kg_dfid=-")
	resp, err := r.client.Do(req)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return nil, ErrTimeout
		}
		return nil, err
	}
	defer resp.Body.Close()
	var info kgSongInfo
	if err := json.NewDecoder(resp.Body).Decode(&info); err != nil {
		return nil, ErrBadPayload
	}
	if strings.TrimSpace(info.URL) == "" {
		msg := strings.TrimSpace(info.Error)
		switch {
		case strings.Contains(msg, "付费") || strings.Contains(msg, "pay"):
			return nil, ErrPaid
		case strings.Contains(msg, "not found"):
			return nil, ErrNoSource
		case msg != "":
			return nil, fmt.Errorf("kugou: %s", msg)
		default:
			return nil, ErrNoSource
		}
	}
	res := &Result{
		URL:       info.URL,
		Bitrate:   info.BitRate,
		Level:     levelOfBitrate(info.BitRate),
		Size:      info.FileSize,
		DurationS: float64(info.TimeLength),
		ExpiresAt: time.Now().Add(2 * time.Hour).Unix(),
		ElapsedMS: time.Since(start).Milliseconds(),
	}
	if p, perr := r.probeAny(ctx, info.URL, float64(info.TimeLength)); perr == nil {
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
		// 酷狗免费曲实测 128k 完整曲 ≈ 2.9MB 起，过小视为片段
		return res, ErrPlaceholder
	}
	return res, nil
}

func levelOfBitrate(br int) string {
	switch {
	case br >= 500:
		return "lossless"
	case br >= 256:
		return "high"
	case br > 0:
		return "standard"
	default:
		return "standard"
	}
}

// KugouPlaylist 走免签 special/song 接口取歌单歌曲。
func (r *Resolver) KugouPlaylist(ctx context.Context, specialID string, offset, limit int) (string, int, []TrackMeta, error) {
	if limit <= 0 || limit > 300 {
		limit = 100
	}
	page := offset/limit + 1
	u := "http://mobilecdnbj.kugou.com/api/v5/special/song?specialid=" + url.QueryEscape(specialID) +
		"&page=" + strconv.Itoa(page) + "&pagesize=" + strconv.Itoa(limit)
	code, body, err := httpGet(ctx, r.client, u, "http://m.kugou.com/", uaMobile, 2<<20)
	if err != nil {
		return "", 0, nil, err
	}
	if code != 200 {
		return "", 0, nil, fmt.Errorf("kugou playlist status %d", code)
	}
	var resp struct {
		Errcode int `json:"errcode"`
		Data    struct {
			Total int `json:"total"`
			Info  []struct {
				FileName string `json:"filename"`
				Hash     string `json:"hash"`
				AlbumID  string `json:"album_id"`
				Duration int    `json:"duration"`
				Timelen  int    `json:"timelen"`
			} `json:"info"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return "", 0, nil, ErrBadPayload
	}
	if resp.Errcode != 0 {
		return "", 0, nil, ErrNoSource
	}
	songs := make([]TrackMeta, 0, len(resp.Data.Info))
	for _, it := range resp.Data.Info {
		name, artist := splitKgFilename(it.FileName)
		dur := it.Duration
		if dur == 0 {
			dur = it.Timelen / 1000
		}
		songs = append(songs, TrackMeta{
			Platform: "kg", ID: it.Hash, Name: name, Artist: artist, Duration: dur,
			Extra: map[string]string{"albumId": it.AlbumID},
		})
	}
	return "酷狗歌单", resp.Data.Total, songs, nil
}

func splitKgFilename(s string) (name, artist string) {
	if i := strings.Index(s, " - "); i > 0 {
		return strings.TrimSpace(s[i+3:]), strings.TrimSpace(s[:i])
	}
	return strings.TrimSpace(s), ""
}
