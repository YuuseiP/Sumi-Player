# 响度均衡

入口：播放页 → 声音与输出 → 声音处理 → **响度均衡**（默认开启），下方可选目标响度 -14 / -16（默认）/ -18 LUFS。

## 行为

- 每首歌按 ITU-R BS.1770-4 测得综合响度（K 加权、400 ms 块、-70 LUFS 绝对门限与 -10 LU 相对门限），比目标响的歌曲按差值降低音量。**只衰减、不提升、不压缩动态**，所以不会削波，也不改变原始动态。
- 音量通过 ExoPlayer 播放器音量（AudioTrack 输出端）施加，切歌时与 `onMediaItemTransition` 对齐；不需要开启 DSP 管线，关闭 EQ 等处理时仍保持 Float 输出。与播放／暂停淡变、跨专辑淡变相乘叠加。
- 优先级：ReplayGain 选择“单曲／专辑”且有标签时，由原有 DSP ReplayGain 处理，响度均衡不再叠加；否则有 ReplayGain 标签时直接换算（标签以 -18 LUFS 为参考）；都没有时后台分析。
- 尚未分析完的歌曲先按曲库已测歌曲的中位数估算（至少 3 首后启用），分析完成后 1.5 秒内平滑过渡到准确值。当前歌曲之后，自动预分析播放顺序中的下两首，连续播放通常无需等待。
- 关闭开关立即恢复原音量；结果缓存在应用私有目录 `files/loudness.json`（派生数据，删除只会重新分析）。不修改音乐文件和标签。

## 分析方式

| 来源 | 方法 |
|---|---|
| 本地 FLAC（全部位深） | 内置 libFLAC；按 seek table 或按字节比例跳读 8 段 × 4 秒 |
| 本地 AIFF / AIF | 直接读取 PCM，同样 8 段 × 4 秒 |
| 本地 WAV / MP3 / AAC 等 | 系统 MediaExtractor + MediaCodec，8 段 × 4 秒 |
| 本地 ALAC（M4A） | 系统不支持时改用内置 FFmpeg；ALAC 参数从文件 moov 读取 |
| DSD | 使用已生成的 PCM 缓存；转换完成后自动分析 |
| 网易云 / 在线 FLAC | libFLAC + 限定长度的 HTTP Range 请求，6 段 × 3 秒，只下载片段 |
| 网易云 / 在线 MP3 | 只下载 6 个片段的字节范围并单独解码 |
| CUE 子曲目 | 只分析该子曲目的起止范围 |

不足 45 秒（在线 20 秒）的歌曲完整分析。分析线程为后台低优先级，单次上限 45 秒；失败的歌曲 10 分钟后才会重试，期间按曲库中位数估算。分析中的任何异常都只记录日志，不影响播放。

## 验证

```powershell
.\.tools\gradle-8.9\bin\gradle.bat :app:testDebugUnitTest --console=plain
& 'D:\Soft\platform-tools\adb.exe' -s 'a6840768' shell am instrument -w -e class cn.yuusei.m3x.LoudnessDeviceTest#testNormalisesLocalFormatsWhilePaused cn.yuusei.m3x.test/android.test.InstrumentationTestRunner
```

JVM 测试 `LoudnessMeterTest`：BS.1770 参考正弦（-20 dBFS 立体声 = -20 LUFS；单声道满幅 = -3.01 LUFS，44.1/48/96/192 kHz）、门限、片段间隔、只衰减、ReplayGain 标签解析、抽样窗口和 ALAC 参数查找。

### 2026-11-16 物理 Shanling M3X 结果

原发布签名覆盖安装应用与 release AndroidTest APK，`OK (1 test)`；JVM 测试全部通过；release lint 0 errors（8 条告警均为既有代码）。曲库格式分布：FLAC 3,864、网易云 1,102、M4A 144、AIFF/AIF 143、WAV 7、CUE 3。

| 样本 | 格式 | 测得 | 降低 | 单首分析耗时 |
|---|---|---:|---:|---:|
| 网易云 ×3 | 在线 FLAC | -8.3 / -6.0 / -6.2 LUFS | 7.7 / 10.0 / 9.8 dB | 约 7–33 秒（取决于网络） |
| 本地 FLAC | 24 bit / 96 kHz | -7.4 | 8.6 | 约 2.2 秒 |
| 本地 M4A | ALAC 44.1 kHz（FFmpeg） | -6.1 | 9.9 | 约 1.7 秒 |
| 本地 WAV | 32 bit / 96 kHz | -11.5 | 4.5 | 约 5.5 秒 |
| 本地 AIFF / AIF | 32 bit / 96 kHz | -6.8 / -7.8 | 9.2 / 8.2 | 约 5 秒 |
| CUE 子曲目 | FLAC 24 bit / 96 kHz | -10.5 | 5.5 | 约 2.7 秒 |

每首的实际播放器音量与 10^(降低量/20) 相差不超过 0.015；再次切回已分析歌曲立即使用缓存；关闭后音量恢复 1.0，切换 -14 LUFS 正确重算，非法目标被拒绝。截图确认“声音处理”首项为响度均衡及状态说明。

开发中修正的问题：Android 7.1 系统 FLAC 解码器无法初始化高解析在线 FLAC，且其解析器会把整首（约 30 MB）读完，故在线与本地 FLAC 都改走 libFLAC 跳读；`MediaExtractor.getSampleSize()` 为 API 28 接口，已移除，后台分析改为捕获所有 Throwable；系统对 32 bit WAV 的解码结果错误（曾测得 -1.4 LUFS），WAV 改为直接读取 PCM。缓存版本号已升至 3，旧测量自动作废。

已知边界：在线歌曲首次播放时，正确音量要在分析完成（受网速影响，约 10–30 秒）后才平滑生效，此前按曲库中位数估算；在线 M4A / Ogg 暂不分析，按估算值处理。截图、日志与 `results.json` 在已忽略的 `android/captures/loudness-20261116/`。

真机测试 `LoudnessDeviceTest` 全程暂停不发声，不扫描、不改标签；逐一切换到队列中的网易云歌曲和曲库中每种本地格式（含 CUE），核对测得响度与实际播放器音量、缓存命中、关闭／开启与目标切换；结束时恢复原队列、位置、最近播放和音频设置。结果写入设备 `Android/data/cn.yuusei.m3x/files/loudness-verification/`。
