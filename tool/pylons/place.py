"""Where a pylon goes, measured from the hitbox surfaces.

Rack models stand on the point and reach up 0.44 (wingtip rail 0.28), so a weapon station sits
0.42 under the lowest skin over the rack's footprint. Pods are centred on the point with their
top 0.19-0.21 up, so they sit 0.20 under it. Along the chord a wing pylon sits at CHORD of the
local chord back from the leading edge, which keeps the rack under the wing and hangs the store
forward the way real pylons do.
"""
import numpy as np
from hitbox import column, segments

RACK_TOP = 0.42
POD_TOP = 0.20
CHORD = 0.45
FOOT_X = 0.10
FOOT_FWD = 0.85
FOOT_AFT = 1.05


def foot_underside(P, x, z, hx=FOOT_X, fwd=FOOT_FWD, aft=FOOT_AFT, floor=None):
    m = (np.abs(P[:, 0] - x) <= hx) & (P[:, 2] <= z + fwd) & (P[:, 2] >= z - aft)
    c = P[m]
    if floor is not None:
        c = c[c[:, 1] > floor]
    return None if len(c) == 0 else float(c[:, 1].min())


def chord_at(P, ax, zhint=None, ymin=None):
    """Wing chord (TE, LE) at span ax: the z-run of skin in that column nearest the hint."""
    col = column(P, ax, 0, 0.08, 1e9)
    if ymin is not None:
        col = col[col[:, 1] > ymin]
    segs = segments(col[:, 2], gap=0.3)
    if not segs:
        return None
    if zhint is None:
        return max(segs, key=lambda s: s[1] - s[0])
    def dist(s):
        return 0 if s[0] <= zhint <= s[1] else min(abs(zhint - s[0]), abs(zhint - s[1]))
    return min(segs, key=dist)


def wing(P, ax, zhint=None, f=CHORD, ymin=None, dz=0.0):
    seg = chord_at(P, ax, zhint, ymin)
    te, le = seg
    z = le - f * (le - te) + dz
    under = foot_underside(P, ax, z, floor=ymin)
    centre = foot_underside(P, ax, z, 0.1, 0.15, 0.15, floor=ymin)
    return z, under - RACK_TOP, dict(te=te, le=le, under=under, centre=centre)


def body(P, x, z, ymin=None):
    under = foot_underside(P, x, z, floor=ymin)
    return under - RACK_TOP


def pod(P, x, z, ymin=None):
    under = foot_underside(P, x, z, 0.11, 0.9, 1.0, floor=ymin)
    return under - POD_TOP


def tip(P, zhint=None, band=None):
    """Wingtip rail: beside the outermost skin, its middle level with the middle of the tip."""
    semi = float(np.abs(P[:, 0]).max()) if band is None else band
    near = P[np.abs(P[:, 0]) > semi - 0.12]
    if zhint is not None:
        segs = segments(near[:, 2], 0.3)
        seg = min(segs, key=lambda s: 0 if s[0] <= zhint <= s[1] else min(abs(zhint - s[0]), abs(zhint - s[1])))
        near = near[(near[:, 2] >= seg[0]) & (near[:, 2] <= seg[1])]
    tipx = float(np.abs(near[:, 0]).max())
    zmid = (near[:, 2].min() + near[:, 2].max()) / 2
    ymid = (near[:, 1].min() + near[:, 1].max()) / 2
    return tipx + 0.07, ymid - 0.17, zmid, dict(chord=(float(near[:, 2].min()), float(near[:, 2].max())))


def section(P, z, xs, dz=0.3):
    """Lowest skin across the span at one station along the fuselage."""
    out = []
    for x in xs:
        u = foot_underside(P, x, z, 0.08, dz, dz)
        out.append(None if u is None else round(u, 2))
    return out
