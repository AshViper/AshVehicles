"""Measurements of the cockpit around a crew station, taken from the model's cube surfaces.

Nothing here reads names: the bones are called cockpit, cokpit, group29 or nothing at all, and
the 'cockpit' bone of a dozen aircraft is the shared interior (seat, panel, stick), not the glass.
Glass is told by shape instead: a cube with no thickness.
"""
import math, os
import numpy as np


def faces(c, step=0.04):
    """Points over the six faces of a posed cube."""
    h = c.half
    pts = []
    for axis in range(3):
        a, b = [i for i in range(3) if i != axis]
        na = max(2, int(math.ceil(2 * h[a] / step)) + 1)
        nb = max(2, int(math.ceil(2 * h[b] / step)) + 1)
        ua = np.linspace(-h[a], h[a], na)
        ub = np.linspace(-h[b], h[b], nb)
        A, B = np.meshgrid(ua, ub, indexing='ij')
        for sign in ((-1, 1) if h[axis] > 1e-6 else (1,)):
            p = np.zeros((A.size, 3))
            p[:, axis] = sign * h[axis]
            p[:, a] = A.ravel()
            p[:, b] = B.ravel()
            pts.append(p)
    p = np.concatenate(pts)
    return p @ c.axes + c.mid


def tri_points(t, step=0.04):
    """Points over one triangle."""
    a, b, c = t
    n = max(1, int(math.ceil(max(np.linalg.norm(b - a), np.linalg.norm(c - a), np.linalg.norm(c - b)) / step)))
    out = []
    for i in range(n + 1):
        for j in range(n + 1 - i):
            u, v = i / n, j / n
            out.append(a + u * (b - a) + v * (c - a))
    return np.array(out)


class Skin:
    """The model as surface points, each knowing whether it is glass."""

    @classmethod
    def of(cls, aid, near=None, radius=6.0):
        import model as M
        if os.path.exists(os.path.join(M.BBMODELS, aid + '.bbmodel')):
            return cls(M.load(aid), near, radius)
        tris, names = M.load_obj(aid)
        s = cls([], None)
        pts, glass = [], []
        for t, n in zip(tris, names):
            if near is not None and (np.any(t.min(0) > np.asarray(near) + radius) or np.any(t.max(0) < np.asarray(near) - radius)):
                continue
            p = tri_points(t)
            pts.append(p)
            glass.append(np.full(len(p), n == 'glass'))
        s.P = np.concatenate(pts)
        s.glass = np.concatenate(glass)
        return s

    def __init__(self, cubes, near=None, radius=6.0):
        pts, glass = [np.zeros((0, 3))], [np.zeros(0, bool)]
        for c in cubes:
            if near is not None:
                k = c.corners()
                lo, hi = k.min(0), k.max(0)
                if np.any(lo > np.asarray(near) + radius) or np.any(hi < np.asarray(near) - radius):
                    continue
            p = faces(c)
            pts.append(p)
            glass.append(np.full(len(p), min(c.size()) < 0.012))
        self.P = np.concatenate(pts)
        self.glass = np.concatenate(glass)

    def box(self, lo, hi, solid_only=False):
        lo, hi = np.asarray(lo, float), np.asarray(hi, float)
        m = np.all((self.P >= lo) & (self.P <= hi), axis=1)
        if solid_only:
            m &= ~self.glass
        return self.P[m]


def ceiling(skin, x, z, above, rx=0.22, rz=0.22, top=None):
    """Lowest surface over the head's footprint that is higher than `above`."""
    q = skin.box([x - rx, above, z - rz], [x + rx, 1e9 if top is None else top, z + rz])
    return None if len(q) == 0 else float(q[:, 1].min())


def canopy(skin, x, z, above, dxs=(-0.15, -0.07, 0.0, 0.07, 0.15), dzs=(0.0, 0.1, 0.2), q=0.3, w=0.03):
    """What closes the cockpit over the head: vertical rays from `above` over the front half of the
    head, each stopping at the first surface, and the q-th lowest of the hits. A seat's side frames
    or a headrest pad catch a ray or two and are outvoted; a canopy or a roof catches them all."""
    hits = []
    for dx in dxs:
        for dz in dzs:
            c = skin.box([x + dx - w, above, z + dz - w], [x + dx + w, 1e9, z + dz + w])
            if len(c):
                hits.append(float(c[:, 1].min()))
    if len(hits) < max(2, len(dxs) * len(dzs) // 2):
        return None
    return float(np.quantile(hits, q))


def floor(skin, x, z0, z1, below, rx=0.18, depth=0.8):
    """Highest surface under the hip between z0 and z1 that is lower than `below`."""
    q = skin.box([x - rx, below - depth, z0], [x + rx, below, z1], solid_only=True)
    return None if len(q) == 0 else float(q[:, 1].max())


def back(skin, x, y0, y1, z, reach=0.7, rx=0.18):
    """Front face of whatever is behind the rider between heights y0 and y1: the seat back."""
    q = skin.box([x - rx, y0, z - reach], [x + rx, y1, z], solid_only=True)
    return None if len(q) == 0 else float(q[:, 2].max())


def coaming(skin, x, z0, z1, below, rx=0.12):
    """Highest solid surface straight ahead between z0 and z1 that is lower than `below`:
    the glare shield, or the panel's top if there is no shield.

    Taken as the lowest of three strips' highest points (left, middle, right of the line of
    sight) so that a stick or a yoke between the knees, one strip wide, does not count."""
    tops = []
    for dx in (-rx, 0.0, rx):
        q = skin.box([x + dx - 0.04, below - 1.5, z0], [x + dx + 0.04, below, z1], solid_only=True)
        if len(q) == 0:
            return None
        tops.append(float(q[:, 1].max()))
    return min(tops)


def profile(skin, x, z0, z1, below, rx=0.12, dz=0.05):
    """(z, top) pairs ahead of the eye: the highest solid surface in each slice."""
    out = []
    z = z0
    while z <= z1 + 1e-9:
        tops = []
        for ddx in (-rx, 0.0, rx):
            q = skin.box([x + ddx - 0.04, below - 1.5, z - dz / 2], [x + ddx + 0.04, below, z + dz / 2], solid_only=True)
            tops.append(float(q[:, 1].max()) if len(q) else None)
        if all(t is not None for t in tops):
            out.append((z, min(tops)))
        z += dz
    return out
