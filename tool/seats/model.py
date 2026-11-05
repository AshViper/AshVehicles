"""The aircraft's bbmodel posed into the definition file's frame, cube by cube.

The hitbox is the raw pass of the model (one box per cube) but leaves out the small cubes, and
the small cubes are the cockpit: the ejection seat, the stick, the panel, the HUD glass. Seats
and eyes are placed against those, so this reads the model itself.

Frame: the definition file's, x right, y up from the wheels, z forward, in blocks. From a
.bbmodel the x flips cancel and only z turns round (geo-coordinates-to-aircraft-axes):

    file = (bb.x, bb.y, -bb.z) * scale / 16

Blockbench turns a cube or a group about its origin by Euler angles in three.js order 'ZYX'
(R = Rz Ry Rx). check.py matches every hitbox box against a cube posed this way.
"""
import json, math, os
import numpy as np

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir))
DEFS = os.path.join(REPO, 'src', 'main', 'resources', 'data', 'ashvehicles', 'ashvehicles', 'aircraft')
BBMODELS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'ashvehicles', 'bbmodel', 'entity')


def rot_zyx(rx, ry, rz):
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def rot_box(rx, ry, rz):
    """The turn of a hitbox box in the file frame, as the game makes it.

    VehicleShape.Box.orientation() is rotateY(-ry) rotateX(-rx) rotateZ(rz) in the entity's own
    frame, whose +x is the left wing; turned into the file's frame (x right) that is
    Ry(ry) Rx(-rx) Rz(-rz). Every hitbox box matches its model cube this way (check_pose.py).
    VapourShape.Extent turns with rotationYXZ(ry, rx, rz), which is this conjugated by a half
    turn about y: a box's points come out mirrored, its bounding extent (all Extent takes) the
    same. tool/pylons/hitbox.py copied Extent and sampled the points, which is where it went
    wrong; fixed 2026-11-04."""
    return rot_yxz(-rx, ry, -rz)


def rot_yxz(rx, ry, rz):
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Ry @ Rx @ Rz


class Cube:
    """One posed cube: centre, three half-edge vectors (the box's own axes) and where it came from."""
    __slots__ = ('mid', 'axes', 'half', 'name', 'bone', 'path')

    def __init__(self, mid, axes, half, name, bone, path):
        self.mid, self.axes, self.half = mid, axes, half
        self.name, self.bone, self.path = name, bone, path

    def corners(self):
        s = np.array([[a, b, c] for a in (-1, 1) for b in (-1, 1) for c in (-1, 1)], float)
        return self.mid + (s * self.half) @ self.axes

    def contains(self, P, pad=0.0):
        d = (np.atleast_2d(P) - self.mid) @ self.axes.T
        return np.all(np.abs(d) <= self.half + pad, axis=1)

    def size(self):
        return 2 * self.half


def definition(aid):
    return json.load(open(os.path.join(DEFS, aid + '.json'), encoding='utf-8'))


def load(aid, scale=None, roles_off=()):
    """Every cube of the machine's bbmodel at rest, in the file frame. Bones named in roles_off
    (and everything under them) are left out."""
    path = os.path.join(BBMODELS, aid + '.bbmodel')
    bb = json.load(open(path, encoding='utf-8'))
    if scale is None:
        scale = float(definition(aid).get('model', {}).get('scale', 1.0))
    k = scale / 16.0
    els = {e['uuid']: e for e in bb['elements'] if e.get('type', 'cube') == 'cube'}
    out = []

    def walk(node, M, t, path):
        # M, t: the parents' turn as x -> M x + t, in bb units
        if isinstance(node, str):
            e = els.get(node)
            if e is None or e.get('export') is False:
                return
            f = np.array(e['from'], float)
            to = np.array(e['to'], float)
            inf = float(e.get('inflate', 0) or 0)
            lo, hi = np.minimum(f, to) - inf, np.maximum(f, to) + inf
            o = np.array(e.get('origin', [0, 0, 0]), float)
            R = rot_zyx(*e.get('rotation', [0, 0, 0]))
            mid = (lo + hi) / 2
            # the cube's own turn about its origin, then the parents'
            mid_w = M @ (R @ (mid - o) + o) + t
            A = M @ R  # columns are the cube's axes in bb space
            half = (hi - lo) / 2
            # to the file frame: scale and z turned round
            F = np.diag([k, k, -k])
            mid_f = F @ mid_w
            axes = (F @ A).T  # rows: unit-ish axes scaled by k
            n = np.linalg.norm(axes, axis=1)
            axes = axes / n[:, None]
            half_f = half * n
            out.append(Cube(mid_f, axes, half_f, e.get('name', ''), path[-1] if path else '', '/'.join(path)))
            return
        name = node.get('name', '')
        if name in roles_off:
            return
        o = np.array(node.get('origin', [0, 0, 0]), float)
        R = rot_zyx(*node.get('rotation', [0, 0, 0]))
        # x -> M (R (x - o) + o) + t
        M2 = M @ R
        t2 = M @ (o - R @ o) + t
        for c in node.get('children', []):
            walk(c, M2, t2, path + [name])

    for g in bb['outliner']:
        walk(g, np.eye(3), np.zeros(3), [])
    return out


def hitbox_cubes(d):
    """The definition's hitbox boxes as Cubes, turned the way the game turns them."""
    out = []
    for b in d['hitbox']['boxes']:
        R = rot_box(*b.get('rotation', [0, 0, 0]))
        out.append(Cube(np.array(b['offset'], float), R.T.copy(), np.array(b['size'], float) / 2,
                        b.get('name', ''), b.get('name', ''), ''))
    return out


def obj_path(aid):
    return os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'ashvehicles', 'obj', 'entity', aid + '.obj')


def load_obj(aid, scale=None):
    """The triangles of an OBJ model in the file frame, as (tris[n,3,3], object names[n]).

    An OBJ is Blockbench's screen in blocks (obj-models-bake-into-the-geckolib-cache), so like a
    .bbmodel only z turns round: file = (x, y, -z) * scale."""
    if scale is None:
        scale = float(definition(aid).get('model', {}).get('scale', 1.0))
    verts, tris, names = [], [], []
    name = ''
    for line in open(obj_path(aid), encoding='utf-8'):
        if line.startswith('v '):
            x, y, z = map(float, line.split()[1:4])
            verts.append((x * scale, y * scale, -z * scale))
        elif line.startswith('o '):
            name = line[2:].strip()
        elif line.startswith('f '):
            idx = [int(t.split('/')[0]) for t in line.split()[1:]]
            idx = [i - 1 if i > 0 else len(verts) + i for i in idx]
            for k in range(1, len(idx) - 1):
                tris.append((verts[idx[0]], verts[idx[k]], verts[idx[k + 1]]))
                names.append(name)
    return np.array(tris, float), names


def has_model(aid):
    return os.path.exists(os.path.join(BBMODELS, aid + '.bbmodel')) or os.path.exists(obj_path(aid))
