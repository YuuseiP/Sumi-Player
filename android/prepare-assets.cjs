const fs = require("node:fs");
const path = require("node:path");
const babel = require("@babel/core");
const { compileClassification } = require("./prepare-classification.cjs");
const { compilePreferences } = require("./prepare-preferences.cjs");
const root = path.resolve(__dirname, "ui");
const output = path.join(__dirname, "app/src/main/assets/player");
const version = fs.readFileSync(path.join(__dirname, "app/build.gradle"), "utf8").match(/versionName '([^']+)'/)[1];
fs.mkdirSync(output, { recursive: true });
const baselineFile = path.resolve(__dirname, "../artist-classification/semantic/library-preview.json");
const draftFile = path.join(root, ".classification-draft.json");
const classification = compileClassification(fs.readFileSync(baselineFile), fs.existsSync(draftFile) ? JSON.parse(fs.readFileSync(draftFile, "utf8")) : null);
fs.writeFileSync(path.join(output, "artist-classification.json"), JSON.stringify(classification));
fs.writeFileSync(path.join(output, "preference-rules.json"), JSON.stringify(compilePreferences()));
const polyfills = ["es.promise", "es.array.from", "es.array.find", "es.array.find-index", "es.array.includes", "es.array.flat", "es.array.flat-map",
  "es.array.iterator", "es.string.iterator", "es.object.assign", "es.object.entries", "es.object.values", "es.object.from-entries",
  "es.object.has-own", "es.string.pad-start", "es.string.pad-end", "es.string.match-all", "es.promise.all-settled", "es.global-this"];
let prelude = "";
const seen = new Set();
// Bundle only the ES polyfills required by the Android 7.1 system WebView.
function moduleSource(file) {
  file = require.resolve(file);
  if (seen.has(file)) return;
  seen.add(file);
  const source = fs.readFileSync(file, "utf8");
  for (const match of source.matchAll(/require\(['"](.+?)['"]\)/g)) moduleSource(require.resolve(match[1], { paths: [path.dirname(file)] }));
}
for (const feature of polyfills) moduleSource("core-js/modules/" + feature);
const ids = new Map([...seen].map((file, index) => [file, index]));
prelude = "(function(){var modules={";
for (const [file, index] of ids) {
  const source = fs.readFileSync(file, "utf8").replace(/require\(['"](.+?)['"]\)/g, (_, name) => `require(${ids.get(require.resolve(name, { paths: [path.dirname(file)] }))})`);
  prelude += index + ":function(module,exports,require){" + source + "},";
}
prelude += "},cache={};function require(id){if(cache[id])return cache[id].exports;var m=cache[id]={exports:{}};modules[id](m,m.exports,require);return m.exports;}";
for (const feature of polyfills) prelude += `require(${ids.get(require.resolve("core-js/modules/" + feature))});`;
prelude += "})();";
fs.writeFileSync(path.join(output, "polyfills.js"), prelude);
for (const name of ["appearance.js", "motion.js", "preferences.js", "library-navigation.js", "app.js", "avatar.js", "player-tools.js", "ambient.js", "native-bridge.js", "native-tools.js", "netease.js"]) {
  const filename = name.startsWith("native-") || name === "netease.js" ? path.join(__dirname, name) : path.join(root, name);
  const result = babel.transformSync(fs.readFileSync(filename, "utf8"), {
    presets: [["@babel/preset-env", { targets: { chrome: "51" }, modules: false }]],
    comments: false, sourceType: "script", compact: false
  });
  fs.writeFileSync(path.join(output, name), result.code);
}
let html = fs.readFileSync(path.join(root, "index.html"), "utf8")
  .replace('<link rel="icon" href="covers/01.jpg">', "")
  .replace('<span class="m3x-caption">0.1</span>', `<span class="m3x-caption">${version}</span>`)
  .replace('<script defer src="classification.js"></script>', "")
  .replace('浏览器原生音频', 'ExoPlayer · AudioTrack')
  .replace('<div class="m3x-feedback" id="playback-status" role="status" hidden></div>',
    '<div class="m3x-feedback" id="playback-status" role="status">正在载入本地曲库…</div>')
  .replace('<script defer src="tracks.js"></script>', '<script defer src="polyfills.js"></script><script defer src="native-bridge.js"></script><script>window.M3X_TRACKS=[];</script>')
  .replace('<script defer src="player-tools.js"></script>', '<script defer src="player-tools.js"></script><script defer src="native-tools.js"></script>');
fs.writeFileSync(path.join(output, "index.html"), html);
let css = fs.readFileSync(path.join(root, "styles.css"), "utf8");
// The device WebView is Chromium 119: styles.css is authored for it directly
// (registered colour tokens, linear() springs, content-visibility). The APK
// page is always full-screen, so only the browser-preview frame is removed.
css += "\n#m3x-concept{display:flex;padding:0;min-height:100vh}#m3x-concept .m3x-screen{width:100%;height:100vh;border:0;border-radius:0;box-shadow:none}\n";
css = css.replace(/#([0-9a-f]{8}|[0-9a-f]{4})\b/gi, (_, hex) => {
  if (hex.length === 4) hex = [...hex].map(value => value + value).join("");
  return `rgba(${parseInt(hex.slice(0, 2), 16)},${parseInt(hex.slice(2, 4), 16)},${parseInt(hex.slice(4, 6), 16)},${(parseInt(hex.slice(6), 16) / 255).toFixed(4)})`;
});
fs.writeFileSync(path.join(output, "styles.css"), css);
for (const asset of ["cover-placeholder.svg", "endfield-contours.svg"]) fs.copyFileSync(path.join(root, asset), path.join(output, asset));
fs.cpSync(path.join(root, "fonts"), path.join(output, "fonts"), { recursive: true });
const licenses = fs.readdirSync(path.join(__dirname, "licenses")).filter(name => name.endsWith(".txt"))
  .map(name => name + "\n\n" + fs.readFileSync(path.join(__dirname, "licenses", name), "utf8")).join("\n\n────────────────\n\n");
fs.writeFileSync(path.join(output, "licenses.txt"), licenses);
console.log("Android 7.1 assets prepared:", output);
