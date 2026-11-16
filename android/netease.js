(() => {
  "use strict";
  // NetEase mode: a second source beside the local library, with its own discover / collection / me pages.
  function initialize() {
    if (!window.M3X_NATIVE || !document.getElementById("ne-discover")) return;
    const ui = window.M3X_UI, $ = id => document.getElementById(id);
    const root = $("m3x-concept"), screen = root.querySelector(".m3x-screen");
    const STORE = "m3x-source-mode";
    const levels = [["standard", "标准"], ["higher", "较高"], ["exhigh", "极高"], ["lossless", "无损"], ["hires", "Hi-Res"]];
    const svg = path => '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + path + "</svg>";
    const icons = {
      calendar: svg('<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 9h16M9 3v4M15 3v4"/>'),
      radio: svg('<rect x="3" y="8" width="18" height="12" rx="2"/><path d="m7 8 10-5"/><circle cx="15.5" cy="14" r="2.5"/><path d="M6.5 12.5h3M6.5 15.5h3"/>'),
      heartbeat: svg('<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10Z"/><path d="M7.5 12h2.5l1.3-2.5 1.9 5 1.3-2.5h2"/>'),
      chart: svg('<path d="M5 20V11M12 20V5M19 20v-6"/>'),
      heart: svg('<path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10Z"/>'),
      clock: svg('<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>'),
      play: '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7Z"/></svg>',
      user: svg('<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>')
    };
    const pending = new Map();
    let sequence = 0, mode = "local", profile = null, likes = new Set(), likedPlaylist = null, myLists = null, home = null;
    let homeRequested = false, searchActive = false, searchState = null, radio = null, extending = false;
    const stack = []; let listOrigin = "netease";

    // ---- plumbing ------------------------------------------------------------------------------------
    function request(operation, args = {}) {
      return new Promise((resolve, reject) => {
        const requestId = "ne-" + Date.now() + "-" + (++sequence);
        pending.set(requestId, { resolve, reject });
        const result = nativeCommand("netease", { operation, requestId, ...args });
        if (result.error) { pending.delete(requestId); reject(new Error(result.error)); }
      });
    }
    window.addEventListener("m3x-netease-result", event => {
      const data = event.detail, entry = pending.get(data.requestId);
      if (!entry) return;
      pending.delete(data.requestId);
      if (data.error) entry.reject(new Error(data.error)); else entry.resolve(data);
    });
    function el(tag, className, text) {
      const node = document.createElement(tag);
      if (className) node.className = className;
      if (text !== undefined && text !== null) node.textContent = text;
      return node;
    }
    function btn(className, content, handler, label) {
      const node = el("button", className); node.type = "button";
      if (typeof content === "string") node.textContent = content; else if (content) node.append(...content);
      if (label) node.setAttribute("aria-label", label);
      // A tap only counts once: ignore taps within 400 ms and while the previous action is still running.
      let busy = false, last = 0;
      node.addEventListener("click", event => {
        const now = Date.now();
        if (busy || now - last < 400) return;
        last = now;
        const result = handler(event);
        if (result && typeof result.then === "function") {
          busy = true; node.setAttribute("aria-busy", "true");
          result.then(() => {}, () => {}).then(() => { busy = false; node.removeAttribute("aria-busy"); });
        }
      });
      return node;
    }
    function iconNode(name, extra) { const b = el("b"); b.innerHTML = icons[name]; if (extra) b.append(extra); return b; }
    function iconFrom(name) { const span = el("span"); span.innerHTML = icons[name]; return span.firstChild; }
    function img(src, alt = "") { const node = el("img"); node.alt = alt; node.loading = "lazy"; node.src = src || "cover-placeholder.svg"; return node; }
    const cover = (url, size = 120) => !url ? "cover-placeholder.svg" : url.startsWith("netease-cover/") || url.endsWith(".svg") ? url
      : "netease-cover/?u=" + encodeURIComponent(url.replace(/^http:/, "https:") + (url.includes("?") ? "" : "?param=" + size + "y" + size));
    const date = ms => { const d = new Date(ms); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
    const count = n => n >= 1e8 ? (n / 1e8).toFixed(1) + "亿" : n >= 1e4 ? Math.round(n / 1e4) + "万" : String(n || 0);
    const loading = text => el("p", "m3x-ne-loading", text || "正在加载…");
    const loggedIn = () => !!profile?.loggedIn;
    const currentNetease = () => ui.currentTrack()?.neteaseId || "";
    function needLogin() { if (loggedIn()) return false; ui.toast("请先登录网易云账号"); ui.go("netease-me"); return true; }
    function sheet(title) {
      const dialog = el("dialog", "m3x-sheet-overlay"); dialog.setAttribute("aria-label", title);
      const scrim = el("div", "m3x-sheet-scrim"), content = el("section", "m3x-sheet"), header = el("header", "m3x-sheet-header");
      header.append(el("h2", "", title), btn("m3x-icon", "×", () => ui.closeDialog(dialog, true), "关闭"));
      content.append(header); dialog.append(scrim, content); screen.append(dialog);
      scrim.addEventListener("click", () => ui.closeDialog(dialog, true));
      dialog.addEventListener("close", () => dialog.remove());
      ui.openDialog(dialog); return content;
    }
    function confirmButton(className, label, prompt, handler) {
      let armed = 0; const text = el("span", "", label);
      const node = btn(className, [text], () => {
        if (!armed) { text.textContent = prompt; armed = setTimeout(() => { armed = 0; text.textContent = label; }, 4000); return; }
        clearTimeout(armed); armed = 0; text.textContent = label; handler();
      });
      return node;
    }

    // ---- source mode ---------------------------------------------------------------------------------
    // Source tabs live in the home-page heading: 曲库 | 网易云, on both the local library and the discover page.
    function renderSwitches() {
      root.querySelectorAll("[data-source-tabs]").forEach(node => {
        node.replaceChildren(...[["local", "曲库"], ["netease", "网易云"]].map(([value, label]) => {
          const b = btn("", label, () => { if (mode !== value) setSource(value); });
          b.setAttribute("aria-pressed", String(mode === value)); b.setAttribute("role", "tab"); return b;
        }));
      });
    }
    function setSource(next, navigate = true) {
      mode = next === "netease" ? "netease" : "local";
      screen.dataset.source = mode;
      try { localStorage.setItem(STORE, mode); } catch (_) { /* Mode simply resets to local. */ }
      renderSwitches();
      const band = root.querySelector(".m3x-endfield-band span");
      if (band) band.textContent = mode === "netease" ? "网易云音乐 · 在线" : "终末地 · 本地音乐";
      if (navigate) {
        const page = ui.page(), map = mode === "netease"
          ? { library: "netease", "create-library": "netease", profile: "netease-me", favorites: "netease-me", playlists: "netease-me", playlist: "netease-me" }
          : { netease: "library", "netease-list": "library", "netease-me": "profile" };
        ui.go(map[page] || page);
      }
      if (mode === "netease") {
        ui.releaseLists();
        ensureHome(); if (home) renderDiscover(); if (profile) renderMe();
      } else {
        // Keep fetched data, drop rendered NetEase pages and their covers until the source is used again.
        closeSearch(); stack.length = 0;
        for (const id of ["ne-discover", "ne-list-view", "ne-me-view"]) $(id)?.replaceChildren();
      }
    }
    // Turn the local library title into the same tab pair; its song count stays on the heading row.
    const libraryTitle = root.querySelector('[data-panel="library"] .m3x-page-heading > .m3x-heading');
    if (libraryTitle) {
      const libraryCount = libraryTitle.querySelector('[data-text="trackCount"]');
      libraryTitle.classList.add("m3x-source-tabs"); libraryTitle.dataset.sourceTabs = "";
      if (libraryCount) { libraryCount.classList.add("m3x-heading-count"); libraryTitle.after(libraryCount); }
    }    // ---- account -------------------------------------------------------------------------------------
    async function loadAccount() {
      try { profile = await request("status"); } catch (error) { profile = profile || { loggedIn: false }; ui.toast(error.message); }
      likes = new Set(); likedPlaylist = null; myLists = null;
      if (loggedIn()) {
        request("likes").then(data => { likes = new Set(data.ids); syncPlayer(); renderMe(); }, () => {});
        loadMyLists();
      }
      renderMe(); renderDiscover(); syncPlayer();
    }
    async function loadMyLists() {
      try {
        myLists = (await request("playlists")).playlists;
        likedPlaylist = myLists.find(item => item.liked) || null;
      } catch (error) { myLists = []; ui.toast(error.message); }
      renderMe();
    }
    async function webLogin() {
      try { await request("web-login"); ui.toast("网易云登录成功"); await loadAccount(); refreshHome(); }
      catch (error) { ui.toast(error.message); }
    }
    async function qrLogin() {
      const content = sheet("扫码登录网易云");
      const hint = el("p", "m3x-caption", "正在生成二维码…"); content.append(hint);
      let data;
      try { data = await request("qr-create"); } catch (error) { hint.textContent = error.message; return; }
      const image = img(data.qr, "网易云音乐登录二维码"); image.className = "m3x-ne-qr"; image.loading = "eager";
      hint.textContent = "打开网易云音乐 App，扫一扫登录";
      content.insertBefore(image, hint);
      const dialog = content.closest("dialog");
      const poll = async () => {
        if (!dialog.isConnected) return;
        try {
          const result = await request("qr-check", { key: data.key });
          hint.textContent = result.message + (result.nickname ? " · " + result.nickname : "");
          if (result.code === 803) { ui.closeDialog(dialog, true); ui.toast("网易云登录成功"); await loadAccount(); refreshHome(); return; }
          if (result.code === 800) return;
        } catch (error) { hint.textContent = error.message + "。可改用网页登录。"; return; }
        setTimeout(poll, 2000);
      };
      setTimeout(poll, 2000);
    }

    // ---- discover ------------------------------------------------------------------------------------
    function ensureHome() { if (!homeRequested) { homeRequested = true; loadAccount(); refreshHome(); } }
    async function refreshHome() {
      try { home = await request("home"); } catch (error) { home = home || { playlists: [], toplists: [], hot: [], error: error.message }; }
      renderDiscover();
      if (searchActive && !searchState) renderSearchIdle();
    }
    function card(item, onClick) {
      const node = btn("m3x-ne-card", [img(item.cover), el("span", "", item.name)], onClick, item.name);
      if (item.plays) node.append(el("small", "", "▶ " + count(item.plays)));
      return node;
    }
    function listRow(item, note, onClick) {
      const info = el("span"); info.append(el("strong", "", item.name), el("small", "", note));
      return btn("m3x-ne-row", [img(item.cover), info], onClick, item.name);
    }
    function renderDiscover() {
      const view = $("ne-discover"); if (!view) return;
      const nodes = [];
      if (profile && !loggedIn()) {
        const banner = el("div", "m3x-ne-banner");
        banner.append(el("p", "", "登录网易云，解锁每日推荐、私人FM、心动模式和你的歌单"), btn("m3x-ne-pill", "去登录", () => ui.go("netease-me")));
        nodes.push(banner);
      }
      const entries = el("div", "m3x-ne-entries");
      entries.append(
        btn("m3x-ne-entry", [el("b", "m3x-ne-day", String(new Date().getDate())), el("span", "", "每日推荐")], () => { if (!needLogin()) openList("daily", {}); }),
        btn("m3x-ne-entry", [iconNode("radio"), el("span", "", "私人FM")], () => startRadio("fm")),
        btn("m3x-ne-entry", [iconNode("heart"), el("span", "", "我喜欢")], () => { if (needLogin()) return; if (likedPlaylist) openList("playlist", { id: likedPlaylist.id }); else ui.toast("正在读取歌单"); }),
        btn("m3x-ne-entry", [iconNode("chart"), el("span", "", "排行榜")], () => openToplists()));
      nodes.push(entries);
      if (!home) nodes.push(loading());
      else {
        if (home.error && !home.playlists.length) nodes.push(el("p", "m3x-ne-loading", home.error));
        if (home.playlists.length) {
          const section = el("section", "m3x-ne-section"), heading = el("div", "m3x-section-heading"), grid = el("div", "m3x-ne-grid");
          heading.append(el("h3", "", loggedIn() ? "为你推荐的歌单" : "推荐歌单"));
          home.playlists.slice(0, 9).forEach(item => grid.append(card(item, () => openList("playlist", { id: item.id }))));
          section.append(heading, grid); nodes.push(section);
        }
        if (home.toplists.length) {
          const section = el("section", "m3x-ne-section"), heading = el("div", "m3x-section-heading");
          heading.append(el("h3", "", "排行榜"), btn("m3x-text-button", "全部 ›", () => openToplists()));
          section.append(heading, ...home.toplists.slice(0, 4).map(item => listRow(item, item.note || "官方榜", () => openList("playlist", { id: item.id }))));
          nodes.push(section);
        }
      }
      view.replaceChildren(...nodes);
    }
    function openToplists() {
      if (!home?.toplists?.length) { ui.toast("排行榜加载中"); return; }
      openView({ title: "排行榜", render: () => home.toplists.map(item => listRow(item, item.note || "官方榜", () => openList("playlist", { id: item.id }))) });
    }

    // ---- search: a single bar ------------------------------------------------------------------------
    const searchInput = $("ne-search"), searchView = $("ne-search-view"), discover = $("ne-discover"), cancel = $("ne-search-cancel");
    function openSearch() {
      if (searchActive) return;
      searchActive = true; searchView.hidden = false; discover.hidden = true; cancel.hidden = false;
      if (!searchState) renderSearchIdle();
    }
    function closeSearch() {
      searchActive = false; searchState = null; searchInput.value = ""; searchInput.blur();
      searchView.hidden = true; discover.hidden = false; cancel.hidden = true; searchView.replaceChildren();
    }
    function renderSearchIdle() {
      const chips = el("div", "m3x-ne-chips");
      (home?.hot || []).forEach((word, index) => chips.append(btn("m3x-ne-chip", [el("b", "", String(index + 1)), document.createTextNode(word)], () => { searchInput.value = word; runSearch(word); })));
      if (!chips.children.length) chips.append(el("span", "m3x-caption", "输入歌名、歌手或歌单名"));
      searchView.replaceChildren(el("p", "m3x-ne-label", "热门搜索"), chips);
    }
    async function runSearch(query, more = false) {
      query = query.trim(); if (!query) { renderSearchIdle(); return; }
      openSearch(); searchInput.blur();
      if (!more) { searchState = { query, songs: [], total: 0, playlists: [] }; searchView.replaceChildren(loading("正在搜索…")); }
      const token = searchState;
      try {
        const [songs, lists] = await Promise.all([request("search", { query, offset: token.songs.length, type: 1 }),
          more ? Promise.resolve({ playlists: token.playlists }) : request("search", { query, type: 1000 }).catch(() => ({ playlists: [] }))]);
        if (searchState !== token) return;
        token.songs = token.songs.concat(songs.songs); token.total = songs.total; token.playlists = lists.playlists;
        renderSearchResults();
      } catch (error) { if (searchState === token) searchView.replaceChildren(el("p", "m3x-ne-loading", error.message)); }
    }
    function renderSearchResults() {
      const { query, songs, total, playlists } = searchState, nodes = [];
      if (playlists.length) {
        nodes.push(el("p", "m3x-ne-label", "歌单"));
        playlists.slice(0, 3).forEach(item => nodes.push(listRow(item, item.count + " 首" + (item.creator ? " · " + item.creator : ""), () => openList("playlist", { id: item.id }))));
      }
      nodes.push(el("p", "m3x-ne-label", songs.length ? "单曲 · 共 " + total + " 首" : "没有找到与“" + query + "”相关的歌曲"));
      if (songs.length) nodes.push(playAll(songs), songList(songs));
      if (songs.length < total && songs.length < 300) nodes.push(btn("m3x-ne-more", "加载更多", () => runSearch(query, true)));
      searchView.replaceChildren(...nodes);
    }
    $("ne-search-form").addEventListener("submit", event => { event.preventDefault(); runSearch(searchInput.value); });
    searchInput.addEventListener("focus", openSearch);
    searchInput.addEventListener("input", () => { if (!searchInput.value.trim()) { searchState = null; renderSearchIdle(); } });
    cancel.addEventListener("click", closeSearch);

    // ---- song lists ----------------------------------------------------------------------------------
    function songRow(song, index, songs, numbered) {
      const info = el("span"), title = el("strong"), sub = el("small");
      if (song.vip) title.append(el("i", "m3x-ne-tag", "VIP"));
      if (song.available === false) title.append(el("i", "m3x-ne-tag", "无版权"));
      title.append(song.title || "未知歌曲");
      sub.textContent = [song.artist, song.album].filter(Boolean).join(" · ");
      info.append(title, sub);
      const lead = numbered ? el("em", "", String(index + 1)) : img(cover(song.cover, 100));
      const row = btn("m3x-ne-song", [lead, info], () => play(songs, index), song.title + "，" + song.artist);
      row.dataset.songId = song.id; row.dataset.available = String(song.available !== false);
      row.setAttribute("aria-current", String(song.id === currentNetease()));
      return row;
    }
    function songList(songs, numbered = false) {
      const box = el("div", "m3x-ne-songs");
      let shown = 0;
      const page = () => {
        box.querySelector(".m3x-ne-more")?.remove();
        songs.slice(shown, shown + 150).forEach((song, offset) => box.append(songRow(song, shown + offset, songs, numbered)));
        shown = Math.min(songs.length, shown + 150);
        if (shown < songs.length) box.append(btn("m3x-ne-more", "显示更多（" + shown + " / " + songs.length + "）", page));
      };
      page(); return box;
    }
    function playAll(songs) {
      const playable = songs.filter(song => song.available !== false).length;
      return btn("m3x-ne-playall", [iconNode("play"), el("span", "", "播放全部"), el("small", "", "（" + playable + "）")], () => {
        const index = songs.findIndex(song => song.available !== false); if (index >= 0) play(songs, index);
      });
    }
    function highlight() {
      const id = currentNetease();
      root.querySelectorAll(".m3x-ne-song[data-song-id]").forEach(row => row.setAttribute("aria-current", String(row.dataset.songId === id)));
    }
    // Imports songs and merges the returned tracks into the UI library (no full library reload).
    async function importSongs(songs) {
      const data = await request("import", { songs });
      ui.addTracks(data.tracks || []);
      return data.ids;
    }
    // One play request at a time: repeats of the same request while it loads are ignored,
    // a different request supersedes the older one, and tapping the song already playing just opens the player.
    let pendingPlay = null;
    function play(songs, index, radioKind = null) {
      const start = songs[index];
      if (!start || start.available === false) { ui.toast("该歌曲暂无版权"); return Promise.resolve(); }
      const playable = songs.filter(song => song.available !== false);
      const key = (radioKind || "list") + ":" + start.id + ":" + playable.length + ":" + (playable[0]?.id || "");
      if (pendingPlay?.key === key) return pendingPlay.promise;
      const queue = ui.queueIds();
      if (!radioKind && currentNetease() === start.id && queue.length === playable.length && !ui.isPaused?.()) { ui.go("player"); return Promise.resolve(); }
      if (start.vip && !profile?.vip?.active) ui.toast("VIP 歌曲，非会员可能无法完整播放");
      const token = { key };
      token.promise = (async () => {
        try {
          const started = performance.now();
          const ids = await importSongs(playable);
          console.info("[perf] import " + playable.length + " songs " + Math.round(performance.now() - started) + " ms");
          if (pendingPlay !== token) return;
          radio = radioKind ? { kind: radioKind, ids: new Set(ids) } : null;
          ui.playIds(ids, ids[playable.indexOf(start)]);
          syncPlayer();
        } catch (error) { if (pendingPlay === token) ui.toast(error.message); }
        finally { if (pendingPlay === token) pendingPlay = null; }
      })();
      pendingPlay = token;
      return token.promise;
    }

    // ---- collection page (playlist / daily / album / artist / similar / recent / toplists) -------------
    function openView(view) {
      if (ui.page() !== "netease-list") { listOrigin = ui.page(); stack.length = 0; }
      stack.push(view); renderView(); ui.go("netease-list");
    }
    function renderView() {
      const view = stack[stack.length - 1], target = $("ne-list-view");
      $("ne-list-heading").textContent = view.title;
      const panel = target.closest(".m3x-panel"); if (panel) panel.scrollTop = 0;
      if (view.error) { target.replaceChildren(el("p", "m3x-ne-loading", view.error)); return; }
      if (view.render) { target.replaceChildren(...view.render()); return; }
      if (!view.data) { target.replaceChildren(loading()); return; }
      const data = view.data, nodes = [], hero = el("div", "m3x-ne-hero"), text = el("div");
      text.append(el("h3", "", data.name));
      if (data.subtitle) text.append(el("p", "", data.subtitle));
      if (data.description) text.append(el("p", "", data.description));
      hero.append(img(data.cover), text); nodes.push(hero);
      if (data.songs.length) nodes.push(playAll(data.songs), songList(data.songs, !["daily", "recent", "simi"].includes(data.kind)));
      else nodes.push(el("p", "m3x-ne-loading", "暂无歌曲"));
      if (data.truncated) nodes.push(el("p", "m3x-caption", "歌单较大，仅显示前 3000 首"));
      target.replaceChildren(...nodes);
    }
    const titles = { playlist: "歌单", daily: "每日推荐", album: "专辑", artist: "歌手", simi: "相似歌曲", recent: "最近常听" };
    const listCache = new Map();
    async function openList(operation, args) {
      const key = operation + ":" + JSON.stringify(args);
      const top = stack[stack.length - 1];
      if (top?.key === key && ui.page() === "netease-list") return; // repeated tap on the same entry
      const view = { title: titles[operation] || "歌单", data: null, key };
      // Recently opened lists reopen instantly from memory (bounded, 5 minutes).
      const cached = listCache.get(key);
      if (cached && Date.now() - cached.at < 300000) { view.data = cached.data; openView(view); return; }
      openView(view);
      const started = performance.now();
      try {
        view.data = await request(operation, args);
        listCache.delete(key); listCache.set(key, { data: view.data, at: Date.now() });
        while (listCache.size > 12) listCache.delete(listCache.keys().next().value);
      } catch (error) { view.error = error.message; }
      console.info("[perf] " + operation + " " + Math.round(performance.now() - started) + " ms");
      if (stack[stack.length - 1] === view && ui.page() === "netease-list") renderView();
    }
    $("ne-list-back").addEventListener("click", () => back("netease-list"));

    // ---- me ------------------------------------------------------------------------------------------
    function renderMe() {
      const view = $("ne-me-view"); if (!view) return;
      const nodes = [];
      if (!profile) nodes.push(loading("正在读取账号…"));
      else if (!loggedIn()) {
        const box = el("div", "m3x-ne-login"), head = el("div", "m3x-ne-profile"), avatar = el("span", "m3x-ne-avatar"), who = el("div");
        avatar.innerHTML = icons.user;
        who.append(el("strong", "", profile.expired ? "登录已失效" : "未登录"), el("p", "", "登录后同步红心、歌单、每日推荐与 VIP 音质"));
        head.append(avatar, who);
        const actions = el("div", "m3x-ne-actions");
        actions.append(btn("m3x-ne-pill", "网页登录", webLogin), btn("m3x-ne-pill m3x-ne-ghost", "扫码登录", qrLogin));
        box.append(head, actions, el("p", "m3x-caption", "网页登录在应用内打开网易云官方页面；应用不读取密码，只加密保存登录凭据。"));
        nodes.push(box);
      } else {
        const head = el("div", "m3x-ne-profile"), who = el("div"), badges = el("div", "m3x-ne-badges"), vip = profile.vip || {};
        const avatar = img(profile.avatar, "头像"); avatar.loading = "eager";
        const vipBadge = el("span", "m3x-ne-badge", (vip.label || "非会员") + (vip.level ? " · Lv." + vip.level : "")); vipBadge.dataset.vip = String(!!vip.active);
        badges.append(vipBadge);
        if (profile.userLevel) badges.append(el("span", "m3x-ne-badge", "Lv." + profile.userLevel));
        who.append(el("strong", "", profile.nickname || "网易云用户"), badges);
        const facts = [vip.expireTime ? "会员至 " + date(vip.expireTime) : "", profile.listenSongs ? "累计听歌 " + profile.listenSongs + " 首" : ""].filter(Boolean);
        if (facts.length) who.append(el("p", "", facts.join(" · ")));
        head.append(avatar, who); nodes.push(head);
        const tiles = el("div", "m3x-ne-tiles");
        tiles.append(
          btn("m3x-ne-tile", [iconFrom("heart"), el("strong", "", "我喜欢"), el("small", "", (likedPlaylist ? likedPlaylist.count : likes.size) + " 首")],
            () => likedPlaylist ? openList("playlist", { id: likedPlaylist.id }) : ui.toast("正在读取歌单")),
          btn("m3x-ne-tile", [iconFrom("clock"), el("strong", "", "最近常听"), el("small", "", "本周")], () => openList("recent", {})),
          btn("m3x-ne-tile", [iconFrom("radio"), el("strong", "", "私人FM"), el("small", "", "连续推荐")], () => startRadio("fm")));
        nodes.push(tiles);
        if (!myLists) nodes.push(loading("正在读取歌单…"));
        else for (const [label, filter] of [["创建的歌单", item => item.mine && !item.liked], ["收藏的歌单", item => !item.mine]]) {
          const items = myLists.filter(filter);
          const section = el("section", "m3x-ne-section"), heading = el("div", "m3x-section-heading");
          heading.append(el("h3", "", label), el("span", "", items.length + " 个"));
          section.append(heading, ...items.map(item => listRow(item, item.count + " 首" + (item.mine ? "" : " · " + item.creator), () => openList("playlist", { id: item.id }))));
          if (!items.length) section.append(el("p", "m3x-caption", "暂无"));
          nodes.push(section);
        }
      }
      const settings = el("section", "m3x-ne-section"), heading = el("div", "m3x-section-heading");
      heading.append(el("h3", "", "设置"));
      const quality = el("select"); quality.setAttribute("aria-label", "在线播放音质");
      levels.forEach(([value, label]) => quality.append(new Option(label, value))); quality.value = profile?.level || "lossless";
      quality.addEventListener("change", () => request("level", { level: quality.value }).then(data => { if (profile) profile.level = data.level; ui.toast(data.message); }, error => ui.toast(error.message)));
      const qualityRow = el("label", "m3x-ne-setting"); qualityRow.append(el("span", "", "在线播放音质"), quality);
      settings.append(heading, qualityRow,
        btn("m3x-ne-setting", [el("span", "", "播放与软件设置"), el("small", "", "均衡器、歌词 ›")], () => ui.go("settings")),
        btn("m3x-ne-setting", [el("span", "", "听歌统计"), el("small", "", "›")], () => ui.go("statistics")),
        confirmButton("m3x-ne-setting", "清理已缓存的网易云曲目", "再点一次清理", async () => {
          try { const data = await request("remove-imported"); await ui.reloadLibrary(); ui.toast("已清理 " + data.removed + " 首"); } catch (error) { ui.toast(error.message); }
        }));
      if (loggedIn()) settings.append(confirmButton("m3x-ne-setting m3x-ne-danger", "退出登录", "再点一次退出登录", async () => {
        try {
          await request("logout"); listCache.clear();
          profile = { loggedIn: false, level: profile.level }; likes = new Set(); myLists = null; likedPlaylist = null; radio = null;
          renderMe(); refreshHome(); syncPlayer(); ui.toast("已退出网易云账号");
        } catch (error) { ui.toast(error.message); }
      }));
      nodes.push(settings);
      view.replaceChildren(...nodes);
    }

    // ---- radio: personal FM (an entry) and heartbeat (the 4th play-order mode) -------------------------
    async function startRadio(kind) {
      if (needLogin()) return;
      try {
        const data = await request("fm");
        if (!data.songs.length) throw new Error("私人FM 暂无推荐");
        await play(data.songs, 0, "fm");
        ui.setMode(0);
      } catch (error) { ui.toast(error.message); }
    }
    // Play order cycles 列表循环 → 随机 → 单曲循环 → 心动模式 for NetEase songs; heartbeat keeps the current song.
    async function startHeartbeat(track) {
      if (needLogin()) return;
      try {
        if (!likedPlaylist) await loadMyLists();
        if (!likedPlaylist) throw new Error("没有找到“我喜欢的音乐”歌单");
        const data = await request("heartbeat", { id: track.neteaseId, playlistId: likedPlaylist.id });
        const songs = data.songs.filter(song => song.available !== false && song.id !== track.neteaseId);
        if (!songs.length) throw new Error("心动模式暂无推荐");
        const ids = await importSongs(songs);
        radio = { kind: "heartbeat", ids: new Set([track.id, ...ids]) };
        ui.replaceUpcoming(ids); ui.setMode(0);
        ui.toast("心动模式 · 根据当前歌曲为你推荐");
      } catch (error) { ui.toast(error.message); }
    }
    function stopRadio(silent) {
      if (!radio) return;
      const name = radio.kind === "fm" ? "私人FM" : "心动模式";
      radio = null; ui.setMode(0);
      if (!silent) ui.toast("已退出" + name + " · 列表循环");
    }
    async function extendRadio() {
      if (!radio || extending) return;
      const queue = ui.queueIds(), position = queue.indexOf(ui.currentTrack()?.id);
      if (position < 0 || position < queue.length - 2) return;
      extending = true;
      try {
        const data = radio.kind === "fm" ? await request("fm") : await request("heartbeat", { id: currentNetease(), playlistId: likedPlaylist?.id || "" });
        const songs = data.songs.filter(song => song.available !== false);
        if (!songs.length || !radio) return;
        const ids = await importSongs(songs);
        ids.forEach(id => radio?.ids.add(id));
        ui.appendIds(ids);
      } catch (error) { ui.toast(error.message); }
      finally { extending = false; }
    }
    // While a radio plays, the format text becomes a removable mode chip; the play-order button changes meaning.
    const radioChip = el("button", "m3x-ne-radio-chip"); radioChip.type = "button"; radioChip.hidden = true;
    radioChip.addEventListener("click", () => stopRadio(false));
    const tools = root.querySelector('[data-panel="player"] .m3x-player-tools');
    if (tools) tools.insertBefore(radioChip, tools.firstChild);
    const modeIcons = {
      heartbeat: icons.heartbeat.replace('width="20" height="20"', 'width="20" height="20"'),
      trash: svg('<path d="M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13M10 11v6M14 11v6"/>')
    };
    function renderRadio() {
      const track = ui.currentTrack();
      if (radio && track && !radio.ids.has(track.id)) radio = null;
      screen.dataset.radio = radio ? radio.kind : "";
      radioChip.hidden = !radio;
      if (radio) {
        radioChip.replaceChildren(el("span", "", radio.kind === "fm" ? "私人FM" : "心动模式"), el("i", "", "×"));
        radioChip.setAttribute("aria-label", "退出" + (radio.kind === "fm" ? "私人FM" : "心动模式"));
      }
      const modeButton = root.querySelector('[data-panel="player"] [data-action="mode"]');
      if (!modeButton || !radio) return;
      modeButton.innerHTML = radio.kind === "fm" ? modeIcons.trash : modeIcons.heartbeat;
      modeButton.setAttribute("aria-label", radio.kind === "fm" ? "不喜欢，跳过这首" : "播放模式：心动模式");
      modeButton.dataset.radio = radio.kind;
    }
    // ---- player integration ------------------------------------------------------------------------
    function syncPlayer() {
      renderRadio(); highlight();
      const track = ui.currentTrack();
      if (!track?.neteaseId) return;
      const favorite = root.querySelector('[data-panel="player"] [data-action="favorite"]');
      if (favorite && loggedIn()) {
        const liked = likes.has(track.neteaseId);
        favorite.setAttribute("aria-pressed", String(liked));
        favorite.setAttribute("aria-label", liked ? "取消喜欢" : "喜欢");
      }
      for (const type of ["artist", "album"]) {
        const link = root.querySelector('[data-action="open-song-' + type + '"]');
        if (link) link.disabled = !track[type === "artist" ? "neteaseArtistId" : "neteaseAlbumId"];
      }
    }
    async function toggleLike(track) {
      const id = track.neteaseId, next = !likes.has(id);
      try {
        const data = await request("like", { id, like: next });
        if (next) likes.add(id); else likes.delete(id);
        if (likedPlaylist) listCache.delete("playlist:" + JSON.stringify({ id: likedPlaylist.id }));
        if (likedPlaylist) likedPlaylist.count += next ? 1 : -1;
        syncPlayer(); ui.toast(data.message);
      } catch (error) { ui.toast(error.message); }
    }
    function addToPlaylist(track) {
      if (needLogin()) return;
      const content = sheet("收藏到歌单");
      const mine = (myLists || []).filter(item => item.mine);
      if (!mine.length) { content.append(el("p", "m3x-caption", myLists ? "暂无自己创建的歌单" : "正在读取歌单，请稍后再试")); return; }
      mine.forEach(item => content.append(listRow(item, item.count + " 首", async () => {
        try { const data = await request("playlist-add", { pid: item.id, id: track.neteaseId }); item.count++; ui.toast(data.message); ui.closeDialog(content.closest("dialog"), true); }
        catch (error) { ui.toast(error.message); }
      })));
    }
    async function showComments(track) {
      const content = sheet("评论 · " + track.title), status = loading("正在读取评论…"); content.append(status);
      try {
        const data = await request("comments", { id: track.neteaseId });
        status.remove();
        for (const [label, rows] of [["精彩评论", data.hot], ["最新评论", data.latest]]) {
          if (!rows.length) continue;
          content.append(el("p", "m3x-ne-label", label + (label === "最新评论" ? " · 共 " + count(data.total) + " 条" : "")));
          rows.forEach(row => {
            const item = el("div", "m3x-ne-comment"), body = el("div"), meta = el("small");
            meta.append(el("span", "", row.user), el("span", "", (row.liked ? "♥ " + count(row.liked) + " · " : "") + date(row.time)));
            body.append(meta, el("p", "", row.content)); item.append(img(row.avatar), body); content.append(item);
          });
        }
        if (!data.hot.length && !data.latest.length) content.append(el("p", "m3x-ne-loading", "暂无评论"));
      } catch (error) { status.textContent = error.message; }
    }
    // Extra song-menu actions for NetEase songs.
    const menuDialog = $("song-menu-dialog"), menuSheet = menuDialog?.querySelector(".m3x-sheet");
    const menuExtra = el("div"); menuExtra.hidden = true;
    if (menuSheet) menuSheet.insertBefore(menuExtra, menuSheet.querySelector('[data-action="play-next"]'));
    function menuTrack() { const id = menuDialog.dataset.trackId; return ui.tracks.find(track => track.id === id) || ui.currentTrack(); }
    function renderMenuExtra() {
      const track = menuTrack();
      menuExtra.hidden = !track?.neteaseId;
      if (!track?.neteaseId) return;
      const action = (label, handler) => btn("m3x-sheet-action", label, () => { ui.closeDialog(menuDialog, true); handler(track); });
      menuExtra.replaceChildren(
        action(likes.has(track.neteaseId) ? "取消喜欢" : "喜欢", item => loggedIn() ? toggleLike(item) : needLogin()),
        action("查看评论", showComments),
        action("相似歌曲", item => openList("simi", { id: item.neteaseId })));
    }
    if (menuDialog) new MutationObserver(() => { if (menuDialog.hasAttribute("open")) renderMenuExtra(); }).observe(menuDialog, { attributes: true, attributeFilter: ["open"] });

    // Clicks that mean something different for NetEase songs or in NetEase mode (capture phase, before app.js).
    root.addEventListener("click", event => {
      const target = event.target.closest("button"); if (!target) return;
      const stop = () => { event.preventDefault(); event.stopImmediatePropagation(); };
      const page = target.dataset.page;
      if (mode === "netease" && (page === "profile" || page === "library") && !target.closest(".m3x-nav")) { stop(); ui.go(page === "profile" ? "netease-me" : "netease"); return; }
      const action = target.dataset.action;
      if (!action) return;
      const fromMenu = action.startsWith("menu-"), track = fromMenu ? menuTrack() : ui.currentTrack();
      if (!track?.neteaseId) return;
      const closeMenu = () => { if (fromMenu) ui.closeDialog(menuDialog, true); };
      if (action === "mode" && !fromMenu) {
        if (radio?.kind === "fm") {
          stop();
          request("fm-trash", { id: track.neteaseId }).then(() => { ui.toast("已减少此类推荐"); ui.next(); }, error => ui.toast(error.message));
        } else if (radio?.kind === "heartbeat") { stop(); stopRadio(false); }
        else if (ui.playMode() === 2 && loggedIn()) { stop(); startHeartbeat(track); }
        return;
      }
      if (action === "favorite" && loggedIn()) { stop(); toggleLike(track); }
      else if ((action === "add-playlist" || action === "menu-add-playlist") && loggedIn()) { stop(); closeMenu(); addToPlaylist(track); }
      else if (action === "open-song-artist" || action === "menu-open-artist") {
        stop(); closeMenu();
        if (track.neteaseArtistId) openList("artist", { id: track.neteaseArtistId }); else ui.toast("没有歌手信息");
      } else if (action === "open-song-album" || action === "menu-open-album") {
        stop(); closeMenu();
        if (track.neteaseAlbumId) openList("album", { id: track.neteaseAlbumId }); else ui.toast("没有专辑信息");
      }
    }, true);

    // ---- navigation ----------------------------------------------------------------------------------
    function back(page) {
      if (page === "netease-list") {
        stack.pop();
        if (stack.length) renderView(); else ui.go(listOrigin === "netease-list" ? "netease" : listOrigin);
        return true;
      }
      if (mode !== "netease") return false;
      if (page === "netease" && searchActive) { closeSearch(); return true; }
      if (page === "netease") return "exit";
      if (["queue", "lyrics"].includes(page)) return false;
      if (["settings", "statistics", "favorites", "playlists", "playlist"].includes(page)) { ui.go("netease-me"); return true; }
      ui.go("netease"); return true;
    }
    window.M3X_SOURCE = { back, mode: () => mode, setSource, listSection: () => listOrigin === "netease-me" ? "netease-me" : "netease" };
    window.addEventListener("m3x-render", event => {
      const page = event.detail?.page;
      if (page === "netease" || page === "netease-me") ensureHome();
      syncPlayer();
    });
    window.addEventListener("m3x-native-track", () => setTimeout(() => { syncPlayer(); extendRadio(); }, 0));

    let saved = "local";
    try { saved = localStorage.getItem(STORE) || "local"; } catch (_) { /* default local */ }
    setSource(saved, true);
    renderMe(); renderDiscover();
  }
  if (window.M3X_UI) initialize(); else window.addEventListener("m3x-ready", initialize, { once: true });
})();
