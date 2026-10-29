package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"xinggui/internal/config"
	"xinggui/internal/db"
	"xinggui/internal/router"
)

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	log.SetPrefix("[xinggui] ")

	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("配置加载失败: %v", err)
	}
	if err := os.MkdirAll(filepath.Dir(cfg.DBPath), 0o755); err != nil {
		log.Fatalf("创建数据目录失败: %v", err)
	}
	database, err := db.Open(cfg.DBPath)
	if err != nil {
		log.Fatalf("数据库初始化失败: %v", err)
	}
	defer database.Close()

	srv := router.New(cfg, database)
	httpServer := &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	// TLS 由服务端自己终止（部署环境为纯 NAT，没有反向代理）。
	// 判定：XG_TLS_CERT 与 XG_TLS_KEY 同时非空 -> HTTPS；任一为空 -> 明文 HTTP。
	//
	// 重要：两个变量允许指向同一个合并 PEM 文件（例如 one.pem 由 3 段证书 +
	// 1 段 PKCS#8 私钥拼成）。http.Server.ListenAndServeTLS(certFile, keyFile)
	// 支持 certFile == keyFile，Go 会分别取其中的 CERTIFICATE 块与 PRIVATE KEY
	// 块，所以不要假设证书与私钥必须是两个不同文件。
	useTLS := cfg.TLSCert != "" && cfg.TLSKey != ""

	if useTLS {
		if err := preflightTLS(cfg.TLSCert, cfg.TLSKey); err != nil {
			log.Fatalf("TLS 预检失败，已中止启动: %v", err)
		}
		log.Printf("TLS 模式: %s", cfg.TLSCert)
	} else {
		log.Printf("明文 HTTP 模式（未配置 XG_TLS_CERT/XG_TLS_KEY）")
	}

	go func() {
		log.Printf("监听 %s (base=%s, db=%s)", httpServer.Addr, cfg.BaseURL, cfg.DBPath)
		var serveErr error
		if useTLS {
			serveErr = httpServer.ListenAndServeTLS(cfg.TLSCert, cfg.TLSKey)
		} else {
			serveErr = httpServer.ListenAndServe()
		}
		if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			if useTLS {
				log.Fatalf("HTTPS 监听失败 (cert=%s, key=%s): %v", cfg.TLSCert, cfg.TLSKey, serveErr)
			}
			log.Fatalf("监听失败: %v", serveErr)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	<-stop

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("关闭异常: %v", err)
	}
	log.Println("已退出")
}

// preflightTLS 在真正启动监听前预检证书与私钥，把常见失败原因翻译成可诊断的错误。
// 只输出路径、PEM 块类型统计和底层错误，绝不打印证书或私钥内容。
func preflightTLS(certFile, keyFile string) error {
	for _, item := range []struct{ name, path string }{
		{"证书", certFile},
		{"私钥", keyFile},
	} {
		info, err := os.Stat(item.path)
		switch {
		case errors.Is(err, os.ErrNotExist):
			return fmt.Errorf("%s文件不存在: %s", item.name, item.path)
		case err != nil:
			return fmt.Errorf("%s文件不可访问: %s: %w", item.name, item.path, err)
		case info.IsDir():
			return fmt.Errorf("%s路径是目录而不是文件: %s", item.name, item.path)
		case info.Size() == 0:
			return fmt.Errorf("%s文件为空: %s", item.name, item.path)
		}
	}

	pair, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return fmt.Errorf("加载证书/私钥失败 (cert=%s, key=%s): %w%s",
			certFile, keyFile, err, describePEM(certFile, keyFile))
	}
	if len(pair.Certificate) == 0 {
		return fmt.Errorf("证书文件中没有可用的 CERTIFICATE 块: %s", certFile)
	}
	if pair.PrivateKey == nil {
		return fmt.Errorf("私钥文件中没有可用的私钥块: %s", keyFile)
	}

	leaf, err := x509.ParseCertificate(pair.Certificate[0])
	if err != nil {
		return fmt.Errorf("叶子证书解析失败 (%s): %w", certFile, err)
	}
	now := time.Now()
	if now.Before(leaf.NotBefore) {
		return fmt.Errorf("证书尚未生效: NotBefore=%s", leaf.NotBefore.Format(time.RFC3339))
	}
	if now.After(leaf.NotAfter) {
		return fmt.Errorf("证书已过期: NotAfter=%s", leaf.NotAfter.Format(time.RFC3339))
	}
	log.Printf("TLS 预检通过: 证书主体=%q 颁发者=%q 有效期 %s ~ %s 证书链%d张",
		leaf.Subject.CommonName, leaf.Issuer.CommonName,
		leaf.NotBefore.Format(time.RFC3339), leaf.NotAfter.Format(time.RFC3339),
		len(pair.Certificate))
	return nil
}

// describePEM 只统计 PEM 块类型，用来区分「文件不是 PEM」「私钥被加密」等情形。
func describePEM(certFile, keyFile string) string {
	counts := map[string]int{}
	for _, f := range []string{certFile, keyFile} {
		for _, blockType := range decodeAllPEM(readAll(f)) {
			counts[blockType]++
		}
	}
	if len(counts) == 0 {
		return "；诊断: 两个文件均未解析出任何 PEM 块（可能不是 PEM 格式，或路径指向了错误文件）"
	}
	msg := "；诊断: PEM 块统计"
	for _, k := range []string{"CERTIFICATE", "PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY", "ENCRYPTED PRIVATE KEY"} {
		if n, ok := counts[k]; ok {
			msg += fmt.Sprintf(" [%s=%d]", k, n)
		}
	}
	if counts["CERTIFICATE"] == 0 {
		msg += "；未找到 CERTIFICATE 块"
	}
	if counts["PRIVATE KEY"]+counts["RSA PRIVATE KEY"]+counts["EC PRIVATE KEY"] == 0 {
		msg += "；未找到未加密的私钥块（加密私钥不被支持）"
	}
	return msg
}

func readAll(path string) []byte {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	return b
}

func decodeAllPEM(data []byte) []string {
	var out []string
	for {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			return out
		}
		out = append(out, block.Type)
	}
}
