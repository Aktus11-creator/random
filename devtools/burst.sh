#!/bin/bash
# Turns the camera and grabs a burst of window screenshots meanwhile. usage: devtools/burst.sh <outprefix> [dx] [n]
OUT=$1; DX=${2:-3}; N=${3:-6}
python3 "$(dirname "$0")/xin.py" rel:$DX:0:200 &
sleep 1.2
for i in $(seq 1 $N); do DISPLAY=:99 import -window root -crop 854x480+213+120 "${OUT}_$i.png"; sleep 0.1; done
wait
