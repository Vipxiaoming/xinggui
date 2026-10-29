/* app.js — 应用外壳：登录、初始化向导、侧栏、路由注册与全局状态 */
(function (global) {
  "use strict";
  const { h, icon, toast, $ } = UI;

  const NAV = [
    { group: "总览", items: [{ path: "/dashboard", label: "监控面板", icon: "dashboard" }] },
    {
      group: "运营", items: [
        { path: "/users", label: "用户管理", icon: "users" },
        { path: "/approvals", label: "待审核", icon: "shield", badgeKey: "pending" }
      ]
    },
    { group: "配置", items: [{ path: "/config", label: "系统配置", icon: "settings" }, { path: "/version", label: "版本管理", icon: "box" }] },
    { group: "观测", items: [{ path: "/errors", label: "错误日志", icon: "alert" }] }
  ];

  const App = {
    user: null,
    pendingCount: 0,
    theme: "light",
    shellMounted: false,
    currentPath: "/dashboard",

    boot: function () {
      App.loadTheme();
      const params = new URLSearchParams(location.search);
      if (params.get("mock") === "1") {
        // mock 演示模式：免登录直接进入，省去验收时手输
      }
      App.registerRoutes();
      window.addEventListener("hashchange", () => {
        App.markActive();
        UI.resolveRoute();
      });

      if (API.token || API.mock) {
        if (!location.hash) UI.navigate("/dashboard", true);
        App.mountShell();
        UI.resolveRoute();
      } else {
        App.showLogin();
      }
    },

    /* ---------- 主题 ---------- */
    loadTheme: function () {
      let t = "light";
      try { t = localStorage.getItem("xg_theme") || "light"; } catch (e) { }
      /* 允许 ?theme=dark 直接指定，便于验收与演示 */
      const qp = new URLSearchParams(location.search).get("theme");
      if (qp === "dark" || qp === "light") t = qp;
      App.theme = t === "dark" ? "dark" : "light";
      document.documentElement.setAttribute("data-theme", App.theme);
    },
    toggleTheme: function () {
      App.theme = App.theme === "dark" ? "light" : "dark";
      document.documentElement.setAttribute("data-theme", App.theme);
      try { localStorage.setItem("xg_theme", App.theme); } catch (e) { }
      const btn = $("#themeBtn");
      if (btn) btn.innerHTML = icon(App.theme === "dark" ? "sun" : "moon", 17);
    },

    /* ---------- 登录 ---------- */
    showLogin: function () {
      document.body.innerHTML = "";
      const user = h("input", { class: "input", type: "text", id: "loginUser", autocomplete: "username", placeholder: "管理员用户名", value: "admin", required: true });
      const pwd = h("input", { class: "input", type: "password", id: "loginPwd", autocomplete: "current-password", placeholder: "管理员密码", required: true });
      const totp = h("input", { class: "input", type: "text", id: "loginTotp", inputmode: "numeric", maxlength: "6", autocomplete: "one-time-code", placeholder: "6 位动态码", required: true, style: { letterSpacing: ".42em", fontFamily: "var(--mono)", textAlign: "center" } });
      const errBox = h("div", { class: "notice warn hidden", role: "alert" });
      const submit = h("button", { class: "btn btn-primary btn-block", type: "submit", style: { marginTop: "6px", padding: "10px" } }, "登录管理后台");
      const mockHint = API.mock ? h("div", { class: "notice", style: { marginTop: "14px" } }, [
        h("span", { class: "ico", html: icon("alert", 15) }),
        h("div", null, [h("b", { text: "Mock 演示模式" }), "　密码 ", h("span", { class: "mono", text: "admin12345" }), "，动态码任意 6 位"])
      ]) : null;

      const form = h("form", {
        onsubmit: async function (e) {
          e.preventDefault();
          errBox.classList.add("hidden");
          const btnText = submit.innerHTML;
          submit.disabled = true; submit.innerHTML = '<span class="spin"></span> 验证中';
          try {
            const d = await API.login(pwd.value, totp.value.trim(), user.value);
            App.user = { username: d.username || "admin", role: d.role || "superadmin" };
            App.mountShell();
            UI.navigate("/dashboard", true);
            UI.resolveRoute();
            toast("已登录", "ok");
          } catch (err) {
            errBox.textContent = err.message || "登录失败";
            errBox.classList.remove("hidden");
            totp.select();
          } finally {
            submit.disabled = false; submit.innerHTML = btnText;
          }
        }
      }, [
        h("div", { class: "field" }, [h("label", { text: "管理员用户名", for: "loginUser" }), user,
          h("div", { class: "hint", text: "非 admin 管理员请填写自己的用户名；默认 admin 与旧版行为一致" })]),
        h("div", { class: "field" }, [h("label", { text: "管理员密码", for: "loginPwd" }), pwd]),
        h("div", { class: "field" }, [h("label", { text: "动态码（TOTP）", for: "loginTotp" }), totp,
          h("div", { class: "hint", text: "打开身份验证器 App，输入当前 6 位数字" })]),
        errBox,
        submit
      ]);

      const wrap = h("div", { class: "auth-wrap" }, [
        h("div", { class: "auth-hero" }, [
          h("div", { class: "brand", html: icon("logo", 22) + "<span>星轨 Xinggui</span>" }),
          h("div", null, [
            h("h2", { text: "聚合音乐 · 管理后台" }),
            h("p", { text: "用户审核、设备绑定、系统配置与解析监控，集中在一个控制台。" })
          ]),
          h("div", { class: "hero-meta" }, [
            h("span", { text: "聚合层 v1.0" }),
            h("span", { text: "·" }),
            h("span", { text: API.mock ? "Mock 模式" : "生产模式" })
          ])
        ]),
        h("div", { class: "auth-form-side" }, [
          h("div", { class: "auth-card" }, [
            h("h1", { text: "管理员登录" }),
            h("p", { class: "sub", text: "仅限授权管理员访问。所有操作均记录审计日志。" }),
            form,
            mockHint,
            h("div", { class: "auth-foot" }, [
              h("span", { text: "服务端 " }),
              h("span", { class: "mono", text: API.mock ? "mock://local" : API.base })
            ])
          ])
        ])
      ]);
      document.body.appendChild(wrap);
      setTimeout(() => pwd.focus(), 60);
    },

    /* ---------- 外壳 ---------- */
    mountShell: function () {
      if (App.shellMounted) return;
      document.body.innerHTML = "";

      const nav = h("nav", { class: "nav" });
      NAV.forEach(sec => {
        nav.appendChild(h("div", { class: "nav-label", text: sec.group }));
        sec.items.forEach(it => {
          const a = h("a", { href: "#" + it.path, "data-path": it.path, dataset: { path: it.path } }, [
            h("span", { html: icon(it.icon, 17), style: { display: "inline-flex" } }),
            h("span", { text: it.label }),
            it.badgeKey ? h("span", { class: "dot hidden", text: "0" }) : null
          ]);
          nav.appendChild(a);
        });
      });

      const sidebar = h("aside", { class: "sidebar", id: "sidebar" }, [
        h("div", { class: "logo", html: '<span class="mark">' + icon("logo", 17) + "</span><span>星轨<small>Xinggui Admin</small></span>" }),
        nav,
        h("div", { class: "sidebar-foot" }, [
          h("div", { class: "avatar", text: UI.initials((App.user && App.user.username) || "admin") }),
          h("div", { class: "grow", style: { minWidth: "0" } }, [
            h("div", { style: { fontWeight: "600", fontSize: "12.5px" }, text: (App.user && App.user.username) || "admin" }),
            h("div", { style: { fontSize: "11px", color: "var(--muted-2)" }, text: "超级管理员" })
          ]),
          h("button", { class: "btn btn-ghost btn-sm icon-btn", title: "退出登录", "aria-label": "退出登录", html: icon("logout", 16), onclick: App.logout })
        ])
      ]);

      const crumb = h("div", { class: "crumb" }, [h("span", { text: "星轨" }), h("span", { text: "/" }), h("b", { id: "crumbNow", text: "监控面板" })]);
      const envPill = h("span", { class: "env-pill" }, [h("span", { class: "pulse" }), h("span", { text: API.mock ? "MOCK" : "LIVE" })]);
      const themeBtn = h("button", { class: "btn btn-ghost btn-sm icon-btn", id: "themeBtn", title: "切换主题", "aria-label": "切换主题", html: icon(App.theme === "dark" ? "sun" : "moon", 17), onclick: App.toggleTheme });
      const menuBtn = h("button", { class: "btn btn-ghost btn-sm icon-btn menu-btn", title: "菜单", "aria-label": "菜单", html: icon("menu", 18), onclick: () => $("#sidebar").classList.toggle("open") });

      const topbar = h("header", { class: "topbar" }, [menuBtn, crumb, h("div", { class: "spacer" }),
        envPill,
        h("button", { class: "btn btn-ghost btn-sm", title: "刷新当前页", html: icon("refresh", 15) + " <span>刷新</span>", onclick: () => UI.resolveRoute() }),
        themeBtn]);

      const content = h("main", { class: "content", id: "view", tabindex: "-1" });
      document.body.appendChild(h("div", { class: "shell" }, [sidebar, h("div", { class: "main" }, [topbar, content])]));
      App.shellMounted = true;
      App.refreshBadge();
    },

    refreshBadge: function () {
      if (API.mock) return;
      API.users({ status: "pending", page: 1, pageSize: 1 }).then(d => {
        const n = (d && (d.total != null ? d.total : (d.items || []).length)) || 0;
        const el = document.querySelector('a[data-path="/approvals"] .dot');
        if (!el) return;
        if (n > 0) { el.textContent = n > 99 ? "99+" : n; el.classList.remove("hidden"); }
        else el.classList.add("hidden");
      }).catch(() => { });
    },

    markActive: function () {
      const p = UI.parseHash().path;
      App.currentPath = p;
      UI.$$(".nav a").forEach(a => {
        const target = a.getAttribute("data-path");
        a.classList.toggle("active", p === target || (target === "/users" && /^\/users\//.test(p)));
      });
      const titles = { "/dashboard": "监控面板", "/users": "用户管理", "/approvals": "待审核", "/config": "系统配置", "/version": "版本管理", "/errors": "错误日志" };
      const key = Object.keys(titles).find(t => p === t || (t === "/users" && /^\/users\//.test(p)));
      const el = document.getElementById("crumbNow");
      if (el) el.textContent = titles[key] || (p.indexOf("/users/") === 0 ? "用户详情" : "控制台");
      const sb = document.getElementById("sidebar");
      if (sb && window.innerWidth <= 900) sb.classList.remove("open");
    },

    logout: async function () {
      const yes = await UI.confirm({ title: "退出登录", message: "确认退出管理后台？", confirmLabel: "退出" });
      if (!yes) return;
      await API.logout();
      App.user = null; App.shellMounted = false;
      App.showLogin();
      toast("已退出", "ok");
    },

    /* 页面挂载辅助 */
    mount: function (node) {
      const view = document.getElementById("view");
      if (!view) return null;
      view.innerHTML = "";
      view.appendChild(node);
      App.markActive();
      return view;
    },

    pageHead: function (title, sub, actions) {
      return h("div", { class: "page-head" }, [
        h("div", null, [h("h1", { text: title }), sub ? h("div", { class: "sub", text: sub }) : null]),
        actions ? h("div", { class: "actions" }, actions) : null
      ]);
    },

    registerRoutes: function () {
      UI.route("/dashboard", (p, q) => global.Pages.dashboard(p, q));
      UI.route("/users", (p, q) => global.Pages.users(p, q));
      UI.route("/users/:username", (p, q) => global.Pages.userDetail(p, q));
      UI.route("/approvals", (p, q) => global.Pages.approvals(p, q));
      UI.route("/config", (p, q) => global.Pages.config(p, q));
      UI.route("/version", (p, q) => global.Pages.version(p, q));
      UI.route("/errors", (p, q) => global.Pages.errors(p, q));
    }
  };

  API.onUnauthorized = function () {
    App.shellMounted = false;
    App.showLogin();
    toast("登录已失效，请重新登录", "warn");
  };

  global.App = App;
  window.addEventListener("DOMContentLoaded", () => App.boot());
})(window);
