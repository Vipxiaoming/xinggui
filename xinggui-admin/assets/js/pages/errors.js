/* pages/errors.js — 错误日志 + 热门搜索全量 */
(function (global) {
  "use strict";
  const { h, icon, fmtNum, badge, table, toast } = UI;

  const st = { level: "", platform: "", q: "", items: [], view: "errors" };

  global.Pages = global.Pages || {};
  global.Pages.errors = function () {
    const root = h("div");
    const host = h("div");
    root.appendChild(App.pageHead("错误日志", "解析失败、签名异常与限流记录", [
      h("button", { class: "btn btn-sm", html: icon("refresh", 14) + " <span>刷新</span>", onclick: () => load() })
    ]));
    root.appendChild(host);
    App.mount(root);

    function load() {
      host.innerHTML = "";
      const tabBar = h("div", { class: "tabs", style: { background: "var(--panel)", borderRadius: "12px 12px 0 0", border: "1px solid var(--line)", borderBottom: "none" } }, [
        tabBtn("errors", "错误日志", "alert"),
        tabBtn("searches", "热门搜索 Top 50", "search")
      ]);
      const card = h("div", { class: "card", style: { borderRadius: "0 0 12px 12px" } });
      const toolbar = h("div", { class: "toolbar" });
      const body = h("div", { class: "card-body tight" });
      card.appendChild(toolbar);
      card.appendChild(body);
      host.appendChild(tabBar);
      host.appendChild(card);

      function tabBtn(id, label, ic) {
        const b = h("button", { class: st.view === id ? "active" : "" }, [
          h("span", { html: icon(ic, 14), style: { display: "inline-flex" } }),
          h("span", { text: label })
        ]);
        b.addEventListener("click", () => { st.view = id; load(); });
        return b;
      }

      if (st.view === "errors") {
        toolbar.appendChild(selectEl([
          { v: "", t: "全部级别" }, { v: "error", t: "错误" }, { v: "warn", t: "警告" }
        ], st.level, v => { st.level = v; paintErrors(); }));
        toolbar.appendChild(selectEl([
          { v: "", t: "全部平台" }, { v: "wy", t: "网易云" }, { v: "tx", t: "QQ 音乐" }, { v: "kg", t: "酷狗" }, { v: "kw", t: "酷我" }
        ], st.platform, v => { st.platform = v; paintErrors(); }));
        const search = h("input", { class: "input", placeholder: "搜索错误码 / 信息 / 用户", style: { maxWidth: "280px" } });
        search.addEventListener("input", UI.debounce(() => { st.q = search.value.trim().toLowerCase(); paintErrors(); }, 240));
        toolbar.appendChild(search);
        toolbar.appendChild(h("div", { class: "spacer" }));
        toolbar.appendChild(h("span", { class: "tag", text: "最多 500 条" }));
      }

      paint();

      function paint() {
        body.innerHTML = "";
        body.appendChild(h("div", { style: { padding: "14px 16px" } }, h("div", { class: "skeleton", style: { height: "16px", width: "60%" } })));
        if (st.view === "errors") {
          API.statsErrors({ limit: 500 }).then(d => { st.items = (d && (d.items || d)) || []; paintErrors(); })
            .catch(e => { body.innerHTML = ""; body.appendChild(errNotice(e)); });
        } else {
          API.statsSearches().then(d => { st.items = (d && (d.items || d)) || []; paintSearches(); })
            .catch(e => { body.innerHTML = ""; body.appendChild(errNotice(e)); });
        }
      }

      function paintErrors() {
        let rows = st.items;
        if (st.level) rows = rows.filter(r => r.level === st.level);
        if (st.platform) rows = rows.filter(r => r.platform === st.platform);
        if (st.q) rows = rows.filter(r => ((r.code || "") + " " + (r.message || "") + " " + (r.user || "")).toLowerCase().includes(st.q));
        body.innerHTML = "";
        if (!rows.length) { body.appendChild(UI.emptyState("没有匹配的日志", "调整筛选条件试试")); return; }
        body.appendChild(table([
          { label: "时间", width: "132px", render: r => '<span class="muted mono">' + UI.esc(String(r.at || "").slice(5, 16)) + "</span>" },
          { label: "级别", width: "72px", render: r => badge(r.level, r.level === "warn" ? "警告" : "错误") },
          { label: "平台", width: "80px", render: r => '<span class="tag">' + UI.esc(API.PLATFORM_LABEL[r.platform] || r.platform || "-") + "</span>" },
          { label: "错误码", width: "158px", render: r => '<span class="mono">' + UI.esc(r.code || "-") + "</span>" },
          { label: "信息", render: r => '<span class="muted">' + UI.esc(r.message || "") + "</span>" },
          { label: "用户", width: "110px", render: r => r.user ? '<a href="#/users/' + encodeURIComponent(r.user) + '">' + UI.esc(r.user) + "</a>" : "-" }
        ], rows.slice(0, 500)));
      }

      function paintSearches() {
        const items = st.items.slice(0, 50);
        body.innerHTML = "";
        if (!items.length) { body.appendChild(UI.emptyState("暂无搜索数据")); return; }
        const max = Math.max.apply(null, items.map(x => x.count || 0)) || 1;
        body.appendChild(h("div", { style: { padding: "6px 16px 14px" } }, items.map((s, i) => h("div", { class: "list-item" }, [
          h("span", { class: "tag", text: String(i + 1), style: { width: "26px", textAlign: "center" } }),
          h("span", { class: "grow", style: { minWidth: "90px", fontWeight: "600" }, text: s.keyword }),
          h("div", { class: "bar", style: { width: "38%", maxWidth: "300px" } }, h("i", { style: { width: Math.max(3, ((s.count || 0) / max) * 100) + "%" } })),
          h("span", { class: "small muted nowrap", style: { width: "78px", textAlign: "right" }, text: fmtNum(s.count || 0) + " 次" })
        ]))));
      }
    }

    function selectEl(options, value, onChange) {
      const sel = h("select", { class: "select", style: { width: "132px" } },
        options.map(o => h("option", { value: o.v, text: o.t, selected: o.v === value })));
      sel.addEventListener("change", e => onChange(e.target.value));
      return sel;
    }
    function errNotice(e) {
      return h("div", { style: { padding: "16px" } }, h("div", { class: "notice warn", text: "加载失败：" + e.message }));
    }

    load();
  };
})(window);
