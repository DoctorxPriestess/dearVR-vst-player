# Third-party notices

This repository is licensed under GPL-3.0 (see `LICENSE`). The components below keep their own
licenses, and shipping them does not relicense them. A released APK additionally bundles the guest
environment and the plugin payload described in the table.

## Components in the repository

| Component | Where | License | Upstream |
| --- | --- | --- | --- |
| JUCE (VST3 host framework) | `juce_renderer/` (fetched by CMake, not vendored here) | GPL-3.0 or commercial | https://github.com/juce-framework/JUCE |
| dr_flac | `android/app/src/main/cpp/third_party/dr_flac.h` | public domain / MIT-0 | https://github.com/mackron/dr_flac |
| proot (launcher binary) | `android/app/src/main/assets/proot` | GPL-2.0 | https://github.com/termux/proot |
| patchelf (binary) | `android/app/src/main/assets/patchelf` | GPL-3.0 | https://github.com/NixOS/patchelf |
| libandroid-shmem (binary) | `android/app/src/main/assets/libandroid-shmem.so` | MIT | https://github.com/termux/libandroid-shmem |
| VST3 SDK headers | `legacy/vst3sdk_host/` (dead reference code, SDK not vendored) | GPL-3.0 / proprietary dual | https://github.com/steinbergmedia/vst3sdk |

`proot`, `patchelf` and `libandroid-shmem.so` are redistributed as unmodified binaries so that the
APK can start its guest. Their sources are available at the upstream addresses above; if you need a
copy from this project, open an issue and one will be provided.

## Components bundled in the released APK

| Component | License | Upstream |
| --- | --- | --- |
| wine (Windows API layer) | LGPL-2.1 | https://gitlab.winehq.org/wine/wine |
| box64 (x86_64 emulator) | MIT | https://github.com/ptitSeb/box64 |
| proot | GPL-2.0 | https://github.com/termux/proot |
| Termux glibc/rootfs | per-package licenses | https://github.com/termux/termux-packages |
| dearVR PRO 2 (VST3 plugin) | proprietary, released free of charge by its vendor | https://www.dear-reality.com/ |

The APK is an aggregate of independent programs (the app, the guest environment and the plugin);
none of them are linked into a single derived work with this project's GPL-3.0 code.

### dearVR PRO 2

Dear Reality, part of the Sennheiser Group since 2019, retired its plugins: the license manager was
deactivated on 2025-07-31, and Sennheiser distributes the installers free of charge for use
"without license activation or restrictions"
([announcement](https://help.sennheiser.com/hc/en-us/articles/28365062393874-Transition-of-Dear-Reality-Important-Information-for-Existing-Users)).
The plugin remains proprietary; all rights stay with its rights holders. It is included here only so
that the released APK works out of the box, with attribution, in the spirit of that free release.
If you are a rights holder and want it removed, open an issue and it will be taken down promptly.
