package resolve

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// 第二阶段新增的错误分类，便于路由层映射 HTTP 状态。
var (
	ErrPaid       = errors.New("upstream_paid")
	ErrRateLimit  = errors.New("upstream_rate_limited")
	ErrTimeout    = errors.New("upstream_timeout")
	ErrBadPayload = errors.New("upstream_bad_payload")
)

// platformOrder 是默认解析顺序（管理页可覆盖）。
var platformOrder = []string{"wy", "tx", "kg", "kw"}

// NormalizePlatform 把外部写法归一化为 wy/tx/kg/kw。
func NormalizePlatform(s string) string {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "wy", "netease", "163", "wangyi":
		return "wy"
	case "tx", "qq", "tencent", "qqmusic":
		return "tx"
	case "kg", "kugou":
		return "kg"
	case "kw", "kuwo":
		return "kw"
	default:
		return ""
	}
}

// SetPlatformOrder 允许管理页下发顺序。
func SetPlatformOrder(order []string) {
	out := make([]string, 0, len(order))
	seen := map[string]bool{}
	for _, p := range order {
		n := NormalizePlatform(p)
		if n == "" || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	if len(out) == 0 {
		return
	}
	platformOrder = out
}

// PlatformOrder 返回当前顺序副本。
func PlatformOrder() []string {
	out := make([]string, len(platformOrder))
	copy(out, platformOrder)
	return out
}

// probeInfo 是对外暴露的探测结果。
type probeInfo struct {
	size      int64
	bitrate   int
	durationS float64
	ctype     string
}

// probeAny 探测直链：先看 content-type 决定解析方式。
// MP3 走帧头；FLAC/OGG 按体积与时长估算码率（帧头同步字在 FLAC 里会误命中）。
func (r *Resolver) probeAny(ctx context.Context, rawURL string, knownDuration float64) (*probeInfo, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Range", "bytes=0-8191")
	req.Header.Set("User-Agent", uaDesktop)
	resp, err := r.probe.Do(req)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return nil, ErrTimeout
		}
		return nil, err
	}
	defer resp.Body.Close()

	out := &probeInfo{ctype: strings.ToLower(resp.Header.Get("Content-Type"))}
	if cr := resp.Header.Get("Content-Range"); cr != "" {
		if i := strings.LastIndex(cr, "/"); i >= 0 {
			if n, e := strconv.ParseInt(strings.TrimSpace(cr[i+1:]), 10, 64); e == nil {
				out.size = n
			}
		}
	}
	if out.size == 0 {
		if n, e := strconv.ParseInt(resp.Header.Get("Content-Length"), 10, 64); e == nil {
			out.size = n
		}
	}
	buf, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))

	isMP3 := strings.Contains(out.ctype, "mpeg") || strings.Contains(out.ctype, "mp3")
	if !isMP3 && len(buf) >= 4 && string(buf[:4]) == "fLaC" {
		out.ctype = "audio/flac"
	} else if !isMP3 && len(buf) >= 4 && string(buf[:4]) == "OggS" {
		out.ctype = "audio/ogg"
	} else if !isMP3 && out.ctype == "" {
		isMP3 = true
	}

	if isMP3 || strings.Contains(out.ctype, "mpeg") {
		if br, ok := mp3Bitrate(buf); ok {
			out.bitrate = br
			if out.size > 0 {
				out.durationS = float64(out.size) * 8 / float64(br) / 1000
			}
			return out, nil
		}
	}
	out.durationS = knownDuration
	if out.size > 0 && out.durationS > 0 {
		out.bitrate = int(float64(out.size) * 8 / out.durationS / 1000)
	}
	return out, nil
}

// mp3Bitrate 从缓冲里找第一个合法 MPEG 音频帧头，返回码率 kbps。
func mp3Bitrate(buf []byte) (int, bool) {
	off := 0
	if len(buf) >= 10 && string(buf[:3]) == "ID3" {
		size := int(buf[6])<<21 | int(buf[7])<<14 | int(buf[8])<<7 | int(buf[9])
		off = 10 + size
	}
	for i := off; i+4 <= len(buf); i++ {
		if buf[i] != 0xFF || buf[i+1]&0xE0 != 0xE0 {
			continue
		}
		hdr := binary.BigEndian.Uint32(buf[i : i+4])
		ver := (hdr >> 19) & 0x3
		layer := (hdr >> 17) & 0x3
		brIdx := int((hdr >> 12) & 0xF)
		srIdx := int((hdr >> 10) & 0x3)
		if layer != 1 || brIdx == 0 || brIdx == 15 || srIdx == 3 {
			continue
		}
		var br int
		if ver == 3 {
			br = bitrateTableV1L3[brIdx]
		} else if ver == 2 || ver == 0 {
			br = bitrateTableV2L3[brIdx]
		}
		if br > 0 {
			return br, true
		}
	}
	return 0, false
}
const (
	uaMobile  = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
	uaDesktop = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
	uaIPhone  = "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.0 Mobile/15E148 Safari/604.1"
)

// httpGet 是带 UA/Referer 与超时的 GET，返回 body（限长）。
func httpGet(ctx context.Context, c *http.Client, rawURL, referer, ua string, limit int64) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return 0, nil, err
	}
	if ua != "" {
		req.Header.Set("User-Agent", ua)
	}
	if referer != "" {
		req.Header.Set("Referer", referer)
	}
	resp, err := c.Do(req)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return 0, nil, ErrTimeout
		}
		return 0, nil, err
	}
	defer resp.Body.Close()
	body, rerr := io.ReadAll(io.LimitReader(resp.Body, limit))
	if rerr != nil {
		return resp.StatusCode, nil, rerr
	}
	return resp.StatusCode, body, nil
}

func clampTimeout(d time.Duration) time.Duration {
	if d <= 0 || d > 8*time.Second {
		return 8 * time.Second
	}
	return d
}