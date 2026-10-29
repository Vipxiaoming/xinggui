/* ui.js — 无依赖的组件与工具：DOM 构造、图标、Toast、Modal、格式化、路由、折线图 */
(function (global) {
  "use strict";

  /* ---------- DOM ---------- */
  function h(tag, attrs, children) {
    const el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(k => {
        const v = attrs[k];
        if (v == null || v === false) return;
        if (k === "class") el.className = v;
        else if (k === "text") el.textContent = v;
        else if (k === "html") el.innerHTML = v;
        else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
        else if (k.slice(0, 2) === "on" && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
        else if (k === "dataset") Object.keys(v).forEach(d => { el.dataset[d] = v[d]; });
        else el.setAttribute(k, v === true ? "" : v);
      });
    }
    append(el, children);
    return el;
  }
  function append(el, children) {
    if (children == null) return el;
    (Array.isArray(children) ? children : [children]).forEach(c => {
      if (c == null || c === false) return;
      el.appendChild(c.nodeType ? c : document.createTextNode(String(c)));
    });
    return el;
  }
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.prototype.slice.call((root || document).querySelectorAll(sel));

  /* ---------- 图标（内联 SVG，离线可用） ---------- */
  const P = {
    dashboard: "M3 3h7v7H3zM14 3h7v5h-7zM14 11h7v10h-7zM3 13h7v8H3z",
    users: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75",
    pulse: "M22 12h-4l-3 9L9 3l-3 9H2",
    settings: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.6a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9c.14.38.48.66.9.78H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1",
    box: "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16M3.3 7 12 12l8.7-5M12 22V12",
    search: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16M21 21l-4.35-4.35",
    check: "M20 6 9 17l-5-5",
    x: "M18 6 6 18M6 6l12 12",
    alert: "M12 9v4M12 17h.01M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z",
    clock: "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20M12 6v6l4 2",
    heart: "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1L12 21l7.7-7.6 1.1-1a5.5 5.5 0 0 0 0-7.8z",
    list: "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01",
    download: "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3",
    play: "M5 3l14 9-14 9z",
    key: "M21 2l-2 2m-7.6 7.6a5.5 5.5 0 1 1-7.8 7.8 5.5 5.5 0 0 1 7.8-7.8zm0 0L15.5 7.5m0 0 3 3L22 7l-3-3",
    shield: "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z",
    refresh: "M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15",
    menu: "M3 12h18M3 6h18M3 18h18",
    sun: "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10M12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4",
    moon: "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z",
    trash: "M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6",
    unlock: "M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2M7 11V7a5 5 0 0 1 9.9-1",
    lock: "M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2M7 11V7a5 5 0 0 1 10 0v4",
    drag: "M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01",
    up: "M12 19V5M5 12l7-7 7 7",
    back: "M19 12H5M12 19l-7-7 7-7",
    down: "M12 5v14M19 12l-7 7-7-7",
    calendar: "M8 2v4M16 2v4M3 10h18M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
    device: "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2M12 18h.01",
    chart: "M18 20V10M12 20V4M6 20v-6",
    logout: "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
    logo: "M2 14c3.5-6 6.5-6 10 0s6.5 6 10 0"
  };
  function icon(name, size) {
    const s = size || 16;
    const d = P[name] || P.box;
    return '<svg viewBox="0 0 24 24" width="' + s + '" height="' + s + '" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="' + d + '"/></svg>';
  }
  function iconEl(name, size) {
    const span = document.createElement("span");
    span.style.display = "inline-flex";
    span.innerHTML = icon(name, size);
    return span.firstChild;
  }

  /* ---------- 格式化 ---------- */
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  function fmtNum(n) {
    if (n == null || isNaN(n)) return "-";
    if (Math.abs(n) >= 1e8) return (n / 1e8).toFixed(1) + " 亿";
    if (Math.abs(n) >= 1e4) return (n / 1e4).toFixed(n >= 1e6 ? 0 : 1) + " 万";
    return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }
  function fmtBytes(b) {
    if (b == null) return "-";
    const u = ["B", "KB", "MB", "GB"]; let i = 0; let v = Number(b);
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return v.toFixed(i === 0 ? 0 : 1) + " " + u[i];
  }
  function fmtDuration(sec) {
    if (sec == null) return "-";
    const m = Math.floor(sec / 60); const s = Math.floor(sec % 60);
    return m + ":" + (s < 10 ? "0" + s : s);
  }
  function fmtPct(v, digits) {
    if (v == null) return "-";
    const n = v > 1 ? v : v * 100;
    return n.toFixed(digits == null ? 1 : digits) + "%";
  }
  function relTime(t) {
    if (!t) return "-";
    const d = new Date(String(t).replace(" ", "T"));
    if (isNaN(d)) return String(t);
    const diff = (Date.now() - d.getTime()) / 1000;
    if (diff < 60) return "刚刚";
    if (diff < 3600) return Math.floor(diff / 60) + " 分钟前";
    if (diff < 86400) return Math.floor(diff / 3600) + " 小时前";
    if (diff < 86400 * 30) return Math.floor(diff / 86400) + " 天前";
    return t.slice(0, 10);
  }
  const STATUS_MAP = {
    active: { label: "正常", cls: "ok" },
    pending: { label: "待审核", cls: "warn" },
    banned: { label: "已封禁", cls: "danger" },
    rejected: { label: "已拒绝", cls: "muted" },
    expired: { label: "已过期", cls: "muted" },
    deleted: { label: "已删除", cls: "muted" },
    done: { label: "已完成", cls: "ok" },
    failed: { label: "失败", cls: "danger" },
    downloading: { label: "下载中", cls: "info" },
    error: { label: "错误", cls: "danger" },
    warn: { label: "警告", cls: "warn" }
  };
  function badge(status, overrideLabel) {
    const m = STATUS_MAP[status] || { label: status || "-", cls: "muted" };
    return '<span class="badge ' + m.cls + '">' + esc(overrideLabel || m.label) + "</span>";
  }
  function initials(name) {
    const s = String(name || "?");
    return esc(/[\u4e00-\u9fa5]/.test(s) ? s.slice(-2) : s.slice(0, 2).toUpperCase());
  }

  /* ---------- Toast ---------- */
  let toastBox = null;
  function toast(msg, kind, ms) {
    if (!toastBox) { toastBox = h("div", { class: "toasts", role: "status", "aria-live": "polite" }); document.body.appendChild(toastBox); }
    const el = h("div", { class: "toast " + (kind || "") }, [
      h("span", { html: icon(kind === "err" ? "alert" : kind === "warn" ? "alert" : "check", 16), style: { flex: "0 0 auto", marginTop: "1px", color: kind === "err" ? "var(--danger)" : kind === "warn" ? "var(--warn)" : "var(--ok)" } }),
      h("div", { class: "grow", text: msg })
    ]);
    toastBox.appendChild(el);
    setTimeout(() => {
      el.style.transition = "opacity .2s,transform .2s";
      el.style.opacity = "0"; el.style.transform = "translateX(10px)";
      setTimeout(() => el.remove(), 220);
    }, ms || 2600);
  }

  /* ---------- Modal ---------- */
  function modal(opts) {
    const overlay = h("div", { class: "overlay", role: "dialog", "aria-modal": "true" });
    const box = h("div", { class: "modal" + (opts.wide ? " wide" : "") });
    const head = h("div", { class: "modal-head" }, [
      h("h3", { text: opts.title || "" }),
      h("button", { class: "btn btn-ghost btn-sm icon-btn x", "aria-label": "关闭", html: icon("x", 16), onclick: close })
    ]);
    const body = h("div", { class: "modal-body" }, opts.content || null);
    box.appendChild(head); box.appendChild(body);

    let foot = null;
    if (opts.actions && opts.actions.length) {
      foot = h("div", { class: "modal-foot" });
      opts.actions.forEach(a => {
        foot.appendChild(h("button", {
          class: "btn " + (a.cls || ""), text: a.label, disabled: a.disabled,
          onclick: () => { a.onClick && a.onClick(close, body); }
        }));
      });
      box.appendChild(foot);
    }
    overlay.appendChild(box);
    overlay.addEventListener("mousedown", e => { if (e.target === overlay) close(); });
    document.addEventListener("keydown", onKey);
    document.body.appendChild(overlay);

    function onKey(e) { if (e.key === "Escape") close(); }
    function close() { document.removeEventListener("keydown", onKey); overlay.remove(); opts.onClose && opts.onClose(); }
    const first = body.querySelector("input,select,textarea,button");
    if (first) setTimeout(() => first.focus(), 30);
    return { close: close, body: body, root: box };
  }

  function confirmBox(opts) {
    return new Promise(resolve => {
      let settled = false;
      const m = modal({
        title: opts.title || "确认操作",
        content: h("div", null, [
          h("p", { text: opts.message || "", style: { margin: "0 0 6px" } }),
          opts.detail ? h("div", { class: "code", text: opts.detail }) : null
        ]),
        actions: [
          { label: "取消", onClick: c => { settled = true; c(); resolve(false); } },
          { label: opts.confirmLabel || "确认", cls: opts.danger ? "btn-danger" : "btn-primary", onClick: c => { settled = true; c(); resolve(true); } }
        ],
        onClose: () => { if (!settled) resolve(false); }
      });
      return m;
    });
  }

  function promptBox(opts) {
    return new Promise(resolve => {
      const input = h("input", { class: "input", type: opts.type || "text", placeholder: opts.placeholder || "", value: opts.value || "" });
      let settled = false;
      modal({
        title: opts.title || "输入",
        content: h("div", null, [
          opts.message ? h("p", { text: opts.message, style: { margin: "0 0 12px", color: "var(--muted)" } }) : null,
          h("div", { class: "field" }, [opts.label ? h("label", { text: opts.label }) : null, input]),
          opts.hint ? h("div", { class: "small muted", text: opts.hint }) : null
        ]),
        actions: [
          { label: "取消", onClick: c => { settled = true; c(); resolve(null); } },
          { label: opts.confirmLabel || "确定", cls: "btn-primary", onClick: c => { settled = true; c(); resolve(input.value); } }
        ],
        onClose: () => { if (!settled) resolve(null); }
      });
      input.addEventListener("keydown", e => { if (e.key === "Enter") { settled = true; resolve(input.value); input.closest(".overlay").remove(); } });
    });
  }

  /* ---------- 折线 / 迷你图 ---------- */
  function sparkline(values, w, h2, color) {
    if (!values || !values.length) return "";
    const width = w || 96, height = h2 || 30;
    const min = Math.min.apply(null, values), max = Math.max.apply(null, values);
    const span = max - min || 1;
    const step = width / Math.max(1, values.length - 1);
    const pts = values.map((v, i) => [i * step, height - 2 - ((v - min) / span) * (height - 5)]);
    const line = pts.map((p, i) => (i ? "L" : "M") + p[0].toFixed(1) + " " + p[1].toFixed(1)).join(" ");
    const area = line + " L" + width + " " + height + " L0 " + height + " Z";
    const id = "sg" + Math.random().toString(36).slice(2, 7);
    return '<svg width="' + width + '" height="' + height + '" viewBox="0 0 ' + width + " " + height + '" aria-hidden="true">' +
      '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="0" y2="1">' +
      '<stop offset="0" stop-color="' + (color || "#2b6cf6") + '" stop-opacity=".22"/>' +
      '<stop offset="1" stop-color="' + (color || "#2b6cf6") + '" stop-opacity="0"/></linearGradient></defs>' +
      '<path d="' + area + '" fill="url(#' + id + ')"/>' +
      '<path d="' + line + '" fill="none" stroke="' + (color || "#2b6cf6") + '" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  }

  function lineChart(values, opts) {
    opts = opts || {};
    const w = opts.width || 720, hh = opts.height || 190;
    const pad = { l: 46, r: 12, t: 14, b: 26 };
    const iw = w - pad.l - pad.r, ih = hh - pad.t - pad.b;
    const max = Math.max.apply(null, values) * 1.08 || 1;
    const min = 0;
    const stepX = iw / Math.max(1, values.length - 1);
    const y = v => pad.t + ih - ((v - min) / (max - min)) * ih;
    const pts = values.map((v, i) => [pad.l + i * stepX, y(v)]);
    const line = pts.map((p, i) => (i ? "L" : "M") + p[0].toFixed(1) + " " + p[1].toFixed(1)).join(" ");
    const area = line + " L" + (pad.l + iw).toFixed(1) + " " + (pad.t + ih) + " L" + pad.l + " " + (pad.t + ih) + " Z";
    let grid = "";
    for (let g = 0; g <= 3; g++) {
      const gy = pad.t + (ih / 3) * g;
      const gv = Math.round(max - (max / 3) * g);
      grid += '<line x1="' + pad.l + '" y1="' + gy + '" x2="' + (pad.l + iw) + '" y2="' + gy + '" stroke="currentColor" stroke-opacity=".1"/>';
      grid += '<text x="' + (pad.l - 8) + '" y="' + (gy + 4) + '" text-anchor="end" font-size="10" fill="currentColor" fill-opacity=".45">' + fmtNum(gv) + "</text>";
    }
    const dots = pts.filter((_, i) => i === pts.length - 1).map(p =>
      '<circle cx="' + p[0] + '" cy="' + p[1] + '" r="3.6" fill="#2b6cf6" stroke="#fff" stroke-width="2"/>').join("");
    return '<svg class="linechart" viewBox="0 0 ' + w + " " + hh + '" width="100%" height="' + hh + '" preserveAspectRatio="none" role="img" aria-label="趋势图" style="color:var(--text)">' +
      '<defs><linearGradient id="lc" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2b6cf6" stop-opacity=".25"/><stop offset="1" stop-color="#2b6cf6" stop-opacity="0"/></linearGradient></defs>' +
      grid + '<path d="' + area + '" fill="url(#lc)"/><path d="' + line + '" fill="none" stroke="#2b6cf6" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>' + dots + "</svg>";
  }

  /* ---------- 表格 ---------- */
  function emptyState(text, sub) {
    return h("div", { class: "empty" }, [
      h("div", { html: icon("box", 30) }),
      h("div", { text: text || "暂无数据", style: { fontWeight: "600" } }),
      sub ? h("div", { class: "small", text: sub, style: { marginTop: "4px" } }) : null
    ]);
  }

  function table(cols, rows, opts) {
    opts = opts || {};
    const tableEl = h("table", { class: "tbl" });
    const thead = h("thead", null, h("tr", null, cols.map(c =>
      h("th", { class: c.num ? "num" : "", style: c.width ? { width: c.width } : null, text: c.label })
    )));
    tableEl.appendChild(thead);
    const tbody = h("tbody");
    if (!rows.length) {
      tbody.appendChild(h("tr", null, h("td", { colspan: cols.length, style: { padding: "0" } },
        emptyState(opts.empty || "暂无数据", opts.emptySub))));
    } else {
      rows.forEach((row, ri) => {
        const tr = h("tr", { class: opts.onRow ? "row-link" : "" });
        if (opts.onRow) tr.addEventListener("click", e => { if (!e.target.closest("button,a,input")) opts.onRow(row, ri); });
        cols.forEach(c => {
          const td = h("td", { class: c.num ? "num" : "" });
          const v = c.render ? c.render(row, ri) : row[c.key];
          if (v && v.nodeType) td.appendChild(v); else td.innerHTML = v == null ? "-" : String(v);
          tr.appendChild(td);
        });
        tbody.appendChild(tr);
      });
    }
    tableEl.appendChild(tbody);
    return tableEl;
  }

  function pager(state, onChange) {
    const totalPages = Math.max(1, Math.ceil(state.total / state.pageSize));
    const bar = h("div", { class: "pager" });
    bar.appendChild(h("span", { text: "共 " + fmtNum(state.total) + " 条 · 第 " + state.page + " / " + totalPages + " 页" }));
    bar.appendChild(h("div", { class: "spacer" }));
    const mk = (label, page, dis, cur) => {
      const b = h("button", { text: label, disabled: dis, "aria-current": cur ? "true" : null });
      if (!dis && !cur) b.addEventListener("click", () => onChange(page));
      return b;
    };
    bar.appendChild(mk("‹", state.page - 1, state.page <= 1));
    const win = [];
    for (let p = 1; p <= totalPages; p++) {
      if (p === 1 || p === totalPages || Math.abs(p - state.page) <= 1) win.push(p);
      else if (win[win.length - 1] !== "…") win.push("…");
    }
    win.forEach(p => {
      if (p === "…") bar.appendChild(h("span", { text: "…", style: { padding: "0 4px" } }));
      else bar.appendChild(mk(String(p), p, false, p === state.page));
    });
    bar.appendChild(mk("›", state.page + 1, state.page >= totalPages));
    return bar;
  }

  /* ---------- 简易路由 ---------- */
  const routes = {};
  function route(pattern, handler) {
    const keys = [];
    const re = new RegExp("^" + pattern.replace(/:[a-zA-Z]+/g, m => { keys.push(m.slice(1)); return "([^/]+)"; }) + "$");
    routes[pattern] = { re: re, keys: keys, handler: handler };
  }
  function parseHash() {
    const raw = (location.hash || "#/dashboard").replace(/^#/, "");
    const [path, qs2] = raw.split("?");
    return { path: path || "/dashboard", query: new URLSearchParams(qs2 || "") };
  }
  function navigate(path, replace) {
    if (replace) location.replace("#" + path); else location.hash = path;
  }
  function resolveRoute() {
    const { path, query } = parseHash();
    for (const k in routes) {
      const r = routes[k];
      const m = path.match(r.re);
      if (m) {
        const params = {};
        r.keys.forEach((key, i) => { params[key] = decodeURIComponent(m[i + 1]); });
        return r.handler(params, query);
      }
    }
    navigate("/dashboard", true);
  }

  function debounce(fn, ms) {
    let t;
    return function () { const a = arguments, c = this; clearTimeout(t); t = setTimeout(() => fn.apply(c, a), ms || 260); };
  }

  global.UI = {
    h: h, $: $, $$: $$, append: append, icon: icon, iconEl: iconEl, esc: esc,
    fmtNum: fmtNum, fmtBytes: fmtBytes, fmtDuration: fmtDuration, fmtPct: fmtPct, relTime: relTime,
    badge: badge, initials: initials, STATUS_MAP: STATUS_MAP,
    toast: toast, modal: modal, confirm: confirmBox, prompt: promptBox,
    sparkline: sparkline, lineChart: lineChart, table: table, pager: pager, emptyState: emptyState,
    route: route, navigate: navigate, resolveRoute: resolveRoute, parseHash: parseHash, debounce: debounce
  };
})(window);
