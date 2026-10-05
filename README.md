# ECXP-Forbric+

English | [简体中文](README.zh-CN.md)

The PMCL team's enhanced build of [Forbric](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader). One game loads Fabric, Forge, and NeoForge mods together.

Upstream Forbric currently covers Minecraft 26.2 only. This tree extends the installer pins to **26.2, 1.21.8, and 1.21.1**, and writes its own version id so it does not replace an installed Forbric profile.

| Minecraft | Version id | Status |
| --- | --- | --- |
| 26.2 | `26.2-ecxp-forbric` | Same kernel path as upstream; pins checked against the published bytes |
| 1.21.8 | `1.21.8-ecxp-forbric` | Install coordinates checked; in-game anchors are still the 26.2 measurements |
| 1.21.1 | `1.21.1-ecxp-forbric` | Same as 1.21.8 |

An install that finishes on 1.21.x is not a claim that mods behave as they do on 26.2. See [the multi-version plan](docs/MULTIVERSION_PLAN.md).

## What is current

```
forbric-kernel/                 the kernel that actually runs the game
forbric-kernel-installer/       the installer PMCL launches
forbric-loader/                 first-generation loader, kept for the substrate patches and merge tools
legacy/forbric-installer/       first-generation installer; do not use it
docs/                           layout, the multi-version plan, compatibility notes
tools/dev.py                    prepare, build, and launch on a developer machine
```

Players and PMCL use `forbric-kernel-installer` only. The tree under `legacy/` is the older weld installer, with a different command line and different version ids.

Java packages stay `net.forbric`. That name is what the kernel and its tests are built around. The player-facing name is ECXP-Forbric+, and the profile suffix is `-ecxp-forbric`.

## Install

Once a release exists:

```bash
java -jar forbric-kernel-installer-<version>.jar --dir <game directory> --mc 26.2 --release <tag>
```

`--mc` is `26.2`, `1.21.8`, or `1.21.1`. With no arguments the window opens.

Until this repository publishes a GitHub Release, build the installer jar yourself:

```bash
cd forbric-kernel-installer && ./gradlew jar
```

Minecraft, Forge, and NeoForge are downloaded and built on the player's machine. This repository does not ship those files.

In PMCL, choose **ECXP-Forbric+** in the mod-loader list. It is a separate version from an existing `26.2-forbric`.

Mods still go in one `mods` folder. With per-version isolation that is `versions/<id>/mods/`.

## Build the kernel

git, and JDK 21 or newer. A green build on a fresh clone means the boot sources compiled. It does not mean a game will start.

```bash
cd forbric-kernel && ./gradlew build
```

To launch the current sources in a dev game you also need JDK 25+ and Python 3.9+:

```bash
python3 tools/dev.py client
```

## Docs

- [Layout](docs/architecture.md)
- [Kernel internals](docs/introduction.md) (describes `main`, not a release)
- [Multi-version plan](docs/MULTIVERSION_PLAN.md)
- [Upstream Forbric 0.3.0 player guide](docs/upstream-readme.md) (kept for reference; its version range is out of date)

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). This repository does not contain Minecraft, Forge, or NeoForge code.

ECXP-Forbric+ is not affiliated with Mojang, FabricMC, MinecraftForge, or NeoForged. Forbric is the upstream project's name.
