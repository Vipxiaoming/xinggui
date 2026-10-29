/* pages/users.js — 用户列表 / 待审核 / 用户详情（含数据浏览） */
(function (global) {
  "use strict";
  const { h, icon, fmtNum, badge, table, pager, toast, confirm, prompt } = UI;

  const state = {
    q: "", status: "", page: 1, pageSize: 10, total: 0, items: [], loading: false
  };
  let searchTimer = null;

  function statusOptions() {
    return [
      { v: "", t: "全部状态" },
      { v: "pending", t: "待审核" },
      { v: "active", t: "正常" },
      { v: "banned", t: "已封禁" },
      { v: "rejected", t: "已拒绝" },
      { v: "expired", t: "已过期" }
    ];
  }

  global.Pages = global.Pages || {};

  /* ==================== 列表 ==================== */
  global.Pages.users = function () {
    state.q = ""; state.status = ""; state.page = 1;
    const root = h("div");
    const tableHost = h("div", { class: "card-body tight" });
    const pagerHost = h("div");
    const tableCard = h("div", { class: "card" }, [
      h("div", { class: "toolbar" }, [
        h("div", { class: "search" }, [
          h("span", { html: icon("search", 15) }),
          h("input", { class: "input", placeholder: "搜索用户名或邮箱", oninput: onSearch })
        ]),
        (function () {
          const sel = h("select", { class: "select", style: { width: "132px" }, onchange: e => { state.status = e.target.value; state.page = 1; load(); } },
            statusOptions().map(o => h("option", { value: o.v, text: o.t })));
          return sel;
        })(),
        h("div", { class: "spacer" }),
        h("a", { class: "btn btn-sm", href: "#/approvals", html: icon("shield", 14) + " <span>待审核</span>" }),
        h("button", { class: "btn btn-sm", html: icon("refresh", 14) + " <span>刷新</span>", onclick: () => load() })
      ]),
      tableHost,
      pagerHost
    ]);
    root.appendChild(App.pageHead("用户管理", "审核、封禁、设备绑定与会员到期管理", null));
    root.appendChild(tableCard);
    App.mount(root);

    function onSearch(e) {
      clearTimeout(searchTimer);
      const v = e.target.value;
      searchTimer = setTimeout(() => { state.q = v.trim(); state.page = 1; load(); }, 280);
    }

    function load() {
      state.loading = true;
      tableHost.innerHTML = "";
      for (let i = 0; i < 5; i++) tableHost.appendChild(h("div", { style: { padding: "12px 16px" } }, h("div", { class: "skeleton", style: { height: "18px", width: (70 - i * 7) + "%" } })));
      API.users({ q: state.q, status: state.status, page: state.page, pageSize: state.pageSize })
        .then(d => {
          const items = (d && (d.items || d.list || d.users)) || (Array.isArray(d) ? d : []);
          state.items = items;
          state.total = d && d.total != null ? d.total : items.length;
          if (d && d.page) state.page = d.page;
          render();
        })
        .catch(err => {
          tableHost.innerHTML = "";
          tableHost.appendChild(h("div", { style: { padding: "20px" } }, h("div", { class: "notice warn" }, [
            h("span", { class: "ico", html: icon("alert", 15) }),
            h("div", null, [h("b", { text: "加载失败：" }), err.message])
          ])));
          pagerHost.innerHTML = "";
        })
        .then(() => { state.loading = false; });
    }

    function render() {
      tableHost.innerHTML = "";
      tableHost.appendChild(table([
        {
          label: "用户", render: u => h("div", { class: "u" }, [
            h("div", { class: "avatar", text: UI.initials(u.username) }),
            h("div", null, [
              h("div", { class: "n", text: u.username }),
              h("div", { class: "e", text: u.email || "" })
            ])
          ])
        },
        { label: "状态", width: "96px", render: u => badge(u.status) },
        {
          label: "设备", width: "92px", num: true,
          render: u => '<span>' + (u.devices || 0) + '</span><span class="muted"> / ' + (u.maxDevices || 2) + "</span>"
        },
        { label: "注册时间", width: "150px", render: u => '<span class="muted">' + UI.esc(u.registeredAt || "-") + "</span>" },
        { label: "最后活跃", width: "140px", render: u => '<span class="muted">' + UI.esc(UI.relTime(u.lastActiveAt)) + "</span>" },
        {
          label: "操作", width: "178px", render: u => h("div", { class: "btn-row" }, [
            h("button", { class: "btn btn-sm", text: "详情", onclick: () => UI.navigate("/users/" + encodeURIComponent(u.username)) }),
            u.status === "pending"
              ? h("button", { class: "btn btn-sm btn-ok", text: "通过", onclick: () => act(u, "approve") })
              : u.status === "banned"
                ? h("button", { class: "btn btn-sm", text: "解封", onclick: () => act(u, "unban") })
                : h("button", { class: "btn btn-sm btn-danger", text: "封禁", onclick: () => act(u, "ban") })
          ])
        }
      ], state.items, { empty: "没有匹配的用户", emptySub: "试试调整搜索词或状态筛选" }));

      pagerHost.innerHTML = "";
      pagerHost.appendChild(pager(state, p => { state.page = p; load(); }));
    }

    function act(u, action) {
      const labels = { approve: "通过审核", reject: "拒绝", ban: "封禁", unban: "解封" };
      confirm({
        title: labels[action] + "：" + u.username,
        message: action === "ban" ? "封禁后该用户所有登录态会被立即吊销，且无法登录。" : "确认执行该操作？",
        danger: action === "ban" || action === "reject",
        confirmLabel: labels[action]
      }).then(yes => {
        if (!yes) return;
        API.userAction(u.username, action)
          .then(() => { toast(labels[action] + "成功", "ok"); load(); })
          .catch(e => toast(e.message, "err"));
      });
    }

    load();
  };

  /* ==================== 待审核 ==================== */
  global.Pages.approvals = function () {
    const root = h("div");
    const host = h("div", { class: "card-body tight" });
    const card = h("div", { class: "card" }, [
      h("div", { class: "card-head" }, [
        h("h3", { text: "等待审核的注册申请" }),
        h("span", { class: "sub", text: "通过后用户才能登录客户端" }),
        h("div", { class: "actions" }, [
          h("button", { class: "btn btn-sm", html: icon("refresh", 14) + " <span>刷新</span>", onclick: () => load() })
        ])
      ]),
      host
    ]);
    root.appendChild(App.pageHead("待审核", "新注册用户需管理员放行", null));
    root.appendChild(card);
    App.mount(root);

    function load() {
      host.innerHTML = "";
      API.users({ status: "pending", page: 1, pageSize: 50 }).then(d => {
        const items = (d && (d.items || d.list)) || [];
        if (!items.length) { host.appendChild(UI.emptyState("没有待审核用户", "所有注册申请都已处理")); return; }
        host.appendChild(table([
          {
            label: "用户", render: u => h("div", { class: "u" }, [
              h("div", { class: "avatar", text: UI.initials(u.username) }),
              h("div", null, [h("div", { class: "n", text: u.username }), h("div", { class: "e", text: u.email || "" })])
            ])
          },
          { label: "注册时间", width: "160px", render: u => '<span class="muted">' + UI.esc(u.registeredAt || "-") + "</span>" },
          { label: "留言 / 备注", render: u => '<span class="muted small">' + UI.esc(u.note || "—") + "</span>" },
          {
            label: "操作", width: "188px", render: u => h("div", { class: "btn-row" }, [
              h("button", {
                class: "btn btn-sm btn-ok", text: "通过", onclick: () => {
                  API.userAction(u.username, "approve").then(() => { toast("已通过 " + u.username, "ok"); load(); App.refreshBadge(); })
                    .catch(e => toast(e.message, "err"));
                }
              }),
              h("button", {
                class: "btn btn-sm btn-danger", text: "拒绝", onclick: () => {
                  confirm({ title: "拒绝注册", message: "拒绝 " + u.username + " 的注册申请？", danger: true, confirmLabel: "拒绝" })
                    .then(y => { if (!y) return; API.userAction(u.username, "reject").then(() => { toast("已拒绝", "ok"); load(); }).catch(e => toast(e.message, "err")); });
                }
              }),
              h("button", { class: "btn btn-sm btn-ghost", text: "详情", onclick: () => UI.navigate("/users/" + encodeURIComponent(u.username)) })
            ])
          }
        ], items));
      }).catch(e => {
        host.innerHTML = "";
        host.appendChild(h("div", { style: { padding: "16px" } }, h("div", { class: "notice warn", text: "加载失败：" + e.message })));
      });
    }
    load();
  };

  /* ==================== 详情 ==================== */
  global.Pages.userDetail = function (params) {
    const username = params.username;
    const root = h("div");
    const top = h("div");
    const tabHost = h("div", { class: "card mt-16" });
    root.appendChild(top);
    root.appendChild(tabHost);
    App.mount(root);

    let user = null;

    function loadUser() {
      top.innerHTML = "";
      top.appendChild(h("div", { class: "card" }, h("div", { class: "card-body" }, h("div", { class: "skeleton", style: { width: "40%", height: "22px" } }))));
      API.user(username).then(u => { user = u; renderTop(); }).catch(e => {
        top.innerHTML = "";
        top.appendChild(h("div", { class: "notice warn", text: "用户加载失败：" + e.message }));
      });
    }

    function renderTop() {
      const u = user;
      top.innerHTML = "";
      top.appendChild(h("div", { class: "btn-row", style: { marginBottom: "12px" } }, [
        h("button", { class: "btn btn-sm", html: icon("back", 14) + " <span>返回列表</span>", onclick: () => UI.navigate("/users") }),
        h("div", { class: "grow" }),
        h("button", { class: "btn btn-sm", html: icon("refresh", 14), onclick: () => { loadUser(); } })
      ]));

      const headCard = h("div", { class: "card" }, [
        h("div", { class: "card-body" }, h("div", { class: "flex flex-wrap", style: { gap: "16px" } }, [
          h("div", { class: "avatar", text: UI.initials(u.username), style: { width: "46px", height: "46px", fontSize: "15px" } }),
          h("div", { style: { flex: "1 1 220px" } }, [
            h("h2", { text: u.username, style: { fontSize: "19px", display: "flex", alignItems: "center", gap: "9px" } }),
            h("div", { class: "small muted", text: u.email || "" })
          ]),
          h("div", { class: "flex", style: { gap: "7px", alignItems: "center" } }, [
              h("span", { html: badge(u.status) }),
            u.role === "admin" ? h("span", { class: "badge info", text: "管理员" }) : null
          ])
        ]))
      ]);

      const infoCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "基本信息" })]),
        h("div", { class: "card-body" }, h("dl", { class: "kv" }, [
          h("dt", { text: "用户 ID" }), h("dd", { class: "mono", text: u.id || u.username }),
          h("dt", { text: "注册时间" }), h("dd", { text: u.registeredAt || "-" }),
          h("dt", { text: "最后活跃" }), h("dd", { text: (u.lastActiveAt || "-") + "（" + UI.relTime(u.lastActiveAt) + "）" }),
          h("dt", { text: "会员到期" }), h("dd", { text: u.expireAt || "永久" }),
          h("dt", { text: "设备数" }), h("dd", { text: (u.devices || 0) + " / " + (u.maxDevices || 2) }),
          h("dt", { text: "累计解析" }), h("dd", { text: fmtNum(u.resolveCount || 0) + " 次" }),
          u.note ? h("dt", { text: "备注" }) : null,
          u.note ? h("dd", { text: u.note }) : null
        ]))
      ]);

      const actCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "账号操作" })]),
        h("div", { class: "card-body" }, h("div", { class: "col", style: { gap: "8px" } }, [
          u.status === "pending" ? actionBtn("通过审核", "btn-ok", "check", () => doAct("approve", "通过审核")) : null,
          u.status === "pending" ? actionBtn("拒绝注册", "btn-danger", "x", () => doAct("reject", "拒绝注册")) : null,
          u.status !== "banned" ? actionBtn("封禁账号", "btn-danger", "lock", () => doAct("ban", "封禁账号")) : actionBtn("解除封禁", "btn-ok", "unlock", () => doAct("unban", "解除封禁")),
          actionBtn("重置密码", "", "key", resetPwd),
          actionBtn("设置到期日", "", "calendar", setExpire),
          actionBtn("解绑设备", "", "device", unbind),
          actionBtn("软删除账号", "btn-danger", "trash", softDelete)
        ]))
      ]);

      const devCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "已绑定设备" }), h("span", { class: "sub", text: (u.deviceList || []).length + " 台" })]),
        h("div", { class: "card-body" }, (u.deviceList || []).length ? u.deviceList.map(d => h("div", { class: "list-item" }, [
          h("span", { class: "thumb", html: icon("device", 13) }),
          h("div", { class: "grow" }, [
            h("div", { class: "t", text: d.model || d.name || "未知设备" }),
            h("div", { class: "m mono", text: (d.id || "").slice(0, 16) + "  ·  " + (d.os || "") + "  ·  " + UI.relTime(d.lastSeen) })
          ]),
          d.current ? h("span", { class: "badge ok", text: "当前" }) : null,
          h("button", { class: "btn btn-sm btn-danger", text: "解绑", onclick: () => unbindOne(d) })
        ])) : [h("div", { class: "small muted", text: "暂无绑定设备" })])
      ]);

      top.appendChild(headCard);
      top.appendChild(h("div", { class: "grid split mt-16" }, [infoCard, actCard]));
      top.appendChild(devCard);
      renderTabs();
    }

    function actionBtn(label, cls, ic, fn) {
      return h("button", { class: "btn btn-block " + (cls || ""), style: { justifyContent: "flex-start" }, html: icon(ic, 15) + " <span>" + label + "</span>", onclick: fn });
    }

    function doAct(action, label) {
      confirm({ title: label, message: "确认对 " + username + " 执行「" + label + "」？", danger: action === "ban" || action === "reject" || action === "reject", confirmLabel: label })
        .then(yes => {
          if (!yes) return;
          API.userAction(username, action).then(() => { toast(label + "成功", "ok"); loadUser(); }).catch(e => toast(e.message, "err"));
        });
    }

    function resetPwd() {
      const pwdInput = h("input", { class: "input", type: "text", placeholder: "留空则由服务端生成", autocomplete: "new-password" });
      UI.modal({
        title: "重置密码：" + username,
        content: h("div", null, [
          h("p", { class: "small muted", text: "重置后该用户所有 refresh token 会被立即吊销，需要重新登录。", style: { margin: "0 0 12px" } }),
          h("div", { class: "field" }, [h("label", { text: "新密码（可选）" }), pwdInput]),
          h("div", { class: "notice" }, [h("span", { class: "ico", html: icon("alert", 15) }), h("div", { text: "建议使用一次性口令并通过安全渠道转达。" })])
        ]),
        actions: [
          { label: "取消", onClick: c => c() },
          {
            label: "确认重置", cls: "btn-danger", onClick: async (c) => {
              c();
              try {
                const r = await API.resetPassword(username, pwdInput.value ? { password: pwdInput.value } : {});
                const tmp = (r && (r.tempPassword || r.password)) || pwdInput.value || "";
                UI.modal({
                  title: "密码已重置",
                  content: h("div", null, [
                    h("p", { class: "small muted", text: "请立即复制并通过安全渠道发送给用户，此口令不会再次显示。", style: { margin: "0 0 10px" } }),
                    h("div", { class: "code", style: { fontSize: "15px", padding: "12px" }, text: tmp || "（服务端未返回明文）" })
                  ]),
                  actions: [{ label: "我已保存", cls: "btn-primary", onClick: x => x() }]
                });
                toast("密码已重置", "ok");
              } catch (e) { toast(e.message, "err"); }
            }
          }
        ]
      });
    }

    function setExpire() {
      const val = (user.expireAt || "").slice(0, 10);
      const inp = h("input", { class: "input", type: "date", value: /^\d{4}-\d{2}-\d{2}$/.test(val) ? val : "" });
      UI.modal({
        title: "设置会员到期日：" + username,
        content: h("div", null, [
          h("div", { class: "field" }, [h("label", { text: "到期日" }), inp, h("div", { class: "hint", text: "当天 23:59 后失效；留空表示永久" })]),
          h("div", { class: "btn-row" }, [30, 90, 365].map(d => h("button", {
            class: "btn btn-sm", text: d + " 天", onclick: () => {
              const t = new Date(Date.now() + d * 86400000);
              inp.value = t.toISOString().slice(0, 10);
            }
          })))
        ]),
        actions: [
          { label: "取消", onClick: c => c() },
          { label: "保存", cls: "btn-primary", onClick: async (c) => { c(); try { await API.setExpire(username, inp.value); toast("到期日已更新", "ok"); loadUser(); } catch (e) { toast(e.message, "err"); } } }
        ]
      });
    }

    function unbindOne(d) {
      confirm({ title: "解绑设备", message: "解绑后该设备需要重新登录。", detail: (d.model || "") + " · " + (d.id || ""), danger: true, confirmLabel: "解绑" })
        .then(y => { if (!y) return; API.unbindDevice(username, d.id).then(() => { toast("已解绑", "ok"); loadUser(); }).catch(e => toast(e.message, "err")); });
    }

    function unbind() {
      const list = user.deviceList || [];
      if (!list.length) { toast("该用户没有绑定设备", "warn"); return; }
      const body = h("div", { class: "col" }, list.map(d => h("div", { class: "list-item" }, [
        h("div", { class: "grow" }, [h("div", { class: "t", text: d.model || d.id }), h("div", { class: "m mono", text: d.id })]),
        h("button", {
          class: "btn btn-sm btn-danger", text: "解绑", onclick: async (e) => {
            try { await API.unbindDevice(username, d.id); toast("已解绑 " + (d.model || d.id), "ok"); e.target.closest(".list-item").remove(); loadUser(); }
            catch (err) { toast(err.message, "err"); }
          }
        })
      ])));
      UI.modal({ title: "解绑设备：" + username, content: body, actions: [{ label: "关闭", onClick: c => c() }] });
    }

    function softDelete() {
      confirm({ title: "软删除账号", message: "账号会被标记删除并立即失效，数据保留可恢复。确认继续？", danger: true, confirmLabel: "删除" })
        .then(y => { if (!y) return; API.deleteUser(username).then(() => { toast("已删除", "ok"); UI.navigate("/users"); }).catch(e => toast(e.message, "err")); });
    }

    /* ---------- 数据浏览 tabs ---------- */
    function renderTabs() {
      tabHost.innerHTML = "";
      const tabs = [
        { id: "favorites", label: "收藏", icon: "heart", load: () => API.favorites(username) },
        { id: "playlists", label: "自建歌单", icon: "list", load: () => API.playlists(username) },
        { id: "history", label: "播放历史", icon: "clock", load: () => API.history(username) },
        { id: "downloads", label: "下载任务", icon: "download", load: () => API.downloads(username) },
        { id: "searches", label: "搜索关键词", icon: "search", load: () => API.searches(username) }
      ];
      let active = "favorites";

      const tabBar = h("div", { class: "tabs" });
      const body = h("div", { class: "card-body tight" });
      tabs.forEach(t => {
        const btn = h("button", { class: "tab-btn", dataset: { tab: t.id } }, [
          h("span", { html: icon(t.icon, 14), style: { display: "inline-flex" } }),
          h("span", { text: t.label })
        ]);
        btn.addEventListener("click", () => { active = t.id; paint(); });
        tabBar.appendChild(btn);
      });

      tabHost.appendChild(tabBar);
      tabHost.appendChild(body);

      function paint() {
        UI.$$("button", tabBar).forEach(b => b.classList.toggle("active", b.dataset.tab === active));
        body.innerHTML = "";
        body.appendChild(h("div", { style: { padding: "16px" } }, h("div", { class: "skeleton", style: { height: "16px", width: "50%" } })));
        const t = tabs.find(x => x.id === active);
        t.load().then(d => {
          const items = (d && (d.items || d.list)) || (Array.isArray(d) ? d : []);
          body.innerHTML = "";
          if (!items.length) { body.appendChild(UI.emptyState("该用户暂无" + t.label + "数据")); return; }
          body.appendChild(renderTable(active, items));
          const cnt = h("span", { class: "count", text: String(items.length) });
          const btn = tabBar.querySelector('[data-tab="' + active + '"]');
          if (btn) { const old = btn.querySelector(".count"); if (old) old.remove(); btn.appendChild(cnt); }
        }).catch(e => {
          body.innerHTML = "";
          body.appendChild(h("div", { style: { padding: "16px" } }, h("div", { class: "notice warn", text: "加载失败：" + e.message })));
        });
      }
      paint();
    }

    function songCell(r) {
      return h("div", { class: "flex", style: { gap: "10px" } }, [
        h("span", { class: "thumb", html: icon("play", 11) }),
        h("div", { style: { minWidth: "0" } }, [
          h("div", { style: { fontWeight: "600", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }, text: r.title || r.name || "-" }),
          h("div", { class: "small muted", text: [r.artist, r.album].filter(Boolean).join(" · ") })
        ])
      ]);
    }
    function platTag(r) {
      const m = API.PLATFORM_LABEL;
      return '<span class="tag">' + (m[r.platform] || r.platform || "-") + "</span>";
    }

    function renderTable(kind, items) {
      if (kind === "favorites") {
        return table([
          { label: "歌曲", render: songCell },
          { label: "来源", width: "88px", render: platTag },
          { label: "时长", width: "80px", num: true, render: r => UI.fmtDuration(r.duration) },
          { label: "状态", width: "88px", render: r => r.available === false ? '<span class="badge danger">不可用</span>' : '<span class="badge ok">可播放</span>' }
        ], items);
      }
      if (kind === "playlists") {
        return h("div", { class: "card-body" }, h("div", { class: "grid cols-2" }, items.map(p => h("div", { class: "playlist-card" }, [
          h("h4", null, [h("span", { html: icon("list", 14), style: { display: "inline-flex", color: "var(--brand)" } }), h("span", { text: p.name || "(未命名歌单)" }), p.isPublic ? h("span", { class: "badge ok", text: "公开" }) : h("span", { class: "badge muted", text: "私密" })]),
          h("div", { class: "meta", text: (p.songs || []).length + " 首 · 创建于 " + (p.createdAt || "-") }),
          (p.songs || []).length ? h("ol", null, p.songs.map(s => h("li", null, [h("b", { text: s.title || "-" }), h("span", { text: " — " + (s.artist || "") })]))) : h("div", { class: "small muted", text: "空歌单" })
        ]))));
      }
      if (kind === "history") {
        return table([
          { label: "歌曲", render: songCell },
          { label: "来源", width: "88px", render: platTag },
          { label: "播放时间", width: "150px", render: r => '<span class="muted">' + UI.esc(r.playedAt || "-") + "</span>" },
          {
            label: "进度", width: "130px", render: r => {
              const pct = r.duration ? Math.min(100, (r.playedSeconds / r.duration) * 100) : 0;
              return '<div class="flex" style="gap:8px"><div class="bar grow"><i style="width:' + pct.toFixed(0) + '%"></i></div><span class="small muted">' + pct.toFixed(0) + "%</span></div>";
            }
          },
          { label: "", width: "74px", render: r => r.completed ? '<span class="badge ok">听完</span>' : "" }
        ], items);
      }
      if (kind === "downloads") {
        return table([
          { label: "歌曲", render: songCell },
          { label: "音质", width: "70px", render: r => '<span class="tag">' + UI.esc(r.quality || "-") + "</span>" },
          { label: "大小", width: "82px", num: true, render: r => UI.fmtBytes(r.bytes) },
          { label: "状态", width: "86px", render: r => badge(r.status) },
          { label: "创建时间", width: "140px", render: r => '<span class="muted">' + UI.esc(r.createdAt || "-") + "</span>" }
        ], items);
      }
      return table([
        { label: "关键词", render: r => h("b", { text: r.keyword || "-" }) },
        { label: "结果数", width: "90px", num: true, render: r => fmtNum(r.results || 0) },
        { label: "搜索时间", width: "150px", render: r => '<span class="muted">' + UI.esc(r.at || "-") + "</span>" }
      ], items);
    }

    loadUser();
  };
})(window);