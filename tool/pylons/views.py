"""Top (from below) and front views of the hitbox with the pylon points, one PNG per aircraft.

    python tool/pylons/views.py OUTDIR [id ...]      (needs matplotlib)
"""
import sys, os, json, math
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Polygon, Rectangle
sys.path.insert(0, os.path.dirname(__file__))
from hitbox import *


def hull(pts):
    pts = sorted(set(map(tuple, np.round(pts, 4))))
    if len(pts) < 3:
        return np.array(pts)
    def cross(o, a, b):
        return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return np.array(lower[:-1] + upper[:-1])


def corners(b):
    o = np.array(b['offset'], float); h = np.array(b['size'], float) / 2
    R = rot_box(*b.get('rotation', [0, 0, 0]))
    c = np.array([[sx, sy, sz] for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]) * h
    return c @ R.T + o


STYLE = {'weapon': ('tab:red', 's'), 'special': ('tab:blue', 'D'), 'tip': ('tab:orange', '^'),
         'internal': ('tab:green', 'o')}


def kind_of(h):
    if h.get('internal'):
        return 'internal'
    if h.get('wingtip'):
        return 'tip'
    return h.get('kind', 'weapon')


def draw(aid, d, out, title=''):
    boxes = d['hitbox']['boxes']
    hps = [h for h in d.get('hardpoints', []) if 'fixed' not in h]
    fig, axes = plt.subplots(1, 3, figsize=(21, 8), gridspec_kw={'width_ratios': [1.2, 1.0, 1.2]})
    ax, ax2, ax3 = axes
    for b in boxes:
        c = corners(b)
        ax.add_patch(Polygon(hull(c[:, [0, 2]]), closed=True, fc=(0.6, 0.6, 0.65, 0.18), ec=(0.2, 0.2, 0.3, 0.5), lw=0.4))
        ax2.add_patch(Polygon(hull(c[:, [0, 1]]), closed=True, fc=(0.6, 0.6, 0.65, 0.12), ec=(0.2, 0.2, 0.3, 0.4), lw=0.4))
        ax3.add_patch(Polygon(hull(c[:, [2, 1]]), closed=True, fc=(0.6, 0.6, 0.65, 0.12), ec=(0.2, 0.2, 0.3, 0.4), lw=0.4))
    for h in hps:
        x, y, z = h['pos']
        k = kind_of(h)
        col, mk = STYLE[k]
        if k in ('weapon', 'internal'):
            ax.add_patch(Rectangle((x - 0.09, z - 0.9), 0.18, 1.8, fc=col, alpha=0.35))
            ax2.add_patch(Rectangle((x - 0.09, y + 0.12), 0.18, 0.32, fc=col, alpha=0.5))
            ax2.add_patch(Rectangle((x - 0.13, y - 0.17), 0.26, 0.4, fc='none', ec=col, lw=0.8))
            ax3.add_patch(Rectangle((z - 0.9, y + 0.12), 1.8, 0.32, fc=col, alpha=0.4))
            ax3.add_patch(Rectangle((z - 1.15, y - 0.17), 2.3, 0.4, fc='none', ec=col, lw=0.6))
        elif k == 'tip':
            ax.add_patch(Rectangle((x - 0.07, z - 1.44), 0.14, 2.88, fc=col, alpha=0.4))
            ax2.add_patch(Rectangle((x - 0.07, y + 0.06), 0.14, 0.22, fc=col, alpha=0.5))
        else:
            ax.add_patch(Rectangle((x - 0.1, z - 0.9), 0.2, 1.8, fc='none', ec=col, lw=1))
            ax2.add_patch(Rectangle((x - 0.1, y - 0.15), 0.2, 0.35, fc='none', ec=col, lw=1))
        ax.plot(x, z, mk, color=col, ms=5)
        ax2.plot(x, y, mk, color=col, ms=4)
        ax.annotate(h.get('name', '')[:14], (x, z), fontsize=6, xytext=(3, 3), textcoords='offset points')
    for a in axes:
        a.set_aspect('equal'); a.autoscale_view(); a.grid(alpha=0.25)
    ax.axhline(0, color='k', lw=0.3); ax2.axhline(0, color='brown', lw=0.8)
    ax3.axhline(0, color='brown', lw=0.8)
    ax.set_title(f'{aid} plan (nose up, x right)  {title}')
    ax2.set_title('front'); ax3.set_title('side (nose right)')
    fig.tight_layout()
    fig.savefig(os.path.join(out, aid + '.png'), dpi=70)
    plt.close(fig)


if __name__ == '__main__':
    out = sys.argv[1]
    args = sys.argv[2:]
    os.makedirs(out, exist_ok=True)
    ids = args or sorted(os.path.basename(f)[:-5] for f in __import__('glob').glob(ROOT + '/*.json'))
    for aid in ids:
        draw(aid, load(aid), out)
