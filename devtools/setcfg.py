# Edits run/config/framegen.json: python3 devtools/setcfg.py key=value ...
import json, sys
p = "run/config/framegen.json"
c = json.load(open(p))
for kv in sys.argv[1:]:
    k, v = kv.split("=", 1)
    c[k] = json.loads(v) if v[:1] in "0123456789-tf[{" else v
json.dump(c, open(p, "w"), indent=2)
