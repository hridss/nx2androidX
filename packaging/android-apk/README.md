# packaging/android-apk — the phone app

An Android app that runs **nx2android on the phone**: the porter turns the Switch game files into a data pack, on
the device itself. The app is a shell — the environment (the Linux rootfs, the build tools, the .NET runtime) is
unpacked on the first start, and the porter runs inside it; the screen is Android's own.

```
┌─ the app (com.nx.android) ─────────────────────────────────────┐
│  Setup: prod.keys                        Port: game file       │
│  [准备环境]                                 [开始移植]            │
│  the log: the porter's own output                             │
└────────────────────────────────────────────────────────────────┘
        │  the guest programs are Linux binaries
        ▼
files/rootfs                    the nixport payload (the patched glibc, Python 3.13, coreutils)
files/tool/porter/              the porter (the pipeline) and the C# tools it runs
files/tool/nxnative/            the engine (the host, the graphics)
files/tool/pylibs/              the Python modules (lz4, capstone, pillow, fonttools, pyyaml, pycryptodome)
files/tool/tools/               the runtime tools (nx_extract, dmsh_xlate) and the Vulkan loader's hooks
files/tool/toolchain/clang/     the ARM64 LLVM (clang)
files/tool/toolchain/ndk/       Google's NDK (its sysroot and compiler-rt)
files/tool/toolchain/lib/       the tools' own libraries (libshaderc, which glslc needs)
files/tool/toolchain/bin/       glslc
files/tool/toolchain/dotnet/    the .NET runtime (the C# tools run on it)
files/nx2a/                     the porter's home: the imported game profiles, the build products, the
                                NuGet cache — the player's data, which a rebuild never touches
```

## The environment is one archive with one stamp

The whole environment travels in **one gzip tar** in the APK's assets:

| Asset | What it is |
|---|---|
| `assets/payload.pkg` | the environment: the rootfs and the tool's tree (the compiler, the .NET runtime) — 178 MB in the APK, 553 MB unpacked |
| `assets/payload-stamp` | the SHA-256 of that archive: the version stamp, one number for the whole environment |
| `assets/manifest.json` | what is in there (the payload's size, the Python modules) |
| `assets/profiles.json` | the manifest of the game profiles — the profiles themselves are a separate artifact the player imports |
| `assets/drivers/` | the Vulkan driver (Turnip) |

The permissions travel with the tar, so there is no separate modes table.

At the app's start the stamp decides: when it differs from the one the phone's environment was built with, the
whole environment is built again — one blocking progress dialog, the old tree goes, the archive is unpacked, the
interpreters are patched, the guest is checked, and the stamp is written last. About 6 s on a flagship tablet, 8 s on
a slower one. A build that was cut short (the process died) is done again at the next start, because the stamp was
not written. **Only `rootfs` and `tool` are touched**: the porter's own folder (`nx2a`: the imported game profiles,
the build products, the NuGet cache) and the player's other things are never written or deleted.

No network is needed at any point.

## Building it

```bash
bash packaging/android-apk/build_apk.sh          # the payload and a debug APK
bash packaging/android-apk/build_apk.sh release  # a release APK

# only the app, without the payload (the phone already has the environment unpacked):
bash packaging/android-apk/build_apk.sh debug --no-payload
cd android && ./gradlew :app:assembleDebug -PnoPayload
```

The payload is what makes the APK big (178 MB of it; the APK is 215 MB). The environment itself lives in the app's
own folder, so a build that leaves the payload out keeps it: the app sees that the environment is there and only
runs its check.

The script needs the `gh` CLI (the rootfs comes from the public supply channel
`NixPort/nixport-rootfs@payload-latest`), Python 3.11+, the Android SDK and the .NET SDK (the C# tools are built
here — the phone cannot build them). The APK lands in `android/app/build/outputs/apk/`.

The steps, in the order they have to run (the script does them in this order):

1. the rootfs from the nixport payload release (the patched glibc, Python and the rest of the runtime)
2. the build tools: Google's NDK (its sysroot and compiler-rt) + an ARM64 LLVM + glslc from Debian
3. the tool's own tree and the Python modules, staged and packed into `payload.pkg`
4. the C# tools (`nx_extract`, `dmsh_xlate`) — built on the computer, because the phone has no SDK and no network
5. the Vulkan driver loader (the phone has no C++ compiler)
6. the archive is packed again, so it holds the C# tools and the loader's hooks
7. the APK (Gradle)

## The parts

| File | What it does |
|---|---|
| `build_apk.sh` | the whole build: the payload, the build tools, the assets, the C# tools, the APK |
| `fetch_debs.py` | Debian's arm64 packages (glslc) with their dependencies, checked against the index's SHA-256 |
| `build_toolchain.py` | the build tools: the NDK's sysroot and compiler-rt (from Google's NDK), an ARM64 clang (from the community build) and glslc |
| `build_assets.py` | the app's assets: the rootfs, the tool's tree, the Python modules, the build tools, the .NET runtime — packed into `payload.pkg` and stamped |
| `build_nx_extract.py` | the C# tools for the phone (`nx_extract`, `dmsh_xlate`): built here, run there |
| `build_adrenotools.py` | the Vulkan driver loader (the host dlopens it by name) and its four hooks |
| `rootfs_trim.py` | trims the nixport rootfs down to what the phone needs (the closure of the libraries, the Python, the programs) |
| `patch_interp.py` | sets the ELF interpreter of the programs the porter starts itself (the compiler, the .NET runtime) |
| `fix_globalization.py` | puts the .NET runtime into the invariant globalization mode (the rootfs has no ICU) |

## Why the interpreter is rewritten

Android has no `/lib/ld-linux-aarch64.so.1`, and the guest is not a chroot (nixport runs the programs as they are,
without proot). The rootfs the payload carries was built for another app's path, and the compiler and the .NET
runtime are ordinary Linux programs. So the app rewrites their interpreter path to its own rootfs before it starts
them; the Python of the rootfs is started through the loader directly and needs no rewriting.

## The .NET runtime, not the SDK

The phone runs the C# tools (the extractor and the shader translator) but cannot build them: no SDK, no network.
They are built on the computer and shipped inside the archive. What the phone carries is therefore the .NET
**runtime** (85 MB unpacked), next to the compiler in `tool/toolchain/dotnet`; the app points the porter at it with
`DOTNET_ROOT`, and the porter's own finder takes it from there. There is no `--no-dotnet` build option and no
in-app SDK downloader any more: one archive, one stamp, and the environment is either there or it is rebuilt.

## What the phone does and does not do

- **Makes the data pack** on the phone, into the app's own private folder (`files/ports/<Game>-data`).
- **Says what a port is**: the pack's manifest carries the profile's own status, so the library shows an experimental port as one
  (`experimental` = the game was never brought up on Android: it may stop at a path that was never walked).
- **Installs the APK** with the system installer (the app hands it over; Android asks the user).
- **Copies the data pack** into the game's own folder (`/sdcard/Android/data/<package>/files`), where the game reads it.
- **No adb**: Google ships platform-tools for Linux as x86-64 only, so they cannot run on an ARM64 phone. The tool
  cannot install on a device by itself. The games whose shaders sit in Oodle-compressed archives (Pokémon Legends) are
  ported on the phone all the same: the phone is the ARM64 machine that runs the game's own decoder, so the port decodes
  them here, with no adb and no second device.
