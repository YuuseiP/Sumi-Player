(() => {
  function initialize() {
    const ui = window.M3X_UI, layer = document.getElementById("ambient-display");
    const screen = layer.parentElement, cover = document.getElementById("ambient-cover");
    const content = document.getElementById("ambient-content");
    const source = document.getElementById("lyrics-lines"), viewport = document.getElementById("ambient-lyrics");
    const list = document.getElementById("ambient-lines"), controls = document.getElementById("ambient-controls");
    const audio = document.getElementById("audio"), seek = document.getElementById("ambient-seek");
    let enabled = false, frame = 0, dirty = true, focusBefore, seeking = false;
    let scrollFrame = 0, selectedBefore = -2, holdUntil = 0, holdTimer = 0;
    const reduced = () => window.M3X_MOTION?.reduced() || false;
    function cancelScroll() { cancelAnimationFrame(scrollFrame); scrollFrame = 0; }
    function center(immediate = false) {
      cancelScroll();
      if (!enabled || document.hidden || (!immediate && performance.now() < holdUntil)) return;
      const current = list.children[Math.max(0, selectedBefore)];
      if (!current) return;
      const from = viewport.scrollTop;
      const target = current.offsetTop - list.offsetTop - (viewport.clientHeight - current.offsetHeight) / 2;
      const to = Math.max(0, Math.min(viewport.scrollHeight - viewport.clientHeight, target));
      if (immediate || reduced() || Math.abs(to - from) < 2) { viewport.scrollTop = to; return; }
      const start = performance.now();
      function step(now) {
        if (!enabled || document.hidden) { cancelScroll(); return; }
        const t = reduced() ? 1 : Math.min(1, (now - start) / 440);
        viewport.scrollTop = from + (to - from) * (1 - Math.pow(1 - t, 3));
        if (t < 1) scrollFrame = requestAnimationFrame(step); else scrollFrame = 0;
      }
      scrollFrame = requestAnimationFrame(step);
    }
    function native(enabled, orientation = "auto") {
      if (!window.nativeCommand) return false;
      const result = nativeCommand("ambient-display", { enabled, orientation });
      if (result?.error) { ui.toast(result.error); return false; }
      if (enabled) {
        document.getElementById("ambient-rotate").hidden = !!result.automaticRotation;
        document.getElementById("ambient-hint").textContent = result.automaticRotation
          ? "随设备姿态旋转 · 点击空白处隐藏" : "设备无方向传感器，可点旋转 · 点击空白处隐藏";
      }
      return true;
    }
    function layout() {
      if (!enabled) return;
      // Fix the content height before sizing its square child: old WebView flex
      // layout otherwise lets the previous orientation's cover push controls out.
      const height = Math.max(0, layer.clientHeight - (controls.hidden ? 0 : controls.offsetHeight));
      content.style.height = height + "px";
      const size = Math.floor(innerWidth > innerHeight
        ? Math.min(height, Math.max(0, content.clientWidth - 160))
        : Math.min(content.clientWidth, Math.max(0, height - 80)));
      cover.style.width = size + "px"; cover.style.height = size + "px";
      sync(true);
    }
    function sync(immediate = false) {
      if (!enabled) return;
      const track = ui.currentTrack(), matches = source.dataset.trackId === (track?.id || "");
      const rebuilt = dirty;
      if (dirty) {
        cancelScroll();
        list.replaceChildren();
        if (matches) Array.from(source.children).forEach(node => {
          const line = document.createElement("p");
          if (node.tagName !== "BUTTON") { line.className = "ambient-plain"; line.textContent = node.textContent; }
          else Array.from(node.children).forEach(part => {
            const span = document.createElement("span"); span.className = part.className; span.textContent = part.textContent; line.append(span);
          });
          if (node.tagName === "BUTTON" && !node.children.length) line.textContent = node.textContent;
          list.append(line);
        });
        else { const line = document.createElement("p"); line.className = "ambient-plain"; line.textContent = track ? "正在读取歌词…" : "暂无播放歌曲"; list.append(line); }
        dirty = false;
      }
      const selected = matches ? Array.from(source.children).findIndex(node => node.getAttribute("aria-current") === "true") : -1;
      if (!rebuilt && !immediate && selected === selectedBefore) return;
      selectedBefore = selected;
      Array.from(list.children).forEach((node, index) => {
        node.setAttribute("aria-current", String(index === selected));
        node.dataset.distance = selected < 0 || Math.abs(index - selected) <= 1 ? "near" : "far";
      });
      const current = list.children[Math.max(0, selected)];
      if (current?.className === "ambient-plain" && current.offsetHeight > viewport.clientHeight) {
        list.style.paddingTop = list.style.paddingBottom = "0px";
        viewport.scrollTop = 0; return;
      }
      list.style.paddingTop = list.style.paddingBottom = Math.max(0, viewport.clientHeight / 2 - (current?.offsetHeight || 40) / 2) + "px";
      center(immediate || rebuilt);
    }
    function schedule(rebuild) {
      dirty = dirty || rebuild;
      if (!enabled || frame) return;
      frame = requestAnimationFrame(() => { frame = 0; sync(); });
    }
    new MutationObserver(records => schedule(records.some(record => record.type !== "attributes" || record.attributeName === "data-track-id")))
      .observe(source, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ["aria-current", "data-track-id"] });
    function trackChanged() {
      if (!enabled) { dirty = true; return; }
      const track = ui.currentTrack(); cover.src = track?.cover || "cover-placeholder.svg";
      cover.alt = track?.album ? track.album + " 专辑封面" : "专辑封面";
      schedule(true);
    }
    function showControls(show) {
      controls.hidden = !show;
      if (!show) { seeking = false; layer.focus(); }
      layout();
    }
    function playback() {
      if (!enabled) return;
      const duration = Number.isFinite(audio.duration) ? Math.max(0, audio.duration) : 0;
      const position = Math.max(0, Number(audio.currentTime) || 0);
      const time = value => Math.floor(value / 60) + ":" + String(Math.floor(value % 60)).padStart(2, "0");
      document.getElementById("ambient-position").textContent = time(position);
      document.getElementById("ambient-duration").textContent = time(duration);
      seek.max = duration || 1; seek.disabled = duration <= 0;
      if (!seeking) seek.value = position;
      controls.querySelectorAll("[data-ambient-action]").forEach(button => {
        const original = document.querySelector('.m3x-transport [data-action="' + button.dataset.ambientAction + '"]');
        if (original && button.innerHTML !== original.innerHTML) button.innerHTML = original.innerHTML;
        if (button.dataset.ambientAction === "play") button.setAttribute("aria-label", audio.paused ? "播放" : "暂停");
      });
    }
    function exit() {
      if (!enabled) return false;
      native(false); enabled = false;
      cancelAnimationFrame(frame); frame = 0; seeking = false;
      cancelScroll(); clearTimeout(holdTimer); holdUntil = 0;
      layer.hidden = true; delete screen.dataset.ambient; controls.hidden = true;
      if (focusBefore && document.documentElement.contains(focusBefore)) focusBefore.focus();
      window.dispatchEvent(new Event("m3x-ambient-exit"));
      return true;
    }
    function enter() {
      if (enabled) return;
      if (!ui.currentTrack()) { ui.toast("请先选择歌曲"); return; }
      if (!window.nativeCommand) { ui.toast("伪息屏需要在 M3X 应用中使用"); return; }
      focusBefore = document.activeElement;
      Array.from(document.querySelectorAll("dialog[open]")).forEach(dialog => ui.closeDialog(dialog, true));
      if (!native(true)) return;
      enabled = true; screen.dataset.ambient = "true"; layer.hidden = false;
      dirty = true; holdUntil = 0; trackChanged(); layer.focus(); playback(); showControls(true);
    }
    layer.addEventListener("click", event => {
      event.stopPropagation();
      if (event.target.closest("#ambient-exit")) { exit(); return; }
      const button = event.target.closest("[data-ambient-action]");
      if (button) {
        document.querySelector('.m3x-transport [data-action="' + button.dataset.ambientAction + '"]').click(); playback(); return;
      }
      if (event.target.closest("#ambient-rotate")) { native(true, innerWidth > innerHeight ? "portrait" : "landscape"); return; }
      if (event.target.closest("#ambient-controls")) return;
      showControls(controls.hidden);
    });
    seek.addEventListener("input", () => { seeking = true; });
    seek.addEventListener("change", () => { if (!seek.disabled) audio.currentTime = Number(seek.value); seeking = false; playback(); });
    seek.addEventListener("touchcancel", () => { seeking = false; playback(); });
    const hold = () => {
      cancelScroll(); clearTimeout(holdTimer); holdUntil = performance.now() + 2500;
      holdTimer = setTimeout(() => center(), 2550);
    };
    for (const name of ["touchstart", "touchmove", "touchend", "wheel"]) viewport.addEventListener(name, hold, { passive: true });
    document.addEventListener("visibilitychange", () => {
      if (document.hidden) { cancelScroll(); clearTimeout(holdTimer); }
      else if (enabled) sync(true);
    });
    for (const name of ["timeupdate", "play", "pause", "loadedmetadata"]) audio.addEventListener(name, playback);
    window.addEventListener("m3x-native-state", playback);
    window.addEventListener("m3x-render", playback);
    layer.addEventListener("keydown", event => { if (event.key === "Escape") { event.preventDefault(); exit(); } });
    cover.addEventListener("error", () => { if (!cover.src.endsWith("cover-placeholder.svg")) cover.src = "cover-placeholder.svg"; });
    window.addEventListener("resize", layout);
    window.addEventListener("m3x-track", trackChanged);
    window.addEventListener("pagehide", exit);
    window.M3X_AMBIENT = { enter, exit, active: () => enabled };
  }
  if (window.M3X_UI) initialize(); else window.addEventListener("m3x-ready", initialize, { once: true });
})();
