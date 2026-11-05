"""Every hitbox box against the model cube it came from: proves model.py poses the bbmodel the
way the game does, and that rot_box reads the hitbox the way the game does.

    python tool/seats/check_pose.py [id ...]
"""
import sys, os, glob
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model as M


def check(aid):
    d = M.definition(aid)
    if not os.path.exists(os.path.join(M.BBMODELS, aid + '.bbmodel')):
        return None
    cubes = M.load(aid)
    hb = M.hitbox_cubes(d)
    mids = np.array([c.mid for c in cubes])
    worst, off = 0.0, 0
    for h in hb:
        near = np.argsort(np.linalg.norm(mids - h.mid, axis=1))[:6]
        hc = h.corners()
        e = min(max(np.min(np.linalg.norm(cubes[i].corners() - p, axis=1)) for p in hc) for i in near)
        worst = max(worst, e)
        off += e > 0.03
    return len(cubes), len(hb), worst, off


if __name__ == '__main__':
    ids = sys.argv[1:] or sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(M.DEFS, '*.json')))
    for aid in ids:
        r = check(aid)
        if r is None:
            print(f'{aid:10s} no bbmodel')
        else:
            print(f'{aid:10s} cubes {r[0]:4d} boxes {r[1]:4d} worst {r[2]:.3f} off {r[3]}')
