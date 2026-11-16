# M3X 终末地动效与真机验证

日期：2026-10-31。本地 0.6.0 动效构建，沿用原发布签名；未发布远程版本。

## 已实现

- `ui/motion.js`：页面方向过渡、可取消的图标/曲名/封面动画、歌词跟随；构建时转译并打包到 APK。
- 导航黄色底块 180ms 平移，页面 10 CSS px / 180ms 短滑入；返回方向相反。页面状态立即更新，连续点击以最后目标为准。
- 按钮图标 2px / 100ms 反馈；播放状态由实际音频状态更新。没有循环动画，也没有新增全屏模糊。
- 终末地抽屉 28px / 180ms 进入，沿用 140ms 退出及重开取消关闭计时器；标签内容短淡入。键盘/程序入口原有 instant 行为保留。
- 大小封面先加载再替换；过期加载回调不得覆盖最新请求，失败回退占位图；曲名和封面分别做小幅位移。
- 歌词只滚动自身容器，400ms 三次缓出；新目标取消旧目标。触摸、滚轮、键盘取消自动滚动，并延后跟随 2.5 秒，之后在后续跟随事件恢复。后台停止活动动画。
- 设置新增“减少动态效果”，保存到原有 UI 配置；与系统减少动态效果合并生效。终末地新增运动由该开关关闭，经典主题保留既有页面效果。
- `appearance.js` 仅在实际调色方案变化时重写颜色；复用 1×1 Canvas，避免每次导航重新创建颜色转换 Canvas。

缓动使用 `cubic-bezier(.2,.8,.2,1)`。变换/透明度为主，不永久设置 `will-change`。歌词滚动是显式、短时的 scrollTop 动画，不声称全程由合成线程完成。进度拖动和音频命令不等待动画。

2026-11-02 歌词补充：常规歌词页在经典及终末地主题都支持短时跟随、当前句轻微缩放与柔光，减少动态效果同时关闭这些过渡。沉浸模式另有 440ms 可取消跟随、分级淡出和上下渐隐，详细行为及最新真机证据见 `AMBIENT.md`。此前下方数据是 2026-10-31 的基础导航／音频测试，不作为新歌词效果的性能数据。

本轮完成按钮、导航、页面、抽屉、切歌与歌词的基础体系；没有新增逐行列表入场、共享封面全屏变形、主题颜色渐变或持续装饰运动。

## 真机证据

物理 Shanling M3X，Android 7.1.1；实际 WebView Chrome 119.0.6045.193；真实曲库 4,160 首。
APK 与测试 APK 沿用安装前证书，覆盖安装均返回 Success。设备 APK 拉回后的 SHA-256 与 dist 一致：

`1718398de3111490f8bfbe31323b6737b4a051cc53e90617fdea0ae27a8d8a69`

- `UiMotionDeviceTest#testMotionAndPlayback`：旧版基线和最终新版各 `OK (1 test)`。覆盖快速导航、唯一可见页面、弹层关闭/重开、真实切歌的最终封面、歌词滚动打断、减少动态效果、浅深色及经典主题。
- `UiPerformanceTest`：`OK (1 test)`；注入真实触屏 DOWN/UP。松手到下一次 WebView rAF 回调为 84.6 / 90.2 / 90.2ms（我的 / 播放 / 曲库）；不能等同光学屏幕延迟。
- `UiAppearanceCaptureTest`：`OK (1 test)`，新批次 `captures/appearance-20261031-004538/` 的 15 张物理显示截图完整校验。人工查看浅色曲库和深色歌词菜单，导航位置、弹层和主题布局正常。
- `UiMotionDeviceTest#testMotionPreview`：`OK (1 test)`。独立录制真实设备导航/抽屉/歌词菜单；录像不参与性能采样。
- `node android/test_appearance.cjs` 通过：色彩提取、4,320 对比度检查、过期图片顺序、缓存及失败回退。
- release 构建、旧 WebView assets 构建、release Lint 通过；Lint 0 错误、5 项原有警告。

## 测量结果

同台设备、同一现有曲库；每组导航 3 轮 × 3 页，抽屉 3 轮开关。表内响应指 JS 动作开始到下一次 rAF 回调，不是动画总时长或物理呈现时间。

| 场景 | 旧版响应范围 | 新版响应范围 |
| --- | --- | --- |
| 暂停时导航 | 117.0–258.4ms | 104.0–245.2ms |
| 播放时导航 | 87.8–245.7ms | 94.9–268.3ms |
| 抽屉开关 | 38.4–82.6ms | 41.7–76.4ms |

新版首帧之后的 rAF 间隔中位数约 16.6ms；P95 为暂停导航 30.4ms、播放导航 23.9ms、抽屉 18.7ms。统计只覆盖每次动作后的约 420ms 窗口，包含动画完成后的帧；不能据此宣称动画全程稳定 60fps。新版没有一致降低首帧延迟，仍存在页面布局/光栅化开销。

全主题重绘后导航另列：79.5–284.4ms。首轮测试曾在浅色重绘后测到 381.6ms，未作为通过结果；后续加入主题绘制缓存，并把全主题重绘明确作为独立场景（600ms预算），普通导航保持350ms预算。截图 GPU 读回与下一次计时之间留出400ms。保留首轮失败记录供比较，未删除。

## 音频和数据保持

最终新版测试在静音的真实 FLAC 解码期间连续导航：libflac，96kHz；输出缓冲计数增加425，播放位置增加4.517秒，测量窗口音频 underrun=0。监听器在解码稳定后安装，排除初始准备阶段。这是短时数字输出/欠载检查，不是耳听、模拟DAC或长时间续航证明。

测试恢复 UI 配置、曲目/进度、收藏/歌单所在配置、统计与音频配置；曲库数量、扫描状态保持不变。未扫描、未修改歌曲标签、未卸载、未清除应用数据。

基线首次脚本忘记 prepare 后直接调用底层 player.play，等待播放超时；修正测试准备步骤后，旧版基线通过。首次失败同样执行并通过设置恢复断言。

## 本地证据与复现

私人数据均留在已忽略目录：

- `captures/motion-before/results.json` 与 `instrumentation.txt`：通过的旧版基线；`initial-harness-failure.txt` 保留测试脚本首次失败。
- `captures/motion-after/results.json` 与 `instrumentation.txt`：最终新版测量；`first-run-results.json` / `first-run-instrumentation.txt` 是首轮主题重绘失败。
- `captures/motion-after/touch-instrumentation.txt`、`touch-logcat.txt`：真实触摸。
- `captures/appearance-20261031-004538/INDEX.md`：完整物理截图索引。
- `captures/motion-after/preview.mp4`：设备原始录像；`motion-demo.mp4` 是裁去开头等待的展示片段，无插值补帧。
- 输入批次 `captures/appearance-20261030-210732/IMPLEMENTATION_LOG.md`：输入来源和处理记录。

运行测试前按 README 构建并用原签名覆盖安装 app 和 release AndroidTest APK；始终明确选择物理设备。性能测试不要与录屏并行。指定方法 `cn.yuusei.m3x.UiMotionDeviceTest#testMotionAndPlayback`，参数 `phase=after`；独立演示方法为 `#testMotionPreview`。
