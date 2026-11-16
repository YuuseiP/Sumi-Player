(function (host) {
  "use strict";
  const filters = { artist: "artists", character: "characters", group: "groups" };
  const labels = { artist: "歌手", character: "角色", group: "组合" };
  function values(track, filter, classified) {
    const kind = { artists: "artist", characters: "character", groups: "group" }[filter];
    if (classified && kind) return (track.credits || []).filter(item => item.kind === kind).map(item => item.id);
    return [filter === "albums" ? track.album : filter === "artists" ? track.artist : track.folder || "."];
  }
  function destinations(track, type, classified) {
    if (!track) return [];
    if (type === "album") return typeof track.album === "string" && track.album.trim() ?
      [{ filter: "albums", key: track.album, name: track.album, label: "专辑" }] : [];
    if (!classified) return typeof track.artist === "string" && track.artist.trim() ?
      [{ filter: "artists", key: track.artist, name: track.artist, label: "歌手" }] : [];
    const seen = new Set();
    // Built-in credits can name performers, characters or groups. Use their actual category IDs.
    return (track.credits || []).filter(item => {
      if (!filters[item.kind] || !item.id || seen.has(item.id)) return false;
      seen.add(item.id); return true;
    }).map(item => ({ filter: filters[item.kind], key: item.id, name: item.name, label: labels[item.kind] }));
  }
  const api = { values, destinations };
  if (typeof module === "object" && module.exports) module.exports = api;
  else host.M3X_LIBRARY_NAVIGATION = api;
})(typeof window === "object" ? window : globalThis);
