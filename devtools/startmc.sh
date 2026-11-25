#!/bin/bash
# Starts the dev client in the test world and waits until frames are being generated. usage: devtools/startmc.sh <log>
cd "$(dirname "$0")/.."
LOG=$1
export JAVA_HOME=${JAVA_HOME_25:-/opt/jdk25}
rm -f "$LOG"
(JAVA_HOME=$JAVA_HOME devtools/runmc.sh "$LOG" --quickPlaySingleplayer fgtest &)
sleep 10
until [ "$(grep -c '\[presenter\].*generating=true' "$LOG" 2>/dev/null)" -ge 3 ]; do
	if grep -q -- "---- Minecraft Crash Report\|BUILD FAILED\|InvalidInjectionException" "$LOG" 2>/dev/null; then echo "FAILED"; exit 1; fi
	sleep 2
done
echo "in world"
