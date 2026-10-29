package auth

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base32"
	"encoding/binary"
	"fmt"
	"strings"
	"time"
)

// TOTP 实现 RFC 6238（SHA-1 / 6 位 / 30 秒步长），与 Google Authenticator 兼容。

const (
	totpDigits = 6
	totpPeriod = 30
)

var b32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// NewTOTPSecret 生成 160 位随机密钥的 Base32 表示（无填充）。
func NewTOTPSecret() (string, error) {
	buf := make([]byte, 20)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return b32.EncodeToString(buf), nil
}

func hotp(secret []byte, counter uint64) string {
	var buf [8]byte
	binary.BigEndian.PutUint64(buf[:], counter)
	mac := hmac.New(sha1.New, secret)
	mac.Write(buf[:])
	sum := mac.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	code := (uint32(sum[off])&0x7f)<<24 |
		uint32(sum[off+1])<<16 |
		uint32(sum[off+2])<<8 |
		uint32(sum[off+3])
	return fmt.Sprintf("%0*d", totpDigits, code%1000000)
}

func decodeSecret(secret string) ([]byte, error) {
	s := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(secret), " ", ""))
	s = strings.TrimRight(s, "=")
	return b32.DecodeString(s)
}

// TOTPCode 计算指定时间点的动态码。
func TOTPCode(secret string, t time.Time) (string, error) {
	key, err := decodeSecret(secret)
	if err != nil {
		return "", err
	}
	return hotp(key, uint64(t.Unix()/totpPeriod)), nil
}

// VerifyTOTP 校验动态码，允许前后各 1 个时间窗（±30 秒）的时钟漂移。
func VerifyTOTP(secret, code string) bool {
	code = strings.TrimSpace(code)
	if len(code) != totpDigits || secret == "" {
		return false
	}
	key, err := decodeSecret(secret)
	if err != nil {
		return false
	}
	now := time.Now().Unix() / totpPeriod
	for _, d := range []int64{0, -1, 1} {
		if hotp(key, uint64(now+d)) == code {
			return true
		}
	}
	return false
}

// TOTPURL 生成 otpauth:// 链接，便于二维码展示。
func TOTPURL(issuer, account, secret string) string {
	return fmt.Sprintf("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
		issuer, account, secret, issuer)
}