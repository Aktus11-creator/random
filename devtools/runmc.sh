#!/bin/bash
# Launch the dev client on the Xvfb display. usage: devtools/runmc.sh <logfile> [game args...]
# e.g. devtools/runmc.sh /tmp/run.log --quickPlaySingleplayer fgtest
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/jdk25} DISPLAY=${DISPLAY:-:99}
LOG=$1; shift
./gradlew --no-daemon runClient ${1:+--args="$*"} > "$LOG" 2>&1
