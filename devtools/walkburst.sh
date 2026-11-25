#!/bin/bash
# Third person, walk forward, grab a burst. usage: devtools/walkburst.sh <out.png>
python3 "$(dirname "$0")/xin.py" key:F5 sleep:1 down:w sleep:1.5
for i in 1 2 3 4; do DISPLAY=:99 import -window root -crop 854x480+213+120 /tmp/claude-0/wb$i.png; sleep 0.1; done
python3 "$(dirname "$0")/xin.py" up:w key:F5 key:F5 down:s sleep:1.6 up:s
python3 -c "
from PIL import Image
w=Image.new('RGB',(854*2,480*2))
for i in range(4): w.paste(Image.open(f'/tmp/claude-0/wb{i+1}.png'),(854*(i%2),480*(i//2)))
w.save('$1')"
