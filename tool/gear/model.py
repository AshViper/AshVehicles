"""The bbmodel's bones, posed by its gear animations, in the aircraft's own axes.

Coordinates (memory geo-coordinates-to-aircraft-axes, bbmodel-files-stand-in-for-geo-texture-and-animation):
- bbmodel x is the machine's x (right), y is up, bbmodel -z is the nose. Units 16 per block, times model.scale.
- A group or element rotation [rx, ry, rz] turns points right-handed as Rz . Ry . Rx (X first), about its origin.
- An animation keyframe [ax, ay, az] is added to the geo rotation, whose x and y are the bbmodel's negated, so the
  posed bbmodel rotation is [rx - ax, ry - ay, rz + az]. Scale keyframes scale about the bone's pivot before it turns.
"""
import json, math, os
import numpy as np

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir))
BBMODEL = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'ashvehicles', 'bbmodel', 'entity')
AIRCRAFT = os.path.join(REPO, 'src', 'main', 'resources', 'data', 'ashvehicles', 'ashvehicles', 'aircraft')


def rot_zyx(rx, ry, rz):
    """Blockbench's own turn: points go through X, then Y, then Z."""
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def cube_points(frm, to, step):
    """Points on the six faces of an axis-aligned cube, `step` apart (model units)."""
    frm = np.asarray(frm, float); to = np.asarray(to, float)
    lo, hi = np.minimum(frm, to), np.maximum(frm, to)
    size = hi - lo
    pts = []
    for axis in range(3):
        a, b = [i for i in range(3) if i != axis]
        na = max(2, int(math.ceil(size[a] / step)) + 1)
        nb = max(2, int(math.ceil(size[b] / step)) + 1)
        A, B = np.meshgrid(np.linspace(lo[a], hi[a], na), np.linspace(lo[b], hi[b], nb), indexing='ij')
        for v in (lo[axis], hi[axis]):
            p = np.empty((A.size, 3))
            p[:, axis] = v
            p[:, a] = A.ravel()
            p[:, b] = B.ravel()
            pts.append(p)
    return np.concatenate(pts)


class Group:
    def __init__(self, uuid, name, origin, rotation, parent):
        self.uuid, self.name, self.parent = uuid, name, parent
        self.origin = np.array(origin, float)
        self.rotation = np.array(rotation, float)
        self.children = []   # groups
        self.elements = []   # element dicts

    def chain(self):
        g, out = self, []
        while g is not None:
            out.append(g)
            g = g.parent
        return out  # self first, root last


class Model:
    def __init__(self, aid):
        self.aid = aid
        self.path = os.path.join(BBMODEL, aid + '.bbmodel')
        self.raw = open(self.path, encoding='utf-8').read()
        self.project = json.loads(self.raw)
        self.definition = json.load(open(os.path.join(AIRCRAFT, aid + '.json'), encoding='utf-8'))
        self.scale = float(self.definition.get('model', {}).get('scale', 1.0))
        self.groups = {}       # uuid -> Group
        self.by_name = {}      # name -> Group (later duplicates get _2, as the game does)
        self.roots = []
        elements = {e.get('uuid'): e for e in self.project.get('elements', [])}
        listed = {g.get('uuid'): g for g in self.project.get('groups', [])}

        def walk(node, parent):
            if isinstance(node, str):
                e = elements.get(node)
                if e and e.get('export', True) and e.get('type', 'cube') == 'cube' and parent is not None:
                    parent.elements.append(e)
                return
            g = dict(listed.get(node.get('uuid'), {}), **node)
            name = g.get('name', 'bone')
            base, n = name, 2
            while name in self.by_name:
                name = f'{base}_{n}'; n += 1
            grp = Group(g.get('uuid'), name, g.get('origin', [0, 0, 0]), g.get('rotation', [0, 0, 0]), parent)
            self.groups[grp.uuid] = grp
            self.by_name[name] = grp
            if parent is None:
                self.roots.append(grp)
            else:
                parent.children.append(grp)
            for child in g.get('children', []):
                walk(child, grp)

        for node in self.project.get('outliner', []):
            walk(node, None)
        self.animations = {a['name']: a for a in self.project.get('animations', [])}

    # ---- animation -------------------------------------------------------------------------------------------
    def animated(self, name):
        a = self.animations.get(name)
        if not a:
            return {}
        return {self.groups[k].name if k in self.groups else v['name']: v for k, v in a['animators'].items()}

    @staticmethod
    def sample(keyframes, channel, t, default):
        ks = sorted((k for k in keyframes if k['channel'] == channel), key=lambda k: k['time'])
        if not ks:
            return np.array(default, float)
        def val(k):
            dp = k['data_points'][-1] if t >= k['time'] else k['data_points'][0]
            return np.array([float(dp[c]) for c in 'xyz'])
        if t <= ks[0]['time']:
            return val(ks[0])
        if t >= ks[-1]['time']:
            return np.array([float(ks[-1]['data_points'][-1][c]) for c in 'xyz'])
        for a, b in zip(ks, ks[1:]):
            if a['time'] <= t <= b['time']:
                va = np.array([float(a['data_points'][-1][c]) for c in 'xyz'])
                vb = np.array([float(b['data_points'][0][c]) for c in 'xyz'])
                f = 0 if b['time'] == a['time'] else (t - a['time']) / (b['time'] - a['time'])
                return va + (vb - va) * f

    def pose(self, anim, t):
        """{group name: (rotation keyframe value, scale value)} at time t of an animation."""
        out = {}
        for name, v in self.animated(anim).items():
            out[name] = (self.sample(v['keyframes'], 'rotation', t, [0, 0, 0]),
                         self.sample(v['keyframes'], 'scale', t, [1, 1, 1]))
        return out

    # ---- geometry --------------------------------------------------------------------------------------------
    def group_matrix(self, g, pose):
        """Affine (3x3, 3) taking a point in g's frame to model space, through g and all its parents."""
        M = np.eye(3); c = np.zeros(3)
        for h in g.chain():
            rot = h.rotation.copy()
            scl = np.ones(3)
            if pose and h.name in pose:
                a, s = pose[h.name]
                rot = rot + np.array([-a[0], -a[1], a[2]])
                scl = s
            R = rot_zyx(*rot) @ np.diag(scl)
            # p' = R (p - o) + o, applied after what came before (the child's own transform)
            M = R @ M
            c = R @ (c - h.origin) + h.origin
        return M, c

    def to_machine(self, P):
        s = self.scale / 16.0
        return np.stack([P[:, 0] * s, P[:, 1] * s, -P[:, 2] * s], axis=1)

    def element_points(self, e, step):
        frm = np.array(e['from'], float); to = np.array(e['to'], float)
        inf = float(e.get('inflate', 0) or 0)
        frm, to = np.minimum(frm, to) - inf, np.maximum(frm, to) + inf
        P = cube_points(frm, to, step)
        r = e.get('rotation', [0, 0, 0])
        if any(r):
            o = np.array(e.get('origin', [0, 0, 0]), float)
            P = (P - o) @ rot_zyx(*r).T + o
        return P

    def descendants(self, g):
        out = [g]
        for c in g.children:
            out += self.descendants(c)
        return out

    def points(self, names, pose=None, step_blocks=0.05, own_only=False):
        """Surface points of the named groups (and everything under them), posed, in machine axes (blocks)."""
        step = step_blocks * 16.0 / self.scale
        pts = []
        seen = set()
        for n in names:
            g = self.by_name[n]
            for d in ([g] if own_only else self.descendants(g)):
                if d.uuid in seen:
                    continue
                seen.add(d.uuid)
                if not d.elements:
                    continue
                M, c = self.group_matrix(d, pose)
                P = np.concatenate([self.element_points(e, step) for e in d.elements])
                pts.append(P @ M.T + c)
        if not pts:
            return np.zeros((0, 3))
        return self.to_machine(np.concatenate(pts))

    def cube_centres(self, names=None, pose=None):
        """(name, centre in machine axes) for every element, at rest unless posed."""
        out = []
        groups = self.groups.values() if names is None else [d for n in names for d in self.descendants(self.by_name[n])]
        for g in groups:
            if not g.elements:
                continue
            M, c = self.group_matrix(g, pose)
            for e in g.elements:
                frm = np.array(e['from'], float); to = np.array(e['to'], float)
                p = (frm + to) / 2
                r = e.get('rotation', [0, 0, 0])
                if any(r):
                    o = np.array(e.get('origin', [0, 0, 0]), float)
                    p = rot_zyx(*r) @ (p - o) + o
                out.append((g.name, self.to_machine((M @ p + c)[None])[0]))
        return out


# ---- hitbox ------------------------------------------------------------------------------------------------------
def box_turn(rx, ry, rz):
    """The game's box turn in the file's axes (x right).

    VehicleShape.Box.orientation is Attitude.rotate(rz, rx, ry) = rotateY(-ry).rotateX(-rx).rotateZ(rz) in the
    mod's inner frame, whose +X is left. Mirrored into the file's frame that is Ry(ry) . Rx(-rx) . Rz(-rz).
    Checked against the cubes the raw boxes were made from: every turned cube's corners land on its box's corners
    (F-16, median 0.0007 blocks). VapourShape.Extent turns with rotationYXZ(ry, rx, rz), which has the x and z
    signs the other way round (harmless there: it only keeps the bounding box). tool/pylons/hitbox.rot_box,
    tool/seats/model.rot_box and tool/mfd/frame.rot_box are this same matrix (hitbox.py was fixed 2026-11-04).
    """
    rx, ry, rz = math.radians(-rx), math.radians(ry), math.radians(-rz)
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Ry @ Rx @ Rz


class Hull:
    """The union of the hitbox boxes: how far a point is outside it (0 inside)."""

    def __init__(self, boxes):
        self.boxes = boxes
        self.O = np.array([b['offset'] for b in boxes], float)
        self.H = np.array([b['size'] for b in boxes], float) / 2
        self.R = np.array([box_turn(*b.get('rotation', [0, 0, 0])) for b in boxes])

    def blocked(self, P, axis, sign, chunk=4000):
        """Whether a ray from each point along +-axis meets any box (slab test in each box's frame)."""
        out = np.zeros(len(P), bool)
        e = np.zeros(3); e[axis] = sign
        D = np.einsum('mji,j->mi', self.R, e)                    # ray direction in each box's frame, M x 3
        with np.errstate(divide='ignore', invalid='ignore'):
            inv = 1.0 / D
        for i in range(0, len(P), chunk):
            p = P[i:i + chunk]
            o = np.einsum('mji,nmj->nmi', self.R, p[:, None, :] - self.O[None])   # N, M, 3
            t1 = (-self.H[None] - o) * inv[None]
            t2 = (self.H[None] - o) * inv[None]
            # a zero direction component: inside the slab or never
            par = (D == 0)[None]
            inslab = np.abs(o) <= self.H[None]
            lo = np.where(par, np.where(inslab, -np.inf, np.inf), np.minimum(t1, t2))
            hi = np.where(par, np.where(inslab, np.inf, -np.inf), np.maximum(t1, t2))
            tn = lo.max(-1); tf = hi.min(-1)
            out[i:i + chunk] = ((tf >= np.maximum(tn, 0)) & (tf >= 0)).any(1)
        return out

    def enclosed(self, P):
        """Boxes above, below and on both sides: hidden even where the hull is a hollow shell (intakes, tubes).
        Front and back are left open on purpose, an intake duct is open along z."""
        e = self.blocked(P, 1, -1)
        for axis, sign in ((1, 1), (0, -1), (0, 1)):
            idx = np.where(e)[0]
            if len(idx) == 0:
                break
            e[idx] = self.blocked(P[idx], axis, sign)
        return e

    def hidden_outside(self, P):
        """outside(), but 0 for points the hull encloses."""
        d = self.outside(P)
        idx = np.where(d > 0)[0]
        if len(idx):
            d[idx[self.enclosed(P[idx])]] = 0.0
        return d

    def outside(self, P, chunk=4000):
        """Distance from each point to the nearest box (0 when inside one)."""
        out = np.empty(len(P))
        for i in range(0, len(P), chunk):
            p = P[i:i + chunk]
            d = p[:, None, :] - self.O[None, :, :]              # N, M, 3
            local = np.einsum('mji,nmj->nmi', self.R, d)        # R^T d
            ex = np.maximum(np.abs(local) - self.H[None], 0)
            out[i:i + chunk] = np.sqrt((ex ** 2).sum(-1)).min(1)
        return out
