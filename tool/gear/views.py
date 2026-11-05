"""Pictures of each stowed leg against the hitbox: from below, from the front and from the side.

    python tool/gear/views.py OUTDIR id [proposal.json]      (needs matplotlib)

Boxes are drawn as their outlines in each view, the leg's points green where inside the hull and red where
they stand out. With a proposal (solve.py --json), a second row shows the proposed angles.
"""
import sys, os, json
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Model, box_turn
from audit import hull_for, timing


def hull2d(pts):
    pts = sorted(set(map(tuple, np.round(pts, 4))))
    if len(pts) < 3:
        return np.array(pts)
    def cross(o, a, b):
        return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0])
    lo, up = [], []
    for p in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    for p in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], p) <= 0:
            up.pop()
        up.append(p)
    return np.array(lo[:-1] + up[:-1])


def corners(b):
    o = np.array(b['offset'], float); h = np.array(b['size'], float) / 2
    c = np.array([[sx, sy, sz] for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]) * h
    return c @ box_turn(*b.get('rotation', [0, 0, 0])).T + o


def draw(aid, outdir, proposal=None):
    m = Model(aid)
    hull, _ = hull_for(m)
    length = float(m.animations['gear_up'].get('length', 1.0))
    end = m.pose('gear_up', length)
    legs = [n for n in m.animated('gear_up') if timing(m, n)[0] < 0.5 * length]
    rows = [('now', end)]
    if proposal and aid in proposal:
        prop = dict(end)
        for n, e in proposal[aid].items():
            if 'chosen' in e:
                prop[n] = (np.array(e['chosen'], float), prop[n][1])
        rows.append(('proposed', prop))
    views = [(0, 2, 'below (x, z)'), (0, 1, 'front (x, y)'), (2, 1, 'side (z, y)')]
    for leg in legs:
        g = m.by_name[leg]
        names = [d.name for d in m.descendants(g)]
        fig, axes = plt.subplots(len(rows), 3, figsize=(18, 6 * len(rows)), squeeze=False)
        for r, (label, pose) in enumerate(rows):
            P = m.points(names, pose, step_blocks=0.04, own_only=True)
            Pd = m.points(names, None, step_blocks=0.08, own_only=True)
            d = hull.hidden_outside(P)
            boxd = hull.outside(P)
            centre = np.concatenate([P, Pd]).mean(0)
            span = max(np.ptp(np.concatenate([P, Pd]), 0).max(), 1.0) * 0.9 + 0.6
            for c, (i, j, title) in enumerate(views):
                ax = axes[r][c]
                for b in hull.boxes:
                    k = corners(b)
                    if np.abs(k.mean(0) - centre).max() > span + np.linalg.norm(b['size']):
                        continue
                    ax.add_patch(Polygon(hull2d(k[:, [i, j]]), closed=True, fc=(0.55, 0.6, 0.7, 0.10),
                                         ec=(0.2, 0.25, 0.35, 0.35), lw=0.4))
                ax.scatter(Pd[:, i], Pd[:, j], s=0.4, c='0.75')
                ins = d < 0.03
                inbox = boxd < 0.03
                ax.scatter(P[inbox, i], P[inbox, j], s=0.6, c='tab:green')
                ax.scatter(P[ins & ~inbox, i], P[ins & ~inbox, j], s=0.6, c='tab:blue')
                ax.scatter(P[~ins, i], P[~ins, j], s=0.9, c='tab:red')
                piv = m.to_machine(g.origin[None])[0]
                ax.plot(piv[i], piv[j], 'k+', ms=10)
                ax.set_xlim(centre[i] - span, centre[i] + span)
                ax.set_ylim(centre[j] - span, centre[j] + span)
                ax.set_aspect('equal')
                rot = np.round(pose[leg][0], 1).tolist()
                ax.set_title(f'{aid} {leg} {label} {rot} — {title}  inside {ins.mean()*100:.0f}% max {d.max():.2f}', fontsize=9)
                if j == 2:
                    ax.set_ylabel('z (nose up)')
        fig.tight_layout()
        fig.savefig(os.path.join(outdir, f'{aid}_{leg}.png'), dpi=70)
        plt.close(fig)


if __name__ == '__main__':
    outdir, aid = sys.argv[1], sys.argv[2]
    proposal = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else None
    os.makedirs(outdir, exist_ok=True)
    draw(aid, outdir, proposal)
