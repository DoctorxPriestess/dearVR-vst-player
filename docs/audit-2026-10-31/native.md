# 原生侧审计清单（lane: native-audit）

来源：只读审计 D:\AI Misc\vst3_player_optimized，2026-10-31。
范围：android/app/src/main/cpp/**、juce_renderer/**、pc_renderer/render.ps1、根 CMakeLists.txt、src/main.cpp。

> 行号对应审计时（改动前）的文件状态。后续改动会移动行号，以本文件中的符号名为准。

---

**1. 位置：** juce_renderer/src/juce_main.cpp:35-37（readPreset）
**证据等级：** FACT（代码可见）／INFERENCE（越界可达性）
**问题：** off/sz 取自 .vstpreset 尾部表，仅校验 <0，(uint64_t)(off + sz) 是有符号加法先溢出再转换；溢出后 d.begin()+off 的越界检查被绕过。
**影响：** 加载损坏/第三方 .vstpreset 时触发 UB 或堆越界读；App 的 PRESET| 命令与 -preset 都走此路径，属不可信输入。
**建议改法：** 改为 if (off < 0 || sz < 0 || (uint64_t)off > (uint64_t)n || (uint64_t)sz > (uint64_t)n - (uint64_t)off) continue;
**风险：** 低；回归面仅 preset 读取。

**2. 位置：** juce_renderer/src/juce_main.cpp:294（renderFile）
**证据等级：** FACT
**问题：** writer->writeFromFloatArrays() 返回值丢弃，writer.reset() 前无 flush() 检查。
**影响：** 磁盘满/写错误时仍打印 OK <out> 且退出码 0，产出被截断的 WAV。5 分钟 24bit/48k 立体声约 86 MB。对用户是"渲染成功但音频短了一截"。
**建议改法：** if (!writer->writeFromFloatArrays(chans,outCh,n) || !writer->flush()) return false;（使协议回 ERR）
**风险：** 低。

**3. 位置：** android/app/src/main/cpp/nd_decode.c:417-420（nd_decode_alac_file，nd_remove_file 见 :41-50）
**证据等级：** FACT
**问题：** rc != ND_OK 一律 nd_remove_file(out)，但 goto done 可能发生在 nd_wav_begin 之前（:337/:343/:350/:356），此时输出文件根本没创建。
**影响：** 输入 MP4 的 packet table 损坏/包尺寸异常时，会删掉 out 路径上已存在的无关文件——用户可见数据丢失。FLAC 路径无此问题。
**建议改法：** 增加 int out_created = 0; nd_wav_begin 成功后置 1，仅 out_created && rc != ND_OK 时删除。
**风险：** 低；回归面 ALAC 失败路径。

**4. 位置：** juce_renderer/src/juce_main.cpp:39（readPreset 返回 true）+ :44-47（applyPreset 空 comp 直接 return）
**证据等级：** FACT
**问题：** 尾部表里没有 Comp/Cont 时仍返回成功，applyPreset 静默什么都不做。
**影响：** 加载 foobar 的 VC2! 容器 preset（render.ps1:44-46 注释自述此坑）时打印 preset applied: comp=0 cont=0，用户以为换了预设，实际参数没变 → 渲染结果错误且无告警。
**建议改法：** return !out.comp.empty();，并在 caller 打印失败原因。
**风险：** 低；回归面 preset 加载（render.ps1 已做 repack，不受影响）。

**5. 位置：** juce_renderer/src/juce_main.cpp:549-551
**证据等级：** FACT
**问题：** writeVstPreset 失败被忽略，-save 单独使用时仍 return 0。
**影响：** CLI/脚本无法通过退出码判定保存失败。
**建议改法：** if (!writeVstPreset(...)) { std::cerr << saveError; return 7; }
**风险：** 低；回归面 PC 侧脚本需知新退出码。

**6. 位置：** pc_renderer/render.ps1:301 + :308
**证据等级：** FACT
**问题：** PROG 由 protocolLine() 写 stdout（juce_main.cpp:110），脚本却从 stderr 重定向文件 $logErr 里 grep ^PROG；stdout 被丢进 $logErr.out 且从不读取。
**影响：** 进度百分比永远不显示（$lastPct 恒 -1），长文件期间用户看到的就是静默，误以为卡死。
**建议改法：** 改读 ($logErr + '.out')，或让渲染器把 PROG 写 stderr。
**风险：** 低。

**7. 位置：** juce_renderer/src/juce_main.cpp:264-267
**证据等级：** FACT（行为）／INFERENCE（触发条件）
**问题：** 先 File(outPath).deleteFile() 再 createOutputStream()，不判断 in/out 是否同一路径；reader 仍持有句柄。
**影响：** -in a.wav -out a.wav 时 Windows 下 delete 失败被忽略，随后 createOutputStream 截断正被读取的输入 → 渲染出静音/垃圾，且返回成功。
**建议改法：** if (File(inPath) == File(outPath)) 拒绝；并检查 deleteFile() 返回值。
**风险：** 低。

**8. 位置：** android/app/src/main/cpp/jni_bridge.c:59-69
**证据等级：** FACT
**问题：** GetStringUTFChars 返回 NULL 时直接返回错误码，未 ExceptionCheck()/ExceptionClear()（JNI 此刻有 pending OutOfMemoryError）。
**影响：** 内存紧张时 Java 端收到 OutOfMemoryError 而不是约定的负数错误码，lastError() 语义失效。
**建议改法：** 失败分支先 ExceptionCheck/ExceptionClear 再存错误并返回。
**风险：** 低；回归面仅 JNI 错误路径。

**9. 位置：** android/app/src/main/cpp/nd_mp4.c:15（ND_MP4_MAX_MOOV_BYTES）+ :444（moov 整块 malloc）+ :382（offsets）+ :209（sizes）
**证据等级：** FACT（上限值）／INFERENCE（峰值与设备余量）
**问题：** moov 上限 64 MB 一次性读入，随后按 sample_count（上限 8 M）再分配 64 MB offsets + 32 MB sizes。
**影响：** 畸形 m4a 可让 native 峰值内存到 ~160 MB，Android 低端机可能 OOM。正常文件（1 小时 44.1k/4096 ≈ 3.9 万包）约 0.5 MB。
**建议改法：** moov 上限降到 16 MB，并在 sample 表分配前用 file_size 做上限约束。
**风险：** 中低；回归面：极大 moov 的合法文件（罕见）。

**10. 位置：** android/app/src/main/cpp/nd_alac.c:211、:268、:284-292（(uint32_t *)out 等）
**证据等级：** FACT（类型双关存在）／UNKNOWN（编译器是否利用）
**问题：** 以 uint32_t* 读写 int32_t 对象，违反严格别名规则。
**影响：** NDK Clang 高优化等级下理论上可被重排；-O3 -flto 或换工具链后可能出现解码值与参考不一致的"幽灵 bug"。
**建议改法：** 统一用 uint32_t 做算术、再用 nd_sign_extend 转 int32_t；或该编译单元加 -fno-strict-aliasing 并注释说明。
**风险：** 中；回归面 ALAC 位精确性。

**11. 位置：** juce_renderer/src/juce_main.cpp:312（main(int argc,char** argv)）+ pc_renderer/render.ps1:20 注释
**证据等级：** FACT（脚本注释与 char** 入口）/ INFERENCE（影响面）
**问题：** 控制台程序走窄字符 argv，Windows 上非 ASCII 路径按 ANSI 代码页解码成乱码；脚本只能用 ASCII 临时目录绕过。
**影响：** 中文用户名或中文插件目录时 plugin not found / cannot read；PC 端渲染器对中文路径整体不可用。
**建议改法：** 用 GetCommandLineW + CommandLineToArgvW 重建参数（最小改：仅 Windows 分支）。
**风险：** 中。（Lead 决定不做）

**12. 位置：** 根 CMakeLists.txt:1-61（sdkhost/vstrender/validator）、src/main.cpp、third_party/vst3sdk/（37 MB）
**证据等级：** FACT（全仓 grep：vstrender 仅出现在根 CMakeLists 与 OPTIMIZATION.md；vst3sdk 仅被根 CMakeLists 引用；实际出厂的 pc_renderer/vstrender_juce.exe 来自 juce_renderer/）
**问题：** VST3-SDK 版宿主与整套 SDK 是死代码，附带 validator（file(GLOB_RECURSE ...)）拖长构建。
**影响：** 仓库多 37 MB + 可维护性陷阱。无运行时影响。
**建议改法：** 删除根 CMakeLists 与 src/main.cpp，或归档到 legacy/。
**风险：** 中。（Lead 决定不做）

**13. 位置：** src/main.cpp:127（readWav）
**证据等级：** FACT
**问题：** frames = dataLen / (bits / 8) / ch; bits 未校验；data 出现在 fmt 之前或 fmt 不合法时 bits==0 → 整数除零（SIGFPE）。
**影响：** 畸形 WAV 直接崩进程。当前属死代码。
**建议改法：** 增加 bits < 8 校验并把 bits/8 换成已识别的 bytesPerSample。
**风险：** 低；回归面仅该废弃工具。

**14. 位置：** nd_decode.c:85-90（44 字节头）、:109-112（>4 GB 直接失败）、:139-145（nd_store_sample）
**证据等级：** FACT
**问题：** 固定 44 字节 RIFF 头，不支持 RF64；data 为奇数长度时不补 pad 字节；>4 GB 时中途放弃并删文件。
**影响：** 8ch/192k 长素材解码全部完成后才失败，白跑；奇数长度 WAV 被严格解析器判为损坏。
**建议改法：** 在 nd_wav_begin 前预估并拒绝 >4 GB；奇数补 pad 并 +1 RIFF size。
**风险：** 低。（本轮未做）

**15. 位置：** nd_alac.c:326-327
**证据等级：** FACT（拒绝逻辑）/ INFERENCE（参考实现行为）
**问题：** bps = bit_depth - extra + channels - 1，32bit + 双声道 → 33 > 32 被拒。
**影响：** 合法 32bit 立体声 ALAC 报 ND_ERR_DECODE，误导用户以为文件坏了。
**建议改法：** 对 bps == 33 单独处理或返回 ND_ERR_UNSUPPORTED 并给准确文案。
**风险：** 低。（本轮未做）

**16. 位置：** nd_alac.c:142-144（nd_decode_scalar）
**证据等级：** FACT
**问题：** k <= 32 的守卫允许 k==32，随后 x << k 是 uint32_t 左移 32 位（UB）；当前 kb<=24 使该路径不可达。
**影响：** 今日不可触发；放宽 kb 上限即静默出错。
**建议改法：** 守卫改为 k < 1 || k > 31，或用 64 位中间量。
**风险：** 低。

**17. 位置：** nd_common.h:87-105（nd_br_get）、:108-125（nd_br_peek）、nd_alac.c:131-154
**证据等级：** FACT（实现）/ INFERENCE（量级）
**问题：** 每次取值都先无条件展开 8 次"逐字节装载 + 64 位移位/或"；nd_decode_scalar 还先 peek 再 skip，同一批字节被解析两遍。
**影响（性能）：** 每样本约 16-24 次字节装载 + 16 次 64 位移位。按 ARM64 每步 1-2 ns 估，44.1k 立体声 ≈ 88.2k 样本/s → 约 2-3.5 ms 每 1 s 音频，即 ALAC 解码主要开销。改成 64-bit 窗口缓存预计省 50-70%。
**建议改法：** nd_br 内加 64 位缓存窗口与 refill，get/peek 走一次移位；decode_scalar 用 peek 结果直接推进。
**风险：** 中；回归面：所有 ALAC 位精确性（必须逐样本对拍）。

**18. 位置：** nd_alac.c:230-246（自适应 FIR 内层循环）
**证据等级：** FACT（结构）/ INFERENCE（量级）
**问题：** 每样本 order 次乘加，pred[i-order+j] 每次按索引重读，前半段还有一次系数更新循环。
**影响（性能）：** order=31 时约 62 次整数乘加/样本 → 88.2k 样本/s 约 5.5 M 次/s 音频，估算 2-4 ms 每 1 s 音频，与位读取同量级；滑动窗口仅减 load，收益 ≤10-15%。
**建议改法：** 仅局部优化（指针递推替代双重索引），不做算法替换。
**风险：** 中。

**19. 位置：** nd_mp4.c:207-213（stsz 二次分配覆盖指针）、:266-267（未校验 stsc_first[0] == 1）
**证据等级：** FACT
**问题：** 同名 box 重复出现时 t->sizes 被直接覆盖 → 前一次分配泄漏；stsc 首块索引不等于 1 时按 stsc_spc[0] 处理，与规范语义不符。
**影响：** 泄漏 4×count（畸形文件可到 32 MB）；首块索引异常时包边界错位，输出音频错乱但不越界。
**建议改法：** stsz 分配前 free 旧指针；stsc 校验 stsc_first[0]==1 或跳过未覆盖 chunk。
**风险：** 低。

**20. 位置：** nd_mp4.c:455-456
**证据等级：** FACT
**问题：** if (type == mdat && sz == 0) break; 是死分支——sz==0 已在 :436-438 被替换为 fsize-off。
**影响：** 无功能影响，但读代码者会误解停止条件。
**建议改法：** 记住原始 sz 或在解析前判断。
**风险：** 低。（本轮未做）

**21. 位置：** nd_common.h:195-209（nd_fopen_utf8）、nd_decode.c:41-50（nd_remove_file）
**证据等级：** FACT
**问题：** 固定 wchar_t wpath[1024]，长路径静默失败。
**影响：** Windows 深目录/长中文路径报 cannot open input；失败清理残留半个 WAV。Android 走 POSIX 分支不受影响。
**建议改法：** 先求长度再分配。
**风险：** 低。（Lead 决定不做）

**22. 位置：** android/app/src/main/cpp/CMakeLists.txt:21、pc/CMakeLists.txt:22,25、根 CMakeLists.txt
**证据等级：** FACT（当前开关）/ INFERENCE（开启后的告警数量）
**问题：** 仅 -O2 -Wall -Wno-unused-function（MSVC 仅 /W3）；-Wno-unused-function 是必需的，因为 nd_common.h:163 的 nd_rd_u24be 全仓无引用。
**影响：** -Wextra 预计接近零告警；-Wconversion/-Wsign-conversion 会成片报 size_t↔int 混用（nd_common.h:98-102、nd_mp4.c:185-191,259-283、nd_decode.c:153-169、nd_alac.c:190-198，约 10-20 处）；-Wstrict-aliasing=2 命中 #10。
**建议改法：** 先只开 -Wall -Wextra；删掉 nd_rd_u24be 后去掉 -Wno-unused-function。
**风险：** 低。

**23. 位置：** juce_renderer/src/juce_main.cpp:292-294（chans[32] + writeFromFloatArrays(chans, outCh, n)）、:287（srcCh = jmin(ch, inBuf.getNumChannels()-1)）
**证据等级：** FACT
**问题：** chans 固定 32 项但循环上限是 min(outCh,32)，写出时传 outCh；多输出通道（>32）插件会读未初始化栈指针。inBuf 声道数为 0 时 srcCh == -1 负索引。
**影响：** dearVR 2 进 2 出不触发；换多通道插件即栈越界读/崩溃。
**建议改法：** 拒绝 outCh > 32；srcCh 下限 0。
**风险：** 低。

**24. 位置：** juce_renderer/src/juce_main.cpp:22-25
**证据等级：** FACT
**问题：** n 为 int64，in.read(d.data(), (int)n) 在 >2 GB 时截断为负；std::vector<char> d((size_t)n) 抛 bad_alloc，非 serve 路径的 readPreset 不在 try 块内。
**影响：** 2 GB+ preset 让进程 std::terminate 且无诊断。概率极低。
**建议改法：** 上限 64 MB + 检查 in.read 返回值 + 主流程 try/catch。
**风险：** 低。（本轮未做）

**25. 位置：** nd_decode.c:331-340（预扫 maxpkt）+ :376/nd_mp4.c:576（同包两次 packet_info）+ :353（malloc(maxpkt)）
**证据等级：** FACT（结构）/ INFERENCE（量级）
**问题：** 包表被遍历两遍；maxpkt 上限 64 MB，先 malloc 备用。
**影响（性能/内存）：** 双遍历 < 10 µs 每 1 s 音频（可忽略；MP4 box 遍历线性、无 O(n²)）；真正可优化的是异常文件固定占 64 MB。
**建议改法：** maxpkt 上限收到 8 MB，或按需增长。
**风险：** 低。

---

## 已核对、确认不是问题
- nd_flac_impl.c 3 行是 DR_FLAC_IMPLEMENTATION 单编译单元，正确且必需。
- third_party：JUCE（129 MB）被 juce_renderer 使用，vst3sdk（37 MB）只被死掉的根 CMakeLists 引用，dr_flac.h 被 Android 解码库使用。
- 分配过小/整数溢出：nd_wav_begin（4096×≤32B）、nd_alac_create（frame_length≤65536 ×4B）、stsz/stco/offsets（均有 count ≤ (pl-hdr)/stride 双重校验）——未发现 w*h*ch 型溢出。
- JNI 局部引用：无泄漏；GetStringUTFChars 四条路径均正确 Release（#8 只是异常未清）。
- ALAC 解码器无每帧 malloc：residual/samples/shiftbuf/chan/pkt 全部预分配；唯一的每块 malloc 在死代码 src/main.cpp:168。
- FLAC 路径（nd_decode.c:218-305）资源释放与截断检测正确。
