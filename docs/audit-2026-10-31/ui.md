# UI 层视觉流畅度审计（只读基线，工作副本 vst3_player_optimized）

审计时间：2026-10-31。范围：android/app/src/main/java/com/vstplayer/MainActivity.java 与 ui/ 全部文件。
本文件为审计原文留档，供其它 lane 与 Lead 交叉核对；行号对应审计时的快照，改动后行号会漂移。
证据等级：FACT=代码直接可见；INFERENCE=推断，依据已注明；UNKNOWN=未验证。

## 1. 资料库每次输入字符/展开都全量重建，并在主线程递归遍历文件系统
- 位置：android/app/src/main/java/com/vstplayer/ui/LibraryView.java:146-153（search TextWatcher）、:352（paint）、:356（list.removeAllViews()）、:407-411（visibleRendered→Paths.audioFiles）
- 证据等级：FACT
- 问题：afterTextChanged 同步调用 rerender()→paint()，清空并重建所有可见行；paint() 每次还在 UI 线程递归 listFiles 遍历 work/out。
- 影响：每行约 8 个 View（LinearLayout/FrameLayout/Icon/2×TextView/占位/箭头）。300 首 + 30 专辑展开 ≈ 2400 View，按 15–30µs/View 创建 ≈ 40–70ms，加全树 measure/layout ≈ 30–60ms → 单次按键 80–150ms 掉帧（INFERENCE）；另有 30 次 listFiles + ~330 次 isDirectory/isAudioName stat（1–5ms）。
- 建议：① 搜索 150–200ms 去抖；② visibleRendered 结果随 Snapshot 在扫描线程算好；③ 展开/折叠只增删该 section 子 View，不 removeAllViews。
- 风险：低–中；回归面：filterDir/filterRootId/query 组合过滤、展开状态持久化（KEY_TREE）。

## 2. TrackList 是 ScrollView 全量建行，换曲/拖拽/删除都整表重绘
- 位置：android/app/src/main/java/com/vstplayer/ui/TrackList.java:157（rebuild）、:224-241（applyRow/applyRows）、:82-103（setFiles/setCurrent）、:358-382（drop/removeFile）
- 证据等级：FACT
- 问题：队列未用 ListView，一次性为全部曲目建 View；换曲时 applyRows() 对每行 new 2 个 GradientDrawable + setText；拖拽落点与左滑删除都 removeAllViews 重建。
- 影响：每行 ~8–9 View + 3 Icon。500 首队列打开详情页 = ~4000 View，创建 ≈ 60–120ms、measure/layout ≈ 40–100ms → 打开详情页 100–250ms 卡顿；换曲 500 行 ≈ 10–20ms；删 1 行同样整表重建。
- 建议：① 换 ListView+BaseAdapter（照 VstView.ParamAdapter 的 convertView 复用）；② applyRow 缓存选中/未选背景 drawable；③ removeFile 只 removeView 对应行。
- 风险：中；回归面：长按拖拽（依赖 r.root.getTop()、gap 计算）、左滑位移动画、选中态。

## 3. 封面模糊阶梯：约 2000 万次采样 / 8000 万次乘加，且与所有封面共用一条线程
- 位置：android/app/src/main/java/com/vstplayer/ui/CoverMorph.java:116-130（prepare）、:152-168（addBlurLevel）、:268-316（gauss）；android/app/src/main/java/com/vstplayer/ui/CoverLoader.java:44（单线程 BG）、:77-81（backdrop）
- 证据等级：FACT（参数与循环）+ INFERENCE（耗时）
- 问题：1080px 参考宽度下逐级 W×H×(2r+1)×2：384²×11 + 384²×23 + 270²×37 + 128²×41 + 128²×95 = 9.94M 采样/遍，两遍 ≈ 19.9M 采样 ≈ 79.6M 次浮点乘加；跑在与所有 cover/tags 请求共用的单线程上。
- 影响：按 1.5–3ns/采样估 30–60ms（A76）／60–100ms（A55）；期间首页 512 封面、详情徽标标签排队 → 切歌时封面延后 30–100ms；每次新增 ~1.6MB bitmap。
- 建议：① backdrop 的 prepare 移出共享单线程；② 最大档 σ=130 已贴 128px 下限（σ_bmp≈15.4，r=47，95 tap），kernel radius 上限压到 24。
- 风险：低–中；回归面：详情页背景观感、进出场过渡。

## 4. VST 渲染页源列表：每次勾选/切模式都整表重建 + 主线程同步读标签
- 位置：android/app/src/main/java/com/vstplayer/ui/VstView.java:839-867（refreshSourceRows）、:920-931（refreshTagRows 内 Tags.read）、:1024-1026（addSourceFolder→Paths.audioFiles）、:210-216 / :1115-1119（勾选回调）
- 证据等级：FACT
- 问题：点一个复选框就 removeAllViews 重建整棵源列表，主线程递归 listFiles（inputFiles + 每个展开目录 Paths.audioFiles 再遍历一次），"按标签"模式同步 Tags.read 每个文件。
- 影响：50 个源 ≈ 400 View 重建 + 数十次 syscall ≈ 15–40ms/次勾选；"按标签"首次对 100 首 Tags.read（本地 0.5–3ms/首，SAF 2–10ms/首）→ 0.2–1s 主线程冻结（INFERENCE）。
- 建议：① 勾选只改该行背景/圆圈；② 标签分组放后台线程；③ inputFiles() 结果缓存。
- 风险：中；回归面：选中集合与行状态对应、全选/组选计数、tag 分组缓存过期。

## 5. 详情页过渡每帧全屏 saveLayer，并每帧 new 4 个 LinearGradient
- 位置：android/app/src/main/java/com/vstplayer/ui/CoverMorph.java:201-228（onDraw）、:223（cv.saveLayer(dst, null)）、:253-262（feather）；android/app/src/main/java/com/vstplayer/MainActivity.java:286-304（430ms/330ms）
- 证据等级：FACT
- 问题：进出场动画每帧对 dst 做离屏层合成；feather() 每帧新建 4 个 LinearGradient。
- 影响：progress→1 时 dst 接近全屏 1080×2400 = 每帧约 10.4MB 离屏缓冲 + 全屏合成，中端机估 3–8ms/帧（INFERENCE）；feather 全程 104 次 Shader 分配。
- 建议：① 只在 feather > 1.2 时 saveLayer；② feather 的 4 个 Shader 缓存复用。
- 风险：低；回归面：过渡边缘羽化、进出场视觉连续性。

## 6. 参数拖动在主线程做阻塞管道写 + 同步日志落盘
- 位置：android/app/src/main/java/com/vstplayer/ui/VstView.java:474-484（Slider.OnChange→vst.setParam，:482）；core/Vst.java:617-642 → core/Engine.java:453-457（synchronized send + flush）与 core/Diag.java:40-55
- 证据等级：FACT（调用链直接可见）
- 问题：松手后每 40ms 的 flushParams 都在 UI 线程调用 synchronized 的 Engine.send 和 Diag.log。
- 影响：guest 不读 stdin 时管道写满 → 拖动/松手时 ANR；正常 20–80µs/次。
- 建议：SET 下发移到专用线程；Diag 对 UI 高频路径降级为环形缓冲。
- 风险：中；回归面：SET 与 SAVE/PARAMS 顺序、渲染互斥。

## 7. 每次刷新在 UI 线程重算 Snapshot.signature()，而结果完全没用
- 位置：android/app/src/main/java/com/vstplayer/ui/LibraryView.java:343-349（render）、:245-271（signature/appendTree）
- 证据等级：FACT
- 问题：render() 两个分支都调 paint()，但 signature() 仍在 UI 线程遍历全部曲目并递归 listFiles（每文件 2 次 stat）。
- 影响：1000 引用 + 300 渲染文件 ≈ 1300 次 stat + 60–100KB 拼接，3–12ms/次刷新（INFERENCE）；appendTree 深度 3 与实际 8 不一致，本就无效。
- 建议：删除 signature()/rendered 字段。
- 风险：低；回归面：无（paint 仍每次都执行）。

## 8. LibraryRef 全实例一把锁，load()/flushTags() 持锁做 JSON 与文件 IO
- 位置：core/LibraryRef.java:175-232、:237（load）、:664（flushTags）；UI 调用点 ui/VstView.java:702、:877-885、ui/PlaylistPage.java:144
- 证据等级：FACT
- 问题：所有只读访问与 load()/flushTags() 共用同一 monitor，后者在锁内解析 8–16MB JSON 并整文件写盘。
- 影响：load() 估 30–150ms、flushTags 估 20–100ms；期间 UI 线程 tracks()/tracksOf()/trackByVirtual() 可能等满整段。
- 建议：volatile 不可变快照 + 锁外构造。
- 风险：中；回归面：索引并发一致性。

## 9. 资料库渲染结果区每个文件夹行都递归统计一次
- 位置：android/app/src/main/java/com/vstplayer/ui/LibraryView.java:962-1000（addFolder）、:991（countSubdirs）、:992（Paths.audioFiles(dir)）
- 证据等级：FACT
- 问题：每画一个文件夹行就 listFiles 一次 + 递归遍历子树数音频。
- 影响：30 目录 ×（1 次 listFiles + 11 次 stat）≈ 660 次 syscall ≈ 3–10ms/次 paint（INFERENCE），每次按键/展开/切 tab 重做。
- 建议：扫描线程随 Snapshot 算好 path→{子目录数, 音频数}，paint 查表。
- 风险：低；回归面：目录行副标题数字、导入/删除后失效。

## 10. Icon 每次 onDraw 都 new 一个 PorterDuffColorFilter
- 位置：android/app/src/main/java/com/vstplayer/ui/Icon.java:81-86
- 证据等级：FACT
- 问题：onDraw 内 new PorterDuffColorFilter(tint, SRC_IN) 并 setColorFilter，每帧每图标一次分配。
- 影响：过渡/整屏重绘时可见 Icon 15–25 个 → 每帧 15–25 次小对象分配，且每帧重设 filter 让 drawable 绘制状态反复失效。
- 建议：在 tint(int) 里缓存 filter 字段。
- 风险：低；回归面：动态 tint 调用点。

## 11. 播放列表页解析 O(N²)（在全局锁内）且全表重绘两次
- 位置：android/app/src/main/java/com/vstplayer/ui/PlaylistPage.java:208-215（refresh）、:133-166（resolve）、依赖 core/LibraryRef.java:192
- 证据等级：FACT
- 问题：每条目调 lib.track(rootId, rel) 在 synchronized 下线性扫描全部曲目；setFiles 之后又 setMissing 再全表重绘一遍。
- 影响：N=200 → 4 万次比较（0.5–2ms）；N=2000 → 400 万次（20–60ms，持 LibraryRef monitor）；全表重绘 200 首 ×2 ≈ 30–60ms（INFERENCE）。
- 建议：一次 tracks() 快照建 HashMap，O(N)；合并 setFiles/setMissing。
- 风险：低；回归面：缺失标记、条目顺序、keyByFile。

## 12. 每次切到资料库都重新读全部渲染文件的标签（不复用缓存）
- 位置：android/app/src/main/java/com/vstplayer/ui/LibraryView.java:288-305（refresh 内 Tags.read 循环）；对照 ui/CoverLoader.java:50
- 证据等级：FACT
- 问题：扫描线程对 work/out 下每个文件直接 Tags.read(f)，不用 LRU，也不跨 refresh 复用。
- 影响：300 个渲染输出 × 0.5–3ms ≈ 150–900ms CPU/次切 tab（INFERENCE）；第二版快照要等它跑完。
- 建议：用 (path,size,mtime) 为键的本地缓存跨 refresh 复用。
- 风险：低；回归面：重渲染覆盖同名文件需靠 mtime 失效。

## 13. 渲染页资料库分组每次都按全量曲目造 File 对象（即使未展开）
- 位置：android/app/src/main/java/com/vstplayer/ui/VstView.java:874-897（addLibraryRows，:883-885）
- 证据等级：FACT
- 问题：无论是否展开，先把该 root 全部曲目转成 File 列表，并对每个 root 调 lib.tracksOf（O(总曲目数)）。
- 影响：1000 首 + 10 root → 2000 个 File + 10×1000 次比较，估 2–5ms，叠加在每次勾选重建上。
- 建议：组行只需 {name, 总数, 已选数, key}。
- 风险：低；回归面：组选 toggleSelection 需展开时构造。

## 14. 详情页关闭后仍整条保住模糊阶梯（约 5.8MB）
- 位置：android/app/src/main/java/com/vstplayer/MainActivity.java:306-311（finishClose）、ui/DetailPage.java:411-414、ui/CoverMorph.java:133-144（setPrepared）、:170-181（release）
- 证据等级：FACT
- 问题：closeDetail 只 overlay.removeAllViews()，detail 字段继续持有 CoverMorph，阶梯与 1024px 原图不释放。
- 影响：4MB（sharp）+ 1.6MB 阶梯 ≈ 5.8MB 常驻，并 pin 住 ART_CACHE 的 4MB 条目。
- 建议：关闭后调 bg.release()，重新打开按需重建。
- 风险：低；回归面：再次打开首帧先纯色/旧图。

## 15. VstView 的 45s postDelayed 在 Activity 销毁后仍持有整棵树
- 位置：android/app/src/main/java/com/vstplayer/ui/VstView.java:1309-1318（45000ms）、:1427-1429（700ms）、:66（ui 字段）；MainActivity.java:172-182
- 证据等级：FACT
- 问题：批处理期间 ui.postDelayed 的匿名 Runnable 隐式捕获 VstView（持有 Activity Context），销毁时不清理。
- 影响：整棵 VST 页 View 树被额外持最长 45s，估 2–5MB 延迟释放。
- 建议：onDetachedFromWindow 里 ui.removeCallbacksAndMessages(null)。
- 风险：低；回归面：lane 同步超时检测、停止后自动重启引擎时机。

## 16. 切 Tab 时把保活的整棵页重新挂载，触发全树 measure/layout
- 位置：android/app/src/main/java/com/vstplayer/MainActivity.java:199-231（show；:203 removeAllViews、:210 addView、:221 library.refresh）
- 证据等级：FACT
- 问题：三页常驻字段，切 tab 时 removeAllViews + addView，立刻触发整棵 eager 树 measure/layout。
- 影响：资料库 300 行 ≈ 2400 View，一次全树 measure/layout 估 20–60ms（INFERENCE），同帧还叠加 library.refresh() 的二次 paint。
- 建议：用 setVisibility 切换代替 remove/add；refresh 推到下一帧。
- 风险：低；回归面：insets padding、焦点/输入法、overlay/nav 层级。

## 17. 每个渲染 job 起停都 startService 重建通知（主线程 IPC）
- 位置：android/app/src/main/java/com/vstplayer/ui/VstView.java:1485-1492（setStatusProgress）、:1730；core/EngineService.java:36-46
- 证据等级：FACT
- 问题：每个 job 启动/失败都 startService(Intent)，触发 Binder IPC + Notification build + startForeground。
- 影响：200 切片 → 200+ 次主线程 IPC，估 0.2–0.6s 分散占用。
- 建议：通知节流（≥1s 或百分比变化才发）。
- 风险：低；回归面：后台渲染通知进度（1Hz 可接受）。

## 18. 参数 SET 回执每次都触发可见行重绑，与拖动期本地更新重复
- 位置：android/app/src/main/java/com/vstplayer/ui/VstView.java:361-372（onParams）、:382-389（refreshValues）；core/Vst.java:644-650；core/Param.java:23-27
- 证据等级：FACT
- 问题：拖动期 ≤25 次/秒的 SET 回执都 post onParams → refreshValues 对每个可见行 setText(valueText())，而同一标签已被 onChanging 更新过一次。
- 影响：12 可见行 × 25/s ≈ 300 次 setText/s + 300 次字符串分配，拖动帧内多 2–4ms（INFERENCE）。
- 建议：记录 draggingParamId，回执刷新时跳过该行；onParams 合并成一次 post。
- 风险：低；回归面：外部改值同步（拖动行由本地值兜底）。

## 19. 模糊阶梯没有缓存，A→B→A 会完整重算
- 位置：android/app/src/main/java/com/vstplayer/ui/DetailPage.java:395-415（loadBackdrop 只按当前 track 去重）
- 证据等级：FACT
- 问题：backdropFor 只做"同一文件不重复"，Prepared 不缓存。
- 影响：来回切歌每次重算 79.6M MAC（30–100ms）并占住共享 CoverLoader 线程。
- 建议：保留 1 项 Prepared 缓存；但须先改 setPrepared 的所有权接管语义（否则二次 recycle）。
- 风险：中；回归面：bitmap 生命周期、+1.6MB。

## 20. 封面解码采样量化，可能只有目标宽度的 50–75% 后被放大
- 位置：android/app/src/main/java/com/vstplayer/ui/CoverMorph.java:63-77（decode）；ui/HomeView.java:178（px=512）
- 证据等级：FACT（采样逻辑）+ INFERENCE（观感）
- 问题：while (outWidth / s > max) s *= 2 只取 2 的幂；首页请求 512 而显示边长常见 ~900px。
- 影响：700px 源图目标 512 → 350px，再放大到 ~900px（2.6×），观感发虚。
- 建议：首页按视图边长请求，或对 <0.75×max 的补一次缩放。
- 风险：低；回归面：ART_CACHE 内存。

## 21. 每行都新建 GradientDrawable 背景（选中态变化也重建）
- 位置：android/app/src/main/java/com/vstplayer/ui/Ui.java:30-48；ui/TrackList.java:229-231；ui/VstView.java:527,996,1094,1101；ui/LibraryView.java:538,590,693,874,968
- 证据等级：FACT
- 问题：圆角/描边/圆形背景每次 new GradientDrawable 并 setBackground。
- 影响：详情页 500 行换曲 = 1000 个 GradientDrawable/次 + 每行 setBackground 的 invalidate。
- 建议：Row 内缓存普通/选中两个 drawable 复用。
- 风险：低；回归面：深色固定主题，缓存安全。

## 22. TrackList 行副标题重复标题内容
- 位置：android/app/src/main/java/com/vstplayer/ui/TrackList.java:235
- 证据等级：FACT
- 问题：非当前曲目时副标题=文件名，标题已是去扩展名的同一文件名。
- 影响：视觉噪声；每行每次 applyRows 多一次 setText。
- 建议：副标题改为时长/格式/大小，或 GONE。
- 风险：低；回归面：缺失标记"文件缺失"需保留。

## 23. 死代码与误导性 API
- 位置：android/app/src/main/java/com/vstplayer/ui/DetailPage.java:564-585（Blur.tiny/gradient 无调用）、ui/HomeView.java:236-241（parentFolder 无调用）、ui/CoverMorph.java:147-150（setArt 无调用）、ui/Slider.java:39（thick(..., unusedKnob)）、:44/:46（onChange 与 setOnChange 重复）、ui/Icon.java:71-73（stroke 空实现但 8 处传 1.4–2.2f）
- 证据等级：FACT（全项目 grep 无其他引用）
- 问题：4 处死代码 + 3 个无效 API。
- 影响：运行时无影响，但会误导后续优化。
- 建议：删除；thick 去第二参数；stroke 删除或明确 no-op。
- 风险：低；回归面：编译期。

## 24. 四套目录遍历实现，深度/排序规则不一致
- 位置：core/Paths.java:66-84（depth>8）／ui/VstView.java:710-721（depth>8）／ui/LibraryView.java:264-271（depth<3）、:1292-1307、:1004-1013／ui/PlaylistPage.java 逐文件 exists()
- 证据等级：FACT
- 问题：同一件事 4 份实现，深度上限 3 与 8 不同，排序位置不同。
- 影响：一致性 bug 面（列表里有、计数里没有）；新增格式要改 4 处。
- 建议：统一到 Paths.audioFiles(dir, maxDepth, sort)。
- 风险：低–中；回归面：地址树行内计数、渲染源列表、signature。

## 25. Slider 每帧重复 dp 换算 + 上层每 MOVE 一次 String.format
- 位置：android/app/src/main/java/com/vstplayer/ui/Slider.java:66-87（onDraw）、:112-118（update）；调用方 ui/VstView.java:476、ui/DetailPage.java:198、ui/HomeView.java:103
- 证据等级：FACT
- 问题：onDraw 与 update 每次都 Ui.dp（TypedValue.applyDimension+getDisplayMetrics）；拖动时每个 MOVE 上层再 String.format + setText。
- 影响：0.3–1.2ms/帧（INFERENCE），叠加重建类条目会放大掉帧。
- 建议：onSizeChanged 缓存 dp→px；拖动时只在整数百分比变化时 setText。
- 风险：低；回归面：分辨率/密度变化。
