#!/usr/bin/env bash
# Builds RadioNetwork for Paper 26.1.2, starts a scratch Paper server with it and the probe plugin
# (tests/probe), and prints one PASS or FAIL line per check. Exits 0 only if every check passed.
#
#   tests/run-probes.sh            run everything
#   tests/run-probes.sh -q         only show failures and the summary
#   KEEP=1 tests/run-probes.sh     keep the scratch server (and its log) afterwards
#
# It needs a Paper 26.1.2 server folder to copy (paper.jar, libraries/, cache/, eula.txt) so nothing is downloaded:
#   PAPER_DIR=/path/to/server tests/run-probes.sh
# and a Java 25 runtime (JAVA25=/path/to/jdk-25/Contents/Home, or the one Gradle already downloaded).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QUIET=0
[ "${1:-}" = "-q" ] && QUIET=1

# ---- Java 25
JAVA_HOME_25="${JAVA25:-}"
if [ -z "$JAVA_HOME_25" ]; then
  for candidate in "$HOME"/.gradle/jdks/*25*/*/Contents/Home "$HOME"/.gradle/jdks/*25*/*/Home "$HOME"/.gradle/jdks/*25*/Contents/Home /Library/Java/JavaVirtualMachines/*25*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then JAVA_HOME_25="$candidate"; break; fi
  done
fi
[ -x "${JAVA_HOME_25:-}/bin/java" ] || { echo "No Java 25 found. Set JAVA25=/path/to/jdk-25 home."; exit 2; }

# ---- a server folder to copy
PAPER_DIR="${PAPER_DIR:-$ROOT/../ExileBounty/run/paper-26.1.2}"
[ -f "$PAPER_DIR/paper.jar" ] || { echo "No Paper server at $PAPER_DIR. Set PAPER_DIR to a Paper 26.1.2 folder."; exit 2; }

# ---- build the plugin
echo "Building RadioNetwork for 26.1.2..."
( cd "$ROOT" && ./gradlew build -q -PmcVersion=26.1.2 -PpaperApi=26.1.2.build.74-stable -PapiVersion=26.1 2>&1 | grep -v '^Note:' || true )
PLUGIN_JAR="$ROOT/build/libs/RadioNetwork-1.0.0-mc26.1.2.jar"
[ -f "$PLUGIN_JAR" ] || { echo "Build failed: $PLUGIN_JAR is missing."; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/radio-probe.XXXXXX")"
cleanup() {
  pkill -f "$WORK/paper.jar" 2>/dev/null || true
  if [ "${KEEP:-0}" = "1" ]; then echo "Kept $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

# ---- compile the probe against the plugin classes and the Paper API
PAPER_API="$(find "$HOME/.gradle/caches/modules-2" -name 'paper-api-26.1.2*.jar' | head -1)"
LIBS="$(find "$HOME/.gradle/caches/modules-2" \( -path '*net.kyori*' -o -path '*org.jetbrains/annotations*' -o -path '*org.jspecify*' -o -path '*com.google.guava/guava/*' -o -path '*com.google.code.gson*' \) -name '*.jar' | grep -v sources | tr '\n' ':')"
mkdir -p "$WORK/probe-classes"
"$JAVA_HOME_25/bin/javac" -cp "$ROOT/build/classes/java/main:$PAPER_API:$LIBS" -d "$WORK/probe-classes" "$ROOT/tests/probe/RadioProbe.java"
cp "$ROOT/tests/probe/plugin.yml" "$WORK/probe-classes/"
mkdir -p "$WORK/plugins"
( cd "$WORK/probe-classes" && "$JAVA_HOME_25/bin/jar" cf "$WORK/plugins/RadioProbe.jar" . )
cp "$PLUGIN_JAR" "$WORK/plugins/"

# ---- the scratch server
cp "$PAPER_DIR/paper.jar" "$PAPER_DIR/eula.txt" "$WORK/"
[ -d "$PAPER_DIR/libraries" ] && cp -R "$PAPER_DIR/libraries" "$WORK/"
[ -d "$PAPER_DIR/cache" ] && cp -R "$PAPER_DIR/cache" "$WORK/"
PORT=$((25700 + RANDOM % 200))
cat > "$WORK/server.properties" <<EOF
server-port=$PORT
online-mode=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
allow-nether=false
max-players=1
EOF

echo "Running the probe on a scratch server (port $PORT)..."
( cd "$WORK" && perl -e 'alarm 300; exec @ARGV' "$JAVA_HOME_25/bin/java" -Xmx1G -jar paper.jar --nogui < /dev/null > server.log 2>&1 ) || true

LINES="$(grep -E '\[RadioProbe\] (PASS|FAIL|==|RESULT)|\[RadioProbe\]     at' "$WORK/server.log" | sed -E 's/^\[[0-9:]+ [A-Z]+\]: \[RadioProbe\] //' || true)"
if [ "$QUIET" = "1" ]; then
  echo "$LINES" | grep -E '^(FAIL|RESULT|    at)' || true
else
  echo "$LINES"
fi

RESULT="$(echo "$LINES" | grep '^RESULT' || true)"
if [ -z "$RESULT" ]; then
  echo "The probe never reported. Last server output:"
  tail -30 "$WORK/server.log"
  exit 2
fi
if echo "$RESULT" | grep -q 'fail=0'; then
  echo "All checks passed."
  exit 0
fi
echo "Some checks FAILED."
exit 1
