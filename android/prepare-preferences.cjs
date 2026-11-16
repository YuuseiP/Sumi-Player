const fs = require("node:fs");
const path = require("node:path");

function compilePreferences(directory = __dirname) {
  const config = JSON.parse(fs.readFileSync(path.join(directory, "preferences/rules-draft.json"), "utf8"));
  const credits = JSON.parse(fs.readFileSync(path.join(directory, "../artist-classification/semantic/credits.json"), "utf8"));
  const characters = [...new Set(credits.entries.flatMap(entry => entry.credits.filter(([kind]) => kind === "character").map(([, name]) => name)))].sort();
  // Keep original, unambiguous artist aliases too (some canonical group names are Chinese).
  const groups = [...new Set(credits.entries.flatMap(entry => [
    ...entry.credits.filter(([kind]) => kind === "group").map(([, name]) => name),
    ...(entry.credits.length === 1 && entry.credits[0][0] === "group" ? [entry.raw] : [])
  ]).filter(Boolean))].sort();
  return { schema: 1, rules: config.rules, characters, groups, categories: [
    { id: "backing", label: "伴奏版本", description: "Instrumental、Off Vocal、卡拉 OK" },
    { id: "solo-explicit", label: "明确独唱版本", description: "Solo ver.、ソロ・リミックス等" },
    { id: "solo-named", label: "单角色名版本", description: "只写角色名 Ver. 的版本" },
    { id: "group-named", label: "组合名版本", description: "组合名 Ver.，例如イルミネーションスターズ Ver." },
    { id: "short", label: "游戏／电视短版", description: "Game Size、TV Size、Short ver." },
    { id: "piano", label: "钢琴改编", description: "Piano Arrange Version" }
  ] };
}
module.exports = { compilePreferences };
