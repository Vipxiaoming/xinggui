package auth

import (
	"crypto/rand"
	"encoding/hex"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// AdminTTL 是管理后台会话有效期（契约：2 小时）。
const AdminTTL = 2 * time.Hour

const TypeAdmin = "admin"

type AdminClaims struct {
	Type string `json:"typ"`
	Role string `json:"role"`
	jwt.RegisteredClaims
}

// NewJTI 生成管理令牌唯一 ID，用于服务端吊销。
func NewJTI() (string, error) {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

// IssueAdmin 签发管理令牌，返回 token / expiresIn / jti。
func (m *Manager) IssueAdmin(username, role string) (string, int, string, error) {
	jti, err := NewJTI()
	if err != nil {
		return "", 0, "", err
	}
	nowT := time.Now()
	claims := AdminClaims{
		Type: TypeAdmin,
		Role: role,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   username,
			ID:        jti,
			IssuedAt:  jwt.NewNumericDate(nowT),
			NotBefore: jwt.NewNumericDate(nowT.Add(-30 * time.Second)),
			ExpiresAt: jwt.NewNumericDate(nowT.Add(AdminTTL)),
			Issuer:    "xinggui-admin",
		},
	}
	tok, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(m.secret)
	if err != nil {
		return "", 0, "", err
	}
	return tok, int(AdminTTL.Seconds()), jti, nil
}

// ParseAdmin 解析并校验管理令牌（只接受 typ=admin）。
func (m *Manager) ParseAdmin(token string) (*AdminClaims, error) {
	parsed, err := jwt.ParseWithClaims(token, &AdminClaims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, ErrBadToken
		}
		return m.secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}), jwt.WithIssuer("xinggui-admin"))
	if err != nil {
		return nil, err
	}
	claims, ok := parsed.Claims.(*AdminClaims)
	if !ok || !parsed.Valid || claims.Type != TypeAdmin || claims.Subject == "" || claims.ID == "" {
		return nil, ErrBadToken
	}
	return claims, nil
}