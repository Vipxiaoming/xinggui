package resolve

import "testing"

// 签名向量必须与 smali 还原结果一致（reports/15-kugou-signature.md §5）。
func TestSignKugouH5(t *testing.T) {
	base := KugouBaseParams("1700000000000", "0123456789abcdef0123456789abcdef")
	if got, want := SignKugouH5(base), "c8ef115cf483708989a94754f6e2e820"; got != want {
		t.Fatalf("V1 不符: got %s want %s", got, want)
	}
	v2 := KugouBaseParams("1700000000000", "0123456789abcdef0123456789abcdef")
	v2["global_specialid"] = "collection_3_12345678_1_0"
	v2["sign"] = "h5"
	v2["specialid"] = "0"
	if got, want := SignKugouH5(v2), "14fcd4eade77af47bec2084c4657a880"; got != want {
		t.Fatalf("V2 不符: got %s want %s", got, want)
	}
}

func TestNormalizePlatform(t *testing.T) {
	cases := map[string]string{
		"wy": "wy", "netease": "wy", "163": "wy", "网易云": "",
		"tx": "tx", "qq": "tx", "QQMusic": "tx",
		"kg": "kg", "kugou": "kg", "KW": "kw", "kuwo": "kw",
		"": "", "spotify": "",
	}
	for in, want := range cases {
		if got := NormalizePlatform(in); got != want {
			t.Errorf("NormalizePlatform(%q)=%q want %q", in, got, want)
		}
	}
}

func TestMapQuality(t *testing.T) {
	if _, err := MapQuality("999k"); err == nil {
		t.Error("非法音质应报错")
	}
	for _, q := range []string{"", "128k", "320k", "flac", "high", "lossless"} {
		if _, err := MapQuality(q); err != nil {
			t.Errorf("%q 应被接受: %v", q, err)
		}
	}
}

func TestSetPlatformOrderDedup(t *testing.T) {
	SetPlatformOrder([]string{"kw", "netease", "kg", "kw", "bogus"})
	defer SetPlatformOrder([]string{"wy", "tx", "kg", "kw"})
	got := PlatformOrder()
	want := []string{"kw", "wy", "kg"}
	if len(got) != len(want) {
		t.Fatalf("got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v want %v", got, want)
		}
	}
}

func TestParseDurationText(t *testing.T) {
	cases := map[string]int{"4分29秒": 269, "269": 269, "": 0}
	for in, want := range cases {
		if got := ParseDurationText(in); got != want {
			t.Errorf("ParseDurationText(%q)=%d want %d", in, got, want)
		}
	}
}