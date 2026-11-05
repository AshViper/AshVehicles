"""Cross-sections of the cockpit with the crew drawn in: one PNG per aircraft.

    python tool/seats/views.py OUTDIR [id ...] [--file DIR]   (needs matplotlib)

Left: a slice along the seat's x (side view). Right: a slice across at the head (front view).
Model cubes are cut by the plane, so a curved canopy built of turned slabs shows as its outline,
not as the boxes' shadow. Canopy cubes are blue. The rider is drawn from rider.py: grey body,
red head, the dot at +1.02 is the rider's own eye, the cross is the first-person eye the game
uses (the seat's own eye, else camera.cockpit).
"""
import sys, os, json
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model as M
import rider as RD
from cockpit import is_canopy, seats_of, eye_of

EDGES = [(0, 1), (2, 3), (4, 5), (6, 7), (0, 2), (1, 3), (4, 6), (5, 7), (0, 4), (1, 5), (2, 6), (3, 7)]


def hull2(pts):
    pts = sorted(set(map(tuple, np.round(pts, 5))))
    if len(pts) < 3:
        return np.array(pts)
    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
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


def slice_cube(c, axis, value, dims):
    """The polygon where the plane (axis = value) cuts the cube, in the axes `dims`."""
    k = c.corners()
    pts = []
    for a, b in EDGES:
        pa, pb = k[a], k[b]
        da, db = pa[axis] - value, pb[axis] - value
        if da == 0:
            pts.append(pa)
        if (da < 0) != (db < 0) and db != 0:
            t = da / (da - db)
            pts.append(pa + t * (pb - pa))
    if len(pts) < 2:
        return None
    return hull2(np.array(pts)[:, list(dims)])


def slice_tri(t, axis, value, dims):
    pts = []
    for a, b in ((0, 1), (1, 2), (2, 0)):
        da, db = t[a][axis] - value, t[b][axis] - value
        if (da < 0) != (db < 0):
            u = da / (da - db)
            pts.append(t[a] + u * (t[b] - t[a]))
    if len(pts) < 2:
        return None
    return np.array(pts)[:2][:, list(dims)]


def draw_rider(ax, seat, dims, col='tab:red', ls='-', lw=1.2):
    for n, c in RD.body(seat).items():
        h = hull2(c[:, dims])
        ax.add_patch(Polygon(h, closed=True, fill=False, ec=col, ls=ls, lw=lw if n == 'head' else lw * 0.7))
    e = [seat[0], seat[1] + RD.EYE, seat[2]]
    ax.plot(e[dims[0]], e[dims[1]], 'o', color=col, ms=3)
    ax.plot(seat[dims[0]], seat[dims[1]], 'x', color=col, ms=5)


def draw(aid, d, cubes, out, title='', before=None, tris=None):
    seats = seats_of(d)
    if not seats:
        return
    n = len(seats)
    cols = min(n, 10)
    fig, axes = plt.subplots(3, cols, figsize=(6.5 * cols, 15), squeeze=False)
    for i, s in enumerate(seats[:cols]):
        ax_note = None
        pos = np.array(s[0], float)
        eye = np.array(eye_of(d, i), float)
        for row, (axis, value, dims, lab) in enumerate(((0, pos[0], (2, 1), 'side x=%.2f' % pos[0]),
                                                       (2, pos[2], (0, 1), 'front z=%.2f' % pos[2]),
                                                       (1, pos[1] + 0.45, (0, 2), 'top y=%.2f' % (pos[1] + 0.45)))):
            ax = axes[row][i]
            if tris is not None:
                for t, g in tris:
                    seg = slice_tri(t, axis, value, dims)
                    if seg is not None:
                        ax.plot(seg[:, 0], seg[:, 1], '-', color='tab:blue' if g else '0.3', lw=1.2 if g else 0.6)
            for c in cubes:
                p = slice_cube(c, axis, value, dims)
                if p is None or len(p) < 2:
                    continue
                can = is_canopy(c)
                if len(p) == 2:   # a plane cut edge-on: the canopy is mostly these
                    ax.plot(p[:, 0], p[:, 1], '-', color='tab:blue' if can else '0.3', lw=1.6 if can else 0.8)
                    continue
                ax.add_patch(Polygon(p, closed=True, fc=(0.3, 0.5, 1.0, 0.25) if can else (0.55, 0.55, 0.6, 0.35),
                                     ec=(0.1, 0.2, 0.7, 0.8) if can else (0.2, 0.2, 0.25, 0.6), lw=0.5))
            if before is not None and i < len(seats_of(before)):
                bp = np.array(seats_of(before)[i][0], float)
                be = np.array(eye_of(before, i), float)
                draw_rider(ax, bp, list(dims), col='0.45', ls='--', lw=0.9)
                ax.plot(be[dims[0]], be[dims[1]], '+', color='0.45', ms=10, mew=1.5)
            draw_rider(ax, pos, list(dims))
            ax.plot(eye[dims[0]], eye[dims[1]], '+', color='tab:green', ms=12, mew=2)
            if row == 0:   # the HUD plate the game hangs off the eye
                ax.plot([eye[2] + 0.55, eye[2] + 0.55], [eye[1] - 0.15, eye[1] + 0.05], '-', color='tab:green', lw=3, alpha=0.6)
            cx, cy = pos[dims[0]], (pos[1] + 0.8 if row < 2 else pos[2] + 0.4)
            span = 1.5 if row == 0 else 1.2
            ax.set_xlim(cx - span * (1.5 if row == 0 else 1), cx + span * (1.5 if row == 0 else 1))
            ax.set_ylim(cy - span, cy + span)
            if row == 2:
                for k, (sp, se) in enumerate(seats):   # every crew member, from above
                    if k != i:
                        draw_rider(ax, np.array(sp, float), list(dims), col='tab:purple', lw=0.8)
            ax.set_aspect('equal')
            ax.grid(True, lw=0.3, alpha=0.5)
            ax.set_title(f'{aid} seat {i} {lab}  pos {np.round(pos, 3).tolist()} eye {np.round(eye, 3).tolist()}', fontsize=8)
            if row == 0:
                ax.invert_xaxis()   # nose to the left
    fig.suptitle(title or aid)
    fig.tight_layout()
    fig.savefig(os.path.join(out, aid + '.png'), dpi=90)
    plt.close(fig)


if __name__ == '__main__':
    args = sys.argv[1:]
    src = None
    if '--file' in args:
        k = args.index('--file')
        src = args[k + 1]
        del args[k:k + 2]
    before = None
    if '--before' in args:
        k = args.index('--before')
        before = args[k + 1]
        del args[k:k + 2]
    out = args[0]
    os.makedirs(out, exist_ok=True)
    ids = args[1:]
    if not ids:
        import glob
        ids = sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(M.DEFS, '*.json')))
    for aid in ids:
        if not M.has_model(aid) or (src and not os.path.exists(os.path.join(src, aid + '.json'))):
            print('skip', aid)
            continue
        d = json.load(open(os.path.join(src, aid + '.json'), encoding='utf-8')) if src else M.definition(aid)
        b = M.definition(aid) if before == 'file' else (json.load(open(os.path.join(before, aid + '.json'), encoding='utf-8')) if before else None)
        if os.path.exists(os.path.join(M.BBMODELS, aid + '.bbmodel')):
            draw(aid, d, M.load(aid), out, before=b)
        else:
            t, names = M.load_obj(aid)
            draw(aid, d, [], out, before=b, tris=[(tt, n == 'glass') for tt, n in zip(t, names)])
        print('drew', aid)
