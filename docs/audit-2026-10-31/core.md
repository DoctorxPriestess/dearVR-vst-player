# core 层审计（lane: core-audit）

范围：`android/app/src/main/java/com/vstplayer/core/` 全部文件 + `android/app/src/main/AndroidManifest.xml`
工作副本：`D:\AI Misc\vst3_player_optimized`（行号基于审计时该副本的快照）
基线事实：audit 时该副本 core 层 25 个文件；`OPTIMIZATION.md` / `android/audit_report.md` 为旧快照（旧 audit 时 Java 仅 5120 行），本次结论全部重新取证，未引用旧结论。

| # | 位置 | 证据 | 问题 | 影响 | 建议 | 风险 |
|---|---|---|---|---|---|---|
| 1 | `core/ReplayGain.java:95-105`（keyOf），配合 `:533` scanReferences 的 `rgstage/<nanoTime>/` | FACT | SAF 参考曲目的 RG 缓存键取不到相对路径，退化为暂存文件绝对路径，而该路径含 nanoTime 随机目录名 | 参考库每次扫描必然 miss：整库重解码；每曲每次往 RgStore 追加永不命中的垃圾记录（上限 20000 → replaygain.json≈3.2MB，每次 save 全量重写+fsync） | keyOf 增加用 rootId/rel（或 uri）作键的重载 | 低 |
| 2 | `core/ReplayGain.java:548-568` | FACT | scanReferences 先把全部选中文档复制到 cacheDir/rgstage，无体积上限 | 峰值=Σ曲目大小；50×40MB=2GB，低存储写入失败整批失败；触发系统 low-storage 清理 | 逐曲 materialize→扫描→删除 | 低 |
| 3 | `core/RgTags.java:1279-1312` rewrite、`:1226-1233` installParts 失败路径 | FACT | 写标签=整文件重写到同目录临时文件；失败时完整副本留在用户目录且只写 stderr | 100MB FLAC=100MB 读+100MB 写，用户目录需 2× 空间；失败静默残留同尺寸副本 | 失败路径 Diag.log + 提供清理；写前检查可用空间 | 中 |
| 4 | `core/Player.java:323-372`（open） | FACT（调用方在主线程：DetailPage:145/232-234、HomeView:149/165、PlaylistPage:95） | 切歌在主线程 setDataSource+prepare、首读标签（RgTags.read 最多 3 次开文件）、同步启动前台服务 | 本地 20-150ms，SAF/USB 可达数百 ms~>5s → 掉帧/ANR | setDataSourceAsync + onPrepared；把 applyTo 挪到 onPrepared | 中 |
| 5 | `core/PlaybackService.java:277-302` + `Player.java:404` | FACT | 暂停后前台服务永久存活 | 不进 Doze/App Standby，同类常驻前台服务量级 1-3%/h 待机 | 暂停后 postDelayed(stopSelf, 5min) | 低 |
| 6 | `core/Player.java:128-139` tick、`:370` | FACT | 250ms Handler 轮询 getCurrentPosition/getDuration | 4 次/秒 × 2 IPC ≈ 28800 次 IPC/小时；后台播放时仍持续唤醒主线程 | 后台降频到 1000ms | 低 |
| 7 | `core/Tags.java:26,59-64,286,337`；`core/LibraryRef.java:112,597,649` | FACT 字段+INFERENCE 封面尺寸 | tagCache 无上限、URI_CACHE=512，均持有含完整封面的 Tags.Info；对照 LibraryRef:663 持久化刻意不带封面 | 5000 曲 × 300KB≈1.5GB；最坏 512×8MB=4GB → OOM | 内部读路径不带封面；tagCache 改有界 LRU 并剥离 cover | 中 |
| 8 | `core/LibraryRef.java:192-220,347-354,365-453,477,572` | FACT（全类 synchronized；主线程调用点 VstView:1511、PlaylistPage:223） | 查询是持同一 monitor 的 O(n) 线性扫描；addTree 持锁做完整 SAF 遍历 | 导入 5000 文档持锁 10-25s，主线程 trackByVirtual 阻塞→ANR；每次虚拟路径解析 O(n)=5000 次比较 | byUri/byVirtual 索引；锁外构建、锁内换快照 | 中 |
| 9 | `core/Env.java:452-455`、`core/FastExtract.java:353-356`、`core/RgStore.java:243`、`core/Vst.java:608-611`、`core/ReplayGain.java:626-628,645`、`core/Lyrics.java:208,287` | FACT（8 处；对照 `core/Export.java:120-127` 已有正确写法并注释 read() 可能返回 0） | `while ((n = in.read(buf)) > 0)` 把 0 当 EOF，静默截断 | 解包 tar 成员被截断（rootfs/wine/box64）→ exec 失败或随机 crash，错误不指向根因；marker 仍可能写入 | 统一 >=0 语义（core/Io.java） | 低 |
| 10 | `core/Seekable.java:58-65,67-78,206-221` | FACT 代码 / INFERENCE 触发条件 | readFully/read(int)/pipe 前跳只把 -1 当终点，read()==0 不推进 → 纯 CPU 死循环 | 100% 占一核空转（功耗/发热/ANR） | 连续 8 次无进展抛 IOException | 低 |
| 11 | `core/ReplayGain.java:419,421`；`core/Loudness.java:518-551` | FACT | 专辑模式同一份 PCM 读两遍、K 加权算两遍 | 10 首×5min 24/48k≈860MB → 1.7GB 读 + 双倍滤波 CPU，专辑扫描 +80% | AlbumAnalyzer.addAndMeasure 一次 feed 同时产出 Result | 低 |
| 12 | `core/ReplayGain.java:309`；`core/RgStore.java:162-216,224-237` | FACT | 每 5 首整份 JSON 重写+fsync；prune 每次插入 O(n) 找最老 | 20000 条≈3.2MB/次；1000 首=200 次全量写+200 fsync≈640MB 写放大 | save 节流（≥30s 或 ≥100 条）；prune 批量裁到 90% | 低 |
| 13 | `core/ReplayGain.java:314-317` | FACT | 每曲结束 listFiles 全删 cacheDir/rg | 每曲一次 listdir；并发扫描互相删对方暂存 WAV → 分析失败 | 只删自己的 temp | 低 |
| 14 | `core/Engine.java:89-113,73-82,222,448`；`core/Vst.java:396` | FACT | 每次启停/退出全量枚举 /proc，逐项 open comm+status；stopEngine 在主线程直接调用 | 单次 2×PID（300-800）次 open≈1000+ syscall，10-50ms；8 lane 放大 8× | 缓存 guest pid；限频；stopEngine 扫描移后台 | 低 |
| 15 | `core/Engine.java:225-227` vs `:76-82,105-113` | FACT 顺序 + INFERENCE 时序 | proc 已建但未加入 LIVE 的窗口内，另一 lane 退出触发的 killStrayGuests 会杀掉它 | lane 启动随机失败；随后 Vst.onExit:541 误判"无输出"并把 PROOT_SECCOMP 永久关掉 | 占位（in-flight 视作 live）；killStrayGuests 排除 in-flight | 中 |
| 16 | `core/Vst.java:524-526,339-345` | FACT | 主引擎退出无条件 stopHelpers()，即使 helper 正在渲染 | 8 lane 批量时单点退出=整批中断，已完成分段作废 | 仅在真失败时拆 lane / 交 UI 决策 | 中 |
| 17 | `core/Diag.java:15,40-55` | FACT | 每行 write+flush 且持全局锁；4MB 上限只在 init 检查 | 启动数百行争锁+flash 写；长会话 diag.log 无界增长 | 运行期按字节轮转；flush 节流 | 低 |
| 18 | `core/PlaybackService.java:94` vs `:161-165` | FACT 代码 + INFERENCE 触发 | startForegroundService 的 5s 契约在 hasContent()==false 直接 shutdown 的路径上被违反 | "播放→立刻停止"同轮次内触发 RemoteServiceException/ANR | onStartCommand 先 startForeground 再决定 shutdown | 低 |
| 19 | `core/Vst.java:696-707,661-694` | FACT | 参数同步一条 SET 一次串行往返，7 lane 全量重放 | 100 参数×5-20ms=0.5-2s/lane，7 lane≈3.5-14s 纯同步 | 引擎侧批量 SET 命令 | 中 |
| 20 | `core/LibraryRef.java:572-602,664-691`（消费方 LibraryView.refresh:320-330） | FACT | 首次刷新串行读每曲标签，结束整份 tags-cache.json 重写+fsync | 5000 参考曲目=5000 次串行 binder 打开（5-20ms）≈25-100s | 分批+游标；增量落盘 | 中 |
| 21 | `core/Decode.java:50-60` vs `core/Segment.java:56-65`；`core/Env.java:69-73` vs `core/Engine.java:146-150`；`core/Paths.java:34-49` vs `core/LibraryRef.java:765-777`；`core/PlaybackService.java:423-427` vs `core/Mp4Tags.java:143-146`；`core/Seekable.java:48` | FACT（randomAccess 全仓 grep 0 调用） | 重复实现/死代码；Seekable 文档承诺的 "pipe → 无标签" 降级因无人调用 randomAccess() 而不存在 | 语义漂移已实际发生（read()>0 vs >=0）；pipe provider 下 Mp4Tags/Tags.readWav 会顺 pipe 分支把整个音频流读一遍 | 合并为单点；randomAccess 接上降级 | 低 |
| 22 | `core/ReplayGain.java:95-105` → `core/Vst.java:770-772` → `core/Engine.java:146-162` | FACT | keyOf 每算一次触发 3-6 次 mkdirs | 5000 曲扫描=2-3 万次 mkdir 且在扫描主循环内 | 缓存目录对象，mkdirs 只在首个 start | 低 |
| 23 | `core/Vst.java:573-602,562-571,604-615` | FACT | 参数表的文件读+JSON.parse 在主线程；失败重试最多串 1.6s | 每次 READY 与每次载入预设阻塞主线程 2-10ms | 解析移后台，结果 post 回主线程 | 低 |
| 24 | `core/Segment.java:149,169,194-199`（对照 `core/Decode.java:43` 已有 MAX_DATA） | INFERENCE | 拼接总字节直写 32 位 RIFF 字段，无 4GiB 校验 | >4GiB 工程写出损坏 WAV 头，渲染"成功"但时长错误 | 复用 Decode.MAX_DATA 断言 | 低 |
| 25 | `android/app/src/main/AndroidManifest.xml:42-50`；`core/EngineService.java:90-94` | INFERENCE | 纯渲染批处理也声明 mediaPlayback 类型 | Android 14+ 类型检查可能拒绝/限制，复现注释记录的 45s 冻结 | EngineService 单独 dataSync/specialUse（需改 Manifest，本轮不做） | 中 |

补充口径（交叉核对用）：本范围内没有发现无限循环线程、Timer/ScheduledExecutor、未 removeCallbacks 的定时器。postDelayed 仅 3 处（Player:137 播放停止即停、Vst:566 最多 3 次、Vst:640 队列空即停），全部有界。MediaPlayer 释放在正常与异常路径均有 release()，唤醒锁由 setWakeMode 托管随 release 回收。未验证（UNKNOWN）：vstrender 侧 PROG 输出频率、pipe provider 的 read()==0 实际发生率、#25 的厂商 ROM 行为。
