/* pages/version.js — 版本管理与强制更新 */
(function (global) {
  "use strict";
  const { h, icon, toast, fmtBytes } = UI;

  global.Pages = global.Pages || {};
  global.Pages.version = function () {
    const root = h("div");
    const host = h("div");
    root.appendChild(App.pageHead("版本管理", "客户端检查更新、强制升级与安装包校验", [
      h("button", { class: "btn btn-sm", html: icon("refresh", 14) + " <span>重新读取</span>", onclick: () => load() })
    ]));
    root.appendChild(host);
    App.mount(root);

    let v = null;

    function load() {
      host.innerHTML = "";
      host.appendChild(h("div", { class: "card" }, h("div", { class: "card-body" },
        h("div", { class: "skeleton", style: { width: "55%", height: "16px" } }))));
      API.version().then(d => { v = d || {}; render(); }).catch(e => {
        host.innerHTML = "";
        host.appendChild(h("div", { class: "notice warn", text: "版本信息读取失败：" + e.message }));
      });
    }

    function field(label, key, opts) {
      opts = opts || {};
      const inp = h("input", { class: "input", value: v[key] != null ? v[key] : "", placeholder: opts.placeholder || "", type: opts.type || "text" });
      inp.addEventListener("input", () => { v[key] = inp.value; });
      if (opts.mono) inp.classList.add("mono");
      let hintNode = null;
      if (opts.hint) {
        hintNode = h("div", { class: "hint", text: opts.hint });
      }
      return h("div", { class: "field" }, [
        h("label", { text: label }),
        inp,
        hintNode,
        opts.after || null
      ]);
    }

    function render() {
      host.innerHTML = "";

      const forceInp = h("input", { type: "checkbox" });
      forceInp.checked = !!v.forceUpdate;
      forceInp.addEventListener("change", () => { v.forceUpdate = forceInp.checked; });

      const mainCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [
          h("h3", { text: "客户端版本" }),
          h("span", { class: "sub", text: "客户端启动与每 6 小时检查一次" }),
          h("div", { class: "actions" }, [
            h("span", { class: "badge info", text: "latest " + (v.latestVersion || "-") }),
            h("span", { class: "badge muted", text: "min " + (v.minVersion || "-") })
          ])
        ]),
        h("div", { class: "card-body" }, [
          h("div", { class: "grid cols-2", style: { gap: "0 18px" } }, [
            field("最新版本号", "latestVersion", { placeholder: "1.2.3", hint: "语义化版本，须大于客户端当前版本才会提示更新" }),
            field("最低支持版本", "minVersion", { placeholder: "1.0.0", hint: "低于此版本的客户端强制更新且不可跳过" }),
            field("安装包下载地址", "updateUrl", { placeholder: "https://music.wangxiaoming.vip/dl/app-1.2.3.apk", hint: "必须是 HTTPS；客户端会先下载到缓存再校验" }),
            field("SHA-256 校验值", "updateSha256", { placeholder: "64 位十六进制", mono: true, hint: "客户端下载后校验，不匹配则拒绝安装" })
          ]),
          h("div", { class: "field" }, [
            h("label", { text: "更新说明" }),
            (function () {
              const ta = h("textarea", { class: "textarea", rows: "4", placeholder: "每行一条，客户端按行显示" });
              ta.value = v.updateNotes || "";
              ta.addEventListener("input", () => { v.updateNotes = ta.value; });
              return ta;
            })()
          ]),
          h("div", { class: "list-item", style: { padding: "13px 0" } }, [
            h("div", { class: "grow" }, [
              h("div", { style: { fontWeight: "600" }, text: "强制更新" }),
              h("div", { class: "small muted", text: "开启后低于 latestVersion 的客户端无法跳过更新，必须安装后才能使用" })
            ]),
            h("label", { class: "switch" }, [forceInp, h("i")])
          ]),
          v.publishedAt ? h("div", { class: "small muted", text: "上次发布：" + v.publishedAt }) : null
        ])
      ]);

      const previewCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "更新弹窗预览" })]),
        h("div", { class: "card-body" }, h("div", { style: { border: "1px solid var(--line)", borderRadius: "12px", overflow: "hidden", background: "var(--panel-2)" } }, [
          h("div", { style: { padding: "14px 16px", borderBottom: "1px solid var(--line)", background: "var(--panel)" } }, [
            h("div", { style: { fontWeight: "650" }, text: "发现新版本 " + (v.latestVersion || "1.2.3") }),
            h("div", { class: "small muted", text: v.forceUpdate ? "此版本为强制更新" : "可稍后更新" })
          ]),
          h("div", { style: { padding: "13px 16px", fontSize: "13px", whiteSpace: "pre-wrap", color: "var(--muted)", maxHeight: "140px", overflow: "auto" }, text: v.updateNotes || "（无更新说明）" }),
          h("div", { style: { padding: "12px 16px", borderTop: "1px solid var(--line)", background: "var(--panel)", display: "flex", gap: "8px", justifyContent: "flex-end" } }, [
            !v.forceUpdate ? h("button", { class: "btn btn-sm", text: "以后再说", disabled: true }) : null,
            h("button", { class: "btn btn-sm btn-primary", text: "立即更新", disabled: true })
          ])
        ]))
      ]);

      const tipCard = h("div", { class: "card" }, [
        h("div", { class: "card-head" }, [h("h3", { text: "发布检查" })]),
        h("div", { class: "card-body" }, [
          check("版本号递增", !!(v.latestVersion && v.minVersion)),
          check("下载地址为 HTTPS", /^https:\/\//.test(v.updateUrl || "")),
          check("SHA-256 长度为 64", /^[a-fA-F0-9]{64}$/.test(v.updateSha256 || "")),
          check("更新说明非空", !!(v.updateNotes || "").trim())
        ])
      ]);

      const saveBar = h("div", { class: "card mt-16" }, h("div", { class: "card-body flex flex-wrap" }, [
        h("div", { class: "grow small muted", text: "保存后客户端下次检查更新即可拿到新版本信息。" }),
        h("button", { class: "btn", text: "放弃修改", onclick: () => load() }),
        h("button", {
          class: "btn btn-primary", html: icon("check", 15) + " <span>保存并发布</span>", onclick: async (e) => {
            const btn = e.currentTarget; btn.disabled = true;
            try { await API.saveVersion(v); toast("版本信息已发布", "ok"); }
            catch (err) { toast(err.message, "err"); }
            finally { btn.disabled = false; }
          }
        })
      ]));

      host.appendChild(h("div", { class: "grid split" }, [mainCard, h("div", { class: "col" }, [previewCard, tipCard])]));
      host.appendChild(saveBar);
    }

    function check(label, ok) {
      return h("div", { class: "list-item", style: { padding: "8px 0" } }, [
        h("span", { html: icon(ok ? "check" : "alert", 15), style: { display: "inline-flex", color: ok ? "var(--ok)" : "var(--warn)" } }),
        h("span", { class: "grow", text: label }),
        h("span", { class: "badge " + (ok ? "ok" : "warn"), text: ok ? "通过" : "待完善" })
      ]);
    }

    load();
  };
})(window);
