#!/usr/bin/env bash
# Boot the MC CLIENT through the SOVEREIGN KERNEL (no Knot, no genuine FML/FancyModLoader lifecycle) from the
# merged 3-ABI base, with the Forge + NeoForge runtime jars as PASSIVE ABI carriers. M5 goal: reach the TITLE screen.
#
# Parent -cp: kernel boot jar + kernel deps + MC libraries (incl. LWJGL). Owned (transform-loaded): merged
# base + forge-runtime + neoforge-runtime + the MC libraries (via --libraryPath, so mods can mixin into them).
#
# macOS: -XstartOnFirstThread is MANDATORY (GLFW must own the main thread); the kernel invokes the client Main on
# that same thread, so the window is created on thread 0 as GLFW requires.
#
# Usage: [MC_VER=…] [RUNDIR=…] [FORBRIC_JVM=…] ./launch-kernel-client.sh [extra game args]
#   MC_VER   the Minecraft generation to boot (default 26.2).
set -uo pipefail

MC_VER="${MC_VER:-26.2}"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/.." && pwd)"
# Same FORBRIC_OLD knob lib.sh uses: one variable points a second working tree at the staged artifacts
# instead of needing MERGED, FORGE_RT and NEO_RT set individually.
OLD="$(cd "${FORBRIC_OLD:-$KERNEL/../forbric-loader}" && pwd)"
STAGE="$OLD/run"

MERGED="${MERGED:-$STAGE/merged-base/patched-mc-merged-$MC_VER.jar}"
FORGE_RT="${FORGE_RT:-$STAGE/merged-base/forge-runtime-interop.jar}"
[ -f "$FORGE_RT" ] || FORGE_RT="$STAGE/forge-runtime/forge-runtime.jar"
NEO_RT="${NEO_RT:-$STAGE/neoforge-runtime/neoforge-runtime.jar}"
RUNDIR="${RUNDIR:-$KERNEL/run/client-kernel}"
# 26.2 ships a version-scoped natives folder; older generations use the plain one. Try the scoped name first and
# fall back, so MC_VER does not have to know which naming its generation uses.
NATIVES="${NATIVES_DIR:-$MC/versions/$MC_VER/$MC_VER-natives}"
[ -d "$NATIVES" ] || NATIVES="$MC/versions/$MC_VER/natives"
ASSETS="$MC/assets"
mkdir -p "$RUNDIR/mods"

[ -f "$MERGED" ] || { echo "merged base not found: $MERGED (run $STAGE/build-merged-base.sh)" >&2; exit 2; }
[ -d "$NATIVES" ] || { echo "LWJGL natives not found: $NATIVES" >&2; exit 2; }

if ! "$KERNEL/gradlew" --offline -q -p "$KERNEL" -Pforbric.mcVersion="$MC_VER" jar >/tmp/forbric-kernel-jar.log 2>&1; then
  echo "[kernel-launch] FATAL: kernel jar build failed — refusing to launch a stale jar" >&2
  grep -vE 'WARNING: |native-access|Restricted method|--enable-native' /tmp/forbric-kernel-jar.log >&2
  exit 3
fi
# Overridable so a gate can hand the JVM a COPY and then do something to that copy while the game runs --
# which is how "the kernel jar was replaced mid-session" is reproduced without touching the real build output.
BOOT_JAR="${FORBRIC_BOOT_JAR:-$(ls "$KERNEL"/build/libs/forbric-kernel-*.jar | head -1)}"
BOOT_DEPS="$("$KERNEL/gradlew" --offline -q -p "$KERNEL" -Pforbric.mcVersion="$MC_VER" printBootClasspath 2>/dev/null | grep -vE 'WARNING|native|Restricted|enable' | tail -1)"

# MC libraries (parent-loaded), resolved from the Mojang install's version json for THIS generation. Includes LWJGL.
VANILLA_CP="$(python3 - "$MC" "$MC_VER" <<'PY'
import json, os, sys
mc, ver = sys.argv[1], sys.argv[2]
d = json.load(open(os.path.join(mc, 'versions', ver, ver + '.json')))
out = []
for lib in d.get('libraries', []):
    p = lib.get('name', '').split(':')
    if len(p) < 3: continue
    grp, art, v = p[0].replace('.', '/'), p[1], p[2]
    cls = ('-' + p[3]) if len(p) > 3 else ''
    jar = os.path.join(mc, 'libraries', grp, art, v, f"{art}-{v}{cls}.jar")
    if os.path.exists(jar): out.append(jar)
print(os.pathsep.join(out))
PY
)"
ASSET_INDEX="$(python3 -c "import json;print(json.load(open('$MC/versions/$MC_VER/$MC_VER.json'))['assetIndex']['id'])")"


# Game root metadata (version.json) on the PARENT -cp, as a resources-only jar. Mods that ask
# getSystemClassLoader() for it — CustomSkinLoader's bootstrap picks its bytecode patch variant by the protocol
# version it finds there — get null under Forbric otherwise, because the merged base belongs to
# ForbricClassLoader. See run/game-metadata-jar.sh for why this must never carry class files.
META_JAR="$("$HERE/game-metadata-jar.sh" "$MERGED" 2>/dev/null)" || META_JAR=""
CP="$BOOT_JAR:$BOOT_DEPS:$VANILLA_CP${META_JAR:+:$META_JAR}"

# Guest mixins are written against VANILLA bytecode; the merged base is vanilla+Forge+NeoForge byte-merged, so an
# injection anchor a mixin expects may have moved. The KERNEL now relaxes EVERY discovered guest mod's mixin configs
# by default (ForbricMixinService.setGuestConfigs), turning such a failure into a soft skip instead of a fatal
# MixinApplyError — no launcher-side glob needed. Add more with -Dforbric.relaxMixinOverwrites, or get strict Mixin
# behaviour back for debugging with -Dforbric.relaxGuestMixins=off. Merged-base incompatibilities that survive apply
# but break at runtime are shipped defaults in MergedBaseMixinCompat.

echo "[kernel-launch] CLIENT rundir=$RUNDIR  assetIndex=$ASSET_INDEX"
echo "[kernel-launch] merged base = $MERGED"
echo "[kernel-launch] natives = $NATIVES"
echo "[kernel-launch] mods: $(ls "$RUNDIR/mods" 2>/dev/null | paste -sd' ' -)"
cd "$RUNDIR"

# The unmet-dependency dialog is OFF for every gate and developer run: this script is driven unattended, and a
# window nobody can see reads as a hang rather than a failure. A real install launches through the installer's
# version profile, which does not pass this, so a player still gets it. FORBRIC_DEP_DIALOG=dryRun exercises the
# whole fork with no display -- see gate-m20-depdialog.sh.
# Developer runs fail closed by default instead of waiting on an unattended compatibility prompt. Installed
# profiles keep the product's ask default; only deliberate negative canaries set FORBRIC_COMPAT_POLICY=continue.
exec java -XstartOnFirstThread -Djava.library.path="$NATIVES" \
  -Dforbric.compatibilityPolicy="${FORBRIC_COMPAT_POLICY:-strict}" \
  -Dforbric.dependencyDialog="${FORBRIC_DEP_DIALOG:-off}" ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.kernel.boot.KernelClientLaunch \
  --gameJar "$MERGED" --runtimeJar "$FORGE_RT" --runtimeJar "$NEO_RT" \
  --libraryPath "$VANILLA_CP" \
  -- --version 26.2-forbric-kernel --gameDir "$RUNDIR" --assetsDir "$ASSETS" --assetIndex "$ASSET_INDEX" \
  --accessToken 0 --username ForbricKernel --uuid 00000000000000000000000000000000 \
  --userType legacy --versionType release "$@"
