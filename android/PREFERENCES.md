# 听歌喜好验收（2026-10-31）

入口：我的 → 软件设置 → 听歌喜好，与“通用设置”并列。

六类规则默认全部关闭，切换立即保存，前台和原生后台使用同一份歌曲分类。每类可预览命中原文、搜索、逐首保留或排除；“跟随规则”撤销逐首例外。歌曲菜单可单独排除普通歌曲。手动点击被排除的歌曲时，用户可选择“仅播放一次”或“始终保留并播放”。

| 当前已导入逻辑曲库 | 命中歌曲 |
|---|---:|
| 总计 | 4,160 |
| 伴奏版本 | 595 |
| 明确独唱版本 | 297 |
| 单角色名 Ver. | 641 |
| 已知组合名 Ver. | 92 |
| 游戏／电视短版 | 126 |
| 钢琴改编 | 119 |

这些是应用当前逻辑歌曲统计，不是目录中 4,235 个主曲库物理文件的重新扫描结果；完整目录名称表和两者区别见 [preferences/README.md](preferences/README.md)。分类只依据明确版本标记，不推断发行时是否为附赠曲。普通 Ver.、M@STER VERSION、未知组合／多人版本和普通 Remix 不会仅因版本字样命中。

标题优先；文件名额外标记只进入待核对。CUE 判断子曲目标题，避免整盘文件名污染所有子曲目。始终保留优先级最高，收藏不自动豁免。自动队列保留原选择，关闭规则恢复范围，全部命中时停止并保持空队列。当前正在播放的命中歌曲可播完，单曲循环临时解除，切离后即移出。单次豁免不跨服务重启。

验证使用原发布证书（SHA-256 `fa4e1da7f80a935547b9d61c9cc83c9a9fb138dcb91e292bbbe71bb1d88dde30`），分别成功覆盖安装应用与测试 APK。没有卸载、扫描、清缓存或修改歌曲标签。发布包保留当前工作区已有改动。

- 34 项 JVM 测试通过（含新增 6 项规则、误判、CUE、优先级与导入验证测试）。
- 前端喜好决策检查和 8 项目录分析测试通过。
- Release lint：0 errors / 5 warnings，沿用已有警告。
- `PreferenceDeviceTest#testSettingsAndBackgroundExclusion`：物理 Shanling M3X，默认关闭、精确队列对照、逐首保留和恢复、手动单次、后台上一首／下一首、选择队列保留、随机和单曲循环、当前歌曲宽限、最后一首自然结束、全排除不回退、设置重载、六类组合均通过；全程静音。
- `LibraryPlaybackDeviceTest#testFullLibraryShuffleAndAutomaticLoading`：整库随机与列表自动加载回归通过。
- 真机截图覆盖经典／终末地的浅色和深色、命中预览、逐首例外入口和手动播放确认。
- 测试最终核对原 UI、音频、统计与扫描状态，恢复原队列及播放位置。硬件耳机按键和实际备份文件导入导出没有单独操作；前者沿用过滤后的原生队列，后者沿用完整 UI 备份并增加喜好校验。

私人日志、原始截图和 `results.json` 在已忽略的 `android/captures/preferences-20261031/`。可选列表隐藏与取消隐藏也已在真机验证。最初锁屏取得的黑图已废弃，以最终成功测试重拍的截图为准。安装包：`dist/Cheng-M3X-0.6.0-preferences.apk`，校验文件同目录。设备已安装 APK 与交付包 SHA-256 一致：`a1daf10ed36507d25f7d5be7c9eac08ebbf2a2d580f7dee2f1c3fc65a74a3925`。

复核：

```powershell
node .\android\preferences\test_preferences.cjs
python -X utf8 -m unittest discover -s .\android\preferences -p 'test_*.py'
& 'D:\Soft\platform-tools\adb.exe' -s 'a6840768' shell am instrument -w -e class cn.yuusei.m3x.PreferenceDeviceTest#testSettingsAndBackgroundExclusion cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

实现入口：`ui/preferences.js`（选项卡、预览、决策）、`ui/app.js`（持久化与播放入口）、`PreferenceRules.java`（分类及校验）、`PlaybackService.java`（后台队列）、`prepare-preferences.cjs`（构建字典）。规则不保存到音乐文件。

## 指定组合版本修复

用户提供的真机歌曲是《Spread the Wings!! (イルミネーションスターズ Ver.)》。它属于组合名版本，原有 Solo／单角色规则不命中。现增加独立 `group-named` 开关，仅按完整版本主体匹配现有已知组合名及无歧义原始歌手别名，普通 M@STER VERSION 和未知版本主体不会因此命中。

同时修复曲库顶部始终显示原始 4160 首的问题：现在显示可见数量及隐藏数量。设备保留用户原先五项设置、隐藏开关与逐首例外，并按本次指定歌曲要求启用新增组合选项；当前可见和随机队列均为 2320 首，隐藏 1840 首。无需扫描。

修复版 `PreferenceDeviceTest#testSettingsAndBackgroundExclusion` 明确报告 `OK (1 test)`，包含真实触摸组合开关、指定歌曲实际列表隐藏、重载后整库随机、24 次随机切换、所有原有排除与恢复场景。全六类加一首始终保留时队列为 2321 首。测试恢复原 UI、音频、工具、统计、扫描状态、队列和位置；随后单独执行用户开关设置，`testEnableReportedGroupPreference` 也报告 `OK (1 test)`，核对保存及重载。

最终物理截图和日志在 `android/captures/preferences-fix-20261031/`。修复包为 `dist/Cheng-M3X-0.6.0-preferences-fix.apk`，SHA-256 `f01869a68de149851043a9424e648af847cdf4b24d34208b4d82f0ade03f9aad`，与设备拉回的实际安装 APK 一致；前文旧包和旧截图保留作历史记录。
