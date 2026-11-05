"""The player model sitting in a seat, in the definition file's frame.

The point written in airframe.seats is not where anything of the rider is. Vanilla takes the
player's own vehicle attachment (0, 0.6, 0) off it, so the feet would be 0.6 below; the riding
pose then folds the legs forward about a hip 12/16 above the feet (seat-point-is-not-the-riders-feet).
Measured from the point in the file:

    hip / seat pan  +0.15     shoulders +0.78     neck +0.90     eye +1.02     head top +1.40

The parts and the three flips are tool/vehicle-editor.html's CREW_PARTS / crewPoint, which is
the game's HumanoidModel in its riding pose.
"""
import math
import numpy as np

# Vanilla's 0.6, less the 0.2 AircraftEntity.PLAYER_RIDER_LIFT puts back (2026-11-05):
# in an aircraft the feet are 0.4 under the point, eye +1.22, head top +1.60.
DROP = 0.6 - 0.2
HANG = 1.501
EYE = 1.62 - DROP          # the rider's own eye above the seat point: 1.02
HIP = 0.151
HEAD_TOP = HANG - DROP + 0.5   # 1.401
NECK = HANG - DROP          # 0.901

# pivot, from, size, rotation (radians, applied Z then Y then X as ModelPart does)
PARTS = {
    'head': ((0, 0, 0), (-4, -8, -4), (8, 8, 8), (0, 0, 0)),
    'body': ((0, 0, 0), (-4, 0, -2), (8, 12, 4), (0, 0, 0)),
    'arm_r': ((-5, 2, 0), (-3, -2, -2), (4, 12, 4), (-0.62831855, 0, 0)),
    'arm_l': ((5, 2, 0), (-1, -2, -2), (4, 12, 4), (-0.62831855, 0, 0)),
    'leg_r': ((-1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (-1.4137167, 0.31415927, 0.07853982)),
    'leg_l': ((1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (-1.4137167, -0.31415927, -0.07853982)),
}


def _r(axis, a):
    c, s = math.cos(a), math.sin(a)
    if axis == 'x':
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == 'y':
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def part_corners(name, seat):
    """The eight corners of one part of a rider whose seat point is `seat`, facing the nose."""
    pivot, frm, size, rot = PARTS[name]
    m = _r('z', rot[2]) @ _r('y', rot[1]) @ _r('x', rot[0])
    out = []
    for i in range(8):
        local = np.array([frm[0] + (size[0] if i & 1 else 0),
                          frm[1] + (size[1] if i & 2 else 0),
                          frm[2] + (size[2] if i & 4 else 0)], float)
        p = m @ local + np.array(pivot, float)
        # model (x, y down, z back) -> (right, up, forward) above the feet, then the drop
        out.append([seat[0] - p[0] / 16, seat[1] + HANG - p[1] / 16 - DROP, seat[2] - p[2] / 16])
    return np.array(out)


def body(seat):
    return {n: part_corners(n, seat) for n in PARTS}


def head_box(seat):
    """Head as (lo, hi) in the file frame."""
    c = part_corners('head', seat)
    return c.min(axis=0), c.max(axis=0)


def sample(seat, parts=None, step=0.06):
    """Points filling the named parts (all by default): what must not be inside the airframe."""
    pts = []
    for n in parts or PARTS:
        pivot, frm, size, rot = PARTS[n]
        m = _r('z', rot[2]) @ _r('y', rot[1]) @ _r('x', rot[0])
        g = [np.linspace(frm[k], frm[k] + size[k], max(2, int(size[k] / 16 / step) + 1)) for k in range(3)]
        X, Y, Z = np.meshgrid(*g, indexing='ij')
        local = np.stack([X.ravel(), Y.ravel(), Z.ravel()], axis=1)
        p = local @ m.T + np.array(pivot, float)
        q = np.stack([seat[0] - p[:, 0] / 16, seat[1] + HANG - p[:, 1] / 16 - DROP, seat[2] - p[:, 2] / 16], axis=1)
        pts.append(q)
    return np.concatenate(pts)
