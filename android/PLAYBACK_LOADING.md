# 随机播放与歌曲列表加载修复

2026-10-31，连接设备 Shanling M3X / Android 7.1.1 / `a6840768`。沿用现有播放模式按钮，没有新增入口。

## 修复内容

- ~~切入随机模式时以完整曲库建立队列；从分类中点歌不缩小随机范围。~~ 2026-11-16 起改为：**随机范围 = 点歌所在的列表**。
  - 在专辑、歌手、角色、文件夹、搜索结果、收藏或歌单内点歌，随机只在该列表内进行；切换到随机模式也保留当前队列，不再扩展为整库。
  - 只有在未筛选的“全部歌曲”列表点歌时，随机范围才是完整本地曲库（`queueScope: "library"`，排除隐藏歌曲，新增扫描结果自动纳入）。旧版无 `queueScope` 的随机设置同样按完整本地曲库恢复。
  - 整库随机及任何“回退到整库”的情况都排除网易云在线歌曲（`neteaseId`）；网易云歌曲只在其所在网易云列表内随机。
  - 用户主动移除队列项目、清空队列及“下一首播放”等队列编辑仍保留选择范围。
- 原生界面的上一首、下一首与自动播放、媒体会话共用 Media3 随机顺序，取消界面每次独立抽签，避免一轮未遍历完就重复。
- 接近列表底部时自动追加下一批 40 项，适用于歌曲、网格、分类及队列等现有列表。保留原按钮作为手动与键盘操作的后备方式。
- 追加时保留已有行节点和滚动位置；分类复用已分组和排序的数据。搜索、分类和排序变化时重置批次，滚动加载不保存设置或修改播放队列。

源码：`ui/app.js`、`native-bridge.js`、`app/src/main/java/cn/yuusei/m3x/PlaybackService.java`。生成资源通过 `npm.cmd run assets` 构建。

## 真机验证

基线有 4,160 首歌曲，旧设置保存 4,158 个队列 ID，其中 4,154 个仍能匹配当前曲库；列表首次显示 40 首。原生随机遍历当时覆盖的是这 4,154 首，不能把这个问题简单归因为“随机只取显示的 40 首”。此外，原界面手动切歌独立使用 `Math.random()`，与原生顺序不一致。

最终 `LibraryPlaybackDeviceTest#testFullLibraryShuffleAndAutomaticLoading` 为 `OK (1 test)`，47.842 秒：

- 随机队列包含当前全部 4,160 首，无重复、无遗漏；从当前项遍历一整轮后返回起点。
- 连续手动切换覆盖 13 首不同歌曲，UI 按钮与 `M3X_UI.next()` 均命中原生引擎的下一项；上一首也匹配原生顺序。
- 3 首小队列切换随机模式后扩展到 4,160 首；旧版随机设置恢复、隐藏歌曲排除、分类内点歌仍保持全曲库均通过。
- 列表自动追加 40→80→120→160，网格、角色、专辑和队列自动追加通过；保留已有节点和滚动位置。真实触摸上滑触发追加，手势没有误点歌曲。
- 手动移除队列项和清空队列仍有效。
- 原 UI 设置、工具设置/统计、音频设置、曲库、扫描状态及原生队列/播放位置恢复。测试没有扫描、改写歌曲或标签、清缓存、卸载应用。

28 项 JVM 测试通过；Android lint 为 0 errors / 5 warnings。现有警告不影响构建。设备测试不声称验证听感、DAC 或全部音频格式。

证据在已忽略的 `captures/library-playback-20261031/`：基线日志、最终构建和 instrumentation 输出、`device-final/results.json` 及真实物理屏幕截图。JSON 中的 `observedMs` 包含跨 instrumentation 读取与等待开销，不是纯绘制耗时或稳定帧率指标。

本地交付为 `dist/Cheng-M3X-0.6.0-random-loading-fix.apk`，另有 `.sha256` 和 `verification-0.6.0-random-loading-fix.json`。同原发布证书覆盖安装，拉回设备 APK 后 SHA-256 与交付包一致。版本仍为 0.6.0，没有创建 Git 提交、推送或发布远程 Release。已有未提交的界面/动效工作保留在构建中。

## 2026-11-16 随机范围修复真机验证

同原发布签名覆盖安装后，`LibraryPlaybackDeviceTest#testFullLibraryShuffleAndAutomaticLoading` 为 `OK (1 test)`，51.199 秒（曲库 5,263 条，含网易云导入条目）：

- 3 首选择队列切换到随机模式后仍为 3 首（`shuffleKeepsSelection`）。
- 在“全部歌曲”中点歌后，随机队列为 2,805 首 = 全部本地歌曲减去隐藏与听歌喜好排除；不含网易云条目，无重复、无遗漏，一轮遍历返回起点。旧版无 `queueScope` 的随机设置同样恢复为这 2,805 首。
- 随机模式下在角色分类内点歌，原生队列与界面队列均为该分类的 87 首，原生随机保持开启。
- 手动切歌 13 首不重复且与原生顺序一致；分类内移除队列项、清空队列仍有效；设置、工具、音频、扫描状态恢复，没有扫描或改写曲库。

测试同时修正三处与本修复无关的旧假设：`poll` 现需 `{stats:true}` 才返回统计；重置播放模式改用 `M3X_UI.setMode(0)`，避免当前为网易云歌曲且处于单曲循环时，点击模式按钮进入异步心动模式而使循环卡死；期望集合计入听歌喜好排除。物理上滑自动加载一步首次运行偶发未触发，重跑通过。证据在已忽略的 `captures/library-playback-20261116/`。

## 复现

在 `android` 目录，使用现有本地发布签名和匹配签名的测试包：

```powershell
npm.cmd run assets
.\.tools\gradle-8.9\bin\gradle.bat -I .tools/release-test.init.gradle :app:assembleRelease :app:assembleReleaseAndroidTest :app:testDebugUnitTest :app:lintRelease --console=plain
$adbTool = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adbTool -s a6840768 install -r '.\app\build\outputs\apk\release\app-release.apk'
if ($LASTEXITCODE -ne 0) { throw '应用安装失败' }
& $adbTool -s a6840768 install -r -t '.\app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
if ($LASTEXITCODE -ne 0) { throw '测试包安装失败' }
& $adbTool -s a6840768 shell am instrument -w -e class cn.yuusei.m3x.LibraryPlaybackDeviceTest#testFullLibraryShuffleAndAutomaticLoading cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

测试针对这台具有 4,000 余首歌曲的物理设备；不要将空曲库或其他设备上的未执行条件当作通过。
