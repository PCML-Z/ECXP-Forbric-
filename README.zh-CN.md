# ECXP-Forbric+

[English](README.md) | 简体中文

PMCL 团队基于 [Forbric](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader) 的增强版。一个游戏里同时加载 Fabric、Forge 和 NeoForge 模组。

上游 Forbric 目前只覆盖 Minecraft 26.2。这一版把安装器的 pin 扩到了 **26.2、1.21.8、1.21.1**，并且使用自己的版本号，不会覆盖已经装好的 Forbric。

| Minecraft | 装好后的版本号 | 状态 |
| --- | --- | --- |
| 26.2 | `26.2-ecxp-forbric` | 与上游同一条内核路径，pin 已按字节核对 |
| 1.21.8 | `1.21.8-ecxp-forbric` | 安装坐标已核对；游戏内锚点还没按这个版本重测 |
| 1.21.1 | `1.21.1-ecxp-forbric` | 同上 |

1.21.x 能走完安装，不代表模组行为已经和 26.2 一样。锚点表仍是按 26.2 实测的，见 [多版本计划](docs/MULTIVERSION_PLAN.md)。

## 仓库里什么在用

```
forbric-kernel/                 当前内核。游戏里跑的就是它
forbric-kernel-installer/       当前安装器。PMCL 调用的也是它
forbric-loader/                 第一代加载器，只给底座补丁和合并工具用
legacy/forbric-installer/       第一代安装器。不要用，命令和版本号都是旧的
docs/                           结构说明、多版本计划和兼容性记录
tools/dev.py                    开发机上准备游戏文件、构建和启动
```

玩家和 PMCL 只接触 `forbric-kernel-installer`。`legacy/` 里那一套是焊接方案留下的，入口、模式名和版本号都和现在不同。

Java 包名仍然是 `net.forbric`。这是内核和模组对接的内部名字，改掉会让已有的类加载和测试全部对不上。对外的名字是 ECXP-Forbric+，版本号后缀是 `-ecxp-forbric`。

## 安装

发布版出来之后：

```bash
java -jar forbric-kernel-installer-<版本>.jar --dir <游戏目录> --mc 26.2 --release <tag>
```

`--mc` 可以是 `26.2`、`1.21.8` 或 `1.21.1`。不带参数会打开窗口。

这个仓库还没有 GitHub Release 时，安装器 jar 需要自己构建：

```bash
cd forbric-kernel-installer && ./gradlew jar
```

Minecraft、Forge、NeoForge 由安装器在本机下载并构建，仓库里不带这些文件。

PMCL 的模组加载器列表里选 **ECXP-Forbric+** 即可。它和已经装好的 `26.2-forbric` 是两个版本。

模组仍放在同一个 `mods` 文件夹。启动器按版本隔离时，目录是 `versions/<版本号>/mods/`。

## 从源码构建内核

需要 git 和 JDK 21 或更新版本。全新克隆上编译通过，只说明引导侧能编过，不说明游戏能启动。

```bash
cd forbric-kernel && ./gradlew build
```

要在开发用的游戏里跑当前源码，需要 JDK 25+ 和 Python 3.9+：

```bash
python3 tools/dev.py client
```

## 文档

- [仓库结构](docs/architecture.md)
- [内核内部](docs/introduction.zh-CN.md)（描述 `main` 上的实现，不是一份发行说明）
- [多版本计划](docs/MULTIVERSION_PLAN.md)
- [上游 Forbric 0.3.0 玩家说明](docs/upstream-readme.zh-CN.md)（版本范围已过时，只作留档）

## 许可证

Apache-2.0，见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。本仓库不包含 Minecraft、Forge 或 NeoForge 的代码。

ECXP-Forbric+ 与 Mojang、FabricMC、MinecraftForge、NeoForged 均无关联。Forbric 是上游项目的名字。
