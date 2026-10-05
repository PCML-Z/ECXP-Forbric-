# 多版本实施计划：Minecraft 26.2 / 1.21.8 / 1.21.1

本文件记录 Forbric 多版本支持的分阶段落地路径。已完成的部分是**阶段 0（pin 层参数化）**；其余阶段是把某个世代真正跑起来所需的工作。

这不是一个"加几个版本号"的改动。Forbric **不是版本无关**的：它按 Minecraft 世代硬绑定，每个世代的命名空间策略、Forge/NeoForge 工具链与字节码锚点都是针对那个确切构建实测出来的。

---

## 0. 世代模型（已落地）

`Pins` 从单常量改为按 Minecraft 版本索引的 `PinSet` 集合：

| 世代 | 命名空间 | 载体 | 工具链 | 状态 |
|------|---------|------|--------|------|
| **26.2**（默认） | Mojmap identity（`named`） | 三载体（merged + forge-rt + neo-rt） | NeoFormRuntime 2.0.18 | 完整可用 |
| **1.21.8** | intermediary | 三载体 | legacy Forge（待接 MCPConfig/BinaryPatcher） | 声明已注册，构建路径待落地 |
| **1.21.1** | intermediary | **两载体**（无 NeoForge） | legacy Forge | 声明已注册；合并基底降级未实现，构建会明确报未实现 |

关键设计点：
- `PinSet` 是 record，`hasNeoForge()` 对 1.21.1 返回 false。
- `forVersion(mc)` 对未知版本**抛错**，绝不回退到 26.2（静默回退会造出"载体是 A、profile 声称 B"的错配）。
- `BuildStamp.key(mcVersion)` 把 Minecraft 版本折进缓存键，**不同世代永不共享缓存条目**。所有 builder 的 `isFresh`/`write` 都已带上 `mcVersion`。
- `Main --mc` 会对未注册版本直接退出并列出支持列表。

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

### 阶段 A1 — 1.21.8 三载体（较近，NeoForge 存在）
1. 确认 NeoForge 21.8.54 userdev 的坐标与 userdev config 形状（与 26.2 同为 NeoForm 系，读者可复用）。
2. 跑 `MergedBaseBuilder` 产出 1.21.8 的 patched-mc-merged，重测第 1 节全部锚点。
3. 重算 `AUDITED` 指纹。
4. 1.21.8 是 intermediary，**需要 Fabric intermediary jar**（已核实存在）与 `ForgeModRemapper` 把 Mojmap Forge 字节码 remap 进 intermediary。
5. 跑 gate：`MC_VER=1.21.8 ./gates-all.sh`，逐个修到绿。

### 阶段 A2 — 1.21.1 两载体（最难）
1. **两载体合并基底**：`MergedBaseBuilder` 目前吃 vanilla+forge+neo 三输入，核心是"两 loader 之间的方法体冲突仲裁"。1.21.1 无 NeoForge，需要一个 vanilla+Forge 的二输入模式。这不是删个参数——`mergeClass(v,f,n,…)` 的三方语义要重新定义。
2. legacy Forge 工具链：1.21.x 世代的 Forge 侧用 `MCPConfig`/`BinaryPatcher`（`build-patched-forge.sh` 注释已提及），不是 NFRT。
3. 重测全部锚点。
4. 跑 `MC_VER=1.21.1 ./gates-all.sh`。

---

## 3. 已知风险与坑

- **声明 ≠ 支持**：`--mc 1.21.8` 目前能跑通安装流程，但 26.2 之外的锚点未重测，游戏内行为未验证。文档与 `--help` 已按此口径措辞。
- **Fabric API 构建号随世代变**：`-Pforbric.fabricApiVersion` 默认跟随 mc 版本，1.21.x 的正确构建号需核实后固化。
- **`EcosystemVersions` 运行时从载体 jar 读**，不是从 `Pins` 读，多版本下自动正确——但别把它当 pin 来源。
- **`declaresMcIncompatibility` 只护 Forge 系 mod**（`ForbricBootstrap` 跳过非 Forge 家族）。1.21.x 大量 Fabric mod 会因锚点漂移崩溃，这条逻辑不保护它们。
- **`Pins` 注释是活文档**：新增世代必须写明"为什么是这几个版本"，否则有人会把它 update 回失败状态。
- **不变量 10**：不提交/分发含 Mojang/Forge/NeoForge 字节的东西；多版本让产物矩阵变大，但每个世代的产物仍必须在玩家机器本地构建。
- **本机验证边界**：当前环境缺 `fabric-loader` substrate 与 merged-base jar，全量 gate 无法端到端跑；已完成的验证是 kernel-installer 全包 `javac` 编译通过 + Pins 行为冒烟测试通过 + 全部 shell 脚本 `bash -n` 通过 + `build.gradle` Groovy 解析通过。

---

## 4. 下一步（建议顺序）

1. 在有 substrate 与 merged-base 的完整环境跑一次 `26.2` 全量 gate，确认阶段 0 改造零回归。
2. 起 A1（1.21.8）：先固化 NeoForge 21.8.54 坐标 → 重测锚点 → 跑 gate。
3. A1 稳定后再起 A2（1.21.1），其中"二载体合并基底"是独立的架构子任务，值得单独拆一轮。
