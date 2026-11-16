# M3X 外观调试

- 所有终端命令使用 Windows PowerShell 语法。
- 界面源码在 `android/ui/`，原生桥在 `android/native-bridge.js`、`android/native-tools.js`；修改后运行 Android assets 构建。
- 独立网页预览和 `codex/web-preview-design` 分支已移除。后续审美调试使用 ADB 连接的 M3X 物理显示截图，流程见 `android/APPEARANCE.md`。
- 开始外观修改前，读取 `android/captures/` 下对应批次的 `INDEX.md`、`manifest.json`、`review/AGENT_DRAFT.md`、权威草稿 `review/draft.json`，并查看相关原始 PNG 和 `review/annotated/` 标注图。网页保存的本地草稿直接作为修改输入，无需另行提交；保留批次、修订号、截图 ID 和标注 ID，处理结果写入该批次 `IMPLEMENTATION_LOG.md`。
- 验证改动时使用原发布签名覆盖安装，在真机重新截取相关页面；不要用浏览器或模拟器效果代替真机验收。
- 保留设备应用数据、本地歌曲、扫描授权、歌单、播放位置和签名文件。普通外观截图不启动扫描，不修改歌曲标签，不清理缓存，不卸载应用。
- 批注网页由 `python .\android\appearance-review.py` 启动，仅展示真机截图；画笔、方框、箭头、文字自动保存在对应批次的 `review/`。不得用重建的播放器网页替代截图。原图不可修改，标注图片另存。
- 截图及标注包含私人曲库信息，保存在已忽略的 `android/captures/`；本地音频夹具位于已忽略的 `android/local-fixtures/`，不纳入 Git。
