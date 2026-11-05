"""Put every wheel on the ground with the gear down.

    python tool/gear/ground.py [--apply] [id ...]

The entity's feet are machine y = 0 and the model is drawn from its own y = 0, so a wheel whose lowest point is
above 0 floats and one below sinks. Taildraggers are measured after their ground_stance tilt and sink.
Tricycle aircraft cannot be levelled with ground_stance: a positive stance is read as "steering wheel behind"
(AircraftModel steer sign, AircraftEntity.gearPivot), so the legs themselves are fixed:

- the whole leg moves (pivot, every cube and group under it) when its top stays buried in the hitbox after the
  move: the strut just shows a little more or less of itself
- otherwise the strut is lengthened at a horizontal cut: everything under the cut moves down, the cubes the cut
  passes through grow. The cut is chosen where only unturned, per-face-UV cubes cross it (box UV would read
  neighbouring texels once the cube grows)

A leg is touched when its lowest point is above +0.015 or below -0.04 blocks, and set to -0.01 (the tyre
pressed in a little).
"""
import sys, os, math, json
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Model, AIRCRAFT, rot_zyx
from audit import timing, hull_for

TARGET = -0.01
FLOAT, SINK = 0.015, -0.04


def chain_rot(g):
    """Linear part of a group's rest transform, through all its parents."""
    R = np.eye(3)
    for h in g.chain():
        R = rot_zyx(*h.rotation) @ R
    return R


def lowest(m, leg, pose):
    P = m.points([leg], pose, step_blocks=0.03)
    lg = m.definition.get('landing_gear', {})
    stance = float(lg.get('ground_stance', 0) or 0)
    if stance:
        a = math.radians(stance)
        zp = float(lg.get('stance_pivot', 0) or 0)
        return float((P[:, 1] * math.cos(a) + P[:, 2] * math.sin(a)).min() - zp * math.sin(a)), stance
    return float(P[:, 1].min()), 0.0


def top_legs(m):
    length = float(m.animations['gear_up'].get('length', 1.0))
    animated = set(m.animated('gear_up'))
    legs = [n for n in animated if timing(m, n)[0] < 0.5 * length]
    return sorted(n for n in legs if not any(h.name in animated for h in m.by_name[n].chain()[1:]))


class Editor:
    """Edits the project JSON in place: elements by uuid, group origins in the outliner and the 5.0 group list."""

    def __init__(self, m):
        self.m = m
        self.d = m.project
        self.elements = {e['uuid']: e for e in self.d['elements']}
        self.nodes = {}
        def walk(node):
            if isinstance(node, dict):
                self.nodes[node.get('uuid')] = node
                for c in node.get('children', []):
                    walk(c)
        for n in self.d['outliner']:
            walk(n)
        self.listed = {g.get('uuid'): g for g in self.d.get('groups', [])}

    def move_origin(self, uuid, t):
        for src in (self.nodes.get(uuid), self.listed.get(uuid)):
            if src is not None and 'origin' in src:
                src['origin'] = [r(src['origin'][i] + t[i]) for i in range(3)]

    def move_element(self, e, t):
        for k in ('from', 'to', 'origin'):
            if k in e:
                e[k] = [r(e[k][i] + t[i]) for i in range(3)]

    def write(self):
        write_project(self.m.path, self.d, self.m.raw)


def write_project(path, d, read_as):
    """Write back in the file's own style: Blockbench's compact JSON, or the spaced one some files were made with
    (B-21, Eurofighter, MQ-9). Every bundled bbmodel reads back byte for byte in one of these four.
    Refuses when the file is no longer what was read (another session wrote it meanwhile)."""
    raw = open(path, encoding='utf-8').read()
    if raw != read_as:
        raise RuntimeError(f'{path} changed on disk since it was read; run again')
    original = json.loads(raw)
    for seps in ((',', ':'), (', ', ': ')):
        for ascii_ in (False, True):
            if json.dumps(original, separators=seps, ensure_ascii=ascii_) == raw:
                text = json.dumps(d, separators=seps, ensure_ascii=ascii_)
                with open(path, 'w', encoding='utf-8', newline='') as f:
                    f.write(text)
                return
    raise RuntimeError(f'{path}: no JSON style reproduces the file, not writing it')


def r(v):
    v = round(float(v), 4)
    return int(v) if v == int(v) else v


def world_span(m, g, e):
    frm = np.array(e['from'], float); to = np.array(e['to'], float)
    inf = float(e.get('inflate', 0) or 0)
    c = np.array([[x, y, z] for x in (frm[0], to[0]) for y in (frm[1], to[1]) for z in (frm[2], to[2])])
    rot = e.get('rotation', [0, 0, 0])
    if any(rot):
        o = np.array(e.get('origin', [0, 0, 0]), float)
        c = (c - o) @ rot_zyx(*rot).T + o
    M, t = m.group_matrix(g, None)
    w = c @ M.T + t
    return w[:, 1].min() - inf, w[:, 1].max() + inf


def plan_stretch(m, leg):
    """The cut (model y) and what crosses it, or None."""
    g = m.by_name[leg]
    parts = []
    for d in m.descendants(g):
        aligned_group = np.allclose(chain_rot(d), np.eye(3), atol=1e-9)
        for e in d.elements:
            lo, hi = world_span(m, d, e)
            parts.append((d, e, lo, hi, aligned_group and not any(e.get('rotation', [0, 0, 0])) and not e.get('box_uv')))
    lo = min(p[2] for p in parts); hi = max(p[3] for p in parts)
    top = min(hi, g.origin[1])
    best = None
    for yc in np.arange(lo + 0.45 * (top - lo), top - 0.08 * (top - lo), 0.25):
        cross = [p for p in parts if p[2] < yc - 0.05 and p[3] > yc + 0.05]
        if not cross or not all(p[4] for p in cross):
            continue
        mid = lo + 0.7 * (top - lo)
        key = (len(cross), abs(yc - mid))
        if best is None or key < best[0]:
            best = (key, yc, cross)
    if best is None:
        return None
    return best[1], best[2], parts


def stretch(m, ed, leg, delta, plan):
    """Lengthen by delta model units at the cut (delta > 0 drops the wheel)."""
    yc, cross, parts = plan
    crossing = {id(p[1]) for p in cross}
    g = m.by_name[leg]
    world = np.array([0.0, -delta, 0.0])
    hi_of = {id(p[1]): p[3] for p in parts}
    # subtrees wholly under the cut move rigidly, origins included (wheel axles keep spinning about themselves):
    # every origin and cube in one is moved by the same vector, in the frame of the subtree's parent
    moved = set()

    def below(d):
        es = [e for dd in m.descendants(d) for e in dd.elements]
        return bool(es) and all(hi_of[id(e)] <= yc + 0.05 for e in es)

    def visit(d):
        for c in d.children:
            if below(c):
                t = chain_rot(d).T @ world
                for dd in m.descendants(c):
                    ed.move_origin(dd.uuid, t)
                    for e in dd.elements:
                        ed.move_element(e, t)
                        moved.add(id(e))
            else:
                visit(c)

    visit(g)
    for d, e, lo, hi, _ in parts:
        if id(e) in moved:
            continue
        if id(e) in crossing:
            low_key = 0 if e['from'][1] <= e['to'][1] else 1
            k = ('from', 'to')[low_key]
            e[k] = [e[k][0], r(e[k][1] - delta), e[k][2]]
        elif hi <= yc + 0.05:
            ed.move_element(e, chain_rot(d).T @ world)


def shift(m, ed, leg, delta):
    """Move the whole leg by delta model units down (negative: up)."""
    g = m.by_name[leg]
    world = np.array([0.0, -delta, 0.0])
    Rp = chain_rot(g.parent) if g.parent is not None else np.eye(3)
    t = Rp.T @ world
    for d in m.descendants(g):
        ed.move_origin(d.uuid, t)
        for e in d.elements:
            ed.move_element(e, t)


def ground(aid, apply=False, out=print):
    m = Model(aid)
    if 'gear_up' not in m.animations:
        return []
    L = float(m.animations['gear_down'].get('length', 1.0))
    pose = m.pose('gear_down', L)
    hull, _ = hull_for(m)
    ed = Editor(m)
    done = []
    for leg in top_legs(m):
        low, stance = lowest(m, leg, pose)
        if SINK <= low <= FLOAT:
            continue
        want = TARGET - low                                   # machine y to add (negative = down)
        delta = -want * 16.0 / m.scale / math.cos(math.radians(stance))   # model units down
        Q = m.points([leg], pose, step_blocks=0.05)
        buried_after = int((hull.outside(Q + np.array([0, want, 0])) < 1e-6).sum())
        how = None
        if want > 0 or buried_after > 0:
            how = 'shift'
            shift(m, ed, leg, delta)
        else:
            plan = plan_stretch(m, leg)
            if plan is None:
                out(f'  {aid} {leg}: low {low:+.3f}, no clean cut to lengthen at; left as is')
                continue
            how = f'lengthen at model y {plan[0]:.2f} ({len(plan[1])} cube{"s" if len(plan[1]) > 1 else ""} grow)'
            stretch(m, ed, leg, delta, plan)
        done.append((leg, low, want, how))
        out(f'  {aid:10} {leg:10} lowest {low:+.3f} -> {TARGET:+.3f}  ({want:+.3f} blocks, {delta:+.2f} model units): {how}')
    if done and apply:
        ed.write()
    return done


if __name__ == '__main__':
    args = sys.argv[1:]
    apply = '--apply' in args
    args = [a for a in args if a != '--apply']
    ids = args or sorted(f[:-5] for f in os.listdir(AIRCRAFT) if f.endswith('.json'))
    for aid in ids:
        try:
            ground(aid, apply)
        except FileNotFoundError:
            pass
