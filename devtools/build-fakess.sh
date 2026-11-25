#!/bin/bash
# Builds the Supersample stand-in test mod into run/mods/fakess.jar (dev testing only, never shipped).
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/jdk25}
CP=$(./gradlew --no-daemon -q -I devtools/fakess/print-classpath.gradle printClientCp --no-configuration-cache 2>/dev/null | grep '^CP=' | sed 's/^CP=//')
OUT=$(mktemp -d)
$JAVA_HOME/bin/javac -proc:none -d "$OUT" -cp "$CP" $(find devtools/fakess/src -name '*.java')
cp devtools/fakess/res/* "$OUT"/
mkdir -p run/mods
(cd "$OUT" && $JAVA_HOME/bin/jar cf "$OLDPWD/run/mods/fakess.jar" .)
echo "built run/mods/fakess.jar"
