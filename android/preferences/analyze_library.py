"""Read-only M3X filename inventory and opt-in preference-rule analysis.

No dependencies, app installation, player scan, audio writes, or preference writes.
Private outputs must remain under android/local-fixtures/.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import subprocess
import unicodedata


REPO = Path(__file__).resolve().parents[2]
PRIVATE = REPO / "android" / "local-fixtures"
RULE_FILE = Path(__file__).with_name("rules-draft.json")
CREDITS = REPO / "artist-classification" / "semantic" / "credits.json"
ROOTS = ["/storage/external_sd1", "/storage/emulated/0"]
AUDIO = set("flac mp3 wav m4a aac ogg opus aif aiff aifc dsf dff wv ape wma mp2 mpa mka tta tak dts ac3 eac3 m4b alac amr".split())
ROW = re.compile(r"^Row: (\d+) _id=(.*?), _data=(.*?), title=(.*?), artist=(.*?), album=(.*?), duration=(.*?)$", re.M)
LABEL = re.compile(r"\(([^()]*)\)|\[([^\[\]]*)\]|【([^【】]*)】")
VERSION = re.compile(r"^(.*?)\s*(?:version|ver\.?)$", re.I)
MARKER_TAIL = re.compile(r"^(?:(?:version|ver\.?)\s*)?(?:$|[)\]】]|\[(?:ort\b|\d))")


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def normalize(value):
    return unicodedata.normalize("NFKC", value).casefold()


def compact(value):
    return re.sub(r"\s+", "", normalize(value))


def private_directory(value):
    path = Path(value).resolve()
    if path == PRIVATE.resolve() or not path.is_relative_to(PRIVATE.resolve()):
        raise ValueError("Output must be a subdirectory of android/local-fixtures")
    return path


def adb(serial, args, timeout=120):
    result = subprocess.run(["adb", "-s", serial, *args], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace"))
    return result


def collect(directory, serial):
    directory.mkdir(parents=True, exist_ok=False)
    paths = []
    for index, root in enumerate(ROOTS):
        result = adb(serial, ["exec-out", "find", root, "-type", "f", "-print0"])
        (directory / f"directory-{index}.bin").write_bytes(result.stdout)
        (directory / f"directory-{index}.stderr.txt").write_bytes(result.stderr)
        if result.stderr.strip() or not result.stdout.endswith(b"\0"):
            raise RuntimeError(f"Incomplete directory enumeration: {root}")
        paths.extend(item.decode("utf-8") for item in result.stdout.split(b"\0") if item)
    write_json(directory / "directory-paths.json", paths)
    result = adb(serial, ["exec-out", "content", "query", "--uri", "content://media/external/audio/media", "--projection", "_id:_data:title:artist:album:duration"])
    (directory / "mediastore.txt").write_bytes(result.stdout)
    (directory / "mediastore.stderr.txt").write_bytes(result.stderr)
    if result.stderr.strip() or b"Exception" in result.stdout:
        raise RuntimeError("MediaStore query failed; see saved response")
    write_json(directory / "collection.json", {
        "serial": serial, "roots": ROOTS,
        "captured_at": datetime.now(timezone(timedelta(hours=8))).isoformat(),
        "file_count": len(paths), "media_returncode": result.returncode,
        "cue_files": [p for p in paths if p.lower().endswith(".cue")],
    })


def collect_cues(directory, serial):
    paths = json.loads((directory / "directory-paths.json").read_text("utf-8"))
    destination = directory / "cues"
    destination.mkdir(exist_ok=True)
    index = []
    for path in sorted(p for p in paths if p.lower().endswith(".cue")):
        filename = hashlib.sha256(path.encode("utf-8")).hexdigest()[:16] + ".cue"
        adb(serial, ["pull", path, str(destination / filename)])
        index.append({"path": path, "file": filename})
    write_json(destination / "index.json", index)


def decode_cue(raw):
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return raw.decode("utf-16"), "utf-16 BOM"
    for encoding in ("utf-8-sig", "cp932", "gb18030"):
        try:
            return raw.decode(encoding), encoding
        except UnicodeDecodeError:
            pass
    raise ValueError("CUE encoding not recognized")


def cue_tracks(directory, paths):
    index_file = directory / "cues" / "index.json"
    if not index_file.is_file():
        return []
    result = []
    for source in json.loads(index_file.read_text("utf-8")):
        text, encoding = decode_cue((index_file.parent / source["file"]).read_bytes())
        current_file = ""
        current = None
        for line in text.splitlines():
            file_match = re.match(r'\s*FILE\s+(?:"([^"]+)"|(\S+))\s+\S+', line, re.I)
            track_match = re.match(r"\s*TRACK\s+(\d+)\s+AUDIO\b", line, re.I)
            title_match = re.match(r'\s*TITLE\s+(?:"(.*)"|(.*))$', line, re.I)
            if file_match:
                current_file = file_match[1] or file_match[2]
                current = None
            elif track_match:
                candidate = posixpath.normpath(posixpath.join(posixpath.dirname(source["path"]), current_file.replace("\\", "/")))
                local_name = posixpath.join(posixpath.dirname(source["path"]), posixpath.basename(current_file.replace("\\", "/")))
                resolved = candidate if candidate in paths else local_name if local_name in paths else ""
                current = {"cue": source["path"], "track": int(track_match[1]), "file_reference": current_file,
                           "resolved_file": resolved, "title": "", "encoding": encoding}
                result.append(current)
            elif title_match and current is not None:
                current["title"] = title_match[1] if title_match[1] is not None else title_match[2]
    return result


def load_engine():
    rules = json.loads(RULE_FILE.read_text("utf-8"))
    patterns = [(rule, re.compile(rule["pattern"])) for rule in rules["rules"]]
    catalogue = json.loads(CREDITS.read_text("utf-8"))
    characters = {compact(name) for entry in catalogue["entries"] for kind, name in entry["credits"] if kind == "character"}
    return rules, patterns, characters


def classify(text, patterns, characters):
    text = normalize(text)
    hits = []
    for rule, pattern in patterns:
        for match in pattern.finditer(text):
            if MARKER_TAIL.match(text[match.end():].strip()):
                hits.append({"rule": rule["id"], "category": rule["category"], "evidence": match[0]})
                break
    for match in LABEL.finditer(text):
        label = next((part for part in match.groups() if part is not None), "")
        version = VERSION.fullmatch(label.strip())
        if version and compact(version[1]) in characters:
            hits.append({"rule": "named-character-version", "category": "solo-named", "evidence": label})
    return hits


def analyze(directory):
    paths = json.loads((directory / "directory-paths.json").read_text("utf-8"))
    if len(paths) != len(set(paths)):
        raise ValueError("Duplicate paths in inventory")
    raw_media = (directory / "mediastore.txt").read_text("utf-8")
    media = {}
    parsed = list(ROW.finditer(raw_media))
    if len(parsed) != len(re.findall(r"(?m)^Row:", raw_media)):
        raise ValueError("MediaStore response contains unparsed rows")
    for match in parsed:
        _, media_id, path, title, artist, album, duration = match.groups()
        media.setdefault(path, []).append({"media_id": media_id, "title": title, "artist": artist, "album": album, "duration_ms": duration})
    rules, patterns, characters = load_engine()
    cues = cue_tracks(directory, set(paths))
    cue_by_file = {}
    for cue in cues:
        if cue["resolved_file"]:
            cue_by_file.setdefault(cue["resolved_file"], []).append(cue)
    songs = []
    for path in sorted(paths):
        file = PurePosixPath(path)
        if file.suffix.lower()[1:] not in AUDIO:
            continue
        metadata = media.get(path, [])
        title = metadata[0]["title"] if metadata else ""
        if title == "NULL":
            title = ""
        cue = cue_by_file.get(path, [])
        source = "mediastore" if title else "cue" if len(cue) == 1 and cue[0]["title"] else "filename"
        if not title:
            title = cue[0]["title"] if source == "cue" else file.stem
        group = "main-library" if path.startswith("/storage/external_sd1/THE_IDOLMASTER/") else "test-fixture" if "/.m3x-audio-probe/" in path else "other-audio"
        hits = []
        for field, value in (("title", title), ("filename", file.stem)):
            for hit in classify(value, patterns, characters):
                hits.append({"field": field, **hit})
        songs.append({"path": path, "filename": file.name, "title": title, "title_source": source, "scope": group,
                      "artist": metadata[0]["artist"] if metadata else "", "album": metadata[0]["album"] if metadata else "",
                      "media_rows": metadata, "cue_tracks": cue, "hits": hits})
    main = [s for s in songs if s["scope"] == "main-library"]
    counts = Counter()
    rule_counts = Counter()
    variants = Counter()
    disagreements = []
    for song in main:
        counts.update({hit["category"] for hit in song["hits"]})
        rule_counts.update({hit["rule"] for hit in song["hits"]})
        if song["title_source"] != "filename":
            title_categories = {hit["category"] for hit in song["hits"] if hit["field"] == "title"}
            file_categories = {hit["category"] for hit in song["hits"] if hit["field"] == "filename"}
            if title_categories != file_categories:
                disagreements.append(song)
        for match in LABEL.finditer(normalize(song["title"])):
            label = next(part for part in match.groups() if part is not None)
            if re.search(r"ver\.?|version|size|instrumental|vocal|ソロ|カラオケ", label):
                variants[label] += 1
    target = {"backing", "solo-explicit"}
    combined = sum(any(h["category"] in target for h in s["hits"]) for s in main)
    expanded = sum(any(h["category"] in target | {"solo-named"} for h in s["hits"]) for s in main)
    summary = {
        "audio_files": len(songs), "main_library_files": len(main), "scopes": dict(Counter(s["scope"] for s in songs)),
        "audio_extensions": dict(Counter(PurePosixPath(s["path"]).suffix for s in songs)),
        "main_title_sources": dict(Counter(s["title_source"] for s in main)),
        "mediastore_rows": len(parsed), "mediastore_not_on_disk": sorted(set(media) - set(paths)),
        "duplicate_mediastore_paths": {p: len(rows) for p, rows in media.items() if len(rows) > 1},
        "categories": dict(counts), "rules": dict(rule_counts),
        "backing_plus_explicit_solo_unique": combined, "plus_named_character_unique": expanded,
        "title_filename_category_disagreements": len(disagreements),
        "cue_files": len({cue["cue"] for cue in cues}), "cue_tracks": len(cues),
        "unresolved_cue_tracks": [cue for cue in cues if not cue["resolved_file"]],
        "character_dictionary_size": len(characters),
        "input_hashes": {name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in ("directory-paths.json", "mediastore.txt")},
        "rules_sha256": hashlib.sha256(RULE_FILE.read_bytes()).hexdigest(),
        "character_dictionary_sha256": hashlib.sha256(CREDITS.read_bytes()).hexdigest(),
        "warning": "Directory file counts are not the installed player's logical catalog count. MediaStore is cached metadata, not fresh audio tag verification. All matches are review candidates, not proof of album bonus status.",
    }
    write_json(directory / "songs.json", songs)
    write_json(directory / "summary.json", summary)
    write_json(directory / "cue-tracks.json", cues)
    write_json(directory / "title-filename-disagreements.json", disagreements)
    write_json(directory / "version-labels.json", dict(variants.most_common()))
    write_json(directory / "rule-candidates.json", [s for s in main if s["hits"]])
    lines = ["M3X 完整音频名称表（每个物理音频文件一项；CUE 子曲目另见 cue-tracks.json）", "来源标记：mediastore=系统缓存标题；cue=CUE 标题；filename=文件名回退。", ""]
    for index, song in enumerate(songs, 1):
        lines.extend([f"{index:04d}. {song['title']}", f"  来源: {song['title_source']} | 范围: {song['scope']}", f"  文件: {song['filename']}", f"  路径: {song['path']}"])
    (directory / "song-names.txt").write_text("\n".join(lines) + "\n", encoding="utf-8-sig")
    review = ["# M3X 喜好排除规则分析", "", f"主曲库 **{len(main)}** 个物理音频文件；设备共享存储共 **{len(songs)}** 个音频文件。", "", "所有选项默认关闭；这些是名称候选，不证明发行时属于附赠曲。", "", "| 类别 | 命中文件数（类内去重） |", "|---|---:|"]
    labels = {"backing": "伴奏", "solo-explicit": "明确 Solo / ソロ 版本", "solo-named": "单角色名 Ver.", "short": "Game / TV / Short 版本", "piano": "钢琴改编"}
    review.extend(f"| {labels.get(category, category)} | {count} |" for category, count in counts.items())
    review.extend(["", f"仅选伴奏 + 明确独唱：{combined} 个文件；同时选择单角色名版本：{expanded} 个文件。", f"标题与文件名的类别冲突：{len(disagreements)}；CUE：{summary['cue_files']} 份 / {len(cues)} 条子曲目 / 未解析引用 {len(summary['unresolved_cue_tracks'])}。", "", "## 数据边界", "", "MediaStore 标题来自系统缓存，未重读音频标签。无系统记录的文件仍完整保留并标明回退来源。物理文件不等于播放器逻辑曲目，未导出播放器私有曲库。CUE 条目单列，不与物理文件直接相加。", "", "## 命中明细", ""])
    for category in labels:
        matches = [s for s in main if any(h["category"] == category for h in s["hits"])]
        review.extend([f"### {labels[category]}（{len(matches)}）", ""])
        for song in matches:
            evidence = "; ".join(f"{h['field']}:{h['rule']}={h['evidence']}" for h in song["hits"] if h["category"] == category)
            review.extend([f"- {song['title']}", f"  - {song['path']}", f"  - {evidence}"])
        review.append("")
    (directory / "RULE_ANALYSIS.md").write_text("\n".join(review), encoding="utf-8")
    console = {"output": str(directory), **summary}
    console["unresolved_cue_tracks"] = len(summary["unresolved_cue_tracks"])
    print(json.dumps(console, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--collect", action="store_true")
    parser.add_argument("--collect-cues", action="store_true")
    parser.add_argument("--serial")
    parser.add_argument("--input", type=private_directory)
    args = parser.parse_args()
    if (args.collect or args.collect_cues) and not args.serial:
        parser.error("--serial is required for device reads")
    directory = args.input or PRIVATE / ("preferences-" + datetime.now(timezone(timedelta(hours=8))).strftime("%Y%m%d-%H%M%S"))
    if args.collect:
        collect(directory, args.serial)
    if args.collect or args.collect_cues:
        collect_cues(directory, args.serial)
    if not directory.is_dir():
        parser.error("Use --collect or supply an existing --input")
    analyze(directory)


if __name__ == "__main__":
    main()
