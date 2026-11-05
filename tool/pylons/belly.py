"""python tool/pylons/belly.py <id> [step]

Print a belly map: lowest skin at (x, z) on a grid, x from 0 outwards. '.' = no skin."""
import sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from hitbox import *
from place import *

aid = sys.argv[1]
step = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
d = load(aid)
P = cloud(d)
semi = float(np.abs(P[:, 0]).max())
xs = np.arange(0, semi + step, step)
zs = np.arange(P[:, 2].max(), P[:, 2].min() - step, -step)
print(f'{aid} semi {semi:.2f}  (rows z from nose, cols |x|, value = lowest skin y; avg of +x/-x min)')
print('   z  | ' + ' '.join(f'{x:5.1f}' for x in xs))
for z in zs:
    row = []
    for x in xs:
        a = foot_underside(P, x, z, step / 2, step / 2, step / 2)
        b = foot_underside(P, -x, z, step / 2, step / 2, step / 2)
        v = [u for u in (a, b) if u is not None]
        row.append('    .' if not v else f'{min(v):5.2f}')
    print(f'{z:6.1f}| ' + ' '.join(row))
