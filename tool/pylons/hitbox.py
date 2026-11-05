"""Hitbox surface sampling shared by the pylon audit and the re-layout.

Boxes are turned about their centre the way the game turns them for hits and drawing
(VehicleShape.Box.orientation(): rotateY(-ry) rotateX(-rx) rotateZ(rz) in the entity's frame,
whose +x is the left wing), which in the file's frame (x right) is Ry(ry) Rx(-rx) Rz(-rz).
Until 2026-11-04 this copied VapourShape.Extent's rotationYXZ(ry, rx, rz) and so read every
box with a pitch or a roll mirrored: dihedral as anhedral, 110 stations 3 cm or more off the
skin, the B-52's wing racks 1.1 under the wing. tool/seats/check_pose.py proves the turn: with
it every box lands on the model cube it came from.

Surfaces are sampled as points so a swept, rotated wing is measured by its faces, not by its
bounding box (the trap written down in what-a-new-machine-needs).
"""
import json, math, os
import numpy as np

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir))
ROOT = os.path.join(REPO, 'src', 'main', 'resources', 'data', 'ashvehicles', 'ashvehicles', 'aircraft')


def rot_yxz(rx, ry, rz):
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Ry @ Rx @ Rz


def rot_box(rx, ry, rz):
    """A hitbox box's turn in the file's frame, as the game makes it."""
    return rot_yxz(-rx, ry, -rz)


def box_points(box, step=0.06):
    o = np.array(box['offset'], float)
    s = np.array(box['size'], float)
    r = box.get('rotation', [0, 0, 0])
    R = rot_box(*r)
    h = s / 2
    pts = []
    for axis in range(3):
        a, b = [i for i in range(3) if i != axis]
        na = max(2, int(math.ceil(s[a] / step)) + 1)
        nb = max(2, int(math.ceil(s[b] / step)) + 1)
        ua = np.linspace(-h[a], h[a], na)
        ub = np.linspace(-h[b], h[b], nb)
        A, B = np.meshgrid(ua, ub, indexing='ij')
        for sign in (-1, 1):
            p = np.zeros((A.size, 3))
            p[:, axis] = sign * h[axis]
            p[:, a] = A.ravel()
            p[:, b] = B.ravel()
            pts.append(p)
    p = np.concatenate(pts)
    return p @ R.T + o


def load(aid):
    path = os.path.join(ROOT, aid + '.json')
    d = json.load(open(path, encoding='utf-8'))
    return d


def cloud(d, skip_mounts=()):
    pts = []
    for b in d['hitbox']['boxes']:
        if b.get('mount') in skip_mounts:
            continue
        pts.append(box_points(b))
    return np.concatenate(pts)


def column(P, x, z, rx=0.12, rz=0.12):
    m = (np.abs(P[:, 0] - x) <= rx) & (np.abs(P[:, 2] - z) <= rz)
    return P[m]


def underside(P, x, z, rx=0.12, rz=0.12, above=None):
    """Lowest surface in the column at (x, z); with `above`, the lowest one higher than it."""
    c = column(P, x, z, rx, rz)
    if above is not None:
        c = c[c[:, 1] > above]
    return None if len(c) == 0 else float(c[:, 1].min())


def segments(vals, gap=0.25):
    vals = np.sort(vals)
    if len(vals) == 0:
        return []
    out = [[vals[0], vals[0]]]
    for v in vals[1:]:
        if v - out[-1][1] > gap:
            out.append([v, v])
        else:
            out[-1][1] = v
    return out
