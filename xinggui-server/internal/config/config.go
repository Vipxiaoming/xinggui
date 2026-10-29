package config

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config 保存服务端运行期配置，全部可通过环境变量覆盖。
type Config struct {
	Port      int
	DBPath    string
	JWTSecret []byte
	BaseURL   string
	DataDir   string
	// TLSCert / TLSKey 为服务端自行终止 TLS 所用的证书与私钥路径。
	// 两者同时非空才启用 HTTPS；任一为空则回落到明文 HTTP（兼容本地开发）。
	// 允许二者指向同一个合并 PEM 文件（证书链 + 私钥），Go 会分别解析。
	TLSCert string
	TLSKey  string
}

func getenv(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

// Load 读取配置；JWT 密钥优先取环境变量，其次读取 data/jwt.secret，缺失则生成并落盘。
func Load() (*Config, error) {
	port := 8443
	if v := os.Getenv("XG_PORT"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			port = n
		}
	}
	dataDir := getenv("XG_DATA_DIR", "data")
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, err
	}
	dbPath := getenv("XG_DB_PATH", filepath.Join(dataDir, "xinggui.db"))
	baseURL := getenv("XG_BASE_URL", "https://music.wangxiaoming.vip:20001")

	secret, err := loadSecret(dataDir)
	if err != nil {
		return nil, err
	}
	return &Config{
		Port:      port,
		DBPath:    dbPath,
		JWTSecret: secret,
		BaseURL:   baseURL,
		DataDir:   dataDir,
		TLSCert:   strings.TrimSpace(os.Getenv("XG_TLS_CERT")),
		TLSKey:    strings.TrimSpace(os.Getenv("XG_TLS_KEY")),
	}, nil
}

func loadSecret(dataDir string) ([]byte, error) {
	if v := strings.TrimSpace(os.Getenv("XG_JWT_SECRET")); v != "" {
		return []byte(v), nil
	}
	path := filepath.Join(dataDir, "jwt.secret")
	if b, err := os.ReadFile(path); err == nil {
		s := strings.TrimSpace(string(b))
		if s != "" {
			return []byte(s), nil
		}
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return nil, err
	}
	secret := []byte(hex.EncodeToString(buf))
	if err := os.WriteFile(path, secret, 0o600); err != nil {
		return nil, err
	}
	return secret, nil
}
