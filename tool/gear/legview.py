"""Side and front views of one leg's cubes at rest (or stowed, --up), with the ground line and the hitbox.

    python tool/gear/legview.py [--up] OUT.png id bone [bone ...]
"""
import sys, os
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Model, rot_zyx
from views import hull2d


def cube_corners(m, g, e, pose=None):
    frm = np.array(e['from'], float); to = np.array(e['to'], float)
    c = np.array([[x, y, z] for x in (frm[0], to[0]) for y in (frm[1], to[1]) for z in (frm[2], to[2])])
    r = e.get('rotation', [0, 0, 0])
    if any(r):
        o = np.array(e.get('origin', [0, 0, 0]), float)
        c = (c - o) @ rot_zyx(*r).T + o
    M, t = m.group_matrix(g, pose)
    return m.to_machine(c @ M.T + t)


def draw(out, aid, bones, pose=None):
    m = Model(aid)
    fig, axes = plt.subplots(1, 2, figsize=(14, 7))
    cmap = plt.get_cmap('tab10')
    groups = []
    for b in bones:
        groups += m.descendants(m.by_name[b])
    allc = []
    for k, g in enumerate(groups):
        for e in g.elements:
            c = cube_corners(m, g, e, pose)
            allc.append(c)
            rot = any(e.get('rotation', [0, 0, 0]))
            for ax, (i, j) in zip(axes, ((2, 1), (0, 1))):
                ax.add_patch(Polygon(hull2d(c[:, [i, j]]), closed=True, fc=(*cmap(k % 10)[:3], 0.15),
                                     ec=cmap(k % 10), lw=1.2 if rot else 0.6, ls='--' if rot else '-'))
        piv = m.to_machine(g.origin[None])[0]
        axes[0].plot(piv[2], piv[1], '+', color=cmap(k % 10), ms=9)
        axes[1].plot(piv[0], piv[1], '+', color=cmap(k % 10), ms=9)
        axes[0].annotate(g.name, (piv[2], piv[1]), fontsize=7, color=cmap(k % 10))
    allc = np.concatenate(allc)
    from views import corners
    from audit import hull_for
    lo3, hi3 = allc.min(0) - 0.4, allc.max(0) + 0.4
    hull, _ = hull_for(m) if 'gear_up' in m.animations else (None, 0)
    for b in (hull.boxes if hull else []):
        k = corners(b)
        if (k.max(0) < lo3).any() or (k.min(0) > hi3).any():
            continue
        for ax, (i, j) in zip(axes, ((2, 1), (0, 1))):
            ax.add_patch(Polygon(hull2d(k[:, [i, j]]), closed=True, fc=(0.5, 0.5, 0.5, 0.06), ec=(0.4, 0.4, 0.4, 0.3), lw=0.3))
    for ax, (i, j), t in zip(axes, ((2, 1), (0, 1)), ('side (z fwd, y)', 'front (x, y)')):
        lo, hi = allc[:, [i, j]].min(0), allc[:, [i, j]].max(0)
        pad = 0.3
        ax.set_xlim(lo[0] - pad, hi[0] + pad); ax.set_ylim(min(lo[1], 0) - pad, hi[1] + pad)
        ax.axhline(0, color='k', lw=0.8)
        ax.set_aspect('equal'); ax.set_title(f'{aid} {" ".join(bones)} — {t}', fontsize=9)
        ax.grid(alpha=0.3)
    fig.tight_layout(); fig.savefig(out, dpi=80); plt.close(fig)


if __name__ == '__main__':
    args = sys.argv[1:]
    stowed = '--up' in args
    args = [a for a in args if a != '--up']
    pose = None
    if stowed:
        mm = Model(args[1])
        pose = mm.pose('gear_up', float(mm.animations['gear_up'].get('length', 1.0)))
    draw(args[0], args[1], args[2:], pose)
