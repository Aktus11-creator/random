#!/bin/bash
# Turns the camera continuously and prints the self-test lines logged meanwhile.
# usage: devtools/turntest.sh <log> [dx] [steps]
LOG=$1; DX=${2:-20}; N=${3:-900}
START=$(wc -l < "$LOG")
python3 "$(dirname "$0")/xin.py" rel:$DX:0:$N
tail -n +$START "$LOG" | grep "\[self-test\] camera\|\[presenter\]\|\[bench\]"
