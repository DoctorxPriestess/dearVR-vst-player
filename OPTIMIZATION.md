# VST Player 优化报告（2026-10-30）

真机：iQOO V2546A / Android API 34 / 未 root。口径：`adb shell am force-stop com.vstp` 后冷启动，
以 `diag.log` 的 `shell onCreate` → `params loaded` 为端到端耗时；同一设备、同一命令、多次取中位。

## 1. 结果

| 指标 | 优化前 | 优化后 | 手段 |
|---|---|---|---|
| 冷启动（进程创建 → 参数可用） | **36.5 s** | **4.7–5.2 s**（3 次：5033 / 5172 / 4718 ms） | 去掉 proot 的 `PROOT_NO_SECCOMP=1`（每 syscall ptrace → seccomp 快路径） |
| 首次安装抽取 | **61.4 s** | **15.6 s**（rootfs 9.6 + wine 5.4 + payload 0.25） | 资产按 tar 条目边界切 6 段 + 6 线程并行解压 |
| 首次安装后首个可用状态 | **不可用**（`plugin not found`，需用户手动重启一次） | 自动修复后 **+9.4 s** 就绪 | 检测到插件路径无法解析 → 修 wine `Z:` 映射 → 自动重试一次 |
| 启动遮罩 | 全屏阻塞 36 s，文案出现 `null 47%` | 仅首次抽取时阻塞；文案正常（`Linux 运行环境 27%`） | 遮罩只在抽取阶段显示；进度回调保留上个阶段名 |
| APK | 368.0 MB | 280.3 MB | 资产重打包（副产物，非目标） |

启动期实测分解（`diag.log` 探针）：`engine spawn +7ms` → `engine READY +4.5s` → `params loaded +60ms`。

## 2. 根因与修法

### 2.1 启动慢：proot 的 seccomp 加速被关闭（最大单点，-17 s）
- 现象：冷启动 36.5 s，其中 `proot` 进程自身吃 48 % CPU。
- 根因：`Engine` 硬编码 `PROOT_NO_SECCOMP=1`，proot 只能对每个 syscall 做 ptrace 拦截；
  box64 JIT + wine bootstrap 期间 syscall 量极大，纯翻译开销 ≈17 s。
- 证据（设备侧对照，同一 run.sh）：带该变量 READY 22.4 / 23.6 / 23.7 s；去掉 5.04 / 4.96 s。
- 修法：默认不再注入；保留 `Engine.PROOT_SECCOMP` 开关，并加**自动回退**——若引擎没有任何输出就退出，
  说明内核拒绝 seccomp 过滤器，自动带 `PROOT_NO_SECCOMP=1` 重试一次。

### 2.2 首次安装后必须手动重启（功能性缺陷）
- 现象：全新安装首次进 VST 页报 `plugin not found: Z:\opt\vstenv\payload\dearVR PRO 2.vst3`。
- 根因：wine prefix 在首次启动时才由 wine 创建，其 `dosdevices/z:` 指向 Termux 根；
  `fixWineDrives()` 在 prefix 还不存在时无事可做，于是首个进程必然失败。
- 修法：捕获 `plugin not found` → 修 `Z:` → 自动重启引擎一次（`driveFixRetry` 有界）。
  期间状态栏显示「初始化 Wine 前缀（首次约 1 分钟）」。
- 已验证：清空 prefix + 抽取标记后，一次启动即自动到 READY +9.4 s，无需人工干预。

### 2.3 重新抽取撞 `ETXTBSY`
- 现象：升级后重新抽取时 `copyAsset: proot-loader ... ETXTBSY (Text file busy)` 导致启动失败。
- 根因：上一轮 guest 进程尚未完全退出，仍持有 `proot-loader` 的映射；直接覆盖写被内核拒绝。
- 修法：① 抽取前 `Engine.killStrayGuests()`；② `copyAsset` 改为「写 `.new` 后 rename」原子替换；
  ③ 整段抽取遇 busy 类错误重试一次（`extractWithRetry`）。

### 2.4 其他
| 问题 | 修法 |
|---|---|
| 遮罩文案 `null 47%` | 进度回调只传百分比时保留上一阶段名（`Vst.stageLabel`） |
| 引擎重启后旧 reader 线程覆盖新状态 | `pump()` 退出时校验 `proc == p` 才回调 `onExit` |
| wine 组件以 PPID=1 逃逸、累积 | `Engine.killStrayGuests()`（按 uid + comm 精确匹配）在启动前/退出后清理 |
| `Diag` 每行 open/close + 每次 new SimpleDateFormat | 常驻 `FileOutputStream` + 复用 formatter + 4 MB 轮转 |
| 渲染导出在主线程复制整首 WAV（ANR） | 后台单线程执行 + 失败 Toast + WeakReference |
| 播放列表非原子写、解析失败静默覆盖 | tmp + fsync + rename 原子替换；解析失败保留原文件并拒绝后续 save |
| 主线程封面解码、列表全量重建 | 统一 `ui/CoverLoader`（后台 + LruCache + 采样）；adapter 复用 convertView |
| `while ((n = in.read(buf)) > 0)` 0 字节死循环 | 改为按 `-1` 判 EOF |

## 3. 关键改动文件

- `core/Engine.java`：seccomp 开关与回退、启动探针、guest 进程清理、`Z:` 修复入口、`boot.conf` 调试钩子。
- `core/Vst.java`：抽取重试/自愈重试、阶段名、`isPreparing()`、退出竞态。
- `core/Env.java`：`copyAsset` 原子替换 + busy 重试、分段快速路径（+6 行，旧单流路径保留为回退）。
- `core/FastExtract.java`（新增）：6 段并行解压（符号链接延迟统一创建）。
- `MainActivity.java`：遮罩仅在抽取阶段阻塞、`onDestroy` 解绑监听、导入循环修复。
- `core/Diag.java`：常驻日志写入 + 轮转。
- `core/Playlists.java`、`core/Export.java`、`ui/*`：见 `android/audit_report.md` 的 A/B 批。
- 资产：`standalone/assets/*_Nof6.bin`（6+6+6 段）；原始整包移入 `standalone/assets_orig/`（不进 APK）。

## 4. 如何复测

```bash
bash tools/build_install.sh      # 构建 + 安装（约 2 分钟）
bash tools/bootbench.sh 3        # 冷启动 3 次，输出 ms
bash tools/extractbench.sh       # 删 .ready-* 触发重新抽取并计时
```
调试期想改引擎环境变量（不改代码）：`files/boot.conf` 写 `KEY=VALUE`（仅 debug 构建生效，`Engine.applyBootConf`）。

## 5. 回滚

- 分段资产：把 `standalone/assets_orig/*.bin` 移回 `assets/`、把 18 个 `*_Nof6.bin` 移出即可；
  `FastExtract.segmentCount()` 发现段不齐会返回 0，`Env` 自动回到单流路径。
- seccomp：`Engine.PROOT_SECCOMP = false` 即回到优化前行为（慢 ~17 s）。

## 6. 未验证 / 风险

- **冷页缓存场景未测**：所有 E3/E2 测量都在 page cache 已热的状态下完成（`/proc/*/io` 的 `read_bytes` 全程 0）。
  真正的冷盘首次启动可能多 1–3 s（UNKNOWN）。
- **UI 帧统计不完整**：`dumpsys gfxinfo` 在脚本化点击下只采到 10 帧（60 % janky，样本太小无意义），
  B 批 UI 改动的收益需要用人工操作 + Perfetto 复核。
- 首次安装总耗时仍约 75 s（抽取 15.6 s + wine 建 prefix ≈47 s + 重试 9.4 s）。prefix 无法预置
  （已实测：预置 `dosdevices` 会让 wineboot 缺 `drive_c` 并空转，故回退）。
- 测试期间清空过 `files/glibc`（含 `opt/vstenv/work/in|out`），设备上原有的导入测试音频与渲染结果已丢失，需重新导入。
## 7. 功耗审查（2026-10-30 第二轮）

实测结论：**代码侧已无遗留耗电项**。

| 检查项 | 实测 |
|---|---|
| 前台空闲 CPU | 0.0%（30 s 内 wineserver 0 tick） |
| 退到后台 | 0 tick / 10 s（系统冻结本包） |
| 唤醒源 | `dumpsys alarm` 0 条、无 JobScheduler 任务、无 WAKE_LOCK |
| 屏幕 | 代码无 `KEEP_SCREEN_ON`；MediaPlayer 未 `setWakeMode` |
| 定时器 | 4 处 `postDelayed` 全为事件驱动，无轮询 |

### 7.1 关闭 App 时释放引擎

- 按 HOME：保留热引擎（回前台秒开），guest 进程 9 个常驻、CPU 为 0。
- 按返回/划掉：`MainActivity.onDestroy` 且 `isFinishing()` 且未播放/未渲染 → `Vst.stopEngine()` → 释放约 417 MB RSS。
- 代价：释放后下次冷启动 7.2 s（保留时 3.4–5.2 s），仍在 15 s 目标内。

### 7.2 渲染绑核（实测 -15%）

60 s 音频渲染（含引擎启动，各 3 次、交替执行、每次先 force-stop）：

| 绑核 | 中位 |
|---|---|
| 不绑 | 9844 ms |
| 只绑 cpu7（最高频核） | **8344 ms** |
| cpu6+7 | 9500 ms（1 次） |
| 冷启动 | 3.42 s vs 3.42 s（绑核无影响） |

实现要点：
- 掩码必须按**十六进制**传给 toybox `taskset`（`80`→cpu7；`0x50` 会被判 bad）。
- 目标核 = `cpuinfo_max_freq` 最高者（并列取最高编号），不写死核号。
- 引擎首行输出后读 `/proc/<pid>/status` 的 `Cpus_allowed_list` 自校验：不匹配就永久停用绑核并记日志，避免在其他机型上静默绑到小核。
- 调试开关：`files/boot.conf` 写 `engine.affinity=0` 可关闭（仅 debug 构建）。

### 7.3 未处理项

- 设备设置 `stay_on_while_plugged_in=15`（充电常亮）是测试遗留，但用户选择保留，未改动。
- 后台渲染会被系统冻结（无前台服务）：渲染只在 App 前台推进。如需后台渲染要加前台服务（会显著增加耗电，未做）。
## 8. 渲染静音根因（2026-10-30 第三轮，已修复）

### 8.1 现象与取证

App 内渲染出来的 wav **一直是全 0**，引擎却报 `render done ok=true`。取证结论：

| 事实 | 证据 |
| --- | --- |
| 输出是宿主预填值/0，插件从未写入 | 16 声道调试文件逐样本恒为预填 0.5；2 声道文件 rms=0 |
| 与本项目代码无关 | 同一个 `vstrender_juce.exe`（md5 一致）在 Windows PC 上渲染正常，rms 与设备修好后完全一致（10873 / peak 23317） |
| 与 box64 参数无关 | FASTNAN/FASTROUND/SAFEFLAGS/STRONGMEM/BIGBLOCK 五组变体全部仍然静音 |
| 不是插件特有 | 自建最简增益 VST3（`tools/vst3probe`，2in2out、固定 0.5 增益）在设备上**正常出声**（rms 4638 = 输入减半） |
| 也不是 serve 模式问题 | serve 与单次渲染两种模式在设备上都复现 |

### 8.2 根因

dearVR PRO 2 的 HRTF / 虚拟声学数据**不是**从插件包内加载，而是固定从
`C:\ProgramData\DearReality\dearVRPRO2\*.dat` 读取。Wine 前缀里没有这个目录时：

- 插件照样实例化成功，`PARAMS|` 仍然列出全部 36 个参数（所以"插件找不到"的检测不会触发）；
- `process()` 被调用、输入样本正确、但输出缓冲一个字节都不写 → 渲染结果为静音；
- 耗时异常快（60 s 音频 0.38 s），这也是之前把渲染性能"优化"到 8.3 s 的假象来源。

### 8.3 修复

`Env.ensurePluginData()`：抽取完成后在 `wineprefix/drive_c/ProgramData/DearReality/dearVRPRO2`
预置 payload 里的 7 个 `.dat`（优先硬链接，失败回退复制；wineboot 不会清掉该目录）。
预置发生在**引擎首次启动之前**，因此全新安装第一次渲染就是有声的。

同时修复宿主的重复写入 bug：`juce_renderer/src/juce_main.cpp` 在创建输出流前
`File(outPath).deleteFile()`。此前对同一路径重渲染会把**整份 WAV 追加**到旧文件之后
（960104 → 1920208 字节，PC 上同样复现），文件越渲染越大。

### 8.4 修复后的真实数字

| 项 | 值 |
| --- | --- |
| 单文件 60 s 渲染（cpu7 绑核、热引擎） | **11.1 s / 11.2 s**（约 5.4x 实时） |
| 双文件批量（双引擎，60 s × 2） | **约 13 s**（串行基线 22.3 s，约 **1.72x**） |
| 第二个引擎就绪（暖前缀） | 0.9–1.0 s |
| 重新渲染同路径 | 尺寸稳定 17280104 字节，单 RIFF 头 |
| 输出音频 | rms 1.83 M / peak 3.76 M（24 bit，与 PC 端一致） |

> ⚠️ 第 7 节里"60 s 音频 8.3 s""并发 1.92x"的旧结论作废：当时插件根本没在处理，
> 测到的是 wine 启动与插件加载。恢复出声后请以本节数字为准。



## 9. 解码链路（2026-10-30 第四轮，已修复）

**需求**：ALAC/AAC 必须能直接渲染，且**输入与输出的位深、采样率一致**（FLAC/WAV 同样）。

**实现**：core/Decode.java（MediaExtractor + MediaCodec 同步 API，零第三方依赖）把非 PCM 源解成临时 WAV；
ui/VstView.plan() 在渲染前按容器决定是否解码：

| 源 | 处理 | 输出位深 |
| --- | --- | --- |
| WAV / AIFF | 直接交给渲染器（PCM 原样） | 与源相同 |
| FLAC ≤16bit | 解成 16bit PCM 再渲染（可分段） | 16bit |
| **FLAC 24bit** | **不解码、不分段**，渲染器原生读 FLAC | **24bit 保留** |
| OGG/Opus | 解成 16bit PCM | 16bit |
| ALAC 24bit | 解成 24bit PCM | **24bit** |
| ALAC 16 / AAC / MP3 | 解成 16bit PCM | 16bit |

**位深不谎报**：Decode 只在真拿到 24bit 样本时写 24bit 头。平台 FlacExtractor 实际只吐 16bit 却报
bits-per-sample=24，代码用「轨道时长/PTS 反推实际 bytes-per-frame」识破并降级为 16bit，日志留证
declared 24bit but the stream is 16bit ... writing 16。

**真机验证（最终构建，files/boot.conf 的 decode.selftest=1，日志 files/diag.log）**：

    prepared src_flac16.flac: 2ch/16bit/48000Hz, 60.0s -> 1 slice(s) flac=16bit +341ms
    no split for src_flac24.flac: flac 24bit would decode to 16bit
    prepared src_flac24.flac: native container -> 1 slice(s) flac=24bit +0ms
    prepared src_aac.m4a: 2ch/16bit/48000Hz, 20.0s -> 1 slice(s) +513ms
    prepared src_alac16.m4a: 2ch/16bit/48000Hz, 20.0s -> 1 slice(s) +668ms
    prepared src_alac24.m4a: 2ch/24bit/48000Hz, 20.0s -> 1 slice(s) +220ms

跑完把 5 个输出拉回宿主机逐个头解析（wave 读头 + 24/16bit 峰值/RMS）：

    src_alac24_vst     2ch 24bit 48000Hz   20.0s peak   -1.4 dBFS rms  -12.3 dBFS
    src_alac16_vst     2ch 16bit 48000Hz   20.0s peak   -1.4 dBFS rms  -12.3 dBFS
    src_aac_vst        2ch 16bit 48000Hz   20.0s peak   -1.4 dBFS rms  -12.3 dBFS
    src_flac24_vst     2ch 24bit 48000Hz   20.0s peak   -1.4 dBFS rms  -12.3 dBFS
    src_flac16_vst     2ch 16bit 48000Hz   60.0s peak    0.0 dBFS rms  -12.5 dBFS

位深与采样率全部与源一致，且都不是静音（源素材本身峰值 -1.4 dBFS）。

## 10. 单文件分段并行渲染（2026-10-30 第四轮）

**为什么**：一个 guest 进程只吃一个核（实测 60 s 音频 11.0–11.2 s，约 5.4x 实时）。20 分钟文件单引擎
要 3:44；把长文件切成带前置的片段、多条车道并行渲染再拼接，才吃得下 8 核。

**切片规则**（core/Segment.java，帧级精确）：第 s 段渲染 [max(0, s*L - P), min(total, (s+1)*L))，
拼接时丢掉每段前 P 帧。默认 **L=300 s，P=10 s**，且只有**时长 ≥10 分钟**的文件才分段（短文件没有收益还要多一次拼接）。
可用 files/boot.conf 的 render.segment / render.preroll 覆盖（仅 debuggable 构建读）。

**接缝质量实测（能否分段的关键）**：24bit/48kHz 真实音乐（Apple Music ALAC，4:15），两处接缝分别放在
最密集段（211 s）与最安静的环境声段（245.5 s），10 s 前置，tools/seam_analysis.py 分析：

| 接缝 | 差值 RMS | 差值峰值 | 阶跃/局部p99.9 | 包络 z | 频谱通量/基线 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| 211.0 s | **-142.4 dBFS** | -124.5 dBFS | 0.16x | 1.1 | 0.52x | 无听感差异 |
| 245.5 s | **-134.3 dBFS** | -105.0 dBFS | 0.40x | 1.6 | 0.54x | 无听感差异 |

- 差值不是阶跃尖峰：能量散布在整段（1 ms 内占比 0.0001，20 ms 内 0.002），形状 colored-like-reference
  （与参考频谱相关 0.64–0.78）= 极低电平状态残差，不是 click，也不是环境声/混响突变。
- 峰值 -105 dBFS 比 16bit LSB 还低 19 dB。
- 首段渲染结果与整段渲染**逐位相同**；10 s 前置之外的片段在「同引擎连续渲染」与「新引擎」下也**逐位相同**，
  说明引擎状态残留完全被前置吸收。
- 早期用 4 s 前置时残差达 169 LSB(24bit)，10 s 前置把残差压到 1 LSB 以下 —— 前置必须 ≥10 s。

**并行车道**：core/Vst.java 重构为 1 个主引擎 + 最多 2 个 helper 车道（各自 proot/wine 进程、各自绑核），
VstView 按 render.engines 或**内存自适应**（totalMem ≥ 6 GB 且非 lowRam → 3 车道，否则 2 车道）决定开几条。
每个引擎约 400 MB RSS。

**后台/锁屏保活**：渲染期间 core/EngineService 以前台服务（channel vstplayer，通知显示阶段与进度）
加 PARTIAL_WAKE_LOCK 持有，批次结束后释放。没有这两者，切后台或灭屏后系统会冻结进程、连带杀掉
proot/wine 子进程，渲染会停在半路。

### 11.6 基准实测（20 分钟 / 1200 s，24bit 48kHz，同一素材 long20.wav）

每行是应用日志里 batch done 的 total（含准备/切片/渲染/拼接的整批耗时）：

| 配置 | 段长 | 车道 | 切片数 | 总耗时 | 相对单核 |
| --- | --- | --- | --- | --- | --- |
| single_core | 不分段 | 1 | 1 | 219.6 s (3:40) | 1.00x |
| seg120_e1 | 120 s | 1 | 10 | 237.6 s | 0.92x（切片开销 +8%） |
| seg180_e1 | 180 s | 1 | 7 | 232.0 s | 0.95x（+5.6%） |
| seg180_e2 | 180 s | 2 | 7 | 127.2 s | 1.73x |
| seg300_e2 | 300 s | 2 | 4 | 115.2 s | 1.91x |
| seg120_e3 | 120 s | 3 | 10 | 94.3 s / 94.7 s（复测） | 2.33x |
| seg180_e3 | 180 s | 3 | 7 | 92.6 s | 2.37x |


结论：

- 单车道下分段只会更慢（+5..8% 的切片/拼接开销），并行才有收益 —— 正式版因此只在 >10 分钟的文件上分段。
- 2 车道 1.73–1.91x，3 车道 2.32–2.37x（本机 cpu6/cpu7 为 4.6GHz 快核，第 3 条落在 3.6GHz，仍净赚）。
- 段长 120 s 与 180 s 差别在 1–3%（段越少切片/拼接开销越小，但并行度不变），300 s 段在 2 车道下最快；
  正式版默认 300 s 是综合考虑（段太少会在 8 分钟文件上退化成 2 段）。
- 拼接耗时：7 段 438 ms、10 段 351–444 ms（20 分钟素材），相对总耗时可以忽略。

### 11.6.1 3 车道踩坑（已修）

三个 guest 共用同一个 WINEPREFIX（同一个 wineserver）。两个 helper 同时回放 36 条 SET| 时，会有一个
guest 卡死不再应答（实测：engine1 只发出 32 条 SET 后无响应，engine2 正常）。修法两条：

1. helper 参数同步**串行化**（一次只同步一条车道，实测 134 ms 内两条都完成）；
2. 同步超过 45 s 未完成就把该车道标记为 dead，其份额转给其它车道，**不缩减整个车道池**（早期版本错误地
   把 laneCount 改成 1，连正常车道一起废掉，导致 193 s 的退化）。

车道数现在按**可用内存**决定：totalMem ≥6 GB 且 availMem ≥2.6 GB 才开 3 条（三个 guest 约需 1.2 GB）。


### 11.7 后台/锁屏渲染：代码侧完备，但本机 ROM 会冻结进程（实测证据）

代码侧：渲染批次开始时启动前台服务（EngineService，foregroundServiceType=mediaPlayback）并持有
PARTIAL_WAKE_LOCK，通知显示阶段与进度，批次结束自动释放。

真机实测（iQOO V2546A / OriginOS 14 / Android 14，按 HOME 切后台）：

    isForeground=true foregroundId=3 types=0x00000002   (mediaPlayback)
    adj=200                                            (perceptible)
    /sys/fs/cgroup/apps/uid_10473/pid_<pid>/cgroup.freeze = 1     (20 s 后被冻结)

前台服务与通知都正常注册、进程优先级也不是 cached，但 OriginOS 自己的「应用冻结」在切后台约 20 s 后
仍然冻结了整个进程（不是 AOSP CachedAppOptimizer 的行为）。冻结后 Java 侧 stdout 读取线程停摆，
引擎写管道阻塞，渲染停在半路（本次两个引擎各停在半片，输出文件只有 12.5 MB / 8.6 MB）。

要在此类 ROM 上真正后台渲染，需要用户在系统侧放行（应用信息 → 耗电管理/自启动 → 允许后台高耗电），
这与 App 代码无关。另外试过把包加入 Doze 白名单（dumpsys deviceidle whitelist +com.vstp），无效，
说明是厂商独立策略。

结论：后台渲染能力已保留并在标准 Android 语义下生效（前台服务+唤醒锁+通知），本机需用户手动放行
ROM 的「后台高耗电」开关；前台渲染不受影响（本轮全部基准都在前台完成）。

### 11.8 helper 车道参数一致性（已修）

参数（SET|）与预设（PRESET|）此前只发给主引擎，helper 引擎以插件默认值启动 —— 多车道分段会让
接缝两侧音色不同。现在 helper 就绪后会自动执行 syncParamsTo(lane)：向主引擎发 PARAMS| 拿到它
当前 36 个参数值，再逐条 SET| 回放到该车道（命令入队，渲染排在其后），日志 engineN params synced: 36。
