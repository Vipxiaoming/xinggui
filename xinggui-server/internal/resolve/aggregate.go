package resolve

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ResolveRequest 是 /v1/resolve 的解析入参（已归一化）。
type ResolveRequest struct {
	Source   string
	ID       string
	Name     string
	Artist   string
	Duration int
	Quality  Quality
	Disabled map[string]bool
	Order    []string
	PerTry   time.Duration
}

// Attempt 是单平台单候选的尝试记录，用于 trace 与管理页统计。
type Attempt struct {
	Platform string
	ID       string
	OK       bool
	Err      string
	MS       int64
	Paid     bool
	Bitrate  int
}

// ResolveOutcome 是聚合结果。
type ResolveOutcome struct {
	Result   *Result
	Platform string
	Trace    []string
	Attempts []Attempt
}

// ErrAllFailed 表示四路全部失败。
var ErrAllFailed = errors.New("all_platforms_failed")

// candidateFor 决定某平台用什么 ID/关键词。
// 关键规则（沿用第一阶段结论）：只有同平台 id 才可直接使用，
// 跨平台 id 语义不同，必须走歌名搜索。
func candidateFor(platform, source, id, name, artist string) (useID, keyword string) {
	if NormalizePlatform(source) == platform && strings.TrimSpace(id) != "" {
		useID = strings.TrimSpace(id)
	}
	kw := strings.TrimSpace(strings.TrimSpace(name) + " " + strings.TrimSpace(artist))
	if strings.TrimSpace(name) == "" {
		kw = strings.TrimSpace(artist)
	}
	return useID, kw
}

// tooShort 判定实测时长明显短于请求时长（视为试听片段）。
func tooShort(res *Result, wantDuration int) bool {
	if res == nil || wantDuration < 60 || res.DurationS <= 0 {
		return false
	}
	return res.DurationS < float64(wantDuration)*0.4
}

// ResolveAny 并行竞速四路：所有平台同时发起，按 Order 的优先级挑选结果，
// 并把每一次尝试写进 trace（便于客户端/管理页排障）。
func (r *Resolver) ResolveAny(ctx context.Context, req ResolveRequest) (*ResolveOutcome, error) {
	order := req.Order
	if len(order) == 0 {
		order = PlatformOrder()
	}
	// 归一化并去重，顺序即优先级。
	cleanOrder := make([]string, 0, len(order))
	idxOf := make(map[string]int, len(order))
	for _, p := range order {
		n := NormalizePlatform(p)
		if n == "" {
			continue
		}
		if _, dup := idxOf[n]; dup {
			continue
		}
		idxOf[n] = len(cleanOrder)
		cleanOrder = append(cleanOrder, n)
	}
	if len(cleanOrder) == 0 {
		cleanOrder = PlatformOrder()
		for i, p := range cleanOrder {
			idxOf[p] = i
		}
	}
	order = cleanOrder

	perTry := clampTimeout(req.PerTry)
	if perTry <= 0 {
		perTry = 7 * time.Second
	}

	type outcome struct {
		platform string
		res      *Result
		err      error
		attempts []Attempt
	}

	outCh := make(chan outcome, len(order))
	var wg sync.WaitGroup

	for _, p := range order {
		p := p
		if req.Disabled != nil && req.Disabled[p] {
			outCh <- outcome{
				platform: p,
				err:      errors.New("disabled"),
				attempts: []Attempt{{Platform: p, Err: "disabled"}},
			}
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			pctx, cancel := context.WithTimeout(ctx, perTry)
			defer cancel()

			useID, keyword := candidateFor(p, req.Source, req.ID, req.Name, req.Artist)
			var attempts []Attempt

			tryOne := func(id string) (*Result, error) {
				t0 := time.Now()
				res, err := r.resolveByPlatform(pctx, p, id, req.Quality)
				a := Attempt{Platform: p, ID: id, OK: err == nil, MS: time.Since(t0).Milliseconds()}
				if err != nil {
					a.OK = false
					a.Err = err.Error()
					a.Paid = errors.Is(err, ErrPaid)
					if res != nil {
						a.Bitrate = res.Bitrate
					}
					attempts = append(attempts, a)
					return res, err
				}
				// 直连 ID 与搜索命中都要做时长校验，否则试听片段会被当成正片返回。
				// 用双向容差：既拦「181KB 试听占位」，也拦「治愈版 120s 冒充 269s 原版」。
				if req.Duration >= 60 && res.DurationS > 0 && !durationClose(int(res.DurationS), req.Duration, durationTolerance(req.Duration)) {
					a.Err = "short"
					a.OK = false
					attempts = append(attempts, a)
					return res, ErrPlaceholder
				}
				a.Bitrate = res.Bitrate
				attempts = append(attempts, a)
				return res, nil
			}

			if useID != "" {
				if res, err := tryOne(useID); err == nil {
					outCh <- outcome{platform: p, res: res, attempts: attempts}
					return
				} else if errors.Is(err, ErrPaid) && keyword == "" {
					outCh <- outcome{platform: p, err: err, attempts: attempts}
					return
				}
			}
			if keyword == "" {
				outCh <- outcome{platform: p, err: ErrNoSource, attempts: attempts}
				return
			}
			hits, err := r.searchByPlatform(pctx, p, keyword, 12)
			if err != nil {
				attempts = append(attempts, Attempt{Platform: p, Err: "search:" + err.Error()})
				outCh <- outcome{platform: p, err: err, attempts: attempts}
				return
			}
			kept := rankMetas(hits, req.Name, req.Artist, req.Duration)
			if len(kept) == 0 {
				attempts = append(attempts, Attempt{Platform: p, Err: "no_source:no_ranked_hit"})
				outCh <- outcome{platform: p, err: ErrNoSource, attempts: attempts}
				return
			}
			var lastErr error
			// 逐候选尝试；候选 metadata 带时长时先做时长一致性闸门，
			// 避免「稻香(治愈版) 120s」被当成 269s 的原版返回。
			var sawDurationMismatch bool
			for i, m := range kept {
				if i >= 4 {
					break
				}
				if pctx.Err() != nil {
					lastErr = ErrTimeout
					break
				}
				if req.Duration >= 60 && m.Duration > 0 && !durationClose(m.Duration, req.Duration, durationTolerance(req.Duration)) {
					attempts = append(attempts, Attempt{Platform: p, ID: m.ID, Err: "duration_mismatch"})
					sawDurationMismatch = true
					lastErr = ErrNoSource
					continue
				}
				res, err := tryOne(m.ID)
				if err == nil {
					if res.Warnings == nil {
						res.Warnings = []string{}
					}
					if containsBadWord(m.Name) {
						res.Warnings = append(res.Warnings, "suspect_not_original:"+m.Name)
					}
					outCh <- outcome{platform: p, res: res, attempts: attempts}
					return
				}
				lastErr = err
			}
			if lastErr == nil {
				lastErr = ErrNoSource
			}
			if sawDurationMismatch && errors.Is(lastErr, ErrNoSource) {
				attempts = append(attempts, Attempt{Platform: p, Err: "no_source:duration_mismatch"})
			}
			outCh <- outcome{platform: p, err: lastErr, attempts: attempts}
		}()
	}

	go func() { wg.Wait(); close(outCh) }()

	var (
		best      *outcome
		bestIdx   = len(order) + 1
		perPlat   = make(map[string][]Attempt, len(order))
		anyPaid   bool
	)

	for o := range outCh {
		perPlat[o.platform] = append(perPlat[o.platform], o.attempts...)
		if o.err != nil && errors.Is(o.err, ErrPaid) {
			anyPaid = true
		}
		if o.res == nil {
			continue
		}
		// 竞速不按到达先后，按 resolveOrder 的优先级选优：排在前面的平台赢得结果。
		if idx, ok := idxOf[o.platform]; ok && idx < bestIdx {
			cp := o
			best = &cp
			bestIdx = idx
		}
	}

	out := &ResolveOutcome{}
	for _, p := range order {
		atts, exist := perPlat[p]
		if !exist || len(atts) == 0 {
			out.Trace = append(out.Trace, fmt.Sprintf("%s:-:skipped", p))
			continue
		}
		for _, a := range atts {
			out.Attempts = append(out.Attempts, a)
			if a.OK {
				out.Trace = append(out.Trace, fmt.Sprintf("%s:%s:ok(%dk,%dms)", a.Platform, traceID(a.ID), a.Bitrate, a.MS))
				continue
			}
			tag := "err"
			switch {
			case a.Paid:
				tag = "paid"
			case strings.Contains(a.Err, "placeholder"):
				tag = "placeholder"
			case strings.Contains(a.Err, "no_source"):
				tag = "no_source"
			case strings.Contains(a.Err, "timeout") || strings.Contains(a.Err, "deadline"):
				tag = "timeout"
			case strings.HasPrefix(a.Err, "search:"):
				tag = "search_fail"
			case a.Err == "short":
				tag = "short"
			case a.Err == "disabled":
				tag = "disabled"
			}
			out.Trace = append(out.Trace, fmt.Sprintf("%s:%s:%s", a.Platform, traceID(a.ID), tag))
		}
	}

	if best == nil {
		if anyPaid {
			return out, ErrPaid
		}
		return out, ErrAllFailed
	}
	out.Result = best.res
	out.Platform = best.platform
	out.Trace = append(out.Trace, "winner:"+best.platform)
	return out, nil
}

func (r *Resolver) resolveByPlatform(ctx context.Context, platform, id string, q Quality) (*Result, error) {
	switch platform {
	case "kw":
		return r.Resolve(ctx, id, q)
	case "kg":
		return r.ResolveKugou(ctx, id, q)
	case "tx":
		return r.ResolveQQ(ctx, id, q)
	case "wy":
		return r.ResolveNetease(ctx, id, q)
	}
	return nil, ErrNoSource
}

func (r *Resolver) searchByPlatform(ctx context.Context, platform, keyword string, limit int) ([]TrackMeta, error) {
	switch platform {
	case "kw":
		hits, err := r.Search(ctx, strings.TrimSpace(keyword), "", limit)
		if err != nil {
			return nil, err
		}
		out := make([]TrackMeta, 0, len(hits))
		for _, h := range hits {
			out = append(out, TrackMeta{Platform: "kw", ID: h.RID, Name: h.Name, Artist: h.Artist, Duration: h.Duration})
		}
		return out, nil
	case "kg":
		return r.SearchKugou(ctx, keyword, limit)
	case "tx":
		return r.SearchQQ(ctx, keyword, limit)
	case "wy":
		return r.SearchNetease(ctx, keyword, limit)
	}
	return nil, ErrNoSource
}

// rankMetas 对跨平台命中统一排序，并做两级硬过滤：
//  1) 歌手优先：请求带歌手且确有候选命中该歌手时，丢弃歌手明显不符的候选；
//  2) 时长优先：请求带时长时，优先保留时长接近的候选，
//     否则「稻香(治愈版) 120s」会因为歌手串里带「周杰伦.」而冒充原版（实测踩到）。
func rankMetas(hits []TrackMeta, name, artist string, wantDuration int) []TrackMeta {
	wantName := strings.ToLower(strings.TrimSpace(name))
	wantArtist := strings.ToLower(strings.TrimSpace(artist))
	type scored struct {
		m TrackMeta
		s int
		i int
	}
	all := make([]scored, len(hits))
	for i, m := range hits {
		all[i] = scored{m: m, s: scoreHit(SearchHit{Name: m.Name, Artist: m.Artist, Duration: m.Duration}, wantName, wantArtist), i: i}
	}
	for i := 1; i < len(all); i++ {
		for j := i; j > 0 && (all[j].s > all[j-1].s || (all[j].s == all[j-1].s && all[j].i < all[j-1].i)); j-- {
			all[j], all[j-1] = all[j-1], all[j]
		}
	}
	kept := make([]scored, 0, len(all))
	for _, v := range all {
		if v.s < -100 {
			continue
		}
		kept = append(kept, v)
	}
	if wantArtist != "" {
		anyArtistHit := false
		for _, v := range kept {
			if artistMatches(v.m.Artist, wantArtist) {
				anyArtistHit = true
				break
			}
		}
		if anyArtistHit {
			filtered := make([]scored, 0, len(kept))
			for _, v := range kept {
				if artistMatches(v.m.Artist, wantArtist) {
					filtered = append(filtered, v)
				}
			}
			kept = filtered
		}
	}
	if wantDuration >= 60 {
		tolerance := int(float64(wantDuration) * 0.12)
		if tolerance < 15 {
			tolerance = 15
		}
		anyClose := false
		for _, v := range kept {
			if durationClose(v.m.Duration, wantDuration, tolerance) {
				anyClose = true
				break
			}
		}
		if anyClose {
			filtered := make([]scored, 0, len(kept))
			for _, v := range kept {
				if durationClose(v.m.Duration, wantDuration, tolerance) {
					filtered = append(filtered, v)
				}
			}
			kept = filtered
		}
	}
	out := make([]TrackMeta, 0, len(kept))
	for _, v := range kept {
		out = append(out, v.m)
	}
	return out
}

// durationClose 判断候选时长是否与请求时长接近（候选时长未知时不算接近）。
func durationClose(cand, want, tolerance int) bool {
	if cand <= 0 {
		return false
	}
	d := cand - want
	if d < 0 {
		d = -d
	}
	return d <= tolerance
}

// artistMatches 判断候选歌手是否命中请求歌手（支持多歌手用 / 、 , & 分隔）。
func artistMatches(cand, want string) bool {
	c := strings.ToLower(strings.TrimSpace(cand))
	w := strings.ToLower(strings.TrimSpace(want))
	if c == "" || w == "" {
		return false
	}
	if strings.Contains(c, w) || strings.Contains(w, c) {
		return true
	}
	parts := strings.FieldsFunc(c, func(r rune) bool {
		return r == 47 || r == 12289 || r == 44 || r == 38
	})
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if strings.Contains(part, w) || strings.Contains(w, part) {
			return true
		}
	}
	return false
}

// ParseDurationText 解析 "4分29秒" / "269" 之类的时长文本。
func ParseDurationText(s string) int {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0
	}
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return int(parseInterval(s))
}

// containsBadWord 与 router 层同名函数保持一致，用于标注"疑似非原版"。
func containsBadWord(s string) bool {
	for _, w := range badWords {
		if strings.Contains(s, w) {
			return true
		}
	}
	return false
}

// traceID 压缩 trace 里的 ID：空 ID 用 "-"，长 hash 截断到 12 位，便于客户端展示。
func traceID(id string) string {
	id = strings.TrimSpace(id)
	if id == "" {
		return "-"
	}
	if len(id) > 12 {
		return id[:12]
	}
	return id
}

// durationTolerance 返回时长一致性的允许误差：12%，最少 15 秒。
func durationTolerance(want int) int {
	t := int(float64(want) * 0.12)
	if t < 15 {
		t = 15
	}
	return t
}
