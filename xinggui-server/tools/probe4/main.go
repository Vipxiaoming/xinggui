package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"xinggui/internal/resolve"
)

func main() {
	r := resolve.NewResolver()
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	q, _ := resolve.MapQuality("320k")
	cases := []struct{ platform, id, name, artist string }{
		{"kg", "", "晴天", "蓝心羽"},
		{"wy", "", "稻香", "周杰伦"},
		{"tx", "", "遇见", "纯音乐"},
		{"kw", "474680496", "稻香", "周杰伦"},
	}
	for _, c := range cases {
		t0 := time.Now()
		var res *resolve.Result
		var err error
		switch c.platform {
		case "kg":
			hits, e := r.SearchKugou(ctx, c.name+" "+c.artist, 10)
			if e != nil {
				err = e
				break
			}
			for _, h := range hits {
				res, err = r.ResolveKugou(ctx, h.ID, q)
				if err == nil {
					break
				}
			}
		case "wy":
			hits, e := r.SearchNetease(ctx, c.name+" "+c.artist, 10)
			if e != nil {
				err = e
				break
			}
			for _, h := range hits {
				res, err = r.ResolveNetease(ctx, h.ID, q)
				if err == nil {
					break
				}
			}
		case "tx":
			hits, e := r.SearchQQ(ctx, c.name+" "+c.artist, 10)
			if e != nil {
				err = e
				break
			}
			for _, h := range hits {
				res, err = r.ResolveQQ(ctx, h.ID, q)
				if err == nil {
					break
				}
			}
		case "kw":
			res, err = r.Resolve(ctx, c.id, q)
		}
		ms := time.Since(t0).Milliseconds()
		if err != nil {
			fmt.Printf("[FAIL] %s %s/%s -> %v (%dms)\n", c.platform, c.name, c.artist, err, ms)
			continue
		}
		fmt.Printf("[OK] %s %s/%s -> level=%s br=%d size=%d dur=%.1f ms=%d\n    url=%.90s\n",
			c.platform, c.name, c.artist, res.Level, res.Bitrate, res.Size, res.DurationS, ms, res.URL)
	}
	os.Exit(0)
}