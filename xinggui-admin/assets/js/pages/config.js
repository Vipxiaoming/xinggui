/* pages/config.js — 系统配置：全局开关、设备上限、解析顺序拖拽、平台禁用 */
(function (global) {
  "use strict";
  const { h, icon, toast } = UI;

  /* 后端平台编码固定为 wy/tx/kg/kw；短码直接用于 platformDisabled 与 resolveOrder */
  const PLATFORM_NAMES = API.PLATFORM_LABEL;

  /* 后端历史数据里可能残留 netease/kugou 这类长名，读入时统一归一化，
   * 避免「界面上网易云是开的、实际禁的是 wy」这种静默错配。 */
  function normalizeConfig(raw) {
    const c = Object.assign({}, raw || {});
    c.resolveOrder = (c.resolveOrder || API.PLATFORMS.slice()).map(API.normPlatform);
    const pd = {};
    API.PLATFORMS.forEach(function (p) { pd[p] = false; });
    const src = c.platformDisabled || {};
    Object.keys(src).forEach(function (k) {
      const nk = API.normPlatform(k);
      if (API.PLATFORMS.indexOf(nk) >= 0) pd[nk] = !!src[k];
    });
    c.platformDisabled = pd;
    return c;
  }

  global.Pages = global.Pages || {};
  global.Pages.config = function () {
    const root = h("div");
    const host = h("div");
    root.appendChild(App.pageHead("系统配置", "客户端行为、解析策略与平台开关", [
      h("button", { class: "btn btn-sm", html: icon("refresh", 14) + " <span>重新读取</span>", onclick: () => load() })
    ]));
    root.appendChild(host);
    App.mount(root);

    let cfg = null;

    function load() {
      host.innerHTML = "";
      host.appendChild(h("div", { class: "card" }, h("div", { class: "card-body" }, [
        h("div", { class: "skeleton", style: { width: "60%", height: "16px" } }),
        h("div", { class: "skeleton mt-12", style: { width: "85%", height: "16px" } }),
        h("div", { class: "skeleton mt-12", style: { width: "45%", height: "16px" } })
      ])));
      API.config().then(c => { cfg = normalizeConfig(c); render(); }).catch(e => {
        host.innerHTML = "";
        host.appendChild(h("div", { class: "notice warn", text: "配置读取失败：" + e.message }));
      });
    }

    function switchRow(title, desc, key, onChange) {
      const inp = h("input", { type: "checkbox" });
      inp.checked = !!cfg[key];
      inp.addEventListener("change", () => { cfg[key] = inp.checked; if (onChange) onChange(inp.checked); });
      return h("div", { class: "list-item", style: { padding: "13px 0" } }, [
        h("div", { class: "grow" }, [
          h("div", { style: { fontWeight: "600" }, text: title }),
          h("div", { class: "small muted", text: desc })
        ]),
        h("label", { class: "switch" }, [inp, h("i")])
      ]);
    }

    function numberField(label, key, hint, min, max) {
      const inp = h("input", { class: "input", type: "number", value: cfg[key] != null ? cfg[key] : "", min: min, max: max });
      inp.addEventListener("change", () => { cfg[key] = Number(inp.value); });
      return h("div", { class: "field" }, [
        h("label", { text: label }),
        inp,
        hint ? h("div", { class: "hint", text: hint }) : null
      ]);
    }

    function render() {
      host.innerHTML = "";

      /* 运行状态 */
      const runCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "运行开关" }), h("span", { class: "sub", text: "变更后立即对客户端生效" })]),
        h("div", { class: "card-body" }, [
          switchRow("客户端总开关", "关闭后所有 API 返回 503，客户端提示「服务维护中」", "appEnabled"),
          switchRow("开放注册", "关闭后注册接口返回 403", "registrationOpen"),
          switchRow("注册需审核", "新用户注册后进入待审核，管理员放行后才能登录", "requireApproval")
        ])
      ]);

      /* 设备与限制 */
      const limitCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "设备与限额" })]),
        h("div", { class: "card-body" }, h("div", { class: "grid cols-2", style: { gap: "0 18px" } }, [
          numberField("每账号设备上限", "maxDevices", "推荐 2 台；超出时新设备登录会被拒绝，需管理员解绑", 1, 10),
          numberField("解析结果缓存（秒）", "cacheTtlSeconds", "同一歌曲 URL 的复用时长", 0, 86400),
          numberField("每设备每分钟请求上限", "rateLimitPerMinute", "超出返回 429", 10, 600),
          h("div", { class: "field" }, [
            h("label", { text: "客户端下载目录" }),
            (function () {
              const inp = h("input", { class: "input", value: cfg.downloadDir || "Download/星轨" });
              inp.addEventListener("change", () => { cfg.downloadDir = inp.value; });
              return inp;
            })(),
            h("div", { class: "hint", text: "相对外部存储根目录；客户端使用 MediaStore 写入" })
          ])
        ]))
      ]);

      /* 解析顺序 */
      const orderList = h("ul", { class: "sortable", id: "orderList" });
      const order = (cfg.resolveOrder || API.PLATFORMS.slice()).slice();
      function paintOrder() {
        orderList.innerHTML = "";
        order.forEach((p, i) => {
          const li = h("li", { draggable: "true", dataset: { platform: p } }, [
            h("span", { class: "grip", html: icon("drag", 15) }),
            h("span", { class: "idx", text: String(i + 1) }),
            h("span", { class: "grow", text: PLATFORM_NAMES[p] || p }),
            h("span", { class: "tag mono", text: p }),
            h("button", { class: "btn btn-sm btn-ghost icon-btn", title: "上移", html: icon("up", 14), onclick: () => move(i, -1), disabled: i === 0 }),
            h("button", { class: "btn btn-sm btn-ghost icon-btn", title: "下移", html: icon("down", 14), onclick: () => move(i, 1), disabled: i === order.length - 1 })
          ]);
          orderList.appendChild(li);
        });
      }
      function move(i, delta) {
        const j = i + delta;
        if (j < 0 || j >= order.length) return;
        const tmp = order[i]; order[i] = order[j]; order[j] = tmp;
        cfg.resolveOrder = order.slice();
        paintOrder();
      }
      paintOrder();

      let dragIdx = null;
      orderList.addEventListener("dragstart", e => {
        const li = e.target.closest("li"); if (!li) return;
        dragIdx = Array.prototype.indexOf.call(orderList.children, li);
        li.classList.add("dragging");
        e.dataTransfer.effectAllowed = "move";
        try { e.dataTransfer.setData("text/plain", String(dragIdx)); } catch (err) { }
      });
      orderList.addEventListener("dragover", e => {
        e.preventDefault();
        const li = e.target.closest("li"); if (!li || dragIdx == null) return;
        const overIdx = Array.prototype.indexOf.call(orderList.children, li);
        if (overIdx === dragIdx) return;
        const moved = order.splice(dragIdx, 1)[0];
        order.splice(overIdx, 0, moved);
        dragIdx = overIdx;
        cfg.resolveOrder = order.slice();
        paintOrder();
        const newLi = orderList.children[overIdx];
        if (newLi) newLi.classList.add("dragging");
      });
      orderList.addEventListener("dragend", () => {
        dragIdx = null;
        UI.$$("#orderList li").forEach(x => x.classList.remove("dragging"));
      });

      const orderCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [
          h("h3", { text: "解析顺序" }),
          h("span", { class: "sub", text: "拖拽排序，靠前优先；失败自动降级到下一个" })
        ]),
        h("div", { class: "card-body" }, [
          orderList,
          h("div", { class: "notice mt-12" }, [
            h("span", { class: "ico", html: icon("alert", 15) }),
            h("div", { text: "顺序只影响「先试哪家」；四家都保留时任一可用即成功。" })
          ])
        ])
      ]);

      /* 平台禁用 */
      const platCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "平台可用性" }), h("span", { class: "sub", text: "临时禁用某个源进行灰度或止损" })]),
        h("div", { class: "card-body" }, (cfg.resolveOrder || Object.keys(PLATFORM_NAMES)).map(p => {
          const disabled = !!(cfg.platformDisabled || {})[p];
          const inp = h("input", { type: "checkbox" });
          inp.checked = !disabled;
          inp.addEventListener("change", async () => {
            const enable = inp.checked;
            inp.disabled = true;
            try {
              await API.disablePlatform(p, !enable);
              cfg.platformDisabled = cfg.platformDisabled || {};
              cfg.platformDisabled[p] = !enable;
              toast((enable ? "已启用 " : "已禁用 ") + (PLATFORM_NAMES[p] || p), "ok");
            } catch (e) { inp.checked = !inp.checked; toast(e.message, "err"); }
            finally { inp.disabled = false; }
          });
          return h("div", { class: "list-item", style: { padding: "11px 0" } }, [
            h("span", { class: "grow", text: PLATFORM_NAMES[p] || p }),
            h("span", { class: "tag mono", text: p }),
            h("label", { class: "switch", style: { marginLeft: "10px" } }, [inp, h("i")])
          ]);
        }))
      ]);

      /* 公告 */
      const annInp = h("textarea", { class: "textarea", placeholder: "显示在客户端首页顶部的公告，可留空", rows: "3" });
      annInp.value = cfg.announcement || "";
      annInp.addEventListener("input", () => { cfg.announcement = annInp.value; });
      const annCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "客户端公告" })]),
        h("div", { class: "card-body" }, annInp)
      ]);

      const saveBar = h("div", { class: "card mt-16" }, h("div", { class: "card-body flex flex-wrap" }, [
        h("div", { class: "grow small muted", text: "保存后立即推送给在线客户端；离线设备下次启动时拉取。" }),
        h("button", { class: "btn", text: "放弃修改", onclick: () => load() }),
        h("button", {
          class: "btn btn-primary", html: icon("check", 15) + " <span>保存配置</span>", onclick: async (e) => {
            const btn = e.currentTarget;
            btn.disabled = true;
            try { await API.saveConfig(cfg); toast("配置已保存", "ok"); }
            catch (err) { toast(err.message, "err"); }
            finally { btn.disabled = false; }
          }
        })
      ]));

      host.appendChild(runCard);
      host.appendChild(h("div", { class: "grid split mt-16" }, [limitCard, orderCard]));
      host.appendChild(h("div", { class: "grid split mt-16" }, [platCard, annCard]));
      host.appendChild(saveBar);
    }

    load();
  };
})(window);

