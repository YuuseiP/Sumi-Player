(() => {
  "use strict";
  const root = document.getElementById("m3x-concept");
  const screen = root.querySelector(".m3x-screen");
  const nav = screen.querySelector(".m3x-nav");
  const media = window.matchMedia("(prefers-reduced-motion: reduce)");
  const animations = new Map();
  const covers = new WeakMap();
  const ease = "cubic-bezier(.2,.8,.2,1)";
  let enabled = false, preference = false, previousPage = null, previousTrack = null, direction = 1;
  let lyricFrame = 0, lyricHoldUntil = 0;
  const sections = {settings:"profile", statistics:"profile", favorites:"profile", playlists:"profile", playlist:"profile", queue:"player", lyrics:"player", "create-library":"library", "netease-list":"netease"};
  const order = () => screen.dataset.source === "netease" ? ["netease","player","netease-me"] : ["library","player","profile"];
  const section = page => { const value = page === "netease-list" ? (window.M3X_SOURCE?.listSection?.() || "netease") : sections[page] || page; return screen.dataset.source === "netease" ? ({profile:"netease-me", library:"netease"})[value] || value : value; };
  const visible = element => element && !document.hidden && !element.closest("[hidden]");
  function stop(element) {
    const animation = animations.get(element);
    if (animation) { animation.cancel(); animations.delete(element); }
  }
  function cancelScroll() { cancelAnimationFrame(lyricFrame); lyricFrame = 0; }
  function cancelAll() { animations.forEach(animation => animation.cancel()); animations.clear(); cancelScroll(); }
  function configure(reduced) {
    preference = !!reduced;
    const reduce = preference || media.matches;
    screen.dataset.motionReduced = String(reduce);
    enabled = !reduce && screen.dataset.visualTheme === "endfield";
    screen.dataset.motion = enabled ? "endfield" : "off";
    if (!enabled) cancelAll();
  }
  function animate(element, frames, duration = 180) {
    if (!element) return;
    stop(element);
    if (!enabled || !visible(element) || typeof element.animate !== "function") return;
    const animation = element.animate(frames, {duration, easing:ease});
    animations.set(element, animation);
    animation.onfinish = () => { if (animations.get(element) === animation) { animation.cancel(); animations.delete(element); } };
  }
  function enter(element, x = 0, y = 0, duration = 180) {
    animate(element, [{opacity:.65, transform:`translate(${x}px,${y}px)`}, {opacity:1, transform:"translate(0,0)"}], duration);
  }
  function icon(button) {
    if (button) enter(button.querySelector("svg"), 0, 2, 100);
  }
  function render(state, track) {
    configure(state.reduceMotion);
    const page = state.page, currentSection = section(page);
    nav.style.setProperty("--m3x-nav-position", String(Math.max(0, order().indexOf(currentSection)) * 100) + "%");
    if (previousPage !== null && previousPage !== page) {
      cancelAll();
      const before = section(previousPage), sequence = order();
      const back = page === before || (previousPage === "playlist" && page === "playlists");
      const sign = before !== currentSection ? (sequence.indexOf(currentSection) > sequence.indexOf(before) ? 1 : -1) : back ? -1 : 1;
      enter(screen.querySelector('[data-panel="' + page + '"]'), sign * 10);
    } else if (previousTrack && track && previousTrack !== track.id && page === "player") {
      enter(screen.querySelector(".m3x-song"), direction * 8);
    }
    previousPage = page; previousTrack = track && track.id;
  }
  // Preload only the requested image; a late load must never restore an old cover.
  function cover(element, source) {
    if (covers.get(element) === source) return;
    covers.set(element, source);
    if (!enabled || element.getAttribute("src") === source) { element.src = source; return; }
    const incoming = new Image();
    const finish = failed => {
      if (covers.get(element) !== source) return;
      element.src = failed ? "cover-placeholder.svg" : source;
      enter(element, direction * 6, 0, 160);
    };
    incoming.onload = () => finish(false);
    incoming.onerror = () => finish(true);
    incoming.src = source;
  }
  function centerLyric(line, immediate = false) {
    cancelScroll();
    if (!line || !visible(line) || performance.now() < lyricHoldUntil) return;
    const box = document.getElementById("lyrics-lines");
    const bounds = box.getBoundingClientRect(), row = line.getBoundingClientRect();
    const from = box.scrollTop;
    const to = Math.max(0, Math.min(box.scrollHeight - box.clientHeight, from + row.top - bounds.top - (box.clientHeight - row.height) / 2));
    if (preference || media.matches || immediate || Math.abs(to - from) < 2) { box.scrollTop = to; return; }
    const started = performance.now();
    function step(now) {
      if (!visible(line)) { cancelScroll(); return; }
      const t = preference || media.matches ? 1 : Math.min(1, (now - started) / 400);
      box.scrollTop = from + (to - from) * (1 - Math.pow(1 - t, 3));
      if (t < 1) lyricFrame = requestAnimationFrame(step); else lyricFrame = 0;
    }
    lyricFrame = requestAnimationFrame(step);
  }
  root.addEventListener("click", event => {
    const button = event.target.closest("button");
    if (!button || button.disabled) return;
    direction = button.dataset.action === "previous" ? -1 : 1;
    if (!button.dataset.page && !button.closest("#lyrics-lines")) {
      requestAnimationFrame(() => icon(button));
    }
  }, true);
  const lyrics = document.getElementById("lyrics-lines");
  const hold = () => { lyricHoldUntil = performance.now() + 2500; cancelScroll(); };
  ["touchstart", "touchmove", "touchend", "wheel"].forEach(name => lyrics.addEventListener(name, hold, {passive:true}));
  lyrics.addEventListener("keydown", hold);
  document.addEventListener("visibilitychange", () => { if (document.hidden) cancelAll(); });
  if (media.addListener) media.addListener(() => configure(preference));
  window.M3X_MOTION = {render, configure, cover, icon, centerLyric, cancelScroll, reduced:() => preference || media.matches};
})();
