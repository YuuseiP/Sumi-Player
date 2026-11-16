(() => {
  "use strict";
  const ids = ["backing", "solo-explicit", "solo-named", "group-named", "short", "piano"];
  function normalize(value = {}) {
    const strings = key => [...new Set((Array.isArray(value[key]) ? value[key] : []).filter(id => typeof id === "string"))];
    return { schema: 1, enabledCategories: strings("enabledCategories").filter(id => ids.includes(id)),
      keepTrackIds: strings("keepTrackIds"), excludeTrackIds: strings("excludeTrackIds"), hideInLibrary: value.hideInLibrary === true };
  }
  function excludes(track, settings) {
    if (settings.keepTrackIds.includes(track.id)) return false;
    return settings.excludeTrackIds.includes(track.id) || (track.preference?.categories || []).some(id => settings.enabledCategories.includes(id));
  }
  function override(settings, id, mode) {
    const next = normalize(settings);
    next.keepTrackIds = next.keepTrackIds.filter(item => item !== id);
    next.excludeTrackIds = next.excludeTrackIds.filter(item => item !== id);
    if (mode === "keep") next.keepTrackIds.push(id);
    if (mode === "exclude") next.excludeTrackIds.push(id);
    return next;
  }
  function mount(options) {
    const { root, getSettings, getLibrary, commit, openDialog } = options;
    const panel = document.getElementById("preferences-panel");
    const preview = document.getElementById("preference-preview-dialog");
    const list = document.getElementById("preference-preview-list");
    let tab = "general", category = "all", query = "", limit = 30, cacheKey = "", rowKey = "", renderedLibrary = null;
    const labels = () => new Map((getLibrary()?.preferenceRules?.categories || []).map(item => [item.id, item.label]));
    const node = (tag, text, className) => {
      const element = document.createElement(tag);
      if (text !== undefined) element.textContent = text;
      if (className) element.className = className;
      return element;
    };
    function renderPreview() {
      if (!preview.open) return;
      const settings = getSettings(), tracks = getLibrary()?.tracks || [], names = labels();
      const filtered = tracks.filter(track => {
        const info = track.preference || {}, isOverride = settings.keepTrackIds.includes(track.id) || settings.excludeTrackIds.includes(track.id);
        const included = category === "all" ? (info.categories || []).length || info.needsReview || isOverride :
          category === "exceptions" ? isOverride : category === "review" ? info.needsReview : (info.categories || []).includes(category);
        return included && (!query || [track.title, track.artist, track.album, track.filename].join(" ").toLocaleLowerCase().includes(query));
      });
      document.getElementById("preference-preview-count").textContent = `${filtered.length} 首 · 已显示 ${Math.min(limit, filtered.length)} 首`;
      const key = JSON.stringify([filtered.map(track => track.id), settings, category, query, limit]);
      if (rowKey === key) return;
      rowKey = key;
      const scrollTop = list.scrollTop; list.replaceChildren();
      filtered.slice(0, limit).forEach(track => {
        const row = node("article", undefined, "m3x-preference-song");
        row.append(node("strong", track.title), node("small", track.artist + " · " + track.album));
        const found = (track.preference?.categories || []).map(id => names.get(id) || id);
        row.append(node("p", (excludes(track, settings) ? "已排除" : "可播放") + (found.length ? " · " + found.join("、") : ""), "m3x-caption"));
        if (track.preference?.needsReview) row.append(node("p", "文件名与歌曲名的版本标记不一致，请核对。", "m3x-caption"));
        const evidence = [...new Set((track.preference?.reasons || []).map(hit => (hit.field === "title" ? "歌曲名：" : "文件名：") + hit.evidence))];
        if (evidence.length) row.append(node("small", evidence.join("；")));
        const actions = node("div", undefined, "m3x-preference-actions");
        [["keep", "始终保留"], ["exclude", "单独排除"], ["default", "跟随规则"]].forEach(([mode, label]) => {
          const button = node("button", label, "m3x-text-button"); button.type = "button";
          button.dataset.preferenceId = track.id; button.dataset.preferenceMode = mode;
          button.setAttribute("aria-pressed", String(mode === "keep" ? settings.keepTrackIds.includes(track.id) : mode === "exclude" ? settings.excludeTrackIds.includes(track.id) : !settings.keepTrackIds.includes(track.id) && !settings.excludeTrackIds.includes(track.id)));
          actions.append(button);
        });
        row.append(actions); list.append(row);
      });
      if (!filtered.length) list.append(node("p", "没有符合条件的歌曲", "m3x-empty"));
      document.getElementById("preference-more").hidden = filtered.length <= limit;
      list.scrollTop = scrollTop;
    }
    function render() {
      const library = getLibrary(), tracks = library?.tracks || [], settings = getSettings();
      if (renderedLibrary !== library) { renderedLibrary = library; cacheKey = ""; rowKey = ""; }
      const categories = library?.preferenceRules?.categories || [];
      const key = JSON.stringify([tracks.map(track => track.id), categories, settings]);
      if (cacheKey !== key) {
        cacheKey = key;
        const rules = document.getElementById("preference-rules"); rules.replaceChildren();
        categories.forEach(item => {
          const count = tracks.filter(track => track.preference?.categories?.includes(item.id)).length;
          const row = node("div", undefined, "m3x-preference-rule");
          const label = node("label", undefined, "m3x-setting"), info = node("span");
          info.append(node("strong", item.label), node("small", item.description));
          const input = document.createElement("input"); input.type = "checkbox"; input.dataset.preferenceCategory = item.id;
          input.checked = settings.enabledCategories.includes(item.id); input.setAttribute("aria-label", "排除" + item.label);
          label.append(info, input);
          const button = node("button", `查看 ${count} 首命中歌曲`, "m3x-text-button"); button.type = "button"; button.dataset.preferencePreview = item.id;
          row.append(label, button); rules.append(row);
        });
        document.getElementById("preference-summary").textContent = `当前曲库 ${tracks.length} 首 · 已排除 ${tracks.filter(track => excludes(track, settings)).length} 首`;
        document.getElementById("preference-hide").checked = settings.hideInLibrary;
      }
      renderPreview();
    }
    function selectTab(value, focus = false) {
      tab = value;
      root.querySelectorAll("[data-settings-tab]").forEach(button => {
        const selected = button.dataset.settingsTab === tab;
        button.setAttribute("aria-selected", String(selected)); button.tabIndex = selected ? 0 : -1;
        if (selected && focus) button.focus();
      });
      document.getElementById("general-settings-panel").hidden = tab !== "general";
      panel.hidden = tab !== "preferences";
      if (tab === "preferences") render();
    }
    root.addEventListener("click", event => {
      const button = event.target.closest("button"); if (!button) return;
      if (button.dataset.settingsTab) selectTab(button.dataset.settingsTab);
      if (button.dataset.preferencePreview) {
        category = button.dataset.preferencePreview; query = ""; limit = 30;
        document.getElementById("preference-search").value = "";
        document.getElementById("preference-preview-title").textContent = category === "all" ? "命中歌曲与逐首例外" : category === "exceptions" ? "逐首例外" : category === "review" ? "待核对歌曲" : labels().get(category);
        openDialog(preview); list.scrollTop = 0; renderPreview();
      }
      if (button.dataset.preferenceId) { commit(override(getSettings(), button.dataset.preferenceId, button.dataset.preferenceMode)); render(); }
      if (button.id === "preference-more") { limit += 30; renderPreview(); }
    });
    root.addEventListener("change", event => {
      const input = event.target, settings = normalize(getSettings());
      if (input.dataset.preferenceCategory) {
        settings.enabledCategories = settings.enabledCategories.filter(id => id !== input.dataset.preferenceCategory);
        if (input.checked) settings.enabledCategories.push(input.dataset.preferenceCategory);
        commit(settings); render();
      } else if (input.id === "preference-hide") { settings.hideInLibrary = input.checked; commit(settings); render(); }
    });
    root.querySelector("[data-settings-tabs]").addEventListener("keydown", event => {
      if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
      event.preventDefault(); selectTab(event.key === "Home" ? "general" : event.key === "End" ? "preferences" : tab === "general" ? "preferences" : "general", true);
    });
    document.getElementById("preference-search").addEventListener("input", event => { query = event.target.value.trim().toLocaleLowerCase(); limit = 30; renderPreview(); });
    window.addEventListener("m3x-render", () => { if (tab === "preferences" || preview.open) render(); });
    selectTab("general");
    return { render, selectTab };
  }
  const api = { normalize, excludes, override, mount };
  if (typeof module !== "undefined") module.exports = api;
  else window.M3X_PREFERENCES = api;
})();
