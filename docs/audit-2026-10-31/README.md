# 审计原文（2026-10-31）

这三份清单是 2026-10-31 那轮优化的取证材料，**只读快照**，行号对应当时的树：

| 文件 | 范围 |
| --- | --- |
| `ui.md` | `android/app/src/main/java/com/vstplayer/ui/` 与 `MainActivity`，25 条（视觉流畅度） |
| `core.md` | `android/app/src/main/java/com/vstplayer/core/`，25 条（启动、功耗、正确性） |
| `native.md` | `android/app/src/main/cpp/`、`juce_renderer/`、`pc_renderer/`，25 条（解码、宿主、构建） |

每条都带位置、证据等级（FACT / INFERENCE / UNKNOWN）、影响量级、最小修法与回归面。
最终采纳了什么、放弃了什么，见上级目录的 `OPTIMIZATION-2026-10-31.md`。

> 注意：文件行号会随代码漂移，改动后请按符号名定位。
