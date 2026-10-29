#!/usr/bin/env node
/* 零依赖静态服务器 + /admin/api 同源反向代理
 *
 * 为什么要代理：聚合层默认 8443，管理页默认 8787，浏览器跨域访问时
 * 聚合层未下发 Access-Control-Allow-* 头，fetch 会被直接拦掉，
 * 于是前端只能降级到 mock。走同源代理后浏览器看到的是 8787，
 * 不存在跨域预检，也不需要改后端 CORS 策略。
 *
 * 用法：
 *   node server.js [port]
 *   XG_BACKEND=http://127.0.0.1:9443 node server.js 8787
 */
const http = require("http"), https = require("https"), fs = require("fs"), path = require("path");

const root = __dirname;
const port = Number(process.argv[2] || process.env.PORT || 8787);
const backend = (process.env.XG_BACKEND || "http://127.0.0.1:9443").replace(/\/$/, "");

const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8", ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg",
  ".ico": "image/x-icon", ".woff2": "font/woff2", ".map": "application/json"
};

const HOP = new Set(["connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
  "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length"]);

function proxy(req, res, p) {
  let target;
  try { target = new URL(backend + p); } catch (e) {
    res.writeHead(502, { "Content-Type": "application/json; charset=utf-8" });
    return res.end(JSON.stringify({ error: "bad_gateway", message: "XG_BACKEND 不是合法 URL" }));
  }
  const mod = target.protocol === "https:" ? https : http;
  const headers = {};
  Object.keys(req.headers).forEach(k => { if (!HOP.has(k.toLowerCase())) headers[k] = req.headers[k]; });

  const up = mod.request({
    protocol: target.protocol, hostname: target.hostname,
    port: target.port || (target.protocol === "https:" ? 443 : 80),
    path: target.pathname + target.search, method: req.method, headers: headers
  }, upRes => {
    const outHeaders = {};
    Object.keys(upRes.headers).forEach(k => { if (!HOP.has(k.toLowerCase())) outHeaders[k] = upRes.headers[k]; });
    outHeaders["Cache-Control"] = "no-store";
    res.writeHead(upRes.statusCode || 502, outHeaders);
    upRes.pipe(res);
  });

  up.on("error", err => {
    if (res.headersSent) { res.end(); return; }
    res.writeHead(502, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: "bad_gateway", message: "聚合层不可达：" + err.message + "（目标 " + backend + "）" }));
  });
  req.pipe(up);
}

http.createServer((req, res) => {
  const parsed = new URL(req.url, "http://localhost");
  let p = decodeURIComponent(parsed.pathname || "/");

  /* 1) API 走代理，必须在静态映射之前判断 */
  if (p === "/admin/api" || p.startsWith("/admin/api/")) {
    return proxy(req, res, p + (parsed.search || ""));
  }

  /* 2) /admin 前缀映射到静态根，方便与网关同域部署 */
  if (p === "/admin" || p.startsWith("/admin/")) p = p.slice(6) || "/index.html";
  if (p === "/" || p === "") p = "/index.html";

  const file = path.join(root, path.normalize(p).replace(/^([/\\])+/, ""));
  if (!file.startsWith(root)) { res.writeHead(403); return res.end("forbidden"); }

  fs.readFile(file, (err, buf) => {
    if (err) {
      return fs.readFile(path.join(root, "index.html"), (e2, idx) => {
        if (e2) { res.writeHead(404); return res.end("not found"); }
        res.writeHead(200, { "Content-Type": MIME[".html"], "Cache-Control": "no-cache" });
        res.end(idx);
      });
    }
    res.writeHead(200, {
      "Content-Type": MIME[path.extname(file).toLowerCase()] || "application/octet-stream",
      "Cache-Control": "no-cache"
    });
    res.end(buf);
  });
}).listen(port, () => {
  console.log("xinggui-admin  http://127.0.0.1:" + port + "/");
  console.log("  API 代理      " + backend + "/admin/api  （同源，无 CORS）");
  console.log("  纯 mock 演示  http://127.0.0.1:" + port + "/?mock=1");
});

