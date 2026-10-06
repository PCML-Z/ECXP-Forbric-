# 仓库结构

[English below](#english)

ECXP-Forbric+ 是 Forbric 的增强分支，不是把内核推倒重写。整理时把「玩家会碰到的入口」和「实测出来的兼容层」分开。

## 现在这条线

| 目录 | 作用 | 谁调用 |
| --- | --- | --- |
| `forbric-kernel/` | 加载器本体。发现 mod、启动、Mixin、三套生态的衔接 | 安装器写进版本 JSON 的 `mainClass` |
| `forbric-kernel-installer/` | 在玩家机器上组装游戏并写出 `versions/<mc>-ecxp-forbric` | PMCL、双击安装 |
| `tools/dev.py` | 开发机准备 `.dev/`、编译、启动 | 本地和 CI 的准备步骤 |

版本号后缀是 `-ecxp-forbric`，和上游 Forbric 的 `-forbric` 分开。库坐标仍是 `net.forbric:*`，因为内核按这些坐标找自己的 jar。PMCL 安装时会把本加载器用到的那一份复制到带 `-ecxp` 的坐标下，避免盖掉已经装好的 Forbric。

## 留着但不是入口

| 目录 | 为什么还在 |
| --- | --- |
| `forbric-loader/` | 第一代加载器。`bootstrap.sh` 给它打 Fabric Loader 底座补丁，合并工具也从这里构建。当前内核不走它的 Knot 启动。 |
| `legacy/forbric-installer/` | 第一代安装器，模式是 `intermediary-v1` / `full-forge-26.2`。命令、版本号都和内核安装器不同。不要从这里给玩家打包。 |

## 内核包，按职责

`net.forbric.kernel` 下面文件很多，是因为每一处衔接都对应一个实测过的故障，不是因为目录没人管。锚点、指纹和闸门是对着具体字节写的。合并基底的修复已经按族拆到 `MergedBase*Repair`，调度顺序仍在 `ForbricMergedBaseCompatTransformer`。不要再拆 mixin，也不要改这个顺序：不跑游戏夹具就改字节，只会让 mod 静默失效。

| 包 | 做什么 |
| --- | --- |
| `boot` | 启动顺序、mod 发现之后的生命周期、重复 mod 仲裁 |
| `transform` | 合并基底上的字节码修复。`ForbricMergedBaseCompatTransformer` 保持顺序和声明，具体修复在 `MergedBase*Repair` |
| `mixin` | 把写给原版或某一加载器的 mixin 挪到合并后的方法上，并对不上的记下来 |
| `fabric` | Fabric Loader API 的实现。Fabric Loader 本身不运行 |
| `interop` | 供客方字节码反射调用的着陆点：`PayloadInterop`（自定义包的编解码与分发，分 Fabric API / MinecraftForge / NeoForge 三路）、`ClientShutdown`、`ForgeRuntimeInterop`（Forge/NeoForge 的 `FluidType` ABI 分叉）。**物品、流体、能量的跨生态传输不在这个包**，在 `src/runtime` 的 `runtime/transfer/`（`KernelTransferInterop` + `runtime/transfer/`），且只在相关 API 实际存在时启用 |
| `metadata` | 读取三种生态的 mod 清单 |
| `classloading` | 内核自己的类加载器 |
| `discovery` | 找出 `mods` 里的 jar，并判断它属于哪一家 |
| `ui` | 缺前置和兼容性提示 |
| `config` | `forbric/forbric.toml` |
| `mapping` | 从第一代沿用的映射代码，不在当前 26.2 启动路径上 |
| `access` / `util` / `soak` | 访问扩展、小工具、浸泡测试 |

游戏侧源码在 `forbric-kernel/src/runtime`，只有准备好合并基底之后才会编译进运行时 jar。

## 文档放哪

玩家说明在仓库根目录。内部说明、兼容性记录和多版本计划在 `docs/`。上游 0.3.0 的长文留在 `docs/upstream-readme*.md`，里面的「只支持 26.2」和上游仓库地址不要当成这一版的说明。

# English

ECXP-Forbric+ is an enhanced Forbric tree, not a rewrite. The layout separates the player entry points from the measured compatibility layer.

Current line: `forbric-kernel/` runs the game, `forbric-kernel-installer/` writes `versions/<mc>-ecxp-forbric`, and `tools/dev.py` prepares a developer machine. `forbric-loader/` remains for the first-generation substrate and merge tools. `legacy/forbric-installer/` is the old installer and is not what ships.

Packages under `net.forbric.kernel` stay as they are. The merged-base repairs now live in `MergedBase*Repair`; `ForbricMergedBaseCompatTransformer` still runs them in the order the anchor census pins. `mixin` stays in its measured classes. Splitting those without the game-fixture suite would drop mods silently. The Java package name stays `net.forbric`; the profile suffix `-ecxp-forbric` is what keeps this install beside upstream Forbric.
