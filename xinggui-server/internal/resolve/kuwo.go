package resolve

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const kuwoAntiServer = "http://antiserver.kuwo.cn/anti.s"
const kuwoSearch = "http://search.kuwo.cn/r.s"

// placeholderSize 是酷我“试听占位”文件的固定字节数（实测 181521 B / 约 4.5s）。
// 多个不同 rid 会返回同一文件，因此不能当作正片。
const placeholderSize int64 = 181521

var (
	ErrNoSource    = errors.New("no_source")
	ErrBadQuality  = errors.New("bad_quality")
	ErrPlaceholder = errors.New("placeholder_audio")
	ErrRefused     = errors.New("upstream_refused")
)

// Quality 映射：客户端 quality -> 酷我 br 参数与期望码率
type Quality struct {
	BR      string
	Bitrate int
	Level   string
}

func MapQuality(q string) (Quality, error) {
	switch strings.ToLower(strings.TrimSpace(q)) {
	case "", "320k", "320", "high", "hq":
		return Quality{BR: "320kbps", Bitrate: 320, Level: "high"}, nil
	case "128k", "128", "standard", "std":
		return Quality{BR: "128kbps", Bitrate: 128, Level: "standard"}, nil
	case "flac", "lossless", "sq":
		return Quality{BR: "flac", Bitrate: 0, Level: "lossless"}, nil
	default:
		return Quality{}, ErrBadQuality
	}
}

// Result 是解析结果。Bitrate 为实测值（来自 MP3 帧头），非请求值。
type Result struct {
	URL       string
	Bitrate   int
	Level     string
	Size      int64
	DurationS float64
	ExpiresAt int64
	ElapsedMS int64
	Warnings  []string
}

type Resolver struct {
	client *http.Client
	probe  *http.Client
}

func NewResolver() *Resolver {
	return &Resolver{
		client: &http.Client{Timeout: 8 * time.Second},
		probe:  &http.Client{Timeout: 10 * time.Second},
	}
}

// ---------- 直链获取 ----------

func (r *Resolver) convertURL(ctx context.Context, rid, br string) (string, error) {
	qs := url.Values{}
	qs.Set("type", "convert_url")
	qs.Set("rid", rid)
	qs.Set("format", "mp3")
	qs.Set("response", "url")
	qs.Set("br", br)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, kuwoAntiServer+"?"+qs.Encode(), nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
	req.Header.Set("Referer", "http://www.kuwo.cn/")

	resp, err := r.client.Do(req)
	if err != nil {
		return "", fmt.Errorf("kuwo request: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if err != nil {
		return "", fmt.Errorf("kuwo read: %w", err)
	}
	text := strings.TrimSpace(string(body))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("kuwo status %d", resp.StatusCode)
	}
	if !strings.HasPrefix(text, "http://") && !strings.HasPrefix(text, "https://") {
		// 酷我在无版权/无音质时返回 "refuse request!" 之类的纯文本
		if strings.Contains(strings.ToLower(text), "refuse") {
			return "", ErrRefused
		}
		return "", ErrNoSource
	}
	return text, nil
}

// Resolve 按请求音质取直链；flac 不被支持时自动降级到 320k 并在 Warnings 标注。
func (r *Resolver) Resolve(ctx context.Context, rid string, q Quality) (*Result, error) {
	start := time.Now()
	var warnings []string

	attempts := []string{q.BR}
	if q.BR == "flac" {
		attempts = append(attempts, "320kbps")
	}

	var (
		rawURL string
		lastErr error
	)
	for _, br := range attempts {
		u, err := r.convertURL(ctx, rid, br)
		if err == nil {
			rawURL = u
			if br != q.BR {
				warnings = append(warnings, "flac_unsupported_fallback_320k")
			}
			break
		}
		lastErr = err
		if !errors.Is(err, ErrRefused) {
			break
		}
	}
	if rawURL == "" {
		if lastErr == nil {
			lastErr = ErrNoSource
		}
		return nil, lastErr
	}

	info, perr := r.probeAudio(ctx, rawURL)
	res := &Result{
		URL:       rawURL,
		Bitrate:   q.Bitrate,
		Level:     q.Level,
		ExpiresAt: parseKwExpiry(rawURL, 6*time.Hour),
		ElapsedMS: time.Since(start).Milliseconds(),
		Warnings:  warnings,
	}
	if perr == nil {
		res.Size = info.size
		res.DurationS = info.durationS
		if info.bitrate > 0 {
			res.Bitrate = info.bitrate
			switch {
			case info.bitrate >= 256:
				res.Level = "high"
			case info.bitrate >= 96:
				res.Level = "standard"
			}
		}
		if info.size == placeholderSize {
			return res, ErrPlaceholder
		}
	}
	return res, nil
}

// ---------- 音频探测 ----------

type audioInfo struct {
	size      int64
	bitrate   int
	durationS float64
}

var (
	bitrateTableV1L3 = []int{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
	bitrateTableV2L3 = []int{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
	sampleRateTable  = []int{44100, 48000, 32000, 0}
)

// probeAudio 用 Range GET 取头部，解析 MP3 帧头得到真实码率，并结合 Content-Range 得到总长度。
func (r *Resolver) probeAudio(ctx context.Context, rawURL string) (*audioInfo, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Range", "bytes=0-8191")
	req.Header.Set("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
	resp, err := r.probe.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	info := &audioInfo{}
	if cr := resp.Header.Get("Content-Range"); cr != "" {
		if i := strings.LastIndex(cr, "/"); i >= 0 {
			if n, err := strconv.ParseInt(strings.TrimSpace(cr[i+1:]), 10, 64); err == nil {
				info.size = n
			}
		}
	}
	if info.size == 0 {
		if n, err := strconv.ParseInt(resp.Header.Get("Content-Length"), 10, 64); err == nil {
			info.size = n
		}
	}
	buf, err := io.ReadAll(io.LimitReader(resp.Body, 8192))
	if err != nil {
		return info, err
	}
	// 跳过 ID3v2
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
		if ver == 3 { // MPEG1
			br = bitrateTableV1L3[brIdx]
		} else if ver == 2 || ver == 0 { // MPEG2 / 2.5
			br = bitrateTableV2L3[brIdx]
		}
		if br <= 0 {
			continue
		}
		info.bitrate = br
		if info.size > 0 {
			info.durationS = float64(info.size) * 8 / float64(br) / 1000
		}
		return info, nil
	}
	if info.size > 0 {
		info.durationS = float64(info.size) * 8 / 128 / 1000 // 无法识别帧头时按 128k 粗估
	}
	return info, nil
}

// parseKwExpiry 从直链路径的 16 进制段推导过期时间。
// 实测该段是“签发时间”而非过期时间，因此按签发 + fallback 估算。
func parseKwExpiry(raw string, fallback time.Duration) int64 {
	def := time.Now().Add(fallback).Unix()
	u, err := url.Parse(raw)
	if err != nil {
		return def
	}
	nowUnix := time.Now().Unix()
	for _, s := range strings.Split(strings.Trim(u.Path, "/"), "/") {
		if len(s) != 8 {
			continue
		}
		v, err := strconv.ParseInt(s, 16, 64)
		if err != nil {
			continue
		}
		if v < 1704067200 || v > 2051222400 {
			continue
		}
		if v > nowUnix+120 {
			return v
		}
		if nowUnix-v < 86400 {
			return v + int64(fallback.Seconds())
		}
	}
	return def
}

// ---------- 搜索 ----------

// SearchHit 是酷我搜索命中项。
type SearchHit struct {
	RID      string
	Name     string
	Artist   string
	Duration int
}

var (
	reItemStart = regexp.MustCompile(`\{'AARTIST'`)
	reRID       = regexp.MustCompile(`'DC_TARGETID'\s*:\s*'?(\d+)'?`)
	reName      = regexp.MustCompile(`'NAME'\s*:\s*'([^']*)'`)
	reArtist    = regexp.MustCompile(`'ARTIST'\s*:\s*'([^']*)'`)
	reMusicRID  = regexp.MustCompile(`'MUSICRID'\s*:\s*'MUSIC_(\d+)'`)
	reDuration  = regexp.MustCompile(`'DURATION'\s*:\s*'(\d+)'`)
)

func unescapeKw(s string) string {
	rep := strings.NewReplacer(
		"&nbsp;", " ",
		"&apos;", "'",
		"&amp;", "&",
		"&quot;", `"`,
		"\\'", "'",
		"\\/", "/",
		"\\u0026", "&",
		"\\\\u0026", "&",
	)
	return strings.TrimSpace(rep.Replace(s))
}

func firstGroup(re *regexp.Regexp, s string) string {
	m := re.FindStringSubmatch(s)
	if len(m) < 2 {
		return ""
	}
	return m[1]
}

// Search 调用酷我搜索，返回最多 limit 条命中。
func (r *Resolver) Search(ctx context.Context, name, artist string, limit int) ([]SearchHit, error) {
	kw := strings.TrimSpace(name)
	if a := strings.TrimSpace(artist); a != "" {
		kw = kw + " " + a
	}
	if kw == "" {
		return nil, ErrNoSource
	}
	if limit <= 0 || limit > 50 {
		limit = 20
	}
	qs := url.Values{}
	qs.Set("all", kw)
	qs.Set("ft", "music")
	qs.Set("itemset", "web_2013")
	qs.Set("client", "kt")
	qs.Set("pn", "0")
	qs.Set("rn", strconv.Itoa(limit))
	qs.Set("rformat", "json")
	qs.Set("encoding", "utf8")
	target := kuwoSearch + "?" + qs.Encode()
	// 酷我搜索接口对高频请求会返回空/异常响应，做一次退避重试以提升稳定性。
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(time.Duration(150*attempt) * time.Millisecond):
			}
		}
		hits, err := r.searchOnce(ctx, target, name, artist)
		if err == nil {
			return hits, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func (r *Resolver) searchOnce(ctx context.Context, target, name, artist string) ([]SearchHit, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
	req.Header.Set("Referer", "http://www.kuwo.cn/")
	resp, err := r.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("kuwo search: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("kuwo search status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	if err != nil {
		return nil, err
	}
	hits := parseSearchHits(string(body))
	if len(hits) == 0 {
		if os.Getenv("XG_DEBUG_SEARCH") != "" {
			_ = os.WriteFile("search-debug.txt", []byte("target="+target+"\nstatus="+strconv.Itoa(resp.StatusCode)+"\nlen="+strconv.Itoa(len(body))+"\n\n"+string(body)), 0o644)
		}
		return nil, ErrNoSource
	}
	return rankHits(hits, name, artist), nil
}

func parseSearchHits(body string) []SearchHit {
	idxs := reItemStart.FindAllStringIndex(body, -1)
	hits := make([]SearchHit, 0, len(idxs))
	for i, loc := range idxs {
		end := len(body)
		if i+1 < len(idxs) {
			end = idxs[i+1][0]
		}
		block := body[loc[0]:end]
		rid := firstGroup(reRID, block)
		if rid == "" {
			rid = firstGroup(reMusicRID, block)
		}
		if rid == "" {
			continue
		}
		dur := 0
		if v := firstGroup(reDuration, block); v != "" {
			dur, _ = strconv.Atoi(v)
		}
		hits = append(hits, SearchHit{
			RID:      rid,
			Name:     unescapeKw(firstGroup(reName, block)),
			Artist:   unescapeKw(firstGroup(reArtist, block)),
			Duration: dur,
		})
	}
	return hits
}

var badWords = []string{"伴奏", "KTV", "ktv", "翻唱", "cover", "Cover", "DJ版", "dj版", "铃声", "前奏", "纯音乐", "钢琴版", "吉他版", "串烧", "现场", "演唱会", "Live", "live"}

func scoreHit(h SearchHit, wantName, wantArtist string) int {
	n := strings.ToLower(strings.TrimSpace(h.Name))
	a := strings.ToLower(strings.TrimSpace(h.Artist))
	score := 0
	if wantName != "" {
		switch {
		case n == wantName:
			score += 100
		case strings.HasPrefix(n, wantName):
			score += 60
		case strings.Contains(n, wantName):
			score += 30
		default:
			score -= 40
		}
	}
	if wantArtist != "" {
		switch {
		case a == wantArtist:
			score += 80
		case strings.Contains(a, wantArtist) || strings.Contains(wantArtist, a):
			score += 40
		default:
			score -= 10
		}
	}
	for _, bw := range badWords {
		if strings.Contains(h.Name, bw) {
			score -= 150
			break
		}
	}
	// 时长过短（<60s）大概率是片段
	if h.Duration > 0 && h.Duration < 60 {
		score -= 100
	}
	return score
}

// rankHits 按匹配度稳定排序（同分保持原顺序）。
func rankHits(hits []SearchHit, name, artist string) []SearchHit {
	wantName := strings.ToLower(strings.TrimSpace(name))
	wantArtist := strings.ToLower(strings.TrimSpace(artist))
	type scored struct {
		h SearchHit
		s int
		i int
	}
	all := make([]scored, len(hits))
	for i, h := range hits {
		all[i] = scored{h: h, s: scoreHit(h, wantName, wantArtist), i: i}
	}
	for i := 1; i < len(all); i++ {
		for j := i; j > 0 && (all[j].s > all[j-1].s || (all[j].s == all[j-1].s && all[j].i < all[j-1].i)); j-- {
			all[j], all[j-1] = all[j-1], all[j]
		}
	}
	out := make([]SearchHit, len(all))
	for i, v := range all {
		out[i] = v.h
	}
	return out
}

// SearchRid 返回最佳单条命中（兼容旧调用）。
func (r *Resolver) SearchRid(ctx context.Context, name, artist string) (*SearchHit, error) {
	hits, err := r.Search(ctx, name, artist, 20)
	if err != nil {
		return nil, err
	}
	if len(hits) == 0 {
		return nil, ErrNoSource
	}
	return &hits[0], nil
}

// ResolveCandidates 依次尝试候选 rid，跳过占位片段与失败项，返回首个正片。
// 返回的 Result 附带 attempts 明细，便于 trace。
func (r *Resolver) ResolveCandidates(ctx context.Context, rids []string, q Quality, wantDuration int) (*Result, []string, error) {
	trace := make([]string, 0, len(rids)+2)
	var lastErr error
	for i, rid := range rids {
		if i >= 6 {
			break
		}
		res, err := r.Resolve(ctx, rid, q)
		if err == nil {
			// 时长校验：请求有时长且实测明显偏短，视为片段
			if wantDuration >= 60 && res.DurationS > 0 && res.DurationS < float64(wantDuration)*0.4 {
				trace = append(trace, "kw:"+rid+":short("+strconv.Itoa(int(res.DurationS))+"s)")
				lastErr = ErrPlaceholder
				continue
			}
			trace = append(trace, "kw:"+rid+":ok("+strconv.Itoa(res.Bitrate)+"k)")
			return res, trace, nil
		}
		lastErr = err
		switch {
		case errors.Is(err, ErrPlaceholder):
			trace = append(trace, "kw:"+rid+":placeholder")
		case errors.Is(err, ErrNoSource), errors.Is(err, ErrRefused):
			trace = append(trace, "kw:"+rid+":no_source")
		default:
			trace = append(trace, "kw:"+rid+":err")
		}
	}
	if lastErr == nil {
		lastErr = ErrNoSource
	}
	return nil, trace, lastErr
}