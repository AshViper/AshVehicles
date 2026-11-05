"""Closing angle of the gear doors: a closed door lies on the skin, it does not hang under it.

    python tool/gear/doors.py [--json OUT] [id ...]

A closed door covers a hole in the hitbox (the bay), so "distance to the nearest box" would score a door tucked up
into the bay better than one lying flush. Instead each door point is compared with the skin bridged across the
opening: the lowest hull surface within BRIDGE blocks of it (seen from below). Below that skin is hanging out and
costs in full; more than SUNK above it is a door swung into the bay and costs a third.

Only doors that stand out today are searched (OUT), about the axis they already turn on. A door whose stowed angle
is 0 while its rest pose hangs out was modelled open and animated as if modelled closed (Su-34 FRGearHatch): it is
searched too, and written with its deployed angle set to 0 (apply.py --doors).
"""
import sys, os, json, math
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Model, AIRCRAFT
from audit import hull_for, timing
from solve import Leg, PER_DEGREE
from views import corners

BRIDGE = 0.5
SUNK = 0.12
CELL = 0.05
OUT = 0.2


class Skin:
    """Lowest hull surface over a grid seen from below, bridged across openings narrower than 2 * BRIDGE."""

    def __init__(self, hull, lo, hi):
        self.lo = np.array(lo, float) - BRIDGE - 0.1
        n = np.ceil((np.array(hi, float) + BRIDGE + 0.1 - self.lo) / CELL).astype(int) + 1
        self.n = n
        grid = np.full((n[0], n[1]), np.inf)
        from model import box_turn
        for b in hull.boxes:
            k = corners(b)
            if (k[:, 0].max() < self.lo[0] or k[:, 2].max() < self.lo[1] or
                    k[:, 0].min() > self.lo[0] + n[0] * CELL or k[:, 2].min() > self.lo[1] + n[1] * CELL):
                continue
            P = surface(b)
            i = np.floor((P[:, 0] - self.lo[0]) / CELL).astype(int)
            j = np.floor((P[:, 2] - self.lo[1]) / CELL).astype(int)
            ok = (i >= 0) & (i < n[0]) & (j >= 0) & (j < n[1])
            np.minimum.at(grid, (i[ok], j[ok]), P[ok, 1])
        r = int(round(BRIDGE / CELL))
        self.grid = minfilter(minfilter(grid, r, 0), r, 1)

    def below(self, P):
        i = np.clip(np.floor((P[:, 0] - self.lo[0]) / CELL).astype(int), 0, self.n[0] - 1)
        j = np.clip(np.floor((P[:, 2] - self.lo[1]) / CELL).astype(int), 0, self.n[1] - 1)
        return self.grid[i, j]


def surface(b, step=0.04):
    from model import box_turn
    o = np.array(b['offset'], float); s = np.array(b['size'], float); h = s / 2
    pts = []
    for axis in range(3):
        a, c = [i for i in range(3) if i != axis]
        A, C = np.meshgrid(np.linspace(-h[a], h[a], max(2, int(s[a] / step) + 1)),
                           np.linspace(-h[c], h[c], max(2, int(s[c] / step) + 1)), indexing='ij')
        for sign in (-1, 1):
            p = np.zeros((A.size, 3)); p[:, axis] = sign * h[axis]; p[:, a] = A.ravel(); p[:, c] = C.ravel()
            pts.append(p)
    return np.concatenate(pts) @ box_turn(*b.get('rotation', [0, 0, 0])).T + o


def minfilter(g, r, axis):
    out = g.copy()
    for k in range(1, r + 1):
        a = np.roll(g, k, axis); b = np.roll(g, -k, axis)
        if axis == 0:
            a[:k] = np.inf; b[-k:] = np.inf
        else:
            a[:, :k] = np.inf; b[:, -k:] = np.inf
        out = np.minimum(out, np.minimum(a, b))
    return out


def door_cost(skin, P):
    y = skin.below(P)
    hang = np.maximum(y - P[:, 1], 0)
    hang[~np.isfinite(hang)] = 1.0         # nothing above at all: out in the open
    sunk = np.maximum(P[:, 1] - y - SUNK, 0)
    sunk[~np.isfinite(sunk)] = 0.0
    return hang, sunk


def score(hang, sunk):
    return float(hang.mean() + 0.3 * np.percentile(hang, 95) + 0.3 * sunk.mean())


def solve(aid, out=print):
    m = Model(aid)
    if 'gear_up' not in m.animations:
        return None
    hull, _ = hull_for(m)
    L = float(m.animations['gear_up'].get('length', 1.0))
    end = m.pose('gear_up', L)
    dn = m.pose('gear_down', float(m.animations['gear_down'].get('length', 1.0)))
    result = {}
    out(f'== {aid}')
    for n in sorted(m.animated('gear_up')):
        if timing(m, n)[0] < 0.5 * L or not np.allclose(end[n][1], 1):
            continue
        E = np.array(end[n][0], float); D = np.array(dn[n][0], float)
        leg = Leg(m, hull, n, end)
        P0 = leg.points(E, 0.04)
        reach = np.abs(m.points([n], None, 0.1) - leg.pivot).max() + 0.2
        skin = Skin(hull, leg.pivot[[0, 2]] - reach, leg.pivot[[0, 2]] + reach)
        h0, s0 = door_cost(skin, P0)
        if h0.max() <= OUT:
            continue
        inverted = not np.any(E) and np.any(D)
        axis = int(np.argmax(np.abs(D if inverted else E)))
        base = np.zeros(3) if inverted else E
        best = None
        for ang in np.arange(-180, 180.01, 2.5):
            r = np.zeros(3); r[axis] = ang
            h, s = door_cost(skin, leg.points(r, 0.05))
            c = score(h, s) + PER_DEGREE * abs(ang - base[axis])
            if best is None or c < best[0]:
                best = (c, r)
        for step in (1.25, 0.625):
            for dlt in (-step, step):
                r = best[1].copy(); r[axis] += dlt
                h, s = door_cost(skin, leg.points(r, 0.05))
                c = score(h, s) + PER_DEGREE * abs(r[axis] - base[axis])
                if c < best[0]:
                    best = (c, r)
        chosen = best[1]
        h1, s1 = door_cost(skin, leg.points(chosen, 0.04))
        out(f'  {n:18} {"(modelled open) " if inverted else ""}{np.round(E, 1).tolist()} -> {np.round(chosen, 2).tolist()}   '
            f'hangs max {h0.max():.2f} -> {h1.max():.2f}  p95 {np.percentile(h0, 95):.2f} -> {np.percentile(h1, 95):.2f}  '
            f'sunk mean {s0.mean():.2f} -> {s1.mean():.2f}')
        result[n] = {'chosen': [float(x) for x in chosen], 'inverted': bool(inverted), 'deployed': D.tolist(),
                     'before': float(h0.max()), 'after': float(h1.max())}
    return result


def _one(aid):
    import warnings
    warnings.simplefilter('ignore')
    lines = []
    try:
        r = solve(aid, out=lines.append)
    except FileNotFoundError:
        r = None
    return aid, r, lines


if __name__ == '__main__':
    from multiprocessing import Pool
    args = sys.argv[1:]
    dest = None
    if args[:1] == ['--json']:
        dest = args[1]; args = args[2:]
    ids = args or sorted(f[:-5] for f in os.listdir(AIRCRAFT) if f.endswith('.json'))
    everything = {}
    with Pool(min(len(ids), max(1, (os.cpu_count() or 2) - 2))) as pool:
        for aid, r, lines in pool.imap(_one, ids):
            if r:
                print('\n'.join(lines), flush=True)
                everything[aid] = r
    if dest:
        json.dump(everything, open(dest, 'w'), indent=1)
