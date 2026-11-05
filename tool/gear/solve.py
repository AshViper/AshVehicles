"""Search the stowed angle of each gear leg so that it is hidden inside the hitbox.

    python tool/gear/solve.py [--json OUT] [id ...]

A leg keeps the axis and the direction it folds today (memory geckolib-gear-animation: the nose-gear X sign copies
whether the real leg folds forward or aft, and must not be guessed). What is searched is how far it turns and a
twist of up to 30 degrees about the other two axes, under two rules:

- the stowed leg must lie roughly along the airframe: the pivot-to-leg line between 35 degrees below and
  55 degrees above level (a leg turned 165 degrees "fits" only because a solid fuselage hides anything)
- moving away from today's angle costs 0.0006 per degree (twists count double), so a small gain does not buy a
  large change

A point counts as hidden inside a box, or where boxes lie above, below and to both sides of it (Hull.enclosed:
intakes and tube fuselages are hollow shells). A leg whose best answer still stands out is also tried in every
single-axis direction and that is reported, not applied.
"""
import sys, os, json, math
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Model, Hull, AIRCRAFT, rot_zyx
from audit import hull_for, timing

STEP = 0.07          # blocks between sample points while searching
FINAL_STEP = 0.04
TWIST = 30.0
ELEVATION = (-35.0, 55.0)
PER_DEGREE = 0.0006


def score(d):
    """Mean protrusion plus a third of the 95th percentile: one far wheel and a skin of small misses both count."""
    return float(d.mean() + 0.3 * np.percentile(d, 95))


class Leg:
    """One leg and everything it carries. The points are sampled once in the leg's own frame (what it carries
    already posed at the end of gear_up), so a trial angle is one matrix product."""

    def __init__(self, m, hull, name, end_pose):
        self.m, self.name = m, name
        self.end = end_pose
        self.g = g = m.by_name[name]
        self.groups = [d.name for d in m.descendants(g)]
        # the leg's parents, as posed
        self.Mp, self.cp = (m.group_matrix(g.parent, end_pose) if g.parent is not None
                            else (np.eye(3), np.zeros(3)))
        self.local = {}
        self.pivot = self._world(g.origin[None])[0]
        P = m.points(self.groups, None, step_blocks=0.1)
        reach = np.sqrt(((P - self.pivot) ** 2).sum(1)).max() + 0.5
        keep = [b for b in hull.boxes
                if np.linalg.norm(np.array(b['offset']) - self.pivot) <= reach + np.linalg.norm(b['size']) / 2 + 3.0]
        self.hull = Hull(keep) if keep else hull
        self.current = np.array(end_pose[name][0], float)

    def _world(self, Q):
        return self.m.to_machine(Q @ self.Mp.T + self.cp)

    def _local(self, step):
        """Points of the leg and its load in the leg's frame, before the leg's own turn."""
        if step in self.local:
            return self.local[step]
        m, g = self.m, self.g
        sm = step * 16.0 / m.scale
        pts = []
        for d in m.descendants(g):
            if not d.elements:
                continue
            M = np.eye(3); c = np.zeros(3)
            h = d
            while h is not g:                     # d up to, not including, the leg
                rot = h.rotation.copy(); scl = np.ones(3)
                if h.name in self.end:
                    a, s = self.end[h.name]
                    rot = rot + np.array([-a[0], -a[1], a[2]]); scl = s
                R = rot_zyx(*rot) @ np.diag(scl)
                M = R @ M; c = R @ (c - h.origin) + h.origin
                h = h.parent
            P = np.concatenate([m.element_points(e, sm) for e in d.elements])
            pts.append(P @ M.T + c)
        self.local[step] = np.concatenate(pts) if pts else np.zeros((0, 3))
        return self.local[step]

    def points(self, rot, step):
        a = np.asarray(rot, float)
        R = rot_zyx(*(self.g.rotation + np.array([-a[0], -a[1], a[2]])))
        scl = self.end[self.name][1] if self.name in self.end else np.ones(3)
        Q = self._local(step)
        Q = (Q - self.g.origin) @ (R @ np.diag(scl)).T + self.g.origin
        return self._world(Q)

    def field(self, res=0.08):
        """hidden_outside sampled once on a grid over the ball the leg can reach; trials read it back with trilinear
        interpolation instead of casting rays for every point of every angle (minutes per leg otherwise)."""
        if getattr(self, '_field', None) is not None:
            return self._field
        P = self._local(0.1)
        R0 = self.points(self.current, 0.1)
        reach = np.sqrt(((R0 - self.pivot) ** 2).sum(1)).max() + 2 * res + 0.1
        n = int(np.ceil(2 * reach / res)) + 1
        lo = self.pivot - reach
        ax = [lo[i] + res * np.arange(n) for i in range(3)]
        X, Y, Z = np.meshgrid(*ax, indexing='ij')
        G = np.stack([X.ravel(), Y.ravel(), Z.ravel()], 1)
        inball = ((G - self.pivot) ** 2).sum(1) <= (reach + res) ** 2
        vals = np.full(len(G), 1.0)
        vals[inball] = self.hull.hidden_outside(G[inball])
        self._field = (lo, res, n, vals.reshape(n, n, n))
        return self._field

    def lookup(self, P):
        lo, res, n, F = self.field()
        f = (P - lo) / res
        i = np.clip(np.floor(f).astype(int), 0, n - 2)
        t = np.clip(f - i, 0, 1)
        out = 0
        for dx in (0, 1):
            wx = t[:, 0] if dx else 1 - t[:, 0]
            for dy in (0, 1):
                wy = t[:, 1] if dy else 1 - t[:, 1]
                for dz in (0, 1):
                    wz = t[:, 2] if dz else 1 - t[:, 2]
                    out = out + wx * wy * wz * F[i[:, 0] + dx, i[:, 1] + dy, i[:, 2] + dz]
        return out

    def eval(self, rot, step=STEP):
        return self.lookup(self.points(rot, step))

    def elevation(self, rot):
        """Angle above level of the line from the pivot to the leg's middle, stowed."""
        P = self.points(rot, 0.15)
        v = P.mean(0) - self.pivot
        n = np.linalg.norm(v)
        return 0.0 if n < 1e-6 else math.degrees(math.asin(max(-1, min(1, v[1] / n))))

    def stats(self, rot, step=FINAL_STEP):
        d = self.hull.hidden_outside(self.points(rot, step))     # exact, not the grid
        return {'rot': [round(float(x), 3) for x in rot], 'score': score(d), 'inside': float((d < 0.03).mean()),
                'p95': float(np.percentile(d, 95)), 'max': float(d.max()), 'elevation': self.elevation(rot)}


def primary_axis(rot):
    a = int(np.argmax(np.abs(rot)))
    return a, (1.0 if rot[a] >= 0 else -1.0)


def change(r, current, a):
    d = np.abs(np.array(r) - current)
    return d[a] + 2 * sum(d[i] for i in range(3) if i != a)


def search_family(leg):
    a, sgn = primary_axis(leg.current)
    others = [i for i in range(3) if i != a]
    seen = {}

    def trial(r):
        key = tuple(np.round(r, 3))
        if key in seen:
            return seen[key]
        lo, hi = ELEVATION
        el = leg.elevation(r)
        if not (lo <= el <= hi):
            seen[key] = None
            return None
        s = score(leg.eval(r))
        seen[key] = (s + PER_DEGREE * change(r, leg.current, a), s, key)
        return seen[key]

    base = leg.current.copy()
    for ang in np.arange(20, 180.01, 2.5):
        r = base.copy(); r[a] = sgn * ang
        trial(r)
    ranked = sorted(v for v in seen.values() if v)
    for _, _, seed in ranked[:3]:
        for o in others:
            for tw in np.arange(-TWIST, TWIST + 0.01, 5):
                r = np.array(seed); r[o] = tw
                trial(r)
    ranked = sorted(v for v in seen.values() if v)
    if not ranked:
        return None
    cur = ranked[0]
    for step in (2.5, 1.25):
        improved = True
        while improved:
            improved = False
            for i in range(3):
                for dlt in (-step, step):
                    r = np.array(cur[2]); r[i] += dlt
                    if i == a and (r[a] * sgn <= 0 or abs(r[a]) > 180):
                        continue
                    if i != a and abs(r[i]) > TWIST:
                        continue
                    t = trial(r)
                    if t and t[0] < cur[0] - 1e-7:
                        cur = t; improved = True
    return np.array(cur[2])


def search_free(leg):
    out = []
    for a in range(3):
        for ang in np.arange(-180, 180.01, 5):
            if ang == 0:
                continue
            r = np.zeros(3); r[a] = ang
            el = leg.elevation(r)
            if not (ELEVATION[0] <= el <= ELEVATION[1]):
                continue
            out.append((score(leg.eval(r)), tuple(r)))
    out.sort()
    return out


def solve(aid, out=print):
    m = Model(aid)
    if 'gear_up' not in m.animations:
        return None
    hull, _ = hull_for(m)
    length = float(m.animations['gear_up'].get('length', 1.0))
    end = m.pose('gear_up', length)
    animated = set(m.animated('gear_up'))
    result = {}
    out(f'== {aid}')
    legs = [n for n in animated if timing(m, n)[0] < 0.5 * length]
    legs.sort(key=lambda n: (len(m.by_name[n].chain()), n))   # carriers first
    for n in legs:
        leg = Leg(m, hull, n, end)
        before = leg.stats(leg.current)
        chosen = search_family(leg)
        if chosen is None:
            out(f'  {n:12} no stowed attitude in range')
            continue
        after = leg.stats(chosen)
        out(f'  {n:12} {np.round(leg.current, 1).tolist()} -> {np.round(chosen, 2).tolist()}   '
            f'hidden {before["inside"]*100:5.1f}% -> {after["inside"]*100:5.1f}%   '
            f'max {before["max"]:.2f} -> {after["max"]:.2f}   p95 {before["p95"]:.2f} -> {after["p95"]:.2f}   '
            f'elev {before["elevation"]:.0f} -> {after["elevation"]:.0f}')
        entry = {'before': before, 'after': after, 'chosen': [float(x) for x in chosen]}
        if after['p95'] > 0.12:
            free = search_free(leg)
            if free:
                fb = leg.stats(np.array(free[0][1]))
                entry['free'] = fb
                out(f'      any direction: {np.round(free[0][1], 1).tolist()} hidden {fb["inside"]*100:5.1f}% '
                    f'max {fb["max"]:.2f} p95 {fb["p95"]:.2f}')
        result[n] = entry
        end[n] = (chosen, end[n][1])
    symmetrize(m, hull, end, result, out)
    return result


MIRROR = np.array([1.0, -1.0, -1.0])     # x mirrored: a turn about x keeps its sign, about y and z flips


def symmetrize(m, hull, end, result, out):
    """Mirrored legs (pivots mirrored in x, today's angles mirrored) get one angle: the average of the two answers,
    so grid noise does not leave a left and a right leg a degree or two apart."""
    names = list(result)
    done = set()
    for a in names:
        for b in names:
            if a >= b or a in done or b in done:
                continue
            pa = m.to_machine(m.by_name[a].origin[None])[0]
            pb = m.to_machine(m.by_name[b].origin[None])[0]
            ca, cb = np.array(result[a]['before']['rot']), np.array(result[b]['before']['rot'])
            if abs(pa[0] + pb[0]) > 0.05 or abs(pa[0]) < 0.05 or np.abs(pa[1:] - pb[1:]).max() > 0.05:
                continue
            if np.abs(ca - cb * MIRROR).max() > 0.01:
                continue
            ra, rb = np.array(result[a]['chosen']), np.array(result[b]['chosen'])
            avg = np.round((ra + rb * MIRROR) / 2 / 0.625) * 0.625
            for n, r in ((a, avg), (b, avg * MIRROR)):
                if np.abs(r - np.array(result[n]['chosen'])).max() > 1e-6:
                    leg = Leg(m, hull, n, end)
                    result[n]['chosen'] = [float(x) for x in r]
                    result[n]['after'] = leg.stats(r)
                    end[n] = (r, end[n][1])
            done |= {a, b}
            out(f'  pair {a} / {b}: {np.round(avg, 3).tolist()}  max {result[a]["after"]["max"]:.2f} / {result[b]["after"]["max"]:.2f}')


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
        for aid, r, lines in pool.imap_unordered(_one, ids):
            if lines:
                print('\n'.join(lines), flush=True)
            if r:
                everything[aid] = r
            if dest:                                   # after each one, so a killed run keeps what it finished
                json.dump(everything, open(dest, 'w'), indent=1)
