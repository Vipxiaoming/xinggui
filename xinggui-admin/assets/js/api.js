/* api.js — 唯一网络出口。所有页面只通过 API.* 访问后端。
 *
 * 模式：
 *   真实：API.base 指向聚合层（默认同源 /admin/api）
 *   mock：URL 带 ?mock=1，或后端不可达时自动降级
 * 鉴权：登录后返回 token，之后每个请求带 X-Admin-Token。
 */
(function (global) {
  "use strict";

  const TOKEN_KEY = "xg_admin_token";

  /* 平台编码以后端为准：wy/tx/kg/kw。
   * 后端 NormalizePlatform 同时接受 netease/qq/kugou/kuwo，
   * 但 config 与 stats 的回显始终是短码，前端统一用短码可避免
   * 「禁用网易云结果禁的是 netease 键、后端读的是 wy」这类静默错配。 */
  const PLATFORMS = ["wy", "tx", "kg", "kw"];
  const PLATFORM_LABEL = { wy: "网易云", tx: "QQ 音乐", kg: "酷狗", kw: "酷我" };
  const PLATFORM_ALIAS = {
    wy: "wy", netease: "wy", "163": "wy", wangyi: "wy",
    tx: "tx", qq: "tx", tencent: "tx", qqmusic: "tx",
    kg: "kg", kugou: "kg",
    kw: "kw", kuwo: "kw"
  };
  function normPlatform(p) { return PLATFORM_ALIAS[String(p || "").toLowerCase()] || String(p || ""); }
  const qs = new URLSearchParams(location.search);

  const API = {
    /* 可通过 ?api=https://host/admin/api 覆盖 */
    base: (qs.get("api") || "").replace(/\/$/, "") || (location.protocol === "file:" ? "http://127.0.0.1:8443/admin/api" : "/admin/api"),
    mock: qs.get("mock") === "1",
    /* 只有显式 ?mock=auto 才允许网络失败时降级；默认不降级，
     * 避免后端连不上时管理页静默显示 mock 假数据。 */
    autoMock: qs.get("mock") === "auto",
    reachable: null,       // null=未知 true/false
    lastError: null,
    token: null,
    onUnauthorized: null,

    /* ---------- 基础 ---------- */
    init: function () {
      try { API.token = sessionStorage.getItem(TOKEN_KEY) || null; } catch (e) { API.token = null; }
      return API;
    },
    setToken: function (t) {
      API.token = t || null;
      try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY); } catch (e) { }
    },
    isMock: function () { return API.mock; },
    url: function (path) { return API.base + path; },

    request: async function (method, path, opts) {
      opts = opts || {};
      const query = opts.query || null;
      const body = opts.body === undefined ? null : opts.body;

      if (API.mock) {
        const r = await global.Mock.handle(method, path, query, body);
        return unwrap(r);
      }

      let url = API.url(path);
      if (query) {
        const u = new URLSearchParams();
        Object.keys(query).forEach(k => { if (query[k] !== "" && query[k] != null) u.set(k, query[k]); });
        const s = u.toString();
        if (s) url += (url.includes("?") ? "&" : "?") + s;
      }

      const headers = { "Accept": "application/json" };
      if (body !== null) headers["Content-Type"] = "application/json";
      if (API.token) headers["X-Admin-Token"] = API.token;

      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), opts.timeout || 12000);

      let res;
      try {
        res = await fetch(url, {
          method: method,
          headers: headers,
          body: body === null ? undefined : JSON.stringify(body),
          credentials: "omit",
          signal: ctrl.signal
        });
        API.reachable = true;
      } catch (e) {
        clearTimeout(timer);
        API.reachable = false;
        API.lastError = String(e && e.message || e);
        if (API.autoMock) {
          API.mock = true;
          const r = await global.Mock.handle(method, path, query, body);
          return unwrap(r);
        }
        throw new ApiError(0, "NETWORK_ERROR", "无法连接服务端：" + API.lastError);
      }
      clearTimeout(timer);

      let payload = null;
      const text = await res.text();
      if (text) { try { payload = JSON.parse(text); } catch (e) { payload = null; } }

      if (res.status === 401) {
        API.setToken(null);
        if (typeof API.onUnauthorized === "function") API.onUnauthorized();
        throw new ApiError(401, (payload && payload.code) || "UNAUTHORIZED", (payload && payload.message) || "登录已失效，请重新登录");
      }
      if (!res.ok) {
        throw new ApiError(res.status, (payload && payload.code) || "HTTP_" + res.status,
          (payload && (payload.message || payload.error)) || ("请求失败：" + res.status));
      }
      return payload && Object.prototype.hasOwnProperty.call(payload, "data") ? payload.data : payload;
    },

    get: function (p, q, o) { return API.request("GET", p, Object.assign({ query: q }, o || {})); },
    post: function (p, b, o) { return API.request("POST", p, Object.assign({ body: b || {} }, o || {})); },
    put: function (p, b, o) { return API.request("PUT", p, Object.assign({ body: b || {} }, o || {})); },
    del: function (p, o) { return API.request("DELETE", p, o || {}); },

    /* ---------- 认证 ---------- */
    /* username 为第三个参数，保持既有 login(password, totp) 调用点不变；
     * 留空时后端仍按 "admin" 处理（admin.go:75-78），向后兼容。 */
    login: function (password, totp, username) {
      return API.post("/login", { password: password, totp: totp, username: String(username || "").trim() }).then(d => {
        API.setToken(d.token);
        return d;
      });
    },
    logout: function () {
      const p = API.post("/logout", {}).catch(() => { });
      API.setToken(null);
      return p;
    },
    setupStatus: function () { return API.get("/setup/status"); },
    setupInit: function (payload) { return API.post("/setup/init", payload); },
    health: function () { return API.get("/health"); },

    /* ---------- 用户 ---------- */
    users: function (params) { return API.get("/users", params); },
    user: function (u) { return API.get("/users/" + encodeURIComponent(u)); },
    userAction: function (u, action, body) {
      return API.post("/users/" + encodeURIComponent(u) + "/" + action, body || {});
    },
    resetPassword: function (u, body) { return API.post("/users/" + encodeURIComponent(u) + "/reset-password", body || {}); },
    unbindDevice: function (u, deviceId) { return API.post("/users/" + encodeURIComponent(u) + "/unbind", { deviceId: deviceId }); },
    setExpire: function (u, expireAt) { return API.post("/users/" + encodeURIComponent(u) + "/expire", { expireAt: expireAt }); },
    deleteUser: function (u) { return API.del("/users/" + encodeURIComponent(u)); },

    /* ---------- 用户数据 ---------- */
    favorites: function (u) { return API.get("/users/" + encodeURIComponent(u) + "/favorites"); },
    playlists: function (u) { return API.get("/users/" + encodeURIComponent(u) + "/playlists"); },
    history: function (u) { return API.get("/users/" + encodeURIComponent(u) + "/history"); },
    downloads: function (u) { return API.get("/users/" + encodeURIComponent(u) + "/downloads"); },
    searches: function (u) { return API.get("/users/" + encodeURIComponent(u) + "/searches"); },

    /* ---------- 配置 ---------- */
    config: function () { return API.get("/config"); },
    saveConfig: function (cfg) { return API.put("/config", cfg); },
    disablePlatform: function (name, disabled) {
      return API.post("/config/platforms/" + encodeURIComponent(name) + "/disable", { disabled: !!disabled });
    },
    version: function () { return API.get("/version"); },
    saveVersion: function (v) { return API.put("/version", v); },

    /* ---------- 监控 ---------- */
    overview: function () { return API.get("/stats/overview"); },
    statsResolve: function () { return API.get("/stats/resolve"); },
    statsErrors: function (q) { return API.get("/stats/errors", q); },
    statsSearches: function () { return API.get("/stats/searches"); }
  };

  function unwrap(r) {
    if (!r) return null;
    if (r.ok === true) return r.data;
    if (r.ok === false) throw new ApiError(r.status || 400, r.code || "ERROR", r.message || "请求失败");
    return r;
  }

  function ApiError(status, code, message) {
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.message = message;
    if (Error.captureStackTrace) Error.captureStackTrace(this, ApiError);
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  API.ApiError = ApiError;
  API.PLATFORMS = PLATFORMS;
  API.PLATFORM_LABEL = PLATFORM_LABEL;
  API.normPlatform = normPlatform;
  global.API = API.init();
})(window);

