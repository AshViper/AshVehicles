"""How much of each stowed gear leg and closed door sits outside the hitbox.

    python tool/gear/audit.py [id ...]

For every animated bone of `gear_up`, at its last frame: the share of its surface inside the union of the
aircraft's hitbox boxes, and how far the worst points stand out (blocks). Boxes made from the gear's own cubes are
left out of the hull (an open door must not count as room for a leg). Also checks that `gear_down` starts where
`gear_up` ends and ends at rest.
"""
import sys, os
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Model, Hull, AIRCRAFT

TOL = 0.03


def units(m, anim='gear_up'):
    """Each animated bone with the unanimated groups under it (the parts it carries)."""
    animated = set(m.animated(anim))
    out = {}
    for n in animated:
        own = []
        stack = [m.by_name[n]]
        while stack:
            g = stack.pop()
            own.append(g.name)
            stack += [c for c in g.children if c.name not in animated]
        out[n] = own
    return out


def gear_boxes(m, names):
    """Indices of hitbox boxes made from the cubes of these groups (raw boxes sit on cube centres)."""
    boxes = m.definition['hitbox']['boxes']
    O = np.array([b['offset'] for b in boxes])
    hit = set()
    for _, c in m.cube_centres(names):
        d = np.sqrt(((O - c) ** 2).sum(1))
        i = int(d.argmin())
        if d[i] < 0.01:
            hit.add(i)
    return hit


def hull_for(m, extra_skip=()):
    u = units(m)
    every = [n for v in u.values() for n in v]
    skip = gear_boxes(m, every) if every else set()
    boxes = [b for i, b in enumerate(m.definition['hitbox']['boxes']) if i not in skip and b.get('name') not in extra_skip]
    return Hull(boxes), len(skip)


def timing(m, name, anim='gear_up'):
    """(first, last) time the bone's channels change in the animation."""
    v = m.animated(anim)[name]
    times = []
    prev = None
    for k in sorted(v['keyframes'], key=lambda k: k['time']):
        val = tuple(float(k['data_points'][-1][c]) for c in 'xyz') + (k['channel'],)
        times.append((k['time'], val))
    ch = [t for (t, val), (t0, v0) in zip(times[1:], times) if val != v0]
    starts = [t0 for (t, val), (t0, v0) in zip(times[1:], times) if val != v0]
    return (min(starts) if starts else 0.0, max(ch) if ch else 0.0)


def measure(m, hull, names, pose, step=0.04):
    P = m.points(names, pose, step_blocks=step, own_only=True)
    if len(P) == 0:
        return None
    d = hull.hidden_outside(P)
    return {'inside': float((d < TOL).mean()), 'p95': float(np.percentile(d, 95)), 'max': float(d.max()), 'n': len(P),
            'low': float(P[:, 1].min())}


def audit(aid, out=print):
    m = Model(aid)
    if 'gear_up' not in m.animations:
        return None
    hull, skipped = hull_for(m)
    up = m.animations['gear_up']
    length = float(up.get('length', 1.0))
    end = m.pose('gear_up', length)
    u = units(m)
    rows = []
    for n, names in u.items():
        t0, t1 = timing(m, n)
        kind = 'door' if t0 >= 0.5 * length else 'leg'
        r = measure(m, hull, names, end)
        if r is None:
            continue
        rot, scl = end[n]
        rows.append((n, kind, rot, scl, r))
    # consistency of the two halves
    issues = []
    if 'gear_down' in m.animations:
        start = m.pose('gear_down', 0.0)
        fin = m.pose('gear_down', float(m.animations['gear_down'].get('length', 1.0)))
        begin = m.pose('gear_up', 0.0)
        # gear_down must run gear_up backwards: start where it ends, end where it starts (a door modelled
        # closed legitimately ends gear_down open, so "ends at rest" is not the test)
        for n, (rot, scl) in end.items():
            s = start.get(n)
            if s is None or np.abs(s[0] - rot).max() > 0.01 or np.abs(s[1] - scl).max() > 0.001:
                issues.append(f'{n}: gear_down starts at {None if s is None else s[0].tolist()} but gear_up ends at {rot.tolist()}')
            f, b = fin.get(n), begin.get(n)
            if f is not None and b is not None and (np.abs(f[0] - b[0]).max() > 0.01 or np.abs(f[1] - b[1]).max() > 0.001):
                issues.append(f'{n}: gear_down ends at {f[0].tolist()} but gear_up starts at {b[0].tolist()}')
    out(f'== {aid}  (scale {m.scale}, {len(hull.boxes)} boxes, {skipped} gear boxes left out)')
    for n, kind, rot, scl, r in sorted(rows, key=lambda x: (x[1], x[0])):
        flag = '  <--' if r['max'] > 0.25 else ''
        sc = '' if np.allclose(scl, 1) else f' scale {scl.round(2).tolist()}'
        out(f'  {kind:4} {n:18} rot {np.round(rot, 1).tolist()}{sc}  inside {r["inside"]*100:5.1f}%  p95 {r["p95"]:.2f}  max {r["max"]:.2f}{flag}')
    for i in issues:
        out('  !! ' + i)
    return rows, issues


if __name__ == '__main__':
    ids = sys.argv[1:] or sorted(f[:-5] for f in os.listdir(AIRCRAFT) if f.endswith('.json'))
    for aid in ids:
        try:
            audit(aid)
        except FileNotFoundError:
            pass
