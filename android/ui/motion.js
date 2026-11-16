/*
 * 澄 · Motion engine 2.0
 *
 * Every animation here is a Web Animation on transform / opacity, so Chromium
 * runs it on the compositor thread: list rendering, polling or lyric parsing on
 * the main thread cannot stall a page transition that has started. Nothing is
 * permanently promoted, nothing loops, and every transition is interruptible —
 * a newer state always cancels the animation it supersedes.
 */
(() => {
  "use strict";
  const root = document.getElementById("m3x-concept");
  const screen = root.querySelector(".m3x-screen");
  const nav = screen.querySelector(".m3x-nav");
  const media = window.matchMedia("(prefers-reduced-motion: reduce)");
  const linear = window.CSS && CSS.supports && CSS.supports("transition-timing-function", "linear(0, 1)");
  const EASE = {
    out: "cubic-bezier(.2,.8,.2,1)",
    emph: "cubic-bezier(.05,.7,.1,1)",
    accel: "cubic-bezier(.3,0,.8,.15)",
    inout: "cubic-bezier(.4,0,.2,1)",
    mech: "cubic-bezier(.2,.9,.1,1)",
    spring: linear ? "linear(0,.0269,.0944,.186,.2895,.3957,.4985,.5937,.6789,.7531,.816,.8681,.9101,.9433,.9686,.9875,1.0009,1.01,1.0157,1.0187,1.0199,1.0196,1.0185,1.0168,1.0148,1.0126,1.0106,1.0086,1.0069,1.0053,1.004,1.0029,1)" : "cubic-bezier(.05,.7,.1,1)",
    soft: linear ? "linear(0,.0147,.0529,.1069,.1709,.2402,.3115,.3822,.4504,.5151,.5753,.6306,.6808,.726,.7662,.8018,.8331,.8603,.8838,.904,.9213,.936,.9483,.9587,.9674,.9745,.9804,.9852,.989,.9921,.9946,.9965,1)" : "cubic-bezier(.05,.7,.1,1)",
    bounce: linear ? "linear(0,.0415,.1446,.2817,.4314,.578,.711,.8244,.9156,.9844,1.0326,1.063,1.0789,1.0835,1.0802,1.0717,1.0603,1.0478,1.0355,1.0244,1.0149,1.0072,1.0014,.9973,.9948,.9934,.993,.9933,.994,.9949,.996,.997,1)" : "cubic-bezier(.34,1.56,.64,1)"
  };
  const TOP = ["library", "player", "profile"];
  const SECTION = { settings: "profile", statistics: "profile", favorites: "profile", playlists: "profile", playlist: "profile", queue: "player", lyrics: "player", "create-library": "library", "netease-list": "netease" };
  const DEPTH = { library: 0, player: 0, profile: 0, netease: 0, "netease-me": 0, settings: 1, statistics: 1, favorites: 1, playlists: 1, "create-library": 1, queue: 1, lyrics: 1, "netease-list": 1, playlist: 2 };
  // NetEase mode swaps the outer tabs to 发现 / 播放 / 我的 and maps shared pages onto them.
  const netease = () => screen.dataset.source === "netease";
  const top = () => netease() ? ["netease", "player", "netease-me"] : TOP;
  const section = page => {
    const value = page === "netease-list" ? (window.M3X_SOURCE?.listSection?.() || "netease") : SECTION[page] || page;
    return netease() ? ({ profile: "netease-me", library: "netease" })[value] || value : value;
  };
  const running = new Map();
  let enabled = false, preference = false, previousPage = null, previousTrack = null, direction = 1;
  let lyricFrame = 0, lyricHoldUntil = 0, shared = null, indicatorFrame = 0, firstRender = true, bootedAt = Infinity;
  const isEndfield = () => screen.dataset.visualTheme === "endfield";
  const panel = page => page && screen.querySelector('[data-panel="' + page + '"]');
  const visible = element => !!element && !document.hidden && !element.closest("[hidden]") && element.isConnected;
  const reduced = () => preference || media.matches;

  /* ---------- primitives ---------- */
  function stop(element, key = "main") {
    const id = element && running.get(element);
    if (!id || !id[key]) return;
    id[key].cancel(); delete id[key];
  }
  function stopAll(element) {
    const id = running.get(element);
    if (!id) return;
    Object.keys(id).forEach(key => id[key].cancel());
    running.delete(element);
  }
  function run(element, frames, options, key = "main") {
    if (!element || typeof element.animate !== "function") return null;
    stop(element, key);
    const animation = element.animate(frames, options);
    let id = running.get(element);
    if (!id) { id = {}; running.set(element, id); }
    id[key] = animation;
    animation.addEventListener("finish", () => {
      const current = running.get(element);
      if (current && current[key] === animation) {
        delete current[key];
        if (!Object.keys(current).length) running.delete(element);
      }
    });
    return animation;
  }
  // Finished entry animations are cancelled so no inline effect lingers.
  function settle(animation) { if (animation) animation.addEventListener("finish", () => animation.cancel()); return animation; }
  function cancelAll() {
    running.forEach(id => Object.keys(id).forEach(key => id[key].cancel()));
    running.clear();
    cancelScroll();
    screen.querySelectorAll(".m3x-panel.is-leaving").forEach(element => element.classList.remove("is-leaving"));
    releasePending();
    screen.querySelectorAll(".m3x-flight, .m3x-cover-ghost, .m3x-burst").forEach(element => element.remove());
    const cover = screen.querySelector(".m3x-cover"); if (cover) cover.style.opacity = "";
    const mini = screen.querySelector(".m3x-mini"); if (mini) mini.classList.remove("is-leaving");
  }
  function configure(value) {
    preference = !!value;
    const reduce = reduced();
    screen.dataset.motionReduced = String(reduce);
    const next = !reduce;
    screen.dataset.motion = next ? (isEndfield() ? "endfield" : "classic") : "off";
    if (enabled && !next) cancelAll();
    enabled = next;
  }

  /* ---------- page transitions ---------- */
  function classify(from, to) {
    const a = section(from), b = section(to);
    if (shared && to === "player") return "rise";
    if (from === "player" && to === "lyrics") return "zoom-in";
    if (from === "lyrics" && to === "player") return "zoom-out";
    if (a === b && DEPTH[to] !== DEPTH[from]) return DEPTH[to] > DEPTH[from] ? "push" : "pop";
    if (a === b) return to === "playlist" ? "push" : "pop";
    return "tab";
  }
  function spec(kind, sign) {
    const ef = isEndfield();
    const d = ef ? 0.8 : 1;
    const ease = ef ? EASE.mech : EASE.emph;
    switch (kind) {
      case "push": return {
        in: [{ opacity: 0, transform: `translate3d(${ef ? 40 : 56}px,0,0)` }, { opacity: 1, transform: "none" }], inTime: 380 * d, inEase: ease,
        out: [{ opacity: 1, transform: "none" }, { opacity: 0, transform: `translate3d(${ef ? -16 : -28}px,0,0)` }], outTime: 220 * d };
      case "pop": return {
        in: [{ opacity: 0, transform: `translate3d(${ef ? -16 : -28}px,0,0)` }, { opacity: 1, transform: "none" }], inTime: 360 * d, inEase: ease,
        out: [{ opacity: 1, transform: "none" }, { opacity: 0, transform: `translate3d(${ef ? 40 : 56}px,0,0)` }], outTime: 220 * d };
      case "rise": return {
        in: null, inTime: 0,
        out: [{ opacity: 1, transform: "none" }, { opacity: 0, transform: "scale(.985)" }], outTime: 200 };
      case "zoom-in": return {
        in: [{ opacity: 0, transform: "scale(1.04)" }, { opacity: 1, transform: "none" }], inTime: 360 * d, inEase: ease,
        out: [{ opacity: 1, transform: "none" }, { opacity: 0, transform: "scale(.96)" }], outTime: 200 * d };
      case "zoom-out": return {
        in: [{ opacity: 0, transform: "scale(.96)" }, { opacity: 1, transform: "none" }], inTime: 360 * d, inEase: ease,
        out: [{ opacity: 1, transform: "none" }, { opacity: 0, transform: "scale(1.04)" }], outTime: 200 * d };
      default: return {
        in: [{ opacity: 0, transform: `translate3d(${sign * (ef ? 24 : 32)}px,0,0)` }, { opacity: 1, transform: "none" }], inTime: 360 * d, inEase: ease,
        out: [{ opacity: 1, transform: "none" }, { opacity: 0, transform: `translate3d(${sign * (ef ? -16 : -24)}px,0,0)` }], outTime: 170 * d };
    }
  }
  function pageTransition(from, to) {
    const incoming = panel(to), outgoing = panel(from);
    releasePending();
    screen.querySelectorAll(".m3x-panel.is-leaving").forEach(element => { stopAll(element); element.classList.remove("is-leaving"); });
    if (incoming) stopAll(incoming);
    if (!enabled || !incoming || !outgoing || document.hidden) { shared = null; return; }
    const order = top().indexOf(section(to)) - top().indexOf(section(from));
    const kind = classify(from, to), s = spec(kind, order >= 0 ? 1 : -1);
    outgoing.classList.add("is-leaving");
    const out = run(outgoing, s.out, { duration: s.outTime, easing: EASE.accel, fill: "forwards" });
    if (out) out.addEventListener("finish", () => { outgoing.classList.remove("is-leaving"); out.cancel(); });
    else outgoing.classList.remove("is-leaving");
    mini(from, to);
    transitionAt = performance.now();
    if (kind !== "rise") shared = null;
    const enter = () => {
      if (s.in) settle(run(incoming, s.in, { duration: s.inTime, easing: s.inEase, delay: s.inDelay || 0, fill: "backwards" }));
      if (kind === "rise") riseIntoPlayer(incoming);
      if (isEndfield()) headingSweep(incoming, s.inTime);
      if (to === "statistics") charts(incoming);
      incoming.querySelectorAll(INDICATOR_HOSTS).forEach(host => indicator(host, true));
    };
    // A warm page already has its textures: only compositing is left, so both
    // pages start together.
    if (isWarm(to)) { enter(); return; }
    // Two-frame hand-off for cold pages (measured on the M3X): this frame commits
    // only the cheap exit animation, so the response is immediate. The incoming
    // page is unlocked on the next frame, so its style, paint and GPU raster do
    // not delay the first frame of the exit.
    incoming.classList.add("is-pending");
    const token = pendingToken = {};
    requestAnimationFrame(() => {
      if (pendingToken !== token) return;
      incoming.classList.remove("is-pending");
      enter();
    });
  }
  let pendingToken = null;
  function releasePending() { pendingToken = null; screen.querySelectorAll(".m3x-panel.is-pending").forEach(element => element.classList.remove("is-pending")); }

  /* ---------- warm top-level pages ---------- */
  // GPU raster stalls every running animation on the M3X (it shares a thread with
  // WebView's draw), and content-visibility drops a hidden page's textures, so a
  // cold switch re-rasterises the whole page. Top-level pages therefore stay
  // painted at opacity 0. Colour changes would restyle and repaint all of them on
  // every frame of the glide, and a modal dialog's inert state would restyle them
  // when it opens and closes, so they go cold for the duration.
  let warmTimer = 0, transitionAt = -Infinity;
  // The warm set follows the source mode: 发现 / 播放 / 我的 in NetEase mode, 曲库 / 播放 / 我的 locally.
  const ALL_TOP = ["library", "player", "profile", "netease", "netease-me"];
  const isWarm = page => top().includes(page) && !!panel(page) && "warm" in panel(page).dataset;
  function warmLater(delay) {
    clearTimeout(warmTimer);
    warmTimer = setTimeout(() => {
      // Warming restyles and rasterises: never let it interrupt a transition.
      const wait = transitionAt + 700 - performance.now();
      if (wait > 0) { warmLater(wait); return; }
      if (screen.querySelector("dialog[open]")) return; // Its close event comes back here.
      // One page per step, so the main thread stays responsive between them.
      ALL_TOP.forEach(name => { if (!top().includes(name) && panel(name)) delete panel(name).dataset.warm; });
      const page = top().find(name => panel(name) && !isWarm(name));
      if (page) { panel(page).dataset.warm = ""; warmLater(400); }
    }, delay);
  }
  function coolPages() { clearTimeout(warmTimer); ALL_TOP.forEach(name => { if (panel(name)) delete panel(name).dataset.warm; }); }
  function recolor() { coolPages(); warmLater(1500); }
  screen.addEventListener("close", () => warmLater(800), true);
  // Statistics: bars grow from their baselines in reading order.
  function charts(target) {
    target.querySelectorAll(".m3x-day-chart i").forEach((bar, index) => settle(run(bar, [{ transform: "scaleY(0)" }], { duration: 560, delay: 140 + index * 40, easing: EASE.emph, fill: "backwards" }, "chart")));
    target.querySelectorAll(".m3x-hour-chart i").forEach((bar, index) => settle(run(bar, [{ transform: "scaleX(0)" }], { duration: 520, delay: 220 + index * 14, easing: EASE.emph, fill: "backwards" }, "chart")));
    [...target.querySelectorAll(".m3x-rank i")].slice(0, 12).forEach((bar, index) => settle(run(bar, [{ transform: "scaleX(0)" }], { duration: 600, delay: 260 + index * 40, easing: EASE.emph, fill: "backwards" }, "chart")));
  }
  // Endfield: the yellow locator bar under the heading extends like a signal trace.
  function headingSweep(target, delay) {
    const heading = target.querySelector(".m3x-page-heading");
    if (!heading) return;
    settle(run(heading, [{ transform: "scaleX(0)" }, { transform: "scaleX(1)" }], { pseudoElement: "::after", duration: 320, delay: Math.min(120, delay / 3), easing: EASE.mech, fill: "backwards" }, "sweep"));
  }
  // The mini player docks/undocks instead of popping in and out.
  function mini(from, to) {
    const element = screen.querySelector(".m3x-mini");
    if (!element) return;
    const wasHidden = from === "player", nowHidden = to === "player";
    if (wasHidden && !nowHidden) settle(run(element, [{ opacity: 0, transform: "translate3d(0,18px,0) scale(.96)" }, { opacity: 1, transform: "none" }], { duration: 420, easing: EASE.spring, delay: 60, fill: "backwards" }));
    else if (!wasHidden && nowHidden) {
      element.classList.add("is-leaving");
      const animation = run(element, [{ opacity: 1, transform: "none" }, { opacity: 0, transform: "translate3d(0,14px,0)" }], { duration: 180, easing: EASE.accel, fill: "forwards" });
      const done = () => { element.classList.remove("is-leaving"); if (animation) animation.cancel(); };
      if (animation) animation.addEventListener("finish", done); else done();
    }
  }

  /* ---------- shared cover flight (list / mini → player) ---------- */
  const COVER_SOURCES = ".m3x-swatch, .m3x-card-art img, .m3x-mini-cover, .m3x-rank > img, .m3x-preview-card img";
  root.addEventListener("click", event => {
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    direction = button.dataset.action === "previous" ? -1 : 1;
    const trigger = button.closest("[data-track], .m3x-mini-open");
    shared = null;
    if (!enabled || !trigger) return;
    const image = (trigger.matches(".m3x-mini-open") ? trigger : trigger).querySelector(COVER_SOURCES);
    if (!image || image.hidden || !image.complete || !image.naturalWidth) return;
    const rect = image.getBoundingClientRect();
    if (rect.width < 8) return;
    shared = { rect, src: image.currentSrc || image.src, radius: getComputedStyle(image).borderRadius, at: performance.now() };
  }, true);
  function riseIntoPlayer(target) {
    const source = shared; shared = null;
    const cover = target.querySelector(".m3x-cover");
    const blocks = [...target.querySelectorAll(".m3x-song, .m3x-player-tools, .m3x-seek, .m3x-transport, .m3x-output, .m3x-up-next")];
    blocks.forEach((element, index) => settle(run(element, [{ opacity: 0, transform: "translate3d(0,22px,0)" }, { opacity: 1, transform: "none" }],
      { duration: 460, delay: 90 + index * 34, easing: EASE.emph, fill: "backwards" })));
    const glow = target.querySelector(".m3x-player-backdrop");
    if (glow) settle(run(glow, [{ opacity: 0 }, {}], { duration: 700, easing: EASE.inout }));
    if (!cover || !source || performance.now() - source.at > 800) {
      if (cover) settle(run(cover, [{ opacity: 0, transform: "scale(.92)" }, { opacity: 1, transform: "none" }], { duration: 420, easing: EASE.spring, fill: "backwards" }));
      return;
    }
    const to = cover.getBoundingClientRect(), host = screen.getBoundingClientRect();
    if (!to.width) return;
    const ghost = document.createElement("img");
    ghost.className = "m3x-flight"; ghost.alt = ""; ghost.src = source.src;
    Object.assign(ghost.style, { position: "absolute", left: (to.left - host.left) + "px", top: (to.top - host.top) + "px", width: to.width + "px", height: to.height + "px",
      zIndex: 40, pointerEvents: "none", objectFit: "cover", borderRadius: getComputedStyle(cover).borderRadius, transformOrigin: "0 0", boxShadow: getComputedStyle(cover).boxShadow });
    screen.append(ghost);
    cover.style.opacity = "0";
    const sx = source.rect.width / to.width, sy = source.rect.height / to.height;
    const dx = source.rect.left - to.left, dy = source.rect.top - to.top;
    const flight = run(ghost, [{ transform: `translate3d(${dx}px,${dy}px,0) scale(${sx},${sy})` }, { transform: "none" }], { duration: 520, easing: EASE.emph });
    const land = () => { cover.style.opacity = ""; ghost.remove(); };
    if (flight) { flight.addEventListener("finish", land); flight.addEventListener("cancel", land); } else land();
  }

  /* ---------- cover & text changes when the song changes ---------- */
  const covers = new WeakMap();
  function cover(element, source) {
    if (!element || covers.get(element) === source) return;
    covers.set(element, source);
    const current = element.getAttribute("src");
    const flying = shared && element.classList.contains("m3x-cover");
    if (!enabled || flying || !current || current === source || !visible(element) || element.style.opacity === "0") { element.src = source; return; }
    const incoming = new Image();
    const finish = failed => {
      if (covers.get(element) !== source) return;
      const next = failed ? "cover-placeholder.svg" : source;
      if (element.classList.contains("m3x-cover") && element.parentElement) {
        const ghost = element.cloneNode(false);
        ghost.className = "m3x-cover-ghost"; ghost.removeAttribute("alt");
        element.parentElement.append(ghost);
        const out = run(ghost, [{ opacity: 1, transform: "none" }, { opacity: 0, transform: `translate3d(${-direction * 34}px,0,0) scale(.94)` }], { duration: 300, easing: EASE.accel, fill: "forwards" });
        const remove = () => ghost.remove();
        if (out) { out.addEventListener("finish", remove); out.addEventListener("cancel", remove); } else remove();
        element.src = next;
        settle(run(element, [{ opacity: 0, transform: `translate3d(${direction * 40}px,0,0) scale(.94)` }, { opacity: 1, transform: "none" }], { duration: 520, easing: EASE.spring, delay: 40, fill: "backwards" }));
      } else {
        element.src = next;
        settle(run(element, [{ opacity: 0, transform: "scale(.82)" }, { opacity: 1, transform: "none" }], { duration: 380, easing: EASE.spring }));
      }
    };
    incoming.onload = () => finish(false);
    incoming.onerror = () => finish(true);
    incoming.src = source;
  }
  function songChanged() {
    transitionAt = performance.now();
    const texts = screen.querySelectorAll(".m3x-song-text > *");
    if (visible(texts[0])) texts.forEach((element, index) => settle(run(element, [{ opacity: 0, transform: `translate3d(${direction * 18}px,0,0)` }, { opacity: 1, transform: "none" }],
      { duration: 440, delay: 60 + index * 45, easing: EASE.emph, fill: "backwards" })));
    const miniText = screen.querySelector(".m3x-mini-text");
    // A swipe on the mini player brings the new song in sideways itself.
    if (visible(miniText) && !miniText.closest("[data-swiping]")) settle(run(miniText, [{ opacity: 0, transform: `translate3d(0,${direction * 8}px,0)` }, { opacity: 1, transform: "none" }], { duration: 360, easing: EASE.emph }));
    backdrop();
  }

  /* ---------- player backdrop: a pre-blurred cover glow, cross-faded ---------- */
  let backdropSource = "", backdropToken = 0;
  function backdrop(force) {
    const host = screen.querySelector(".m3x-player-backdrop");
    const cover = screen.querySelector(".m3x-cover");
    if (!host || !cover) return;
    const source = cover.getAttribute("src") || "";
    const target = window.M3X_UI && window.M3X_UI.currentTrack && window.M3X_UI.currentTrack();
    const wanted = (target && target.cover) || source;
    if (!wanted || (!force && wanted === backdropSource)) return;
    backdropSource = wanted;
    const token = ++backdropToken, image = new Image();
    image.onload = () => {
      if (token !== backdropToken) return;
      let canvases = host.querySelectorAll("canvas");
      if (canvases.length < 2) { host.replaceChildren(); for (let i = 0; i < 2; i++) { const c = document.createElement("canvas"); c.width = c.height = 40; host.append(c); } canvases = host.querySelectorAll("canvas"); }
      const current = host.querySelector("canvas.is-current");
      const next = current === canvases[0] ? canvases[1] : canvases[0];
      const ctx = next.getContext("2d");
      ctx.clearRect(0, 0, 40, 40);
      try { ctx.filter = "blur(4px) saturate(1.35)"; } catch (_) { /* Older WebView: unfiltered low-res draw still reads as a soft glow. */ }
      ctx.drawImage(image, -8, -8, 56, 56);
      ctx.filter = "none";
      next.classList.add("is-current");
      if (current) current.classList.remove("is-current");
      if (enabled && visible(host)) {
        settle(run(next, [{ opacity: 0 }, { opacity: 1 }], { duration: 700, easing: EASE.inout }));
        if (current) settle(run(current, [{ opacity: 1 }, { opacity: 0 }], { duration: 700, easing: EASE.inout }));
      }
    };
    image.onerror = () => { if (token === backdropToken) host.querySelectorAll("canvas").forEach(c => c.classList.remove("is-current")); };
    image.src = wanted;
  }

  /* ---------- icon swaps and micro-interactions ---------- */
  function icon(button) {
    if (!enabled || !button) return;
    const svg = button.querySelector("svg");
    if (!svg || !visible(button)) return;
    const mode = button.dataset.action === "mode";
    settle(run(svg, [{ opacity: 0, transform: mode ? "rotate(-180deg) scale(.6)" : "scale(.4) rotate(-60deg)" }, { opacity: 1, transform: "none" }],
      { duration: mode ? 460 : 420, easing: EASE.bounce }));
  }
  function pop(button, on) {
    if (!enabled || !button || !visible(button)) return;
    const svg = button.querySelector("svg");
    if (svg) settle(run(svg, on ? [{ transform: "scale(.55)" }, { transform: "scale(1.22)", offset: .55 }, { transform: "none" }] : [{ transform: "scale(.82)" }, { transform: "none" }],
      { duration: on ? 520 : 300, easing: on ? EASE.out : EASE.spring }, "pop"));
    if (!on) return;
    const burst = document.createElement("span");
    burst.className = "m3x-burst";
    Object.assign(burst.style, { position: "absolute", left: "50%", top: "50%", width: "34px", height: "34px", margin: "-17px 0 0 -17px", borderRadius: "50%", border: "2px solid var(--m3x-accent)", pointerEvents: "none" });
    button.append(burst);
    const animation = run(burst, [{ opacity: .9, transform: "scale(.4)" }, { opacity: 0, transform: "scale(1.5)" }], { duration: 480, easing: EASE.out });
    const remove = () => burst.remove();
    if (animation) { animation.addEventListener("finish", remove); animation.addEventListener("cancel", remove); } else remove();
  }

  /* ---------- press feedback ---------- */
  const PRESSABLE = "button, .m3x-seek";
  let pressed = null, pressAt = 0, pressX = 0, pressY = 0, releaseTimer = 0;
  function release() {
    const element = pressed; pressed = null;
    if (!element) return;
    const wait = Math.max(0, 90 - (performance.now() - pressAt));
    clearTimeout(releaseTimer);
    releaseTimer = setTimeout(() => element.classList.remove("is-pressed"), wait);
  }
  function press(target, x, y) {
    if (pressed) { clearTimeout(releaseTimer); pressed.classList.remove("is-pressed"); }
    const element = target.closest && target.closest(PRESSABLE);
    pressed = element && !element.disabled ? element : null;
    if (!pressed) return;
    pressAt = performance.now(); pressX = x; pressY = y;
    pressed.classList.add("is-pressed");
  }
  root.addEventListener("touchstart", event => { if (event.touches.length === 1) press(event.target, event.touches[0].clientX, event.touches[0].clientY); else release(); }, { passive: true, capture: true });
  root.addEventListener("touchmove", event => {
    const point = event.touches[0];
    if (pressed && point && (Math.abs(point.clientX - pressX) > 10 || Math.abs(point.clientY - pressY) > 10)) { clearTimeout(releaseTimer); pressed.classList.remove("is-pressed"); pressed = null; }
  }, { passive: true, capture: true });
  root.addEventListener("touchend", release, { passive: true, capture: true });
  root.addEventListener("touchcancel", release, { passive: true, capture: true });
  root.addEventListener("mousedown", event => { if (!event.sourceCapabilities || !event.sourceCapabilities.firesTouchEvents) press(event.target, event.clientX, event.clientY); }, true);
  window.addEventListener("mouseup", release, true);

  /* ---------- sliding indicators (filters, tabs, segmented switch, nav) ---------- */
  const INDICATOR_HOSTS = ".m3x-filters, .m3x-sheet-tabs, .m3x-view-switch";
  function indicator(host, instant) {
    if (!host || !host.isConnected) return;
    let bar = host.querySelector(":scope > .m3x-tab-indicator");
    if (!bar) { bar = document.createElement("span"); bar.className = "m3x-tab-indicator is-instant"; bar.setAttribute("aria-hidden", "true"); host.prepend(bar); instant = true; }
    if (host.closest("[hidden]") && !host.closest("dialog")) { bar.dataset.stale = "true"; return; }
    if (host.closest("dialog") && !host.closest("dialog").open) { bar.dataset.stale = "true"; return; }
    const active = host.querySelector(":scope > button[aria-pressed=true]:not([hidden]), :scope > button[aria-selected=true]:not([hidden])");
    if (!active || !active.offsetWidth) { bar.style.opacity = "0"; return; }
    const segmented = host.classList.contains("m3x-view-switch");
    const inset = segmented ? 0 : 12;
    const x = active.offsetLeft + inset, width = Math.max(8, active.offsetWidth - inset * 2);
    // Re-entering a page with the same selection must not touch style or layout.
    const at = x + ":" + width;
    if (bar.dataset.at === at && bar.style.opacity === "1") { delete bar.dataset.stale; return; }
    bar.dataset.at = at;
    if (instant || bar.dataset.stale === "true" || !enabled) bar.classList.add("is-instant");
    delete bar.dataset.stale;
    bar.style.opacity = "1";
    if (segmented) { bar.style.width = width + "px"; bar.style.transform = `translate3d(${x}px,0,0)`; }
    else bar.style.transform = `translate3d(${x}px,0,0) scaleX(${width / 100})`;
    if (bar.classList.contains("is-instant")) { void bar.offsetWidth; requestAnimationFrame(() => bar.classList.remove("is-instant")); }
    if (!segmented && host.scrollWidth > host.clientWidth + 4) {
      const left = active.offsetLeft - (host.clientWidth - active.offsetWidth) / 2;
      host.scrollTo({ left: Math.max(0, left), behavior: enabled && !instant ? "smooth" : "auto" });
    }
  }
  function indicators(instant) {
    cancelAnimationFrame(indicatorFrame); indicatorFrame = 0;
    screen.querySelectorAll(INDICATOR_HOSTS).forEach(host => indicator(host, instant));
  }
  function scheduleIndicators() {
    if (indicatorFrame) return;
    indicatorFrame = requestAnimationFrame(() => { indicatorFrame = 0; indicators(false); });
  }
  new MutationObserver(records => {
    if (records.some(record => record.target.closest && record.target.closest(INDICATOR_HOSTS + ", dialog, [data-panel]"))) scheduleIndicators();
  }).observe(screen, { subtree: true, attributes: true, attributeFilter: ["aria-pressed", "aria-selected", "open"] });
  window.addEventListener("resize", () => indicators(true));
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => indicators(true));
  const navIndicator = document.createElement("span");
  navIndicator.className = "m3x-nav-indicator"; navIndicator.setAttribute("aria-hidden", "true");
  navIndicator.style.transition = "none";
  nav.prepend(navIndicator);

  /* ---------- sheets ---------- */
  function sheetParts(dialog) { return { sheet: dialog.querySelector(".m3x-sheet"), scrim: dialog.querySelector(".m3x-sheet-scrim") }; }
  function currentOffset(element) {
    const matrix = getComputedStyle(element).transform;
    if (!matrix || matrix === "none") return 0;
    const values = matrix.match(/matrix.*\((.+)\)/);
    if (!values) return 0;
    const parts = values[1].split(",").map(Number);
    return parts.length === 6 ? parts[5] : parts[13] || 0;
  }
  function sheetIn(dialog, resume) {
    const { sheet, scrim } = sheetParts(dialog);
    attachDrag(dialog);
    if (!sheet) return;
    const from = resume && running.get(sheet) ? currentOffset(sheet) : null;
    stopAll(sheet); if (scrim) stopAll(scrim);
    sheet.style.transform = ""; if (scrim) scrim.style.opacity = "";
    if (!enabled) return;
    const height = sheet.offsetHeight || 320;
    const start = from === null ? height + 24 : from;
    settle(run(sheet, [{ transform: `translate3d(0,${start}px,0)` }, { transform: "none" }], { duration: isEndfield() ? 360 : 460, easing: isEndfield() ? EASE.mech : EASE.soft }));
    if (scrim) settle(run(scrim, [{ opacity: from === null ? 0 : Number(getComputedStyle(scrim).opacity) }, { opacity: 1 }], { duration: 280, easing: EASE.out }));
    if (from === null) dialog.querySelectorAll(".m3x-sheet-header, .m3x-sheet > :not(.m3x-sheet-header):not(.m3x-sheet-handle)").forEach((element, index) => {
      if (index > 5) return;
      settle(run(element, [{ opacity: 0, transform: "translate3d(0,10px,0)" }, { opacity: 1, transform: "none" }], { duration: 360, delay: 70 + index * 28, easing: EASE.emph, fill: "backwards" }, "content"));
    });
    requestAnimationFrame(() => dialog.querySelectorAll(INDICATOR_HOSTS).forEach(host => indicator(host, true)));
  }
  function sheetOut(dialog, done) {
    const { sheet, scrim } = sheetParts(dialog);
    if (!enabled || !sheet || document.hidden) { done(); return; }
    const from = currentOffset(sheet), height = sheet.offsetHeight || 320;
    stopAll(sheet); if (scrim) stopAll(scrim);
    const remaining = Math.max(0.25, 1 - from / (height + 24));
    const animation = run(sheet, [{ transform: `translate3d(0,${from}px,0)` }, { transform: `translate3d(0,${height + 24}px,0)` }], { duration: 240 * remaining + 40, easing: EASE.accel, fill: "forwards" });
    if (scrim) run(scrim, [{ opacity: Number(getComputedStyle(scrim).opacity) }, { opacity: 0 }], { duration: 240 * remaining + 40, easing: EASE.out, fill: "forwards" });
    sheet.style.transform = ""; if (scrim) scrim.style.opacity = "";
    let finished = false;
    const end = () => { if (finished) return; finished = true; done(); stopAll(sheet); if (scrim) stopAll(scrim); };
    if (animation) animation.addEventListener("finish", end); else end();
    setTimeout(end, 600);
    return animation;
  }
  const dragged = new WeakSet();
  function attachDrag(dialog) {
    if (dragged.has(dialog)) return;
    dragged.add(dialog);
    const { sheet, scrim } = sheetParts(dialog);
    if (!sheet) return;
    let start = null;
    sheet.addEventListener("touchstart", event => {
      const grab = event.target.closest(".m3x-sheet-handle, .m3x-sheet-header");
      if (!grab || event.target.closest("button, input, select") || event.touches.length !== 1 || !enabled) { start = null; return; }
      start = { y: event.touches[0].clientY, t: performance.now(), last: event.touches[0].clientY, lastT: performance.now(), dy: 0, h: sheet.offsetHeight };
      stopAll(sheet); if (scrim) stopAll(scrim);
    }, { passive: true });
    sheet.addEventListener("touchmove", event => {
      if (!start) return;
      const y = event.touches[0].clientY;
      start.dy = Math.max(-12, y - start.y);
      const resist = start.dy < 0 ? start.dy * 0.35 : start.dy;
      sheet.style.transform = `translate3d(0,${resist}px,0)`;
      if (scrim) scrim.style.opacity = String(Math.max(0, 1 - Math.max(0, start.dy) / start.h));
      start.velocity = (y - start.last) / Math.max(1, performance.now() - start.lastT);
      start.last = y; start.lastT = performance.now();
    }, { passive: true });
    const end = () => {
      if (!start) return;
      const state = start; start = null;
      if (state.dy > state.h * 0.28 || (state.velocity || 0) > 0.55) {
        if (window.M3X_UI && window.M3X_UI.closeDialog) window.M3X_UI.closeDialog(dialog); else dialog.close();
        return;
      }
      const from = currentOffset(sheet);
      sheet.style.transform = ""; const opacity = scrim ? Number(scrim.style.opacity || 1) : 1; if (scrim) scrim.style.opacity = "";
      settle(run(sheet, [{ transform: `translate3d(0,${from}px,0)` }, { transform: "none" }], { duration: 420, easing: EASE.spring }));
      if (scrim) settle(run(scrim, [{ opacity }, { opacity: 1 }], { duration: 260, easing: EASE.out }));
    };
    sheet.addEventListener("touchend", end, { passive: true });
    sheet.addEventListener("touchcancel", end, { passive: true });
  }

  /* ---------- toast ---------- */
  let toastTimer = 0;
  function toast(element, message, hold = 2400) {
    clearTimeout(toastTimer);
    const wasVisible = !element.hidden && !element.classList.contains("is-hiding");
    element.textContent = message;
    element.classList.remove("is-hiding");
    element.hidden = false;
    stopAll(element);
    if (enabled) {
      if (wasVisible) settle(run(element, [{ transform: "translateX(-50%) scale(.96)" }, { transform: "translateX(-50%)" }], { duration: 320, easing: EASE.bounce }));
      else settle(run(element, [{ opacity: 0, transform: "translateX(-50%) translate3d(0,16px,0) scale(.94)" }, { opacity: 1, transform: "translateX(-50%)" }], { duration: 420, easing: EASE.spring }));
    }
    toastTimer = setTimeout(() => {
      if (!enabled || document.hidden) { element.hidden = true; return; }
      element.classList.add("is-hiding");
      const out = run(element, [{ opacity: 1, transform: "translateX(-50%)" }, { opacity: 0, transform: "translateX(-50%) translate3d(0,8px,0) scale(.97)" }], { duration: 200, easing: EASE.accel, fill: "forwards" });
      const done = () => { if (!element.classList.contains("is-hiding")) return; element.classList.remove("is-hiding"); element.hidden = true; stopAll(element); };
      if (out) out.addEventListener("finish", done); else done();
    }, hold);
  }

  /* ---------- list entrances ---------- */
  const listContexts = new WeakMap();
  function listEnter(list, context) {
    const previous = listContexts.get(list);
    listContexts.set(list, context);
    if (!enabled || !visible(list) || (previous === undefined && performance.now() < bootedAt)) return;
    const items = [...list.children].filter(item => !item.matches(".m3x-tab-indicator")).slice(0, 14);
    if (previous === context) {
      // Same view, new result set (search typing): a quiet fade, no choreography.
      items.forEach(item => settle(run(item, [{ opacity: .35 }, { opacity: 1 }], { duration: 180, easing: EASE.out }, "list")));
      return;
    }
    const grid = list.closest("[data-library-view=grid]") && (list.matches("[data-list=library], #library-groups"));
    items.forEach((item, index) => {
      const step = grid ? (index >> 1) * 42 + (index & 1) * 18 : index * 24;
      settle(run(item, [{ opacity: 0, transform: `translate3d(0,${grid ? 22 : 16}px,0)` }, { opacity: 1, transform: "none" }],
        { duration: grid ? 480 : 420, delay: Math.min(step, 320), easing: EASE.emph, fill: "backwards" }, "list"));
    });
  }

  /* ---------- playback progress (compositor transforms, no per-frame JS) ---------- */
  const progressState = new WeakMap();
  function progress(position, duration, playing, dragging) {
    const p = duration > 0 ? Math.max(0, Math.min(1, position / duration)) : 0;
    screen.querySelectorAll(".m3x-seek, .m3x-mini-progress").forEach(host => {
      const fill = host.querySelector(".m3x-seek-fill, .m3x-mini-progress-fill");
      const rail = host.querySelector(".m3x-seek-rail");
      if (!fill) return;
      const last = progressState.get(host);
      // Nothing to show on a hidden page; on return the bar lands in place instead of sliding from a stale position.
      if (!visible(host)) { if (last) last.stale = true; else progressState.set(host, { p: -1, duration: 0, mode: "none", stale: true }); return; }
      const jump = !last || Math.abs(p - last.p) * Math.max(1, duration) > 1.6 || last.duration !== duration;
      const smooth = enabled && !dragging && !(last && last.stale);
      const mode = !smooth ? "none" : jump ? "jump" : playing && host.classList.contains("m3x-seek") ? "tick" : "none";
      if (last && Math.abs(last.p - p) < 0.00005 && last.mode === mode) return;
      progressState.set(host, { p, duration, mode });
      const transition = mode === "jump" ? "transform 460ms " + EASE.soft : mode === "tick" ? "transform 420ms linear" : "none";
      fill.style.transition = transition; fill.style.transform = `scaleX(${p})`;
      if (rail) { rail.style.transition = transition; rail.style.transform = `translate3d(${(p - 1) * 100}%,0,0)`; }
      host.classList.toggle("is-dragging", !!dragging);
    });
  }

  /* ---------- lyrics follow (bounded main-thread scroll, cancellable) ---------- */
  function cancelScroll() { cancelAnimationFrame(lyricFrame); lyricFrame = 0; }
  function centerLyric(line, immediate = false) {
    cancelScroll();
    if (!line || !visible(line) || performance.now() < lyricHoldUntil) return;
    const box = document.getElementById("lyrics-lines");
    const bounds = box.getBoundingClientRect(), row = line.getBoundingClientRect();
    const from = box.scrollTop;
    const to = Math.max(0, Math.min(box.scrollHeight - box.clientHeight, from + row.top - bounds.top - (box.clientHeight - row.height) / 2));
    if (reduced() || immediate || Math.abs(to - from) < 2) { box.scrollTop = to; return; }
    const started = performance.now(), length = Math.min(620, 360 + Math.abs(to - from) * 0.4);
    function step(now) {
      if (!visible(line)) { cancelScroll(); return; }
      const t = Math.min(1, (now - started) / length);
      box.scrollTop = from + (to - from) * (1 - Math.pow(1 - t, 4));
      lyricFrame = t < 1 ? requestAnimationFrame(step) : 0;
    }
    lyricFrame = requestAnimationFrame(step);
  }
  const lyrics = document.getElementById("lyrics-lines");
  const hold = () => { lyricHoldUntil = performance.now() + 2500; cancelScroll(); };
  ["touchstart", "touchmove", "touchend", "wheel"].forEach(name => lyrics.addEventListener(name, hold, { passive: true }));
  lyrics.addEventListener("keydown", hold);

  /* ---------- theme switches: snapshot cross-fade on the compositor ---------- */
  function themeChange(update) {
    if (!enabled || typeof document.startViewTransition !== "function" || document.hidden) { update(); return; }
    screen.classList.add("is-theme-switching");
    let transition;
    try { transition = document.startViewTransition(() => { update(); }); }
    catch (_) { screen.classList.remove("is-theme-switching"); update(); return; }
    const done = () => { screen.classList.remove("is-theme-switching"); indicators(true); };
    transition.finished.then(done, done);
  }

  /* ---------- per-render orchestration ---------- */
  function render(state, track) {
    configure(state.reduceMotion);
    const page = state.page, current = section(page);
    screen.dataset.page = page;
    nav.style.setProperty("--m3x-nav-index", String(Math.max(0, top().indexOf(current))));
    if (firstRender) {
      firstRender = false; bootedAt = performance.now() + 600;
      warmLater(1200);
      requestAnimationFrame(() => requestAnimationFrame(() => { navIndicator.style.transition = ""; }));
      indicators(true);
    }
    if (previousPage !== null && previousPage !== page) {
      pageTransition(previousPage, page);
      if (!enabled) requestAnimationFrame(() => panel(page) && panel(page).querySelectorAll(INDICATOR_HOSTS).forEach(host => indicator(host, true)));
    }
    const id = track && track.id;
    if (previousTrack && id && previousTrack !== id) songChanged();
    else if (!previousTrack && id) backdrop(true);
    previousPage = page; previousTrack = id || null;
  }

  // 伪息屏: fade up from black, the cover settling into place.
  const ambient = document.getElementById("ambient-display");
  if (ambient) new MutationObserver(() => {
    if (ambient.hidden || !enabled) return;
    settle(run(ambient, [{ opacity: 0 }, { opacity: 1 }], { duration: 420, easing: EASE.out }));
    const art = document.getElementById("ambient-cover");
    if (art) settle(run(art, [{ opacity: 0, transform: "scale(.96)" }, { opacity: 1, transform: "none" }], { duration: 640, delay: 80, easing: EASE.emph, fill: "backwards" }));
  }).observe(ambient, { attributes: true, attributeFilter: ["hidden"] });

  document.addEventListener("visibilitychange", () => { if (document.hidden) cancelAll(); });
  if (media.addEventListener) media.addEventListener("change", () => configure(preference)); else if (media.addListener) media.addListener(() => configure(preference));
  window.M3X_MOTION = { render, configure, cover, icon, pop, centerLyric, cancelScroll, reduced, sheetIn, sheetOut, toast, listEnter, progress, themeChange, indicators, recolor, coolPages, ease: EASE };
})();
