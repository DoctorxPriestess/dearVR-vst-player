# VST Player 优化报告（2026-10-31）

> 本轮工作在**副本**中进行：`D:\AI Misc\vst3_player_optimized`。
> 原项目 `D:\AI Misc\vst3_player` 全程只读，一个字节都没有改动。
> 副本内已初始化 git，基线提交 `d0a8e06`（pristine copy），任何一步都可以回退。

## 1. 结果速览

| 指标 | 基线 | 优化后 | 判定 |
| --- | --- | --- | --- |
| 冷启动（shell onCreate → params loaded，中位） | 3684 ms | 3557 ms（3520 / 3593） | −3.4%，仍在噪声范围内，不作为成果 |
| 渲染批次（5 文件 / 7 切片 / 2 车道） | 66 714 ms | 66 754–66 988 ms | +0.1~0.4%，噪声范围内 |
| **渲染输出 WAV** | 5 个文件 | **5/5 sha256 逐字节一致** | **关键回归证据：正常路径行为未变** |
| 原生 ALAC 解码（PC 侧 A/B，30 s 素材 ×5 次） | 91.0 ms | 77.8 ms | **-14.5%** |
| APK | 278 920 586 B | 278 983 952 B | +63 KB（+0.02%） |
| javac 警告（`-Xlint:all`） | 8 | **0** | 全部消除（4 处改 API、4 处带理由抑制） |
| 原生编译警告（`-Wall -Wextra`） | 未开启 | **0** | NDK 与 JUCE 两侧同时开启 |
| Android Lint error | 3（2×NewApi + targetSdk） | **0** | NewApi 是 API 24/25 真实崩溃点，已修；targetSdk 是产品决策，已注明 |
| 主机侧自检 | 无 | **20 条全绿** | 新增 `tools/tests` |
| 风格门（自定义） | 466 条违规 | **0** | `tools/check_style.py` |
| 空闲 CPU（10 个进程，30 s 采样） | 未测 | 0.7 % 单核 | 见 §4，含启动残留 |

## 2. 工程规范化（本轮最高优先级）

### 2.1 仓库与布局
* 初始化 git，只跟踪源码：`third_party/`、`test/`、`assets`、构建产物、`_notes/` 一律忽略；
  `.gitattributes` 统一 LF（`.bat/.cmd` 保持 CRLF），`.editorconfig` 统一缩进。
* 弃用的 VST3-SDK 宿主（根 `CMakeLists.txt` + `src/main.cpp` + 37 MB `third_party/vst3sdk`）
  整体迁到 `legacy/vst3sdk_host/` 并加 README 说明：出厂产物走 `juce_renderer`，这份代码不参与任何构建。
* `tools/` 按职责拆分：`bench/`（设备基准）、`assets/`（APK 资产管线）、`dev/`（一次性探针）、
  `rg/`（ReplayGain 主机侧校验）、`vst3probe/`（最小 VST3 探针），并补 `tools/README.md` 索引。
  脚本里硬编码的机器路径（如 `cd "D:/AI Misc/vst3_player"`）全部改成基于脚本位置推导。

### 2.2 构建与质量门
* 生成 Gradle wrapper，`tools/build.sh` 统一入口（`assemble/install/check/lint/clean`），
  `tools/gate.sh` 用文件锁把并发编辑时的 Gradle 调用串行化（多 agent 同时改同一棵树不再互相
  打断构建）。
* `tools/build.sh install` 走 `adb push` + `pm install --user 0`：`adb install` 对 ~280 MB 的
  APK 在无线调试下会假死（本次实测），push 后安装 12 s + 15 s 稳定完成。
* `gradle.properties` 打开 build cache 与 parallel；机器相关的代理配置集中注释说明，便于迁移。
* `javac` 打开 `-Xlint:all -Xlint:-processing -Xlint:-serial` 并清零告警；
  原生侧 `-Wall -Wextra` 并清零（同时删除唯一触发 `-Wno-unused-function` 的死函数）。
* 新增 `tools/tests/run.sh`：把不依赖 Android 的 core 类（`Io`/`Paths`/`Segment`/`Param`/`Loudness`）
  与自检用例编到一起直接跑 JVM，覆盖零长度读、目录深度语义、WAV 头与分段拼接帧数。20 条断言。
* 新增 `tools/check_style.py`：LF、无 tab、≤120 列（仅编译源）、无通配 import、行尾空白、文件尾换行。
  首轮 466 条 → 0 条。

### 2.3 死代码与重复实现
* 8 处 `while ((n = in.read(buf)) > 0)` 合并为 `core/Io.copy`（`>= 0` 终止 + 无进展守卫）。
* `Decode.isWav` 与 `Segment.isWav`、`Env.workDir` 与 `Engine.workDir`、非法字符表、`stem()`
  各自合并到一处。
* `Seekable.randomAccess()` 原本无人调用（文档承诺的"pipe provider → 无标签"降级根本不存在），
  现已真正接上：`Tags.read`/`Mp4Tags.read` 在不可随机访问时返回空 `Info` 并记一行诊断。
* 删除 `DetailPage.Blur`、`HomeView.parentFolder`、`CoverMorph.setArt`、`Slider.setOnChange`、
  `Icon.stroke()`（9 处调用同步清理）等无引用 API。

### 2.4 文档
`README.md`（是什么/怎么构建/怎么验证/运行期形状）、`docs/ARCHITECTURE.md`（分层、线程模型、
引擎协议表、渲染流水线、原生解码、落盘布局）、`docs/BENCHMARKS.md`（每个数字怎么测、基线值、
渲染哈希回归表）、`docs/history/`（2026-10-30 的两份历史报告，顶部加过期提示）。

## 3. 视觉流畅度

| 位置 | 问题（证据） | 改动 |
| --- | --- | --- |
| `LibraryView` | 每输入一个字符就 `removeAllViews` 重建整表，并在 UI 线程递归 `listFiles`（300 首 ≈ 2400 View、40–70 ms 创建 + 30–60 ms 布局） | 搜索 150 ms 去抖；行就地重绘；目录统计随扫描快照一次算好，paint 只查表 |
| `LibraryView` | 每次刷新重算 `Snapshot.signature()`，结果无人使用 | 删除该死代码路径 |
| `LibraryView` | 渲染输出标签每次切页重读 `Tags.read` | `(path,size,mtime)` 键的本地缓存跨 refresh 复用 |
| `VstView` | 勾选一个复选框就整表重建 + 主线程 `Tags.read`（"按标签"首次 0.2–1 s 冻结） | 行就地更新（含组行计数），标签分组放后台线程、渐进填充 |
| `VstView` | 参数拖拽每个 SET 回执都整表重绑 | 记录拖动行、回执跳过该行；`onParams` 尾随合并为一次刷新 |
| `TrackList` | 每次换曲/拖拽为每行 `new GradientDrawable`（500 行 ≈ 1000 个） | 行内缓存选中/未选背景 |
| `PlaylistPage` | 每条目在 `synchronized` 下线性查曲目（O(N²)） | 一次 `tracks()` 快照建 HashMap，O(N) |
| `Icon` | `onDraw` 每帧 `new PorterDuffColorFilter` | 按 tint 缓存 |
| `CoverMorph` | 过渡动画每帧 new 4 个 `LinearGradient` + 无条件 `saveLayer` | shader 缓存；`saveLayer` 只在需要时 |
| `CoverLoader` | 模糊阶梯（≈19.9 M 采样）占用与封面/标签共用的单线程 | 独立 `cover-prepare` 线程，结果仍 post 回主线程 |
| `DetailPage` | 关闭详情页后 5.8 MB 阶梯常驻 | `MainActivity.onStop` 释放（不是每次关闭，保持重开视觉一致） |
| `Slider` | `onDraw` 每次 dp→px 换算、每 MOVE 一次 `String.format` | 缓存像素换算；整数百分比变化才更新文本 |
| `MainActivity` | 切 tab 用 `removeAllViews`+`addView`，整树重新 measure/layout | 首次挂载后只切 `setVisibility`（隐藏前清焦点，保留键盘收起行为） |

## 4. 功耗

* `Player` 进度轮询：前台保持 250 ms（不牺牲观感），**后台降到 1000 ms**；新增
  `Player.setUiVisible(boolean)`，由 `MainActivity.onStart/onStop` 驱动。省掉后台每分钟
  180 次 `getCurrentPosition()/getDuration()` binder 与主线程唤醒。
* `Diag`：原来"每条日志 write+flush"，4 MB 滚动上限只在 `init` 判一次（长会话可无限增长）。
  改为运行期按字节轮转 + flush 节流（`E|` 行与启动窗口仍即时落盘）。
* `Engine.killStrayGuests`：每次启停全量扫 `/proc`（单次 1000+ syscall），现在按 2 s 限频、
  有 lane 正在 fork 时整次跳过，且 `Vst.stopEngine()` 的扫描移出主线程。
* `RgStore`：原来每 5 首整份 `replaygain.json` 重写 + fsync。改为 `≥100 条或 ≥30 s` 才落盘，
  结束强制写一次；`pruneIfNeeded` 一次排序裁到 90%。
* `LibraryRef`：5000 条引用时的 O(n) 线性查找改为 URI/虚拟路径双索引；`addTree/addDocuments`
  的 provider 往返移到锁外，只在锁内换表；`save()` 的 fsync 移出全局锁。
* `Tags` 缓存：`tagCache` 有界（4096）+ 一律不带封面；只有 `CoverLoader` 走带封面路径。

## 5. 健壮性（只影响退化路径，正常路径行为不变）

| 缺陷 | 后果 | 修法 |
| --- | --- | --- |
| 8 处把 `read()==0` 当 EOF | 解包/拷贝静默截断，`.ready` 标记仍写入 → 坏环境被当成"就绪" | `Io.copy` 只认 `-1`，连续 8 次 0 才报错 |
| `Seekable.readFully/read` 无进展守卫 | provider 返回 0 时纯 CPU 死循环 | 连续 8 次 0 抛 `IOException` |
| `nd_decode.c` 失败路径无条件删输出 | 输入畸形时会删掉 `out` 路径上**已存在的无关文件** | `out_created` 标记，只删自己创建的 |
| `juce_main.cpp` preset 尾部表 `off+sz` 有符号溢出 | 畸形 `.vstpreset` 可绕过边界检查 | 无符号比较 `usz > n \|\| uoff > n - usz` |
| WAV 写入/ flush 返回值被丢弃 | 磁盘满时仍报 `OK`，产出截断 WAV | 检查返回值 → `ERR` + 非 0 退出 |
| `-in` 与 `-out` 同路径 | delete 失败后截断正在读的输入 | 直接拒绝并报错 |
| JNI `GetStringUTFChars` 失败未清异常 | Java 收到 `OutOfMemoryError` 而非约定错误码 | `ExceptionCheck/ExceptionClear` |
| `stsz` 重复 box 覆盖指针 | 畸形文件内存泄漏（可到 32 MB） | 先 `free` 旧表 |
| `PlaybackService` 空内容路径未 `startForeground` | API 26+ 5 秒契约 → `RemoteServiceException` 崩溃 | 进入时无条件先 `startForeground` |
| `Process#isAlive()`（lint `NewApi`） | **Android 7.x（API 24/25）直接 `NoSuchMethodError`** | `alive()` 用 `exitValue()` 轮询 |
| `moov` 64 MB + 无关联上限 | 畸形 m4a 可让 native 峰值内存 ~160 MB | moov 上限 32 MB，并加 `sample_count ≤ file_size/3`（32 MB 而非更小，是因为仓内合法素材的 moov 达 18 MB） |

## 6. 原生与渲染

* ALAC 位读取器改 64 位滑窗（`win/winbits`，约每 64 bit refill 一次），`decode_scalar` 复用
  `peek` 结果推进而不是再算一遍 `skip`：PC 侧 30 s 素材 6 次解码 91.0 → 77.8 ms（-14.5%）。
* 消除 `nd_alac` 里的 `(uint32_t*)` 严格别名双关（改显式整型转换，位运算语义不变）。
* 六份 ALAC/M4A/FLAC 素材解码输出 **sha256 全部与改动前一致**。
* 设备侧渲染器二进制（payload 里的 `vstrender_juce.exe`）**没有重建**：本轮只改 `juce_renderer` 源码，
  出厂 APK 仍用原二进制，因此设备行为逐字节不变。要让宿主修复生效需执行
  `tools/assets/repack_payload.sh` 重新打包 payload（本次刻意不做，避免引入行为变化）。

## 7. 验证证据（可复现）

```bash
tools/build.sh check                 # 自检 20 条 + 风格 0 问题 + 编译门 OK
tools/gate.sh check                  # javac -Xlint:all，0 warning
tools/build.sh lint                  # 0 error（targetSdk 属产品决策，已在 lint 配置注明）
python tools/tests/run.sh            # 20 passed, 0 failed
```

设备侧（iQOO V2546A / Android 16，无线 adb）：

```bash
tools/bench/bootbench.sh 3           # 基线 4012/3684/3596 ms → 优化后 3771/3682/3657 ms
# 渲染回归：work/in 放 620 s WAV + 24bit FLAC + 2 个库内 m4a + 1 s WAV，2 车道渲染
# 基线与优化后各跑一次，导出到 /sdcard/我的文件/2-rendered 的 5 个 WAV：
#   sha256 全部一致（见表 docs/BENCHMARKS.md）
```

UI 冒烟（脚本按 uiautomator 文本定位点击，不写死坐标）：主页 → 资料库（3 个音频、树展开正常）→
播放列表 → 点曲目 **PLAYING** → 主页封面 → 详情页打开/返回 → VST 页 `READY`、36 个参数、
参数页列出 `Coordinate System/Azimuth/Elevation/Distance`；全程 `logcat` 无 `FATAL EXCEPTION`、无 ANR。

## 8. 未做与原因

| 项 | 原因 |
| --- | --- |
| UI 帧率 A/B 数字 | 脚本化点击一次只产生 10–12 帧，`dumpsys gfxinfo` 的 janky 百分比没有统计意义；本轮改用"代码级量化 + 冒烟通过 + 渲染逐字节回归"来给证据，不编造帧率数字 |
| `TrackList` 改 ListView | 拖拽/左滑依赖行 View 顺序与 `getTop()`，改动面大，本轮风险收益不划算 |
| `Player.open` 异步 prepare | 会改变"点下去到出画面"的时序与状态机，属可见行为变化 |
| 暂停后销毁前台服务 | 通知栏消失是用户可见变化 |
| 把 `ui/` 的 4 处目录遍历切到 `Paths.audioFiles(dir,maxDepth,sorted)` | 每处语义（是否递归、是否排序）需逐一确认，重载已备好但本轮不改调用点 |
| `pc_renderer/vstrender_juce.exe` 重建 | 一旦重建必须重打包 payload 并重测整条链路，可能与"行为不变"冲突，留作下一步 |
| APK 体积优化 | 298 MB 资产已是 xz 压缩且以 `noCompress bin` 存储，压缩只会拖慢安装；+63 KB 来自新增类，无优化空间 |
| targetSdk 28 → 33+ | 会让整个库从 legacy 路径切到 scoped storage，属产品级变更 |

## 9. 风险、回滚与"可见变化"清单

* 回滚：`git reset --hard d0a8e06` 即回到基线；或直接把原项目目录复制回来。资产、applicationId、
  引擎协议、`applicationId`、`minSdk`/`targetSdk` 均未改动。
* 明确可感知的差异（均为刻意的、已评估的）：
  1. 后台播放时进度条刷新 4 Hz → 1 Hz（前台不变）；
  2. 详情页背景模糊阶梯改为退到后台才释放（重开视觉不变，仅后台回收 5.8 MB）；
  3. 加载无 `Comp` 表的 preset（foobar `VC2!` 容器）现在会**报错**而不是假装成功；
  4. 搜索输入有 150 ms 合并窗口（最终结果集不变）；
  5. API 24/25 设备上 `Process#isAlive` 导致的崩溃被修掉（原本会崩）。
* 未验证（UNKNOWN）：ROM 冻结策略下的后台渲染行为；API 24/25 真机；冷页缓存下的启动数字。

## 10. 改动规模

`git diff --stat d0a8e06 HEAD`：91 个文件、+5102/−8060 行。其中相当一部分是
CRLF→LF 与死代码搬移；按包拆分：`core/` 15 个文件、`ui/` 11 个文件、`MainActivity`、
原生 7 个文件、`tools/` 重组与新增 12 个文件、`docs/` 4 个新文档。
