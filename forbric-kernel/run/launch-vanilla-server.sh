#!/usr/bin/env bash
# Boot the MC DEDICATED SERVER as PURE VANILLA — no kernel, no merged base, no loader of any kind.
#
# This is the control arm of gate-m31. A parity gate that compares Forbric against a remembered description of
# vanilla is not a comparison; it needs vanilla actually running, on the same machine and the same JVM, so the
# only difference left between the two arms is the thing under test.
#
# The jar is the player's own installed client jar. On a Mojmap-native generation (26.2) it carries
# net.minecraft.server.Main and Mojang names, so it IS the dedicated server. On an obfuscated generation the
# client jar is NOT a server, so a server jar must be supplied via VANILLA_JAR (the installer downloads one per
# generation). The libraries come from the same versions/<ver>/<ver>.json that launch-kernel-server.sh reads, so
# both arms link against one set of bytes.
#
# Usage: [MC_VER=…] [RUNDIR=…] [VANILLA_JVM=…] ./launch-vanilla-server.sh [extra game args]
set -uo pipefail

MC_VER="${MC_VER:-26.2}"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/.." && pwd)"
RUNDIR="${RUNDIR:-$KERNEL/run/parity-vanilla}"
mkdir -p "$RUNDIR"

JAR="${VANILLA_JAR:-$MC/versions/$MC_VER/$MC_VER.jar}"
[ -f "$JAR" ] || { echo "[vanilla-launch] FATAL: no vanilla jar at $JAR (set VANILLA_JAR or MC_DIR)" >&2; exit 3; }

# EULA (dedicated server refuses to start otherwise). Kernel testing only — the user has accepted MC's EULA.
[ -f "$RUNDIR/eula.txt" ] || echo "eula=true" > "$RUNDIR/eula.txt"

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
[ -n "$VANILLA_CP" ] || { echo "[vanilla-launch] FATAL: resolved no libraries from $MC/versions/$MC_VER/$MC_VER.json" >&2; exit 3; }

# jline (server console) is shipped in the MC libraries tree but not listed in the version json's libraries array;
# add it explicitly so the dedicated-server console handler doesn't NoClassDefFound. Same line as the kernel's.
JLINE="$(find "$MC/libraries/org/jline" -name 'jline-*' -name '*.jar' 2>/dev/null | grep -vE 'sources|javadoc' | paste -sd: -)"

echo "[vanilla-launch] rundir=$RUNDIR"
echo "[vanilla-launch] mc=$MC_VER"
echo "[vanilla-launch] jar=$JAR"
cd "$RUNDIR"
exec java -Djava.awt.headless=true ${VANILLA_JVM:-} \
  -cp "$JAR:$VANILLA_CP${JLINE:+:$JLINE}" net.minecraft.server.Main --nogui "$@"
