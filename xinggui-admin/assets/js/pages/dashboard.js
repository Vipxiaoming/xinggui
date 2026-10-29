/* pages/dashboard.js — 监控面板 */
(function (global) {
  "use strict";
  const { h, icon, fmtNum, fmtPct, badge, lineChart, table, toast } = UI;

  function metric(title, value, meta, spark, delta, iconName) {
    return h("div", { class: "card metric" }, [
      h("div", { class: "k" }, [h("span", { html: icon(iconName || "chart", 14), style: { display: "inline-flex" } }), h("span", { text: title })]),
      h("div", { class: "v", text: value }),
      h("div", { class: "d" }, [
        delta == null ? null : h("span", { class: "delta " + (delta >= 0 ? "up" : "down"), text: (delta >= 0 ? "▲ " : "▼ ") + Math.abs(delta).toFixed(1) + "%" }),
        h("span", { text: " " + (meta || ""), style: { marginLeft: "6px" } })
      ]),
      spark ? h("div", { class: "spark", html: spark }) : null
    ]);
  }

  function skeletonCards(n) {
    const g = h("div", { class: "grid cols-4" });
    for (let i = 0; i < n; i++) g.appendChild(h("div", { class: "card metric" }, [
      h("div", { class: "skeleton", style: { width: "60%", height: "12px" } }),
      h("div", { class: "skeleton", style: { width: "42%", height: "26px", marginTop: "8px" } }),
      h("div", { class: "skeleton", style: { width: "72%", height: "10px", marginTop: "8px" } })
    ]));
    return g;
  }

  global.Pages = global.Pages || {};
  global.Pages.dashboard = function () {
    const root = h("div");
    root.appendChild(App.pageHead("监控面板", "用户、解析质量与系统健康的实时概览",
      [h("button", { class: "btn btn-sm", html: icon("refresh", 14) + " <span>重新加载</span>", onclick: () => global.Pages.dashboard() })]));
    const skel = skeletonCards(4);
    root.appendChild(skel);
    const body = h("div", { class: "mt-16" });
    root.appendChild(body);
    App.mount(root);

    Promise.all([
      API.overview().catch(() => null),
      API.statsResolve().catch(() => null),
      API.health().catch(() => null),
      API.statsErrors({ limit: 8 }).catch(() => null),
      API.statsSearches().catch(() => null)
    ]).then(([ov, rs, hl, er, sc]) => {
      if (skel && skel.parentNode) skel.remove();
      body.innerHTML = "";
      if (!ov) {
        body.appendChild(h("div", { class: "notice warn" }, [h("span", { class: "ico", html: icon("alert", 15) }), h("div", { text: "监控数据加载失败，请检查服务端 /admin/api/stats/overview" })]));
        return;
      }
      const trend = ov.trend || [];
      const d = ov.deltas || {};
      body.appendChild(h("div", { class: "grid cols-4" }, [
        metric("总用户", fmtNum(ov.totalUsers), "本周", UI.sparkline(trend.slice(-14), 88, 30, "#2b6cf6"), d.users, "users"),
        metric("在线用户", fmtNum(ov.onlineUsers), "近 5 分钟", UI.sparkline(trend.slice(-14).map(v => v * 0.01), 88, 30, "#22b3c9"), d.online, "pulse"),
        metric("今日解析", fmtNum(ov.todayResolves), "较昨日", UI.sparkline(trend.slice(-14).map(v => v * 1.9), 88, 30, "#5b5bd6"), d.resolves, "play"),
        metric("待审核", fmtNum(ov.pendingApprovals), "需处理", "", null, "shield")
      ]));

      const chartCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [
          h("h3", { text: "解析量趋势（近 30 天）" }),
          h("span", { class: "sub", text: "单位：次" }),
          h("div", { class: "actions" }, [
            h("span", { class: "badge ok", text: "运行中" })
          ])
        ]),
        h("div", { class: "card-body", html: lineChart(trend.length ? trend : [0, 0], { height: 200 }) })
      ]);

      const healthCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "系统健康" })]),
        h("div", { class: "card-body" }, hl ? [
          mini("运行时长", hl.uptime),
          barStat("CPU", hl.cpu, "%", 70),
          barStat("内存", hl.mem, "%", 80),
          barStat("磁盘", hl.disk, "%", 85),
          mini("DB 延迟", (hl.dbLatencyMs != null ? hl.dbLatencyMs : "-") + " ms"),
          mini("Goroutines", fmtNum(hl.goroutines))
        ] : [h("div", { class: "small muted", text: "健康数据不可用" })])
      ]);

      body.appendChild(h("div", { class: "grid split mt-16" }, [chartCard, healthCard]));

      const rsRows = (rs && (rs.items || rs)) || [];
      const platCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "各平台解析质量" }), h("div", { class: "actions" }, [
          h("span", { class: "tag", text: "P50 / P95 单位 ms" })
        ])]),
        h("div", { class: "card-body" }, rsRows.length ? rsRows.map(r => platRow(r)) :
          [h("div", { class: "small muted", text: "暂无解析统计" })])
      ]);

      const topSearches = (sc && (sc.items || sc)) || [];
      const searchCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "热门搜索 Top 10" })]),
        h("div", { class: "card-body tight" }, topSearches.length ? h("div", { style: { padding: "4px 16px 12px" } },
          topSearches.slice(0, 10).map((s, i) => h("div", { class: "list-item" }, [
            h("span", { class: "idx tag", text: String(i + 1), style: { width: "22px", textAlign: "center" } }),
            h("span", { class: "grow t", text: s.keyword }),
            h("span", { class: "m", text: fmtNum(s.count) + " 次" })
          ]))) : [h("div", { class: "small muted", style: { padding: "12px 16px" }, text: "暂无搜索数据" })])
      ]);

      body.appendChild(h("div", { class: "grid split mt-16" }, [platCard, searchCard]));

      const errRows = (er && (er.items || er)) || [];
      const errCard = h("div", { class: "card mt-16" }, [
        h("div", { class: "card-head" }, [
          h("h3", { text: "最近错误" }),
          h("div", { class: "actions" }, [h("a", { class: "btn btn-sm", href: "#/errors", text: "查看全部" })])
        ]),
        h("div", { class: "card-body tight" }, errRows.length ? table([
          { label: "时间", key: "at", width: "130px", render: r => '<span class="muted">' + UI.esc(String(r.at).slice(5, 16)) + "</span>" },
          { label: "级别", width: "76px", render: r => badge(r.level, r.level === "warn" ? "警告" : "错误") },
          { label: "平台", width: "86px", render: r => '<span class="tag">' + UI.esc(r.platform || "-") + "</span>" },
          { label: "代码", width: "150px", render: r => '<span class="mono">' + UI.esc(r.code || "-") + "</span>" },
          { label: "信息", render: r => '<span class="muted">' + UI.esc(r.message || "") + "</span>" }
        ], errRows.slice(0, 8)) : UI.emptyState("没有错误记录", "系统运行良好"))
      ]);
      body.appendChild(errCard);
    });
  };

  function mini(label, value) {
    return h("div", { class: "stat-mini" }, [h("span", { text: label }), h("b", { text: value == null ? "-" : String(value) })]);
  }
  function barStat(label, v, unit, threshold) {
    const pct = Math.max(0, Math.min(100, Number(v) || 0));
    return h("div", { style: { padding: "7px 0" } }, [
      h("div", { class: "flex", style: { justifyContent: "space-between", fontSize: "12.5px", marginBottom: "5px" } }, [
        h("span", { class: "muted", text: label }),
        h("b", { text: pct.toFixed(1) + (unit || "%"), style: { color: pct > threshold ? "var(--danger)" : "inherit" } })
      ]),
      h("div", { class: "bar" }, h("i", { style: { width: pct + "%", background: pct > threshold ? "var(--danger)" : undefined } }))
    ]);
  }
  function platRow(r) {
    const ok = r.success != null ? r.success : 0;
    const pct = ok > 1 ? ok : ok * 100;
    return h("div", { class: "plat-row" }, [
      h("div", { class: "name" }, [h("span", { style: { width: "6px", height: "6px", borderRadius: "50%", background: pct > 90 ? "var(--ok)" : pct > 80 ? "var(--warn)" : "var(--danger)" } }), h("span", { text: (API.PLATFORM_LABEL[r.platform] || r.platform) + " (" + r.platform + ")" })]),
      h("div", { class: "bar" }, h("i", { style: { width: Math.max(4, pct) + "%", background: pct > 90 ? "linear-gradient(90deg,#0d9a63,#3ddc97)" : pct > 80 ? "linear-gradient(90deg,#b8730a,#f0b354)" : "linear-gradient(90deg,#d23b3b,#ff8a8a)" } })),
      h("div", { class: "pct", text: pct.toFixed(1) + "%" }),
      h("div", { class: "lat", text: (r.p50 != null ? r.p50 : "-") + " / " + (r.p95 != null ? r.p95 : "-") }),
      h("div", { class: "lat", text: fmtNum(r.total || 0) + " 次" })
    ]);
  }
})(window);

