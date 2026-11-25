#!/bin/bash
# Runs chat commands in the game, one per argument. usage: devtools/cmd.sh "/time set day" "/summon pig"
for c in "$@"; do
	python3 "$(dirname "$0")/xin.py" key:t sleep:1 "type:$c" sleep:0.3 key:Return sleep:0.8
done
