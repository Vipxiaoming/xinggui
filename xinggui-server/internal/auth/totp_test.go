package auth

import (
	"testing"
	"time"
)

// RFC 6238 附录 B 官方测试向量（SHA-1，ASCII 密钥 "12345678901234567890"）。
// 官方给出 8 位结果；本实现固定 6 位，取后 6 位比对。
func TestTOTPRFC6238Vectors(t *testing.T) {
	const secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" // base32("12345678901234567890")
	cases := []struct {
		unix int64
		want string
	}{
		{59, "287082"},          // RFC: 94287082
		{1111111109, "081804"},  // RFC: 07081804
		{1111111111, "050471"},  // RFC: 14050471
		{1234567890, "005924"},  // RFC: 89005924
		{2000000000, "279037"},  // RFC: 69279037
		{20000000000, "353130"}, // RFC: 65353130
	}
	for _, c := range cases {
		got, err := TOTPCode(secret, time.Unix(c.unix, 0))
		if err != nil {
			t.Fatalf("t=%d: %v", c.unix, err)
		}
		if got != c.want {
			t.Errorf("t=%d: got %s want %s", c.unix, got, c.want)
		}
	}
}

func TestVerifyTOTPWindow(t *testing.T) {
	const secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
	now := time.Now()
	cur, err := TOTPCode(secret, now)
	if err != nil {
		t.Fatal(err)
	}
	if !VerifyTOTP(secret, cur) {
		t.Error("当前时间窗应校验通过")
	}
	if VerifyTOTP(secret, "000000") && cur != "000000" {
		t.Error("错误码不应通过")
	}
	if VerifyTOTP(secret, "") || VerifyTOTP(secret, "12345") {
		t.Error("空码/长度不足应拒绝")
	}

	// 上一窗口（-30s）也应接受
	prev, _ := TOTPCode(secret, now.Add(-30*time.Second))
	if prev != cur && !VerifyTOTP(secret, prev) {
		t.Error("上一时间窗应接受")
	}
	// 超出窗口（-3 分钟）应拒绝
	old, _ := TOTPCode(secret, now.Add(-3*time.Minute))
	if old != cur && VerifyTOTP(secret, old) {
		t.Error("超出漂移窗口不应接受")
	}
}
