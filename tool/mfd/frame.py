"""The aircraft's hitbox boxes and bbmodel cubes in the definition file's frame.

Frame: x right, y up from the wheels, z forward, in blocks. A .bbmodel cube goes there as
(bb.x, bb.y, -bb.z) * scale / 16 after Blockbench's own turns (three.js order 'ZYX' about each
origin); a hitbox box turns by Ry(ry) Rx(-rx) Rz(-rz) about its offset, which is
VehicleShape.Box.orientation() moved out of the entity's frame (+x left) into the file's.
Both are what tool/seats/check_pose.py proved against each other, copied here so this tool does
not move when that one does.
"""
import json, math, os
import numpy as np

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir))
DEFS = os.path.join(REPO, 'src', 'main', 'resources', 'data', 'ashvehicles', 'ashvehicles', 'aircraft')
BBMODELS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'ashvehicles', 'bbmodel', 'entity')


def _axes(rx, ry, rz):
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rx, Ry, Rz


def rot_zyx(rx, ry, rz):
    Rx, Ry, Rz = _axes(rx, ry, rz)
    return Rz @ Ry @ Rx


def rot_box(rx, ry, rz):
    """The turn of a hitbox box in the file frame, as the game makes it."""
    Rx, Ry, Rz = _axes(-rx, ry, -rz)
    return Ry @ Rx @ Rz


class Cube:
    """One posed box: centre, three unit axes (rows) and the half-edges along them."""
    __slots__ = ('mid', 'axes', 'half', 'name', 'bone')

    def __init__(self, mid, axes, half, name, bone):
        self.mid, self.axes, self.half, self.name, self.bone = mid, axes, half, name, bone

    def corners(self):
        s = np.array([[a, b, c] for a in (-1, 1) for b in (-1, 1) for c in (-1, 1)], float)
        return self.mid + (s * self.half) @ self.axes

    def contains(self, P, pad=0.0):
        d = (np.atleast_2d(P) - self.mid) @ self.axes.T
        return np.all(np.abs(d) <= self.half + pad, axis=1)


def definition(aid):
    return json.load(open(os.path.join(DEFS, aid + '.json'), encoding='utf-8'))


def has_model(aid):
    return os.path.exists(os.path.join(BBMODELS, aid + '.bbmodel'))


def model_cubes(aid, d):
    """Every cube of the machine's bbmodel at rest."""
    bb = json.load(open(os.path.join(BBMODELS, aid + '.bbmodel'), encoding='utf-8'))
    k = float(d.get('model', {}).get('scale', 1.0)) / 16.0
    F = np.diag([k, k, -k])
    els = {e['uuid']: e for e in bb['elements'] if e.get('type', 'cube') == 'cube'}
    out = []

    def walk(node, Mx, t, bone):
        if isinstance(node, str):
            e = els.get(node)
            if e is None or e.get('export') is False:
                return
            inf = float(e.get('inflate', 0) or 0)
            f, to = np.array(e['from'], float), np.array(e['to'], float)
            lo, hi = np.minimum(f, to) - inf, np.maximum(f, to) + inf
            o = np.array(e.get('origin', [0, 0, 0]), float)
            R = rot_zyx(*e.get('rotation', [0, 0, 0]))
            mid = Mx @ (R @ ((lo + hi) / 2 - o) + o) + t
            axes = (F @ Mx @ R).T
            n = np.linalg.norm(axes, axis=1)
            out.append(Cube(F @ mid, axes / n[:, None], (hi - lo) / 2 * n, e.get('name', ''), bone))
            return
        o = np.array(node.get('origin', [0, 0, 0]), float)
        R = rot_zyx(*node.get('rotation', [0, 0, 0]))
        for c in node.get('children', []):
            walk(c, Mx @ R, Mx @ (o - R @ o) + t, node.get('name', ''))

    for g in bb['outliner']:
        walk(g, np.eye(3), np.zeros(3), '')
    return out


def hitbox_cubes(d):
    out = []
    for b in d['hitbox']['boxes']:
        R = rot_box(*b.get('rotation', [0, 0, 0]))
        out.append(Cube(np.array(b['offset'], float), R.T.copy(), np.array(b['size'], float) / 2,
                        b.get('name', ''), b.get('name', '')))
    return out


def seats_of(d):
    """[(pos, eye or None), ...] from airframe.seats."""
    out = []
    for s in d.get('airframe', {}).get('seats', []):
        if isinstance(s, dict):
            out.append((s['pos'], s.get('eye')))
        else:
            out.append((s, None))
    return out


def eye_of(d, i):
    """The seat's first-person eye: its own, else camera.cockpit (VehicleEntityBase.getSeatEye)."""
    s = seats_of(d)
    if i < len(s) and s[i][1] is not None:
        return np.array(s[i][1], float)
    return np.array(d['camera']['cockpit'], float)
