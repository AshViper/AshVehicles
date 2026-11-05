"""Check a set of definitions: rack top on the skin, pods under it, stores clear of the airframe,
of each other and of the ground, pairs mirrored.

    python tool/pylons/audit.py src/main/resources/data/ashvehicles/ashvehicles/aircraft [id ...]

"ground" counts a 0.5-deep store, so a centreline that only ever carries a slim missile or
sits on a low airframe (F-104G, Typhoon) shows up there without being wrong.
"""
import sys, os, glob, json, itertools
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from hitbox import *
import place as PL
from layouts import SPEC
from apply_layouts import skin

src = sys.argv[1]
ids = sys.argv[2:] or sorted(os.path.basename(f)[:-5] for f in glob.glob(src + '/*.json'))
bad = 0
for aid in ids:
    d = json.load(open(os.path.join(src, aid + '.json'), encoding='utf-8'))
    Pc = skin(d, SPEC.get(aid, {}))
    hps = [h for h in d['hardpoints'] if 'fixed' not in h and not h.get('internal')]
    out = []
    vols = []
    for h in hps:
        x, y, z = h['pos']
        k = 'tip' if h.get('wingtip') else h.get('kind', 'weapon')
        msg = []
        if k == 'weapon':
            u = PL.foot_underside(Pc, x, z, 0.10, 0.75, 0.75)
            g = None if u is None else y - u
            if g is None or abs(g + 0.42) > 0.03:
                msg.append(f'gap {g}')
            # store under the rack: 0.24 wide, 0.5 deep, 3 long
            m = (np.abs(Pc[:, 0] - x) < 0.12) & (np.abs(Pc[:, 2] - z) < 1.5) & (Pc[:, 1] > y - 0.5) & (Pc[:, 1] < y + 0.05)
            if m.sum() > 0:
                msg.append(f'store-in-skin {int(m.sum())} (ymax {Pc[m,1].max():.2f})')
            if y - 0.5 < 0.05:
                msg.append(f'ground {y-0.5:.2f}')
            vols.append((h['name'], np.array([x - 0.2, y - 0.5, z - 1.4]), np.array([x + 0.2, y + 0.44, z + 1.4])))
        elif k == 'special':
            u = PL.foot_underside(Pc, x, z, 0.11, 0.9, 1.0)
            g = None if u is None else y - u
            if g is None or abs(g + 0.20) > 0.03:
                msg.append(f'pod gap {g}')
            if y - 0.15 < 0.05:
                msg.append(f'ground {y-0.15:.2f}')
            vols.append((h['name'], np.array([x - 0.11, y - 0.15, z - 1.0]), np.array([x + 0.11, y + 0.2, z + 0.9])))
        else:
            vols.append((h['name'], np.array([x - 0.13, y - 0.17, z - 1.4]), np.array([x + 0.13, y + 0.28, z + 1.4])))
        # mirror partner
        mirror = [o for o in hps if o is not h and abs(o['pos'][0] + x) < 1e-6 and abs(o['pos'][1] - y) < 1e-6 and abs(o['pos'][2] - z) < 1e-6]
        if abs(x) > 1e-6 and not mirror:
            msg.append('no mirror')
        out.append((h['name'], k, (x, y, z), msg))
    clash = []
    for (n1, a1, b1), (n2, a2, b2) in itertools.combinations(vols, 2):
        if np.all(a1 < b2) and np.all(a2 < b1):
            clash.append(f'{n1}/{n2}')
    problems = [o for o in out if o[3]]
    print(f'== {aid}: {len(hps)} external' + ('' if not problems and not clash else '  PROBLEMS'))
    for n, k, p, msg in out:
        if msg:
            print(f'   {n:<20} {k:<7} {p}  ' + '; '.join(msg))
    if clash:
        print('   overlap: ' + ', '.join(clash))
    bad += len(problems) + len(clash)
print('total problems', bad)
