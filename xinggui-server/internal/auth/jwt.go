package auth

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const (
	AccessTTL  = 2 * time.Hour
	RefreshTTL = 30 * 24 * time.Hour
)

const (
	TypeAccess  = "access"
	TypeRefresh = "refresh"
)

var ErrBadToken = errors.New("invalid token")

type Claims struct {
	DeviceID     string `json:"did,omitempty"`
	TokenVersion int    `json:"ver"`
	Type         string `json:"typ"`
	jwt.RegisteredClaims
}

type Manager struct{ secret []byte }

func NewManager(secret []byte) *Manager { return &Manager{secret: secret} }

func (m *Manager) sign(username, deviceID, typ string, ver int, ttl time.Duration) (string, error) {
	nowT := time.Now()
	claims := Claims{
		DeviceID:     deviceID,
		TokenVersion: ver,
		Type:         typ,
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   username,
			IssuedAt:  jwt.NewNumericDate(nowT),
			NotBefore: jwt.NewNumericDate(nowT.Add(-30 * time.Second)),
			ExpiresAt: jwt.NewNumericDate(nowT.Add(ttl)),
			Issuer:    "xinggui",
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(m.secret)
}

// Issue 同时签发 access 与 refresh。
func (m *Manager) Issue(username, deviceID string, ver int) (access, refresh string, expiresIn int, err error) {
	access, err = m.sign(username, deviceID, TypeAccess, ver, AccessTTL)
	if err != nil {
		return
	}
	refresh, err = m.sign(username, deviceID, TypeRefresh, ver, RefreshTTL)
	if err != nil {
		return
	}
	return access, refresh, int(AccessTTL.Seconds()), nil
}

func (m *Manager) Parse(token, wantType string) (*Claims, error) {
	parsed, err := jwt.ParseWithClaims(token, &Claims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, ErrBadToken
		}
		return m.secret, nil
	}, jwt.WithValidMethods([]string{"HS256"}))
	if err != nil {
		return nil, err
	}
	claims, ok := parsed.Claims.(*Claims)
	if !ok || !parsed.Valid {
		return nil, ErrBadToken
	}
	if wantType != "" && claims.Type != wantType {
		return nil, ErrBadToken
	}
	if claims.Subject == "" {
		return nil, ErrBadToken
	}
	return claims, nil
}