/* mock.js — 无后端时的内置演示数据与假路由。仅当 ?mock=1 或后端不可达时启用。 */
(function (global) {
  "use strict";

  const PLATFORMS = ["netease", "qq", "kugou", "kuwo"];
  const PLATFORM_LABEL = { netease: "网易云", qq: "QQ音乐", kugou: "酷狗", kuwo: "酷我" };

  const NAMES = ["林间小鹿", "夜航船", "七月流火", "沉默的羔羊", "半糖去冰", "旧巷南", "山鬼谣", "折光",
    "听风的人", "北岛不冷", "云上白", "荔枝汽水", "暮色四合", "季风带", "空山新雨", "不系之舟",
    "一二三", "青柠", "雾都孤儿", "长夜漫漫", "苏打绿了", "雨落长安", "橘子海", "慢半拍",
    "电气樱桃", "睡不着的鱼", "十二月的风", "低空飞行", "南墙", "拾光者", "空白格", "深海鲸落"];

  const STATUS = ["active", "pending", "active", "active", "banned", "expired", "active", "active"];

  function hash(str) {
    let h = 2166136261;
    for (let i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = Math.imul(h, 16777619); }
    return (h >>> 0);
  }
  function rnd(seed, i) { return ((hash(seed + ":" + i) % 10000) / 10000); }
  function pad(n) { return n < 10 ? "0" + n : "" + n; }
  function daysAgo(n, hh, mm) {
    const d = new Date(Date.now() - n * 86400000);
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + " " +
      pad(hh == null ? 9 : hh) + ":" + pad(mm == null ? 12 : mm);
  }

  /* 30 天趋势 */
  const TREND = Array.from({ length: 30 }, (_, i) => {
    const base = 1600 + i * 26;
    const wave = Math.sin(i / 3.1) * 260 + Math.cos(i / 1.7) * 110;
    return Math.max(600, Math.round(base + wave + (rnd("trend", i) - .5) * 180));
  });

  /* ---- 用户 ---- */
  const users = NAMES.map((name, i) => {
    const st = STATUS[i % STATUS.length];
    const devices = st === "banned" ? 0 : (i % 3) + 1;
    const reg = daysAgo(120 - i * 3, 8 + (i % 10), (i * 7) % 60);
    const last = daysAgo(i % 14, 9 + (i % 12), (i * 11) % 60);
    return {
      username: name,
      email: "user" + (1000 + i) + "@example.com",
      status: st,
      role: i === 0 ? "admin" : "user",
      devices,
      maxDevices: 2,
      registeredAt: reg,
      lastActiveAt: last,
      expireAt: st === "expired" ? daysAgo(6, 0, 0).slice(0, 10) : daysAgo(-(30 + i * 9), 0, 0).slice(0, 10),
      resolveCount: 0,
      deviceList: Array.from({ length: devices }, (_, d) => ({
        id: "dev_" + hash(name + d).toString(16).padStart(8, "0"),
        model: ["Xiaomi 14", "iPhone 15", "Pixel 8", "OnePlus 12", "HUAWEI Mate 60"][(i + d) % 5],
        os: ["Android 14", "iOS 17.5", "Android 15"][(i + d) % 3],
        lastSeen: daysAgo(d, 10 + d, 20),
        current: d === 0
      })),
      note: i % 7 === 0 ? "内测群成员" : ""
    };
  });

  /* ---- 每用户数据 ---- */
  const SONG_POOL = [
    ["晴天", "周杰伦", "叶惠美"], ["海阔天空", "Beyond", "乐与怒"], ["夜曲", "周杰伦", "十一月的萧邦"],
    ["起风了", "买辣椒也用券", "起风了"], ["句号", "G.E.M.邓紫棋", "启示录"], ["孤勇者", "陈奕迅", "孤勇者"],
    ["漠河舞厅", "柳爽", "漠河舞厅"], ["海底", "一支榴莲", "海底"], ["漠河舞厅 (Live)", "柳爽", "Live"],
    ["成都", "赵雷", "无法长大"], ["南方姑娘", "赵雷", "赵小雷"], ["平凡之路", "朴树", "猎户星座"],
    ["稻香", "周杰伦", "魔杰座"], ["七里香", "周杰伦", "七里香"], ["富士山下", "陈奕迅", "What's Going On…"],
    ["蓝色土耳其", "周传雄", "蓝色土耳其"], ["光年之外", "G.E.M.邓紫棋", "光年之外"], ["演员", "薛之谦", "绅士"],
    ["丑八怪", "薛之谦", "意外"], ["大鱼", "周深", "大鱼"], ["玫瑰少年", "蔡依林", "UGLY BEAUTY"],
    ["以父之名", "周杰伦", "叶惠美"], ["挪威的森林", "伍佰 & China Blue", "爱情的尽头"],
    ["突然的自我", "伍佰 & China Blue", "泪桥"], ["一生所爱", "卢冠廷", "齐天周大圣之西游双记"]
  ];
  const SEARCH_POOL = ["晴天", "周杰伦", "漠河舞厅", "孤勇者", "粤语老歌", "city pop", "钢琴曲",
    "陈奕迅", "G.E.M.", "白噪音", "学习专用", "摇滚", "民谣", "抖音热歌", "Beyond", "夜曲",
    "2024新歌", "王菲", "李宗盛", "开车听"];

  function mkSong(i, seed) {
    const s = SONG_POOL[(hash(seed) + i) % SONG_POOL.length];
    return {
      id: "s_" + hash(seed + i).toString(16),
      title: s[0], artist: s[1], album: s[2],
      platform: PLATFORMS[(hash(seed + "p" + i)) % 4],
      duration: 180 + (hash(seed + "d" + i) % 160),
      available: (hash(seed + "a" + i) % 13) !== 0
    };
  }

  const DATA = {};
  users.forEach((u, ui) => {
    const seed = u.username;
    DATA[u.username] = {
      favorites: Array.from({ length: 18 + (hash(seed) % 30) }, (_, i) => mkSong(i, seed + "fav")),
      playlists: [
        {
          id: "pl_" + hash(seed + "1"), name: "深夜循环", isPublic: true, createdAt: daysAgo(60, 22, 10),
          songs: Array.from({ length: 12 }, (_, i) => mkSong(i, seed + "pl1"))
        },
        {
          id: "pl_" + hash(seed + "2"), name: "通勤 40 分钟", isPublic: false, createdAt: daysAgo(31, 8, 5),
          songs: Array.from({ length: 9 }, (_, i) => mkSong(i, seed + "pl2"))
        },
        {
          id: "pl_" + hash(seed + "3"), name: "粤语黄金年代", isPublic: true, createdAt: daysAgo(9, 19, 42),
          songs: Array.from({ length: 7 }, (_, i) => mkSong(i, seed + "pl3"))
        }
      ].slice(0, 2 + (hash(seed) % 2)),
      history: Array.from({ length: 24 }, (_, i) => {
        const s = mkSong(i, seed + "his");
        return Object.assign({}, s, {
          playedAt: daysAgo(i % 9, 7 + (i % 16), (i * 13) % 60),
          playedSeconds: Math.min(s.duration, 20 + (hash(seed + "s" + i) % s.duration)),
          completed: (hash(seed + "c" + i) % 3) === 0
        });
      }),
      downloads: Array.from({ length: 4 + (hash(seed) % 6) }, (_, i) => {
        const s = mkSong(i, seed + "dl");
        return Object.assign({}, s, {
          status: ["done", "done", "done", "failed", "downloading"][hash(seed + "ds" + i) % 5],
          quality: ["128k", "320k", "flac"][hash(seed + "q" + i) % 3],
          bytes: 3000000 + (hash(seed + "b" + i) % 26000000),
          createdAt: daysAgo(i, 12, 30),
          path: "/storage/emulated/0/Download/星轨/" + s.artist + " - " + s.title + ".mp3"
        });
      }),
      searches: Array.from({ length: 12 + (hash(seed) % 14) }, (_, i) => ({
        keyword: SEARCH_POOL[(hash(seed + "sq") + i) % SEARCH_POOL.length],
        at: daysAgo(i % 11, 8 + (i % 14), (i * 17) % 60),
        results: hash(seed + "r" + i) % 60
      }))
    };
  });

  /* 解析统计（每用户 resolveCount 粗略） */
  users.forEach((u, i) => { u.resolveCount = 120 + (hash(u.username) % 3400); });

  const adminMock = {
    token: "mock-admin-token-9f2c",
    expiresIn: 7200
  };

  const systemConfig = {
    appEnabled: true,
    registrationOpen: true,
    requireApproval: true,
    maxDevices: 2,
    resolveOrder: ["netease", "qq", "kugou", "kuwo"],
    platformDisabled: { kuwo: false, kugou: false, qq: false, netease: false },
    cacheTtlSeconds: 1800,
    rateLimitPerMinute: 60,
    downloadDir: "Download/星轨",
    allowedQualities: ["128k", "320k", "flac"],
    forceUpdate: false,
    announcement: ""
  };

  const versionInfo = {
    minVersion: "1.0.0",
    latestVersion: "1.2.3",
    updateUrl: "https://music.wangxiaoming.vip/dl/app-1.2.3.apk",
    updateSha256: "3f8a1c9d2b7e4056a1c3d5e7f90123456789abcdef0123456789abcdef012345",
    updateNotes: "1. 修复部分机型播放中断\n2. 优化解析速度\n3. 新增歌单同步",
    forceUpdate: false,
    publishedAt: "2026-10-21 18:40"
  };

  const stats = {
    overview: {
      totalUsers: 1284, onlineUsers: 176, todayResolves: 18420,
      newUsersToday: 23, pendingApprovals: 7, activeDevices: 1932,
      trend: TREND,
      deltas: { users: 4.2, online: -2.1, resolves: 11.6 }
    },
    resolve: PLATFORMS.map((p, i) => ({
      platform: p,
      success: [0.982, 0.941, 0.907, 0.868][i],
      total: [8421, 5233, 3110, 1656][i],
      p50: [420, 610, 780, 940][i],
      p95: [1180, 1720, 2140, 2680][i]
    })),
    errors: Array.from({ length: 26 }, (_, i) => ({
      at: daysAgo(0, 9 + (i % 10), (i * 7) % 60),
      level: ["error", "warn", "error", "error", "warn"][i % 5],
      platform: PLATFORMS[i % 4],
      code: ["UPSTREAM_TIMEOUT", "SIGN_INVALID", "EMPTY_RESULT", "RATE_LIMITED", "DECODE_FAIL"][i % 5],
      message: [
        "upstream connect timeout after 3000ms",
        "kugou h5 signature rejected (errcode 1002)",
        "resolved url empty, fallback exhausted",
        "client exceeded 60 req/min",
        "audio decode failed: unsupported codec"
      ][i % 5],
      user: users[i % users.length].username
    })),
    searches: SEARCH_POOL.map((k, i) => ({ keyword: k, count: 620 - i * 24 + (hash(k) % 60) })).sort((a, b) => b.count - a.count),
    health: { uptime: "13d 06h", cpu: 18.4, mem: 41.2, disk: 38.7, dbLatencyMs: 3.2, goroutines: 148 }
  };

  /* ---- 假路由 ---- */
  function clone(v) { return JSON.parse(JSON.stringify(v)); }

  const MOCK_DELAY = 160;

  function handle(method, path, query, body) {
    /* 归一化：api.js 传入的是 /stats/overview 这类相对路径 */
    if (path.indexOf("/admin/api") !== 0) path = "/admin/api" + (path.charAt(0) === "/" ? "" : "/") + path;
    const [rawPath] = path.split("?");
    const seg = rawPath.replace(/^\/admin\/api\/?/, "").split("/").filter(Boolean);
    const uname = seg[0] === "users" && seg[1] ? decodeURIComponent(seg[1]) : null;

    /* 登录 */
    if (rawPath === "/admin/api/login" && method === "POST") {
      if (!body || !body.password || !/^\d{6}$/.test(String(body.totp || ""))) {
        return err(400, "BAD_REQUEST", "请输入密码与 6 位动态码");
      }
      if (body.password !== "admin12345") {
        return err(401, "INVALID_CREDENTIALS", "密码或动态码错误（mock 密码 admin12345 / 动态码任意 6 位）");
      }
      return ok({ token: adminMock.token, expiresIn: adminMock.expiresIn, username: "admin", role: "superadmin" });
    }
    if (rawPath === "/admin/api/setup/status") return ok({ initialized: true });
    if (rawPath === "/admin/api/logout") return ok({ ok: true });

    /* 列表 */
    if (rawPath === "/admin/api/users" && method === "GET") {
      let list = users.slice();
      const q = (query.q || "").trim().toLowerCase();
      if (q) list = list.filter(u => u.username.toLowerCase().includes(q) || u.email.toLowerCase().includes(q));
      if (query.status) list = list.filter(u => u.status === query.status);
      const page = Math.max(1, parseInt(query.page || "1", 10));
      const size = Math.max(1, Math.min(100, parseInt(query.pageSize || "10", 10)));
      const total = list.length;
      const items = list.slice((page - 1) * size, page * size).map(u => ({
        username: u.username, email: u.email, status: u.status, role: u.role,
        devices: u.devices, maxDevices: u.maxDevices, registeredAt: u.registeredAt,
        lastActiveAt: u.lastActiveAt, expireAt: u.expireAt, resolveCount: u.resolveCount
      }));
      return ok({ items, total, page, pageSize: size });
    }
    if (rawPath === "/admin/api/stats/overview") return ok(clone(stats.overview));
    if (rawPath === "/admin/api/stats/resolve") return ok({ items: clone(stats.resolve) });
    if (rawPath === "/admin/api/stats/errors") return ok({ items: clone(stats.errors), total: stats.errors.length });
    if (rawPath === "/admin/api/stats/searches") return ok({ items: clone(stats.searches).slice(0, 50) });
    if (rawPath === "/admin/api/health") return ok(clone(stats.health));
    if (rawPath === "/admin/api/config") {
      if (method === "PUT") { Object.assign(systemConfig, body || {}); return ok(clone(systemConfig)); }
      return ok(clone(systemConfig));
    }
    if (rawPath === "/admin/api/version") {
      if (method === "PUT") { Object.assign(versionInfo, body || {}); return ok(clone(versionInfo)); }
      return ok(clone(versionInfo));
    }
    if (/^\/admin\/api\/config\/platforms\/[^/]+\/disable$/.test(rawPath) && method === "POST") {
      const name = rawPath.split("/")[4];
      const disabled = body && typeof body.disabled === "boolean" ? body.disabled : true;
      systemConfig.platformDisabled[name] = disabled;
      return ok({ platform: name, disabled });
    }

    if (uname) {
      const u = users.find(x => x.username === uname);
      const d = DATA[uname];
      if (!u) return err(404, "USER_NOT_FOUND", "用户不存在");
      const action = seg[2];
      if (!action && method === "GET") {
        const out = clone(u);
        return ok(out);
      }
      if (!action && method === "DELETE") { u.status = "deleted"; return ok({ ok: true }); }
      if (method === "POST") {
        if (action === "approve") { u.status = "active"; return ok({ ok: true, status: "active" }); }
        if (action === "reject") { u.status = "rejected"; return ok({ ok: true, status: "rejected" }); }
        if (action === "ban") { u.status = "banned"; u.devices = 0; return ok({ ok: true, status: "banned" }); }
        if (action === "unban") { u.status = "active"; return ok({ ok: true, status: "active" }); }
        if (action === "reset-password") return ok({ ok: true, tempPassword: "Xg-" + Math.random().toString(36).slice(2, 10) });
        if (action === "unbind") {
          const id = body && body.deviceId;
          u.deviceList = (u.deviceList || []).filter(x => x.id !== id);
          u.devices = u.deviceList.length;
          return ok({ ok: true, devices: u.devices });
        }
        if (action === "expire") {
          if (body && body.expireAt) u.expireAt = body.expireAt;
          return ok({ ok: true, expireAt: u.expireAt });
        }
      }
      if (method === "GET") {
        if (action === "favorites") return ok({ items: clone(d ? d.favorites : []) });
        if (action === "playlists") return ok({ items: clone(d ? d.playlists : []) });
        if (action === "history") return ok({ items: clone(d ? d.history : []) });
        if (action === "downloads") return ok({ items: clone(d ? d.downloads : []) });
        if (action === "searches") return ok({ items: clone(d ? d.searches : []) });
      }
    }

    return err(404, "NOT_FOUND", "mock 未实现该端点: " + method + " " + rawPath);
  }

  function ok(data) { return { ok: true, status: 200, data: data }; }
  function err(status, code, message) { return { ok: false, status: status, code: code, message: message }; }

  global.Mock = {
    handle: function (method, path, query, body) {
      const res = handle(method, path, query, body);
      return new Promise(r => setTimeout(() => r(res), MOCK_DELAY));
    },
    PLATFORM_LABEL: PLATFORM_LABEL,
    PLATFORMS: PLATFORMS,
    passwordHint: "admin12345"
  };
})(window);
