"""python tool/pylons/span.py <id> [step] [z-hint]

Spanwise run of the wing: chord (TE..LE) and lowest skin at each |x|. Nacelles show as low skin."""
import sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from hitbox import *
from place import *

aid = sys.argv[1]
step = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
zhint = float(sys.argv[3]) if len(sys.argv) > 3 else None
d = load(aid)
P = cloud(d)
semi = float(np.abs(P[:, 0]).max())
print(f'{aid} semi {semi:.2f}')
for x in np.arange(step, semi + 0.01, step):
    seg = chord_at(P, x, zhint)
    if seg is None:
        print(f'  x {x:5.2f}  -')
        continue
    te, le = seg
    col = column(P, x, 0, 0.1, 1e9)
    col = col[(col[:, 2] >= te) & (col[:, 2] <= le)]
    print(f'  x {x:5.2f}  chord {te:6.2f}..{le:6.2f} ({le-te:4.2f})  low {col[:,1].min():5.2f}  high {col[:,1].max():5.2f}')
