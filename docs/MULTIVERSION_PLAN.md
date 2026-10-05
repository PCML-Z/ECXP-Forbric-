# 多版本实施计划：Minecraft 26.2 / 1.21.8 / 1.21.1

本文件记录 Forbric 多版本支持的分阶段落地路径。已完成的部分是**阶段 0（pin 层参数化）**；其余阶段是把某个世代真正跑起来所需的工作。

这不是一个"加几个版本号"的改动。Forbric **不是版本无关**的：它按 Minecraft 世代硬绑定，每个世代的命名空间策略、Forge/NeoForge 工具链与字节码锚点都是针对那个确切构建实测出来的。

---

## 0. 世代模型（已落地）

`Pins` 从单常量改为按 Minecraft 版本索引的 `PinSet` 集合：

| 世代 | 命名空间 | 载体 | 工具链 | 状态 |
|------|---------|------|--------|------|
| **26.2**（默认） | Mojmap identity（`named`） | 三载体 | NeoFormRuntime 2.0.18 | 完整可用 |
| **1.21.8** | intermediary | 三载体 | NeoFormRuntime 2.0.18（同一管线） | pin 已核实可下载；锚点待重测 |
| **1.21.1** | intermediary | 三载体 | NeoFormRuntime 2.0.18（同一管线） | pin 已核实可下载；锚点待重测 |

### 已核实的上游事实（勿再凭推断改）

| 世代 | Minecraft | MinecraftForge | NeoForge | NeoForm | binarypatcher |
|------|-----------|----------------|----------|---------|---------------|
| 26.2 | 26.2 | `26.2-65.0.1` | `26.2.0.88` | `26.2-2` | 4.0.17 |
| 1.21.8 | 1.21.8 | `1.21.8-58.1.22` | `21.8.54` | `1.21.8-20250717.133445` | 3.0.13 |
| 1.21.1 | 1.21.1 | `1.21.1-52.1.16` | `21.1.255` | `1.21.1-20240808.144430` | 2.1.2 |

三条容易踩错的推断，这里已被上游数据否掉：

1. **1.21.1 有 NeoForge（21.1.255）。** "NeoForge 从 1.20.5 分家"说的是**它线上的起点**，不是覆盖范围的终点——此后每个 1.21.x 都有自己计数器的 NeoForge（21.1.x / 21.2.x / 21.4.x / 21.8.x …）。所以 1.21.1 **不需要**二载体合并基底降级，与另两个世代同为三载体。
2. **Forge 版本号不能按 fml 数字猜。** 1.21.8 的线是 `58.1.x`（不是 54.x，那是 1.21.4 的线），1.21.1 是 `52.1.x`（没有 52.0.x）。Forge 与 NeoForge 各有独立计数器，必须查 maven-metadata。
3. **NeoFormRuntime 不需要按世代换版本。** NFRT 从 NeoForge userdev 的 `config.json` 里读 neoform 坐标，自身不含 MC 版本；`seedArtifacts` 只把本地 jar 按 `minecraft_<ver>_client.jar` 命名喂给它。所以同一个 NFRT 2.0.18 覆盖三个世代（26.2 侧的 2.0.18 是按字节校验过的 pin，新世代沿用后应再做一次字节比对）。

关键设计点：
- `PinSet` 是 record，`hasNeoForge()` 对三个世代都为 true；`ArtifactBuilder` 仍保留一个"空 neoforge pin 即报错"的守卫，以防将来新增世代漏填。
- `forVersion(mc)` 对未知版本**抛错**，绝不回退到 26.2（静默回退会造出"载体是 A、profile 声称 B"的错配）。
- `BuildStamp.key(mcVersion)` 把 Minecraft 版本折进缓存键，**不同世代永不共享缓存条目**。所有 builder 的 `isFresh`/`write` 都已带上 `mcVersion`。
- `Main --mc` 会对未注册版本直接退出并列出支持列表。
- `GameArtifacts.runtimeProblem` 只把 manifest 的 `Implementation-Version` 与 pin 字符串比对，不硬编码任何世代——天然通用。

已参数化并通过编译/语法验证的入口：
- `forbric-kernel/build.gradle`：`-Pforbric.mcVersion=`（默认 26.2），merged base / fabric-api / 版本 JSON 路径全部跟随；错误提示报实际版本。
- `forbric-kernel/run/lib.sh`：导出 `MC_VER`（默认 26.2），58 个 gate 共用。
- `launch-kernel-{server,client}.sh`、`launch-vanilla-server.sh`、`build-merged-base.sh`：`MC_VER`（+ `FORGE_VER`）参数化。
- `gate-m0/m26/m31/m39/m17`：结构性路径与 profile 名参数化。
- `DevPrepare`（kernel 开发暂存）：接受可选第 4 参数 = mc 版本。

---

## 1. 每世代必须重测的锚点（路线 A 的真正工作量）

声明只是"意图"，下面这些才是"能不能跑"：

| 文件 | 行数 | 作用 | 重测方式 |
|------|------|------|----------|
| `kernel/transform/vanilla-early-returns.txt` | 3269 | vanilla 早期返回的字节码指纹 | 逐条重新实测该世代 vanilla |
| `kernel/mixin/native-only-methods.txt` | 1062 | Mojmap 方法签名 + 末尾 platform 版本声明 | 重新生成；末尾三行 `platform … minecraft=<v>` 需按世代改写 |
| `kernel/mixin/carrier-renames.txt` | 394 | 载体类重命名 | 跑 `CarrierRenameCensusTest` 生成器 |
| `kernel/mixin/uncalled-methods.txt` | 210 | 未被调用的方法 | 重新实测 |
| `ForgeTransferShapeAudit.AUDITED` | 24 个 SHA-256 | transfer 形状指纹 | 重算；**注意失败语义**：指纹不符 → 拒绝写入，transfer 相关功能会静默降级 |
| `canary/*`（31 个） | — | 事件/类名版本特定 | 按新世代 API 重写 |

---

## 2. 分阶段路线

A1 与 A2 现在是**同构的**（都是三载体 + 同一 NFRT 管线），差别只在世代本身，工作量也相近。

### 阶段 A1 — 1.21.8（intermediary）
1. 跑 `MergedBaseBuilder` 产出 1.21.8 的 patched-mc-merged，重测第 1 节全部锚点。
2. 重算 `ForgeTransferShapeAudit.AUDITED` 的 24 个指纹。
3. 1.21.8 是 intermediary，**需要 Fabric intermediary jar**（已核实 maven 上存在）配合 `ForgeModRemapper` 把 Mojmap 的 Forge 字节码 remap 进 intermediary。
4. 跑 `MC_VER=1.21.8 ./gates-all.sh`，逐个修到绿。

### 阶段 A2 — 1.21.1（intermediary，与 A1 同构）
1. 同 A1 第 1–3 步，世代换成 1.21.1（NeoForge 21.1.255 / Forge 52.1.16）。
2. 注意 1.21.1 的 binarypatcher 是 2.1.2（1.21.8 是 3.0.13，26.2 是 4.0.17）——三个世代的 userdev 自带各自的版本，代码里已按 `binpatcher.version` 读取，无需硬编码，但若有地方假定了 4.x 要一并查。
3. 跑 `MC_VER=1.21.1 ./gates-all.sh`。

### 两世代共用的前置
- `native-only-methods.txt` 末尾三行 `platform … minecraft=<v>` 是按平台声明的，需决定是按世代各存一份，还是改为运行时按 `SharedConstants` 探测。这是唯一一处把版本写进资源文件的地方。
- Fabric API 的 `+<mc>` 构建号需按世代固化（`-Pforbric.fabricApiVersion` 已可覆盖）。

---

## 3. 已知风险与坑

- **声明 ≠ 支持**：`--mc 1.21.8` 目前能跑通安装流程（坐标已逐个核实可下载、userdev `config.json` 已被真实 `readConfig` 解析成功），但 26.2 之外的锚点未重测，游戏内行为未验证。
- **`native-only-methods.txt` 原是单文件硬编码 `minecraft=26.2`**，而 `unmetRequirement` 会把 mod 自报的 `minecraft` 区间与表里记录的版本比对：实测 `>=1.21.8`、`>=1.21.1` 对 `26.2` 返回 **true**（放行），但精确声明 `1.21.8` / `~1.21.8` 返回 **false** → 该 mod 的注入目标不再算"原生缺失"，静默退化。已改为按 `-Dforbric.mcVersion` 选表（`native-only-methods-<ver>.txt`，可用 `-Dforbric.nativeAbsentTable` 覆盖，也支持从磁盘读，便于重测中不入包）；1.21.x 目前会回退到 26.2 表并**显式告警**说明这是错游戏的表。
- **Fabric API 构建号随世代变**：`-Pforbric.fabricApiVersion` 默认跟随 mc 版本，1.21.x 的正确构建号需核实后固化。
- **`EcosystemVersions` 运行时从载体 jar 读**，不是从 `Pins` 读，多版本下自动正确——但别把它当 pin 来源。
- **`declaresMcIncompatibility` 只护 Forge 系 mod**（`ForbricBootstrap` 跳过非 Forge 家族）。1.21.x 大量 Fabric mod 会因锚点漂移崩溃，这条逻辑不保护它们。
- **binarypatcher 三代不同**：1.21.1 是 2.1.2、1.21.8 是 3.0.13、26.2 是 4.0.17。代码按 `config.json` 的 `binpatcher.version` 读取（不硬编码），但若有地方假定 4.x 要一并查。
- **`Pins` 注释是活文档**：新增世代必须写明"为什么是这几个版本"，且版本号必须查 maven-metadata 核实——Forge 与 NeoForge 各有独立计数器，按数字推断必错。
- **不变量 10**：不提交/分发含 Mojang/Forge/NeoForge 字节的东西；多版本让产物矩阵变大，但每个世代的产物仍必须在玩家机器本地构建。
- **本机验证边界**：当前环境缺 `fabric-loader` substrate 与 merged-base jar，全量 gate 无法端到端跑；已完成的验证是 kernel-installer 全包 `javac` 编译 + Pins 行为冒烟 + `readConfig` 真实 userdev 解析 + 坐标逐个 HTTP 200 核实 + kernel 单元测试全绿 + shell 脚本 `bash -n` + `build.gradle` Groovy 解析。

---

## 4. 下一步（建议顺序）

1. 在有 substrate 与 merged-base 的完整环境跑一次 `26.2` 全量 gate，确认阶段 0 改造零回归。
2. 起 A1（1.21.8）：重测锚点 → 重算形状指纹 → 跑 `MC_VER=1.21.8 ./gates-all.sh`。
3. A1 稳定后起 A2（1.21.1），两者同构，可复用 A1 的全部流程与脚本。
4. 两版都稳后，再决定 `native-only-methods.txt` 的按世代拆分方式（多份 vs 运行时探测）。
