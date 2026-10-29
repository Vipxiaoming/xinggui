package router

import (
	"context"
	"net/http"
	"time"
)

// contextWithTimeout 在请求上下文上叠加超时。
func contextWithTimeout(r *http.Request, d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(r.Context(), d)
}

// fmtTime 把 Unix 秒格式化成管理页可读的本地时间。
func fmtTime(ts int64) string {
	if ts <= 0 {
		return ""
	}
	return time.Unix(ts, 0).Format("2006-01-02 15:04")
}

// NormalizePlatform 与 resolve 包保持一致（router 层不便直接复用未导出别名）。
func NormalizePlatform(s string) string {
	switch lower(s) {
	case "wy", "netease", "163", "wangyi":
		return "wy"
	case "tx", "qq", "tencent", "qqmusic":
		return "tx"
	case "kg", "kugou":
		return "kg"
	case "kw", "kuwo":
		return "kw"
	}
	return ""
}

func lower(s string) string {
	out := make([]rune, 0, len(s))
	for _, r := range s {
		if r >= 'A' && r <= 'Z' {
			r += 'a' - 'A'
		}
		out = append(out, r)
	}
	return string(out)
}
