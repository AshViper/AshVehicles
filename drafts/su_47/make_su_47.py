"""Su-47 Berkut の下書き。MOD には入れない。

    python drafts/su_47/make_su_47.py

を回すと、この横に次を書く:

- su_47.bbmodel   模型・テクスチャ（埋め込み）・脚の出し入れのアニメーション
- su_47.json      機体定義の下書き（data/ashvehicles/ashvehicles/aircraft/ に置けばそのまま読める形）
- preview_*.png   確認用の点群レンダー（上・横・正面・斜め、脚上げ）

座標は機体座標のメートルで書く: x 右、y 上（車輪の下端が 0）、z 機首方向。
bbmodel は x 右・y 上・-z 機首・1 メートル 16 単位なので、model.scale は 1.0 で実寸になる。
"""
import base64
import io
import json
import math
import os
import uuid

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
U = 16.0
NS = uuid.UUID("5b1f4e0a-47b0-4e2d-9a47-000000000047")
_uid_count = [0]


def uid():
    _uid_count[0] += 1
    return str(uuid.uuid5(NS, str(_uid_count[0])))


def bbp(m):
    """機体座標（m）→ bbmodel 座標。"""
    return [m[0] * U, m[1] * U, -m[2] * U]


def rot_matrix(r):
    """bbmodel の回転（度）。点には X, Y, Z の順に掛かる（R = Rz·Ry·Rx）。"""
    rx, ry, rz = (math.radians(a) for a in r)
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    mx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    my = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    mz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return mz @ my @ mx


# ---------------------------------------------------------------------------------------------
# 模型の部品

class Group:
    def __init__(self, name, origin_m=(0, 0, 0), rot=(0, 0, 0), parent=None):
        self.name = name
        self.origin = bbp(origin_m)
        self.rot = list(rot)
        self.parent = parent
        self.children = []
        self.uuid = uid()
        if parent is not None:
            parent.children.append(self)


class Cube:
    def __init__(self, group, tag, frm, to, origin=None, rot=(0, 0, 0), plan=None, face_tags=None, name="cube"):
        self.group = group
        self.tag = tag
        self.frm = [min(a, b) for a, b in zip(frm, to)]
        self.to = [max(a, b) for a, b in zip(frm, to)]
        self.origin = origin if origin is not None else [(a + b) / 2 for a, b in zip(self.frm, self.to)]
        self.rot = list(rot)
        self.plan = plan
        self.face_tags = face_tags or {}
        self.name = name
        self.uuid = uid()
        self.uv = {}
        group.children.append(self)


def box(group, tag, x0, x1, y0, y1, z0, z1, **kw):
    """軸に沿った箱。メートル、z は機首方向。"""
    a = bbp((x0, y0, z0))
    b = bbp((x1, y1, z1))
    return Cube(group, tag, a, b, **kw)


def rbox(group, tag, centre_m, size_bb, rot, **kw):
    """自分の中心で回る箱。size は bbmodel 単位で回転前の x, y, z の寸法。"""
    c = bbp(centre_m)
    frm = [c[i] - size_bb[i] / 2 for i in range(3)]
    to = [c[i] + size_bb[i] / 2 for i in range(3)]
    return Cube(group, tag, frm, to, origin=c, rot=rot, **kw)


def section(group, tag, z0, z1, xc, w, yb, yt, chamfer=0.3, floor=None, wall=0.08, inner_tag="tub", plan=None):
    """胴体の輪切り 1 枚。2 つの箱を重ねて八角形に近づける。floor を渡すとその高さより上をくり抜いて
    側壁だけ残す（操縦席の浴槽）。"""
    h = yt - yb
    c = chamfer * min(w, h / 2)
    za, zb = max(z0, z1), min(z0, z1)
    first = len(group.children)
    if floor is None:
        if c < 0.02:
            box(group, tag, xc - w, xc + w, yb, yt, zb, za, plan=plan)
        else:
            box(group, tag, xc - w, xc + w, yb + c, yt - c, zb, za, plan=plan)
            box(group, tag, xc - w + c, xc + w - c, yb, yt, zb, za, plan=plan)
    else:
        # 側壁は面取りせず縁の高さまで立てる（ガラスの下端と隙間を作らない）
        box(group, tag, xc - w, xc + w, yb + c, floor, zb, za, plan=plan, face_tags={"up": inner_tag})
        box(group, tag, xc - w + c, xc + w - c, yb, floor, zb, za, plan=plan, face_tags={"up": inner_tag})
        box(group, tag, xc - w, xc - w + wall, floor, yt, zb, za, plan=plan, face_tags={"east": inner_tag})
        box(group, tag, xc + w - wall, xc + w, floor, yt, zb, za, plan=plan, face_tags={"west": inner_tag})
    for cube in group.children[first:]:
        cube.sec = (xc, w, yb, yt, c)


def loft(group, tag, table, z_from, z_to, step, xc=0.0, chamfer=0.3, floor_range=None, floor=None, plan=None):
    """表（z, 半幅, 下, 上）を z_from から z_to へ step 刻みで輪切りにする。"""
    t = np.array(table, dtype=float)
    order = np.argsort(t[:, 0])
    t = t[order]
    n = max(1, int(round(abs(z_from - z_to) / step)))
    zs = np.linspace(z_from, z_to, n + 1)
    for z0, z1 in zip(zs[:-1], zs[1:]):
        zm = (z0 + z1) / 2
        w = np.interp(zm, t[:, 0], t[:, 1])
        yb = np.interp(zm, t[:, 0], t[:, 2])
        yt = np.interp(zm, t[:, 0], t[:, 3])
        fl = None
        if floor_range is not None and floor_range[1] <= zm <= floor_range[0]:
            fl = floor
        section(group, tag, z0, z1, xc, w, yb, yt, chamfer=chamfer, floor=fl, plan=plan)


class Planform:
    """翼の平面形。s はスパン方向（水平翼は |x|、垂直翼は根からの高さ）、z は機首方向（m）。"""

    def __init__(self, axis, base, le, te, root, tip, t_root, t_tip, stations=(), hinges=()):
        self.axis = axis          # 'x' 水平、'y' 垂直
        self.base = base          # 'x' なら (0, y, 0) の y、'y' なら (x, y0, 0) の x と y0
        self.le = le              # ((s0, z0), (s1, z1))
        self.te = te
        self.root, self.tip = root, tip
        self.t_root, self.t_tip = t_root, t_tip
        self.stations = stations
        self.hinges = hinges      # (s0, s1, 後縁からの弦長)

    @staticmethod
    def _line(line, s):
        (s0, z0), (s1, z1) = line
        return z0 + (z1 - z0) * (s - s0) / (s1 - s0)

    def zle(self, s):
        return self._line(self.le, s)

    def zte(self, s):
        return self._line(self.te, s)

    def slope(self, line):
        (s0, z0), (s1, z1) = line
        return (z1 - z0) / (s1 - s0)

    def thick(self, s):
        f = (s - self.root) / (self.tip - self.root)
        return self.t_root + (self.t_tip - self.t_root) * f


def plate(group, tag, pf, s0, s1, sgn=1, te_cut=0.0, seg=0.7, le_cap=0.6, te_cap=0.45):
    """平面形の [s0, s1] を板にする。区間ごとに軸に沿った芯の箱と、前縁・後縁の線に沿って回した細い箱。
    te_cut は後縁から舵面ぶん削る弦長（m）。"""
    n = max(1, int(math.ceil((s1 - s0) / seg)))
    ss = np.linspace(s0, s1, n + 1)
    le_slope = pf.slope(pf.le)
    te_slope = pf.slope(pf.te)

    def zle(s):
        return pf.zle(s)

    def zte(s):
        return pf.zte(s) + te_cut

    for a, b in zip(ss[:-1], ss[1:]):
        t = pf.thick((a + b) / 2)
        z_front = min(zle(a), zle(b))
        z_back = max(zte(a), zte(b))
        if z_front - z_back < 0.02:
            continue
        _plate_box(group, tag, pf, sgn, a, b, z_back, z_front, t)
        for which, slope, zf, k in (("le", le_slope, zle, le_cap), ("te", te_slope, zte, te_cap)):
            za, zb = zf(a), zf(b)
            length = math.hypot(b - a, zb - za)
            ang = math.atan(slope)
            depth = abs(zb - za) * math.cos(ang) + 0.06
            # 線の中点から内側（前縁なら後ろ、後縁なら前）へ depth/2 ずらした点が箱の中心
            mid_s, mid_z = (a + b) / 2, (za + zb) / 2
            inward = -1 if which == "le" else 1
            ns, nz = -math.sin(ang), math.cos(ang)   # (s, z) 面で線に垂直、z が前
            if nz < 0:
                ns, nz = -ns, -nz
            cs = mid_s + inward * ns * depth / 2
            cz = mid_z + inward * nz * depth / 2
            _plate_cap(group, tag, pf, sgn, cs, cz, length, depth, t * k, ang)


def _plate_box(group, tag, pf, sgn, a, b, z_back, z_front, t):
    if pf.axis == "x":
        y = pf.base[1]
        x0, x1 = sorted((sgn * a, sgn * b))
        box(group, tag, x0, x1, y - t / 2, y + t / 2, z_back, z_front, plan=(pf, sgn))
    else:
        x, y0 = pf.base[0], pf.base[1]
        box(group, tag, x - t / 2, x + t / 2, y0 + a, y0 + b, z_back, z_front, plan=(pf, sgn))


def _plate_cap(group, tag, pf, sgn, cs, cz, length, depth, t, ang):
    """(s, z) 面で傾き ang（dz/ds の角）の線に沿った箱。"""
    if pf.axis == "x":
        y = pf.base[1]
        # bbmodel では機首が -z。外向き（+s）に z が前へ進む線は、bb の方向 (1, 0, -tan) になる。
        # Ry(θ) は x を (cosθ, 0, -sinθ) へ送るので θ = ang。左（sgn=-1）は鏡像で -θ。
        rbox(group, tag, (sgn * cs, y, cz), (length * U, t * U, depth * U), (0, sgn * math.degrees(ang), 0),
             plan=(pf, sgn))
    else:
        x, y0 = pf.base[0], pf.base[1]
        # 垂直翼: s は +y。bb の方向 (0, 1, -tan)。Rx(φ) は y を (0, cosφ, sinφ) へ送るので φ = -ang。
        rbox(group, tag, (x, y0 + cs, cz), (t * U, length * U, depth * U), (-math.degrees(ang), 0, 0),
             plan=(pf, sgn))


def hinge_surface(parent, name, tag, pf, sgn, a, b, chord, pivot_y, t):
    """後縁の舵面。ボーンの X 軸を蝶番の線に合わせる（Ry で振る）ので、AircraftModel の rotateX が
    蝶番回りの回転になる。中身はボーンの回転前の座標で、蝶番の内端から外へ x、後ろへ +z。"""
    za = pf.zte(a) + chord
    zb = pf.zte(b) + chord
    ang = math.atan((zb - za) / (b - a))          # 外へ行くと前へ出る角（前進翼の後縁は正）
    g = Group(name, (sgn * a, pivot_y, za), (0, sgn * math.degrees(ang), 0), parent)
    length = math.hypot(b - a, zb - za) * U
    depth = chord * math.cos(ang) * U
    p = g.origin
    x0, x1 = (p[0], p[0] + length) if sgn > 0 else (p[0] - length, p[0])
    Cube(g, tag, [x0, p[1] - t * U / 2, p[2]], [x1, p[1] + t * U / 2, p[2] + depth], plan=("surface", sgn))
    return g


# ---------------------------------------------------------------------------------------------
# 機体

root = Group("AllBody")
body = root

# 胴体の芯（機首からテイルコーンまで）。z, 半幅, 下, 上
FUSE = [
    (11.30, 0.04, 2.56, 2.64), (11.05, 0.16, 2.44, 2.78), (10.70, 0.27, 2.32, 2.90), (10.30, 0.36, 2.22, 3.00),
    (9.80, 0.45, 2.13, 3.08), (9.20, 0.53, 2.05, 3.15), (8.60, 0.59, 1.98, 3.18), (8.00, 0.63, 1.92, 3.18),
    (7.30, 0.66, 1.87, 3.10), (6.00, 0.68, 1.83, 3.07), (4.40, 0.70, 1.80, 3.05), (2.00, 0.70, 1.80, 3.02),
    (-1.0, 0.66, 1.82, 2.98), (-3.0, 0.60, 1.88, 2.95), (-6.0, 0.56, 1.95, 2.92), (-8.8, 0.50, 2.05, 2.88),
    (-9.8, 0.40, 2.15, 2.80), (-10.6, 0.32, 2.25, 2.72), (-11.30, 0.22, 2.33, 2.62),
]
COCKPIT = (7.25, 4.35)   # くり抜く z の範囲（前, 後）
TUB_FLOOR = 2.50

loft(body, "body", FUSE, 11.30, 8.00, 0.25, chamfer=0.32)
loft(body, "body", FUSE, 8.00, 7.25, 0.25, chamfer=0.32)
loft(body, "body", FUSE, 7.25, 4.35, 0.29, chamfer=0.32, floor_range=COCKPIT, floor=TUB_FLOOR)
loft(body, "body", FUSE, 4.35, -9.00, 0.5, chamfer=0.32)
loft(body, "tailcone", FUSE, -9.00, -11.30, 0.33, chamfer=0.35)

# 背の盛り上がり（キャノピーの後ろから尾へ）
SPINE = [(4.35, 0.52, 2.95, 3.78), (3.0, 0.50, 2.95, 3.56), (1.0, 0.46, 2.93, 3.30), (-2.0, 0.42, 2.90, 3.12),
         (-4.6, 0.36, 2.88, 2.98)]
loft(body, "spine", SPINE, 4.35, -4.6, 0.45, chamfer=0.45)

# 空気取り入れ口とエンジンナセル
NACELLE = [(4.60, 0.58, 1.36, 2.60), (3.50, 0.58, 1.32, 2.62), (0.00, 0.58, 1.30, 2.62), (-5.0, 0.58, 1.32, 2.60),
           (-7.4, 0.55, 1.38, 2.56), (-8.4, 0.50, 1.46, 2.48)]
NAC_X = 1.32
for sgn in (-1, 1):
    first = len(body.children)
    loft(body, "nacelle", NACELLE, 4.60, -8.40, 0.5, xc=sgn * NAC_X, chamfer=0.3)
    for c in body.children[first:first + 2]:
        c.face_tags["north"] = "intake"
    # ノズル
    for z0, z1, w in ((-8.40, -8.95, 0.48), (-8.95, -9.50, 0.46)):
        section(body, "nozzle", z0, z1, sgn * NAC_X, w, 1.97 - w, 1.97 + w, chamfer=0.38)
    body.children[-1].face_tags["south"] = "nozzle_end"
    body.children[-2].face_tags["south"] = "nozzle_end"

# 胴体とナセルの隙間を塞ぐ芯、その上の平たい甲板（翼の付け根を受ける）。甲板の前縁は後退させて
# 取り入れ口の上を空け、カナードの付け根から主翼の前縁へ流す
box(body, "body", -0.80, 0.80, 1.86, 2.55, -8.6, 4.3)
DECK = Planform("x", (0, 2.585, 0), le=((0.0, 4.3), (1.95, 2.3)), te=((0.0, -7.6), (1.95, -7.6)),
                root=0.0, tip=1.95, t_root=0.27, t_tip=0.27)
for sgn in (-1, 1):
    plate(body, "deck", DECK, 0.0, 1.95, sgn, seg=0.5, le_cap=0.7, te_cap=0.8)

# 尾翼を載せるブーム
BOOM = [(-3.0, 0.10, 2.36, 2.62), (-3.8, 0.22, 2.20, 2.74), (-9.4, 0.22, 2.20, 2.74), (-9.9, 0.15, 2.28, 2.68)]
for sgn in (-1, 1):
    loft(body, "boom", BOOM, -3.0, -9.9, 0.6, xc=sgn * 2.45, chamfer=0.35)

# ---- 主翼（前進翼）
WING_Y = 2.42
WING = Planform("x", (0, WING_Y, 0), le=((2.2, 1.25), (8.35, 3.35)), te=((2.2, -4.40), (8.35, 1.55)),
                root=1.85, tip=8.35, t_root=0.30, t_tip=0.10, stations=(3.3, 4.6, 6.0, 7.2),
                hinges=((2.25, 4.6, 0.75), (4.6, 7.95, 0.60)))
FLAP = (2.25, 4.6, 0.75)
AIL = (4.6, 7.95, 0.60)
for sgn in (-1, 1):
    plate(body, "wing", WING, 1.85, FLAP[0], sgn)
    plate(body, "wing", WING, FLAP[0], FLAP[1], sgn, te_cut=FLAP[2])
    plate(body, "wing", WING, AIL[0], AIL[1], sgn, te_cut=AIL[2])
    plate(body, "wing", WING, AIL[1], 8.35, sgn)

# 主翼の付け根の前縁延長（カナードの後ろから主翼の前縁へ）
LERX = Planform("x", (0, 2.50, 0), le=((1.80, 2.45), (2.40, 1.14)), te=((1.80, -1.0), (2.40, -1.0)),
                root=1.80, tip=2.40, t_root=0.22, t_tip=0.18)
for sgn in (-1, 1):
    plate(body, "wing", LERX, 1.80, 2.40, sgn, seg=0.3)

# ---- カナード（固定。動かす役割が無いので胴体の一部）
CANARD = Planform("x", (0, 2.36, 0), le=((1.85, 4.55), (3.85, 2.55)), te=((1.85, 3.15), (3.85, 2.20)),
                  root=1.85, tip=3.85, t_root=0.12, t_tip=0.05, stations=(2.9,))
for sgn, name in ((-1, "LeftCanard"), (1, "RightCanard")):
    g = Group(name, (sgn * 1.85, 2.36, 3.6), parent=root)
    plate(g, "canard", CANARD, 1.85, 3.85, sgn, seg=0.5)

# ---- 舵面
FLAP_T = 0.12
AIL_T = 0.08
surfaces = {}
for sgn, side in ((-1, "Left"), (1, "Right")):
    surfaces[side + "Flap"] = hinge_surface(root, side + "Flap", "surface", WING, sgn, FLAP[0], FLAP[1], FLAP[2],
                                            WING_Y, FLAP_T)
    surfaces[side + "Aileron"] = hinge_surface(root, side + "Aileron", "surface", WING, sgn, AIL[0], AIL[1], AIL[2],
                                               WING_Y, AIL_T)

# ---- 水平尾翼（全遊動。ボーンごと回る）
STAB_Y = 2.45
STAB = Planform("x", (0, STAB_Y, 0), le=((2.60, -6.70), (4.85, -8.35)), te=((2.60, -9.75), (4.85, -9.80)),
                root=2.60, tip=4.85, t_root=0.16, t_tip=0.06, stations=(3.7,))
for sgn, name in ((-1, "LeftStabilizer"), (1, "RightStabilizer")):
    g = Group(name, (sgn * 2.60, STAB_Y, -8.0), parent=root)
    plate(g, "tail", STAB, 2.60, 4.85, sgn, seg=0.6)

# ---- 垂直尾翼（外へ傾く）。中身は傾ける前の垂直な板で書き、グループの Rz で傾ける
FIN_X, FIN_Y = 2.45, 2.74
FIN_CANT = 4.0
FIN_TE = -8.65
RUDDER = (0.35, 3.30, 0.75)
for sgn, side in ((-1, "Left"), (1, "Right")):
    fin = Planform("y", (sgn * FIN_X, FIN_Y, 0), le=((0.0, -4.60), (3.66, -7.30)), te=((0.0, FIN_TE), (3.66, FIN_TE)),
                   root=0.0, tip=3.66, t_root=0.16, t_tip=0.07, stations=(1.25, 2.5),
                   hinges=((RUDDER[0], RUDDER[1], RUDDER[2]),))
    # 左の板（x<0）の先端を外（-x）へ倒すのは Rz の正
    g = Group(side + "Fin", (sgn * FIN_X, FIN_Y, -6.6), (0, 0, -sgn * FIN_CANT), parent=root)
    plate(g, "fin", fin, 0.0, RUDDER[0], sgn, seg=0.4)
    plate(g, "fin", fin, RUDDER[0], RUDDER[1], sgn, te_cut=RUDDER[2], seg=0.6)
    plate(g, "fin", fin, RUDDER[1], 3.66, sgn, seg=0.4)
    r = Group(side + "Rudder", (sgn * FIN_X, FIN_Y + RUDDER[0], FIN_TE + RUDDER[2]), parent=g)
    t = 0.07
    box(r, "surface", sgn * FIN_X - t / 2, sgn * FIN_X + t / 2, FIN_Y + RUDDER[0], FIN_Y + RUDDER[1],
        FIN_TE, FIN_TE + RUDDER[2], plan=("surface", sgn))
    surfaces[side + "Rudder"] = r

# ---- キャノピー（ガラスは抜き、枠と映り込みだけ描く）
canopy = Group("CockpitHatch", (0, 3.05, 4.4), parent=root)
GLASS = [(7.30, 0.56, 3.02, 3.06), (7.00, 0.58, 3.02, 3.30), (6.70, 0.60, 3.03, 3.55), (6.40, 0.61, 3.04, 3.76),
         (6.20, 0.62, 3.05, 3.86), (5.60, 0.62, 3.05, 3.93), (5.00, 0.61, 3.05, 3.93), (4.35, 0.58, 3.05, 3.82)]
loft(canopy, "glass", GLASS, 7.30, 6.20, 0.11, chamfer=0.5)
loft(canopy, "glass", GLASS, 6.20, 4.40, 0.30, chamfer=0.5)
# 風防と天蓋の継ぎ目の枠 1 本だけ（後ろは背の盛り上がりが塞ぐ）。上の梁と左右の柱
zc = 6.20
w = np.interp(zc, [g[0] for g in GLASS[::-1]], [g[1] for g in GLASS[::-1]]) + 0.02
yt = np.interp(zc, [g[0] for g in GLASS[::-1]], [g[3] for g in GLASS[::-1]]) + 0.02
box(canopy, "frame", -w + 0.2, w - 0.2, yt - 0.05, yt, zc - 0.025, zc + 0.025)
for s in (-1, 1):
    box(canopy, "frame", s * w - 0.025, s * w + 0.025, 3.05, yt - 0.18, zc - 0.025, zc + 0.025)
    rbox(canopy, "frame", (s * (w - 0.1), yt - 0.1, zc), (0.05 * U, 0.26 * U, 0.05 * U), (0, 0, -s * 45))
for s in (-1, 1):
    box(canopy, "frame", s * 0.60 - 0.03, s * 0.60 + 0.03, 3.02, 3.08, 4.38, 7.28)

# ---- 操縦席の中
cockpit = Group("Cockpit", (0, 2.5, 5.5), parent=root)
box(cockpit, "panel", -0.50, 0.50, TUB_FLOOR, 3.16, 6.10, 6.26)
box(cockpit, "glare", -0.46, 0.46, 3.12, 3.21, 6.04, 6.48)
for s in (-1, 1):
    box(cockpit, "console", s * 0.62, s * 0.40, TUB_FLOOR, 2.82, 4.65, 6.10)
box(cockpit, "hud", -0.13, 0.13, 3.21, 3.42, 6.12, 6.15)
box(cockpit, "seat", -0.25, 0.25, TUB_FLOOR, 2.78, 4.80, 5.36)
box(cockpit, "seat", -0.27, 0.27, 2.78, 3.74, 4.58, 4.80)
box(cockpit, "headrest", -0.19, 0.19, 3.74, 3.96, 4.54, 4.86)
box(cockpit, "handle", -0.07, 0.07, 2.78, 2.86, 5.24, 5.36)
stick = Group("stick", (0.50, 2.82, 5.55), parent=cockpit)
box(stick, "black", 0.48, 0.52, 2.82, 2.98, 5.53, 5.57)
throttle = Group("throttle", (-0.50, 2.82, 5.60), parent=cockpit)
box(throttle, "black", -0.53, -0.47, 2.82, 2.95, 5.55, 5.65)

# ---- 降着装置。脚は出た姿勢、扉は開いた姿勢で作る（gear_up が畳む）


def wheel(group, x, y, z, r, width):
    for k in range(4):
        rbox(group, "wheel", (x, y, z), (width * U, 2 * r * U, 0.82 * r * U), (45 * k, 0, 0))
    rbox(group, "hub", (x, y, z), ((width + 0.02) * U, 0.9 * r * U, 0.9 * r * U), (0, 0, 0))


NOSE_Z, NOSE_R, NOSE_PIVOT = 6.0, 0.33, 2.10
fg = Group("FGear", (0, NOSE_PIVOT, NOSE_Z), parent=root)
box(fg, "strut", -0.07, 0.07, 0.62, NOSE_PIVOT, NOSE_Z - 0.07, NOSE_Z + 0.07)
box(fg, "strut", -0.15, 0.15, 0.62, 0.72, NOSE_Z - 0.06, NOSE_Z + 0.06)
for s in (-1, 1):
    box(fg, "strut", s * 0.15 - 0.03, s * 0.15 + 0.03, NOSE_R - 0.04, 0.70, NOSE_Z - 0.05, NOSE_Z + 0.05)
box(fg, "strut", -0.05, 0.05, 1.05, 1.35, NOSE_Z + 0.07, NOSE_Z + 0.16)
fw = Group("FWheel", (0, NOSE_R, NOSE_Z), parent=fg)
wheel(fw, 0, NOSE_R, NOSE_Z, NOSE_R, 0.20)

MAIN_X, MAIN_Z, MAIN_R, MAIN_PIVOT = 1.62, -1.6, 0.50, 1.95
for sgn, side in ((-1, "L"), (1, "R")):
    g = Group(side + "Gear", (sgn * MAIN_X, MAIN_PIVOT, MAIN_Z), parent=root)
    x = sgn * MAIN_X
    box(g, "strut", x - 0.08, x + 0.08, MAIN_R + 0.18, MAIN_PIVOT, MAIN_Z - 0.08, MAIN_Z + 0.08)
    box(g, "strut", x - 0.05, x + 0.05, MAIN_R + 0.10, MAIN_R + 0.62, MAIN_Z + 0.08, MAIN_Z + 0.20)
    box(g, "strut", x - 0.17, x + 0.17, MAIN_R - 0.05, MAIN_R + 0.05, MAIN_Z - 0.05, MAIN_Z + 0.05)
    w = Group(side + "Wheel", (x, MAIN_R, MAIN_Z), parent=g)
    wheel(w, x, MAIN_R, MAIN_Z, MAIN_R, 0.30)

# 扉。前脚は脚室の左右に 2 枚（外の縁が蝶番）、主脚はナセルの下に 1 枚（内の縁が蝶番）
NOSE_BAY = (6.35, 3.85)
NOSE_DOOR_W = 0.30
doors = {}
for sgn, side in ((-1, "L"), (1, "R")):
    hx = sgn * NOSE_DOOR_W
    hy = 1.83
    g = Group("FGearDoor" + side, (hx, hy, (NOSE_BAY[0] + NOSE_BAY[1]) / 2), parent=root)
    box(g, "door", hx - sgn * 0.0 - 0.015, hx + 0.015, hy - NOSE_DOOR_W, hy, NOSE_BAY[1], NOSE_BAY[0],
        face_tags={"east" if sgn < 0 else "west": "bay"})
    doors["FGearDoor" + side] = (g, sgn * 90.0)

MAIN_BAY = (0.45, -1.85)
MAIN_DOOR_W = 0.80
for sgn, side in ((-1, "L"), (1, "R")):
    hx = sgn * (NAC_X - 0.39)
    hy = 1.31
    g = Group(side + "GearDoor", (hx, hy, (MAIN_BAY[0] + MAIN_BAY[1]) / 2), parent=root)
    box(g, "door", hx - 0.015, hx + 0.015, hy - MAIN_DOOR_W, hy, MAIN_BAY[1], MAIN_BAY[0],
        face_tags={"west" if sgn < 0 else "east": "bay"})
    # 内の縁が蝶番で外へ倒して閉じる。下端 (0,-1) を Rz(θ) で回すと x は sinθ なので、右は +90、左は -90。
    # z の符号はアニメーションでも同じ
    doors[side + "GearDoor"] = (g, sgn * 90.0)

# 脚の畳み方（アニメーションの値。x と y は bbmodel の回転と符号が逆、z は同じ）
GEAR_UP = {
    "FGear": (90.0, 0.0, 0.0),     # 前脚は後ろへ
    "LGear": (-90.0, 0.0, 0.0),    # 主脚は前へ（Su-27 と同じ）
    "RGear": (-90.0, 0.0, 0.0),
}
for name, (g, a) in doors.items():
    GEAR_UP[name] = (0.0, 0.0, a)
# 前脚の扉: 左の扉（蝶番 x<0）の下端を +x へ。bb の Rz 正 = アニメーションの z 正
GEAR_UP["FGearDoorL"] = (0.0, 0.0, 90.0)
GEAR_UP["FGearDoorR"] = (0.0, 0.0, -90.0)
DOOR_NAMES = [n for n in GEAR_UP if "Door" in n]
LEG_NAMES = ["FGear", "LGear", "RGear"]

# ---------------------------------------------------------------------------------------------
# 変換と面

FACES = ("north", "east", "south", "west", "up", "down")
NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0),
           "down": (0, -1, 0)}
INTERIOR = {"tub", "panel", "glare", "console", "hud", "seat", "headrest", "handle", "black"}


def all_cubes(g=root):
    for c in g.children:
        if isinstance(c, Cube):
            yield c
        else:
            yield from all_cubes(c)


def all_groups(g=root):
    yield g
    for c in g.children:
        if isinstance(c, Group):
            yield from all_groups(c)


parents = {}
for g in all_groups():
    for c in g.children:
        parents[id(c)] = g


def face_tag(c, f):
    return c.face_tags.get(f, c.tag)


def face_density(c, f):
    t = face_tag(c, f)
    if t in INTERIOR:
        return 4.0
    if t in ("frame", "strut", "hub"):
        return 2.0
    return 1.0


def face_size(c, f):
    d = face_density(c, f)
    sx, sy, sz = (c.to[i] - c.frm[i] for i in range(3))
    if f in ("north", "south"):
        a, b = sx, sy
    elif f in ("east", "west"):
        a, b = sz, sy
    else:
        a, b = sx, sz
    return max(1, int(round(a * d))), max(1, int(round(b * d)))


def face_points(c, f, S, T):
    """面上の点（回転前のキューブ座標、bb）。S は u 方向、T は v 方向（上から下）で 0..1。"""
    (fx, fy, fz), (tx, ty, tz) = c.frm, c.to
    one = np.ones_like(S)
    if f == "north":
        return np.stack([tx + (fx - tx) * S, ty + (fy - ty) * T, fz * one], -1)
    if f == "south":
        return np.stack([fx + (tx - fx) * S, ty + (fy - ty) * T, tz * one], -1)
    if f == "east":
        return np.stack([tx * one, ty + (fy - ty) * T, tz + (fz - tz) * S], -1)
    if f == "west":
        return np.stack([fx * one, ty + (fy - ty) * T, fz + (tz - fz) * S], -1)
    if f == "up":
        return np.stack([fx + (tx - fx) * S, ty * one, fz + (tz - fz) * T], -1)
    return np.stack([fx + (tx - fx) * S, fy * one, tz + (fz - tz) * T], -1)


def cube_transform(c, pts, normal, pose=None, groups=True):
    R = rot_matrix(c.rot)
    o = np.array(c.origin)
    p = (pts - o) @ R.T + o
    n = R @ np.array(normal, dtype=float)
    if groups:
        g = parents[id(c)]
        while g is not None:
            r = list(g.rot)
            if pose and g.name in pose:
                a = pose[g.name]
                r = [r[0] - a[0], r[1] - a[1], r[2] + a[2]]
            if any(abs(v) > 1e-9 for v in r):
                Rg = rot_matrix(r)
                og = np.array(g.origin)
                p = (p - og) @ Rg.T + og
                n = Rg @ n
            g = g.parent
    return p, n


def to_aircraft(p):
    return np.stack([p[..., 0] / U, p[..., 1] / U, -p[..., 2] / U], -1)


# ---------------------------------------------------------------------------------------------
# UV の割り付け

rects = []
for c in all_cubes():
    for f in FACES:
        w, h = face_size(c, f)
        rects.append((h, w, c, f))
rects.sort(key=lambda r: (-r[0], -r[1]))


def pack(width):
    x = y = shelf = 0
    out = []
    for h, w, c, f in rects:
        if x + w > width:
            x, y, shelf = 0, y + shelf, 0
        out.append((c, f, x, y, w, h))
        x += w
        shelf = max(shelf, h)
    return out, y + shelf


for TEX in (1024, 2048):
    placed, used = pack(TEX)
    if used <= TEX:
        break
print(f"cubes {sum(1 for _ in all_cubes())}, faces {len(rects)}, sheet {TEX}x{TEX}, used height {used}")
for c, f, x, y, w, h in placed:
    c.uv[f] = (x, y, w, h)

# ---------------------------------------------------------------------------------------------
# 塗り

TOP = np.array([58, 64, 72])
BOTTOM = np.array([84, 92, 101])
RADOME = np.array([188, 190, 182])
ANTIGLARE = np.array([40, 44, 50])
NOZZLE_A = np.array([66, 66, 70])
NOZZLE_B = np.array([104, 102, 100])
BLACK = np.array([18, 18, 20])
LIP = np.array([150, 156, 162])
BAY = np.array([126, 140, 104])
STRUT = np.array([196, 198, 190])
TYRE = np.array([30, 30, 32])
HUB = np.array([150, 150, 146])
FRAME = np.array([46, 51, 58])
FRAME_EDGE = np.array([150, 196, 214])
STREAK = np.array([182, 222, 238])
TUB = np.array([72, 104, 168])
TUB_RIM = np.array([118, 150, 206])
PANEL = np.array([96, 100, 104])
MFD = np.array([20, 32, 72])
CYAN = np.array([80, 220, 230])
CREAM = np.array([226, 220, 188])
SEAT = np.array([86, 96, 70])
RED = np.array([196, 34, 34])
WHITE = np.array([236, 236, 232])
BLUE = np.array([38, 70, 160])
WALK = np.array([170, 44, 40])

T = 1.0 / 16.0   # 1 テクセル（外板）

FUSE_RINGS = (8.60, 7.30, 4.40, 2.60, 0.80, -1.20, -3.20, -5.20, -7.00, -8.40)
NAC_RINGS = (3.50, 1.40, -0.60, -2.60, -4.60, -6.60, -7.80)

FONT = {
    "0": ["111", "101", "101", "101", "111"],
    "1": ["010", "110", "010", "010", "111"],
}


def text_mask(text, tx, ty, dot=2):
    """3×5 のドット文字。tx, ty はテクセル（文字列の左上が 0）。白、縁取り、外の 3 値。"""
    inside = np.zeros(tx.shape, bool)
    for k, ch in enumerate(text):
        rows = FONT[ch]
        cx = tx - k * (3 * dot + 2)
        for r, row in enumerate(rows):
            for q, bit in enumerate(row):
                if bit == "1":
                    inside |= (cx >= q * dot) & (cx < (q + 1) * dot) & (ty >= r * dot) & (ty < (r + 1) * dot)
    return inside


def star_inside(u, v, r):
    """五芒星（上向き）。u 右、v 上（m）。"""
    ang = np.arctan2(u, v)
    rad = np.hypot(u, v)
    # 72 度ごとに畳み、頂点 (r, 0) と内の頂点 (inner, 36 度) を結ぶ辺の原点側を内とする
    k = np.abs((ang % (2 * math.pi / 5)) - math.pi / 5)
    k = math.pi / 5 - k
    inner = r * 0.382
    x1, y1 = r, 0.0
    x2, y2 = inner * math.cos(math.pi / 5), inner * math.sin(math.pi / 5)
    px = rad * np.cos(k)
    py = rad * np.sin(k)
    cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
    return cross >= 0


def shade(col, f):
    return np.clip(col * f, 0, 255)


def paint_face(c, f, w, h):
    tag = face_tag(c, f)
    I, J = np.meshgrid(np.arange(w), np.arange(h))
    S = (I + 0.5) / w
    Tt = (J + 0.5) / h
    pts = face_points(c, f, S, Tt)
    p, n = cube_transform(c, pts, NORMALS[f], groups=False)
    A = to_aircraft(p)
    x, y, z = A[..., 0], A[..., 1], A[..., 2]
    nx, ny, nz = n[0], n[1], -n[2]
    rgb = np.zeros((h, w, 3))
    alpha = np.full((h, w), 255, np.uint8)

    edge_px = (I == 0) | (J == 0) | (I == w - 1) | (J == h - 1)

    if tag == "glass":
        alpha[:] = 0
        s = z + 0.6 * y
        streak = (np.abs(s - 8.20) < 0.035) | (np.abs(s - 8.42) < 0.02) | (np.abs(s - 6.95) < 0.03) | \
                 (np.abs(s - 7.12) < 0.015)
        if f in ("north", "south"):
            streak[:] = False
        # 輪郭は天蓋（風防の枠より後ろ）の側面の上の縁 1 テクセルだけ。上面の縁や風防の段まで塗ると梯子に見えた
        if f in ("east", "west") and z.mean() < 6.2:
            streak |= J == 0
        rgb[:] = STREAK
        alpha[streak] = 255
        return rgb, alpha

    if tag == "frame":
        rgb[:] = FRAME
        return rgb, alpha

    if tag in ("tub",):
        rgb[:] = TUB
        if ny > 0.5:
            rgb[:] = TUB if y.mean() < TUB_FLOOR + 0.02 else TUB_RIM
        rgb[edge_px] = TUB_RIM
        return rgb, alpha

    if tag == "panel":
        rgb[:] = PANEL
        if nz < -0.5:   # 操縦者の方を向く面（機体の後ろ向き）
            for xc in (-0.26, 0.26):
                m = (np.abs(x - xc) < 0.12) & (y > 2.64) & (y < 2.90)
                rgb[m] = MFD
                cross = m & ((np.abs(x - xc) < 0.008) | (np.abs(y - 2.77) < 0.008))
                rgb[cross] = CYAN
                ring = (np.abs(x - xc) < 0.15) & (y > 2.61) & (y < 2.93) & ~m
                dots = ring & (((I // 3) + (J // 3)) % 2 == 0)
                rgb[dots] = np.where(((I[dots] // 3) % 3 == 0)[:, None], CREAM, np.array([150, 200, 210]))
            m = (np.abs(x) < 0.08) & (y > 2.92) & (y < 3.08)
            rgb[m] = np.array([24, 26, 30])
            rgb[(np.abs(x) < 0.08) & (np.abs(y - 3.0) < 0.006)] = np.array([90, 200, 90])
        rgb[edge_px] = shade(PANEL, 1.25)
        return rgb, alpha

    if tag == "glare":
        rgb[:] = np.array([26, 26, 30])
        return rgb, alpha
    if tag == "hud":
        rgb[:] = np.array([40, 44, 50])
        inner = (I > 1) & (I < w - 2) & (J > 1)
        alpha[inner] = 0
        return rgb, alpha
    if tag == "console":
        rgb[:] = np.array([88, 93, 98])
        if ny > 0.5:
            btn = ((I % 6) < 2) & ((J % 6) < 2)
            kind = ((I // 6) * 7 + (J // 6) * 3) % 9
            col = np.where((kind == 0)[..., None], np.array([214, 130, 130]),
                           np.where((kind == 4)[..., None], np.array([140, 200, 140]), np.array([170, 172, 176])))
            rgb[btn] = col[btn]
        rgb[edge_px] = np.array([120, 126, 132])
        return rgb, alpha
    if tag == "seat":
        rgb[:] = SEAT
        if ny > 0.5:
            chk = ((I // 4) + (J // 4)) % 2 == 0
            rgb[chk] = np.array([120, 122, 118])
        rgb[edge_px] = shade(SEAT, 0.7)
        return rgb, alpha
    if tag == "headrest":
        rgb[:] = np.array([40, 42, 44])
        return rgb, alpha
    if tag == "handle":
        stripe = ((I + J) // 3) % 2 == 0
        rgb[:] = np.array([20, 20, 20])
        rgb[stripe] = np.array([230, 196, 40])
        return rgb, alpha
    if tag == "black":
        rgb[:] = np.array([22, 22, 24])
        return rgb, alpha

    if tag in ("strut", "hub"):
        rgb[:] = STRUT if tag == "strut" else HUB
        rgb[edge_px] = shade(rgb[edge_px], 0.8)
        return rgb, alpha
    if tag == "wheel":
        rgb[:] = TYRE
        return rgb, alpha
    if tag == "bay":
        rgb[:] = BAY
        rgb[edge_px] = shade(BAY, 0.75)
        return rgb, alpha

    if tag == "intake":
        # 取り入れ口の正面。輪切りの外形を 2 テクセル縮めた内側が暗く、縁は金属のリップ。重なる 2 つの箱が
        # 同じ点で同じ色になるよう、箱の縁ではなく輪切りの形から決める
        xc, sw, yb, yt, cc = c.sec
        dx = np.abs(x - xc)
        ym, hh = (yb + yt) / 2, (yt - yb) / 2
        dy = np.abs(y - ym)
        inner = ((dx < sw - 2 * T) & (dy < hh - cc - 2 * T)) | ((dx < sw - cc - 2 * T) & (dy < hh - 2 * T))
        rgb[:] = LIP
        rgb[inner] = BLACK
        return rgb, alpha
    if tag == "nozzle_end":
        cy = 1.97
        cx = math.copysign(NAC_X, x.mean())
        rr = np.hypot(x - cx, y - cy)
        rgb[:] = BLACK
        rgb[(rr > 0.36)] = NOZZLE_B
        rgb[(rr > 0.24) & (rr < 0.30)] = np.array([60, 40, 34])
        return rgb, alpha
    if tag == "nozzle":
        cy = 1.97
        cx = math.copysign(NAC_X, x.mean())
        ang = np.arctan2(y - cy, x - cx)
        sector = np.floor((ang + math.pi) / (2 * math.pi) * 16).astype(int)
        rgb[:] = np.where((sector % 2 == 0)[..., None], NOZZLE_A, NOZZLE_B)
        gap = np.abs(((ang + math.pi) / (2 * math.pi) * 16) % 1.0 - 0.0) < 0.06
        rgb[gap] = np.array([40, 40, 42])
        ring = np.abs(z + 8.95) < T * 0.6
        rgb[ring] = np.array([36, 36, 38])
        return rgb, alpha

    # ---------------- 外板
    if ny > 0.3:
        base = np.broadcast_to(TOP, (h, w, 3)).copy()
    elif ny < -0.3:
        base = np.broadcast_to(BOTTOM, (h, w, 3)).copy()
    else:
        upper = y > 2.36
        base = np.where(upper[..., None], TOP, BOTTOM).astype(float)
    if tag == "door":
        base[:] = BOTTOM
    rgb[:] = base
    dark = np.zeros((h, w), bool)
    light = np.zeros((h, w), bool)
    rivet = np.zeros((h, w), bool)

    if tag in ("body", "tailcone", "spine", "deck", "nacelle", "boom", "door"):
        rings = NAC_RINGS if tag == "nacelle" else FUSE_RINGS
        if abs(nz) < 0.7:
            for zl in rings:
                d = z - zl
                dark |= np.abs(d) < T * 0.5
                light |= (d < -T * 0.5) & (d > -T * 1.5)
                rivet |= (d < -T * 2.5) & (d > -T * 3.5) & ((I + J) % 3 == 0)
        if abs(nx) > 0.5 and tag in ("body", "nacelle"):
            for yl in (2.18, 2.78) if tag == "body" else (1.70, 2.30):
                d = y - yl
                dark |= np.abs(d) < T * 0.5
                light |= (d > T * 0.5) & (d < T * 1.5)
        if ny > 0.5 and tag in ("body", "spine"):
            for xl in (-0.36, 0.36):
                dark |= np.abs(x - xl) < T * 0.5
        if tag == "deck" and ny > 0.5:
            # 赤い破線の歩行帯
            for xl in (-1.55, 1.55):
                walk = (np.abs(x - xl) < T * 0.6) & (z < 2.3) & (z > -2.2) & (np.floor(z * 16) % 5 < 3)
                rgb[walk] = WALK
                corner = (np.abs(np.abs(x - xl) - np.abs(z - 2.3)) < T * 0.6) & (np.abs(x - xl) < T * 2.5)
                rgb[corner] = WALK
            for xl in (-1.15, 1.15):
                dark |= np.abs(x - xl) < T * 0.5
        if tag == "nacelle" and abs(nx) > 0.5:
            # 点検口: 枠と四隅のネジ
            for (za, zb, ya, yb) in ((-1.0, -2.1, 1.82, 2.22), (-3.7, -4.5, 1.62, 2.14), (-5.6, -6.3, 1.80, 2.26)):
                inside = (z < za) & (z > zb) & (y > ya) & (y < yb)
                frame = inside & ((z > za - T) | (z < zb + T) | (y < ya + T) | (y > yb - T))
                dark |= frame
                for zz in (za - 2 * T, zb + 2 * T):
                    for yy in (ya + 2 * T, yb - 2 * T):
                        rivet |= (np.abs(z - zz) < T * 0.5) & (np.abs(y - yy) < T * 0.5)
            # 縞の通気口
            vent = (z < 0.6) & (z > 0.2) & (y > 2.36) & (y < 2.5) & (np.floor(z * 16) % 2 == 0)
            rgb[vent] = np.array([30, 32, 36])
        if tag in ("body",) and z.max() > 8.6 - 1e-6:
            r = z > 8.6
            rgb[r] = RADOME
            dark &= ~r | (np.abs(z - 8.6) < T * 0.5)
            light &= ~r
            rivet &= ~r
        if tag == "body" and ny > 0.3:
            ag = (z < 8.6) & (z > 7.25) & (np.abs(x) < 0.42)
            rgb[ag] = ANTIGLARE
    elif tag in ("wing", "canard", "tail", "fin") and c.plan is not None:
        pf, sgn = c.plan
        if pf.axis == "x":
            s, thick_axis = np.abs(x), ny
        else:
            s, thick_axis = y - pf.base[1], nx
        if abs(thick_axis) > 0.5:
            zle, zte = pf.zle(s), pf.zte(s)
            cle = math.cos(math.atan(pf.slope(pf.le)))
            cte = math.cos(math.atan(pf.slope(pf.te)))
            dle = (zle - z) * cle
            dte = (z - zte) * cte
            light |= dle < T
            dark |= (dle >= 3 * T) & (dle < 4 * T)
            light |= dte < T
            light |= (pf.tip - s) < T
            chord = zle - zte
            spar = z - (zte + 0.45 * chord)
            dark |= np.abs(spar) < T * 0.5
            light |= (spar > T * 0.5) & (spar < T * 1.5)
            for st in pf.stations:
                d = s - st
                dark |= (np.abs(d) < T * 0.5) & (dle > 4 * T)
                light |= (d > T * 0.5) & (d < T * 1.5) & (dle > 4 * T)
            for (h0, h1, cc) in pf.hinges:
                m = (s >= h0) & (s <= h1)
                dark |= m & (np.abs(z - (zte + cc)) < T * 0.6)
            if tag == "wing" and ny > 0.5:
                # 翼根の歩行帯の角と、翼の上の「×」
                xmark = (np.abs(np.abs(s - 3.0) - np.abs(z + 0.6)) < T * 0.6) & (np.abs(s - 3.0) < T * 2.5)
                rgb[xmark] = WALK
            if tag == "fin" and abs(nx) > 0.5:
                # 星（赤・白縁・赤の外縁）
                u = z + 7.45
                v = s - 2.30
                if sgn * nx < 0:
                    u = -u
                outer = star_inside(u, v, 0.62)
                mid = star_inside(u, v, 0.62 - 1.5 * T)
                core = star_inside(u, v, 0.62 - 3.0 * T)
                rgb[outer] = RED
                rgb[mid] = WHITE
                rgb[core] = RED
                dark &= ~outer
                light &= ~outer
        else:
            rgb[:] = shade(rgb, 1.12)
    elif tag == "surface":
        dark |= edge_px

    rgb[light] = shade(rgb[light], 1.28)
    rgb[dark] = shade(rgb[dark], 0.62)
    rgb[rivet & ~dark] = shade(rgb[rivet & ~dark], 1.18)

    # 機番「01」: ナセルの外側の側面、カナードの下
    if tag == "nacelle" and abs(nx) > 0.5 and np.sign(nx) == np.sign(x.mean()):
        z_aft, z_fore, y_top = 3.20, 4.25, 2.24
        tx = ((z - z_aft) if x.mean() > 0 else (z_fore - z)) * 16
        ty = (y_top - y) * 16
        txi, tyi = np.floor(tx).astype(int), np.floor(ty).astype(int)
        ins = text_mask("01", txi - 1, tyi - 1)
        around = np.zeros_like(ins)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                around |= text_mask("01", txi - 1 + dx, tyi - 1 + dy)
        rgb[around & ~ins] = BLUE
        rgb[ins] = WHITE
    return rgb, alpha


sheet = np.zeros((TEX, TEX, 4), np.uint8)
for c in all_cubes():
    for f in FACES:
        x0, y0, w, h = c.uv[f]
        rgb, a = paint_face(c, f, w, h)
        sheet[y0:y0 + h, x0:x0 + w, :3] = np.clip(rgb, 0, 255).astype(np.uint8)
        sheet[y0:y0 + h, x0:x0 + w, 3] = a

img = Image.fromarray(sheet, "RGBA")
img.save(os.path.join(HERE, "su_47.png"))
buf = io.BytesIO()
img.save(buf, "PNG", optimize=True)
png64 = base64.b64encode(buf.getvalue()).decode()

# ---------------------------------------------------------------------------------------------
# bbmodel


def r5(v):
    return [round(float(a), 5) for a in v]


def element(c):
    faces = {}
    for f in FACES:
        x, y, w, h = c.uv[f]
        faces[f] = {"uv": [x, y, x + w, y + h], "texture": 0}
    return {"name": c.name, "box_uv": False, "rescale": False, "locked": False, "render_order": "default",
            "allow_mirror_modeling": True, "from": r5(c.frm), "to": r5(c.to), "autouv": 0, "color": 0,
            "rotation": r5(c.rot), "origin": r5(c.origin), "faces": faces, "type": "cube", "uuid": c.uuid}


def outline(g):
    kids = []
    for c in g.children:
        kids.append(outline(c) if isinstance(c, Group) else c.uuid)
    return {"name": g.name, "origin": r5(g.origin), "rotation": r5(g.rot), "color": 0, "uuid": g.uuid,
            "export": True, "mirror_uv": False, "isOpen": False, "locked": False, "visibility": True, "autouv": 0,
            "children": kids}


group_by_name = {g.name: g for g in all_groups()}


def keyframe(t, v):
    return {"channel": "rotation", "data_points": [{"x": f"{v[0]:g}", "y": f"{v[1]:g}", "z": f"{v[2]:g}"}],
            "uuid": uid(), "time": t, "color": -1, "interpolation": "linear"}


def animation(name, frames):
    animators = {}
    for bone, keys in frames.items():
        g = group_by_name[bone]
        animators[g.uuid] = {"name": bone, "type": "bone", "keyframes": [keyframe(t, v) for t, v in keys]}
    return {"uuid": uid(), "name": name, "loop": "hold", "override": False, "length": 1.0, "snapping": 20,
            "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "",
            "animators": animators}


ZERO = (0.0, 0.0, 0.0)
up_frames, down_frames = {}, {}
for n in LEG_NAMES:
    up_frames[n] = [(0.0, ZERO), (0.7, GEAR_UP[n]), (1.0, GEAR_UP[n])]
    down_frames[n] = [(0.0, GEAR_UP[n]), (0.3, GEAR_UP[n]), (1.0, ZERO)]
for n in DOOR_NAMES:
    up_frames[n] = [(0.0, ZERO), (0.7, ZERO), (1.0, GEAR_UP[n])]
    down_frames[n] = [(0.0, GEAR_UP[n]), (0.3, ZERO), (1.0, ZERO)]

model = {
    "meta": {"format_version": "4.5", "model_format": "animated_entity_model", "box_uv": False},
    "name": "su_47",
    "model_identifier": "su_47",
    "visible_box": [18, 7, 3],
    "variable_placeholders": "",
    "variable_placeholder_buttons": [],
    "timeline_setups": [],
    "unhandled_root_fields": {},
    "resolution": {"width": TEX, "height": TEX},
    "elements": [element(c) for c in all_cubes()],
    "outliner": [outline(root)],
    "textures": [{"path": "", "name": "su_47.png", "folder": "", "namespace": "", "id": "0", "group": "",
                  "width": TEX, "height": TEX, "uv_width": TEX, "uv_height": TEX, "particle": False,
                  "use_as_default": False, "layers_enabled": False, "sync_to_project": "", "render_mode": "default",
                  "render_sides": "auto", "pbr_channel": "color", "frame_time": 1, "frame_order_type": "loop",
                  "frame_order": "", "frame_interpolate": False, "visible": True, "internal": True,
                  "saved": False, "uuid": uid(), "source": "data:image/png;base64," + png64}],
    "animations": [animation("gear_down", down_frames), animation("gear_up", up_frames)],
}
with open(os.path.join(HERE, "su_47.bbmodel"), "w", encoding="utf-8") as fh:
    json.dump(model, fh, ensure_ascii=False, separators=(",", ":"))

# ---------------------------------------------------------------------------------------------
# 当たり判定の箱と定義

STRUCT = {"body", "tailcone", "spine", "deck", "nacelle", "nozzle", "boom", "wing", "canard", "tail", "fin", "surface"}
boxes = []
names = {}
for c in all_cubes():
    if c.tag not in STRUCT:
        continue
    # キューブとその親の回転が全部ヨーだけなら、回した箱 1 つで書ける（舵面のボーンもこれ）
    rots = [c.rot]
    gg = parents[id(c)]
    while gg is not None:
        rots.append(gg.rot)
        gg = gg.parent
    yaw_only = all(abs(r[0]) < 1e-9 and abs(r[2]) < 1e-9 for r in rots)
    total_yaw = sum(r[1] for r in rots)
    size = np.array([c.to[i] - c.frm[i] for i in range(3)]) / U
    if c.tag in ("nacelle", "nozzle"):
        base = "engine_left" if c.origin[0] < 0 else "engine_right"
    elif c.tag in ("wing", "surface"):
        base = "lwing" if c.origin[0] < 0 else "rwing"
    elif c.tag in ("tail", "fin"):
        base = "tail"
    elif c.tag == "canard":
        base = "canard"
    else:
        base = "hull"
    k = names.get(base, 0)
    names[base] = k + 1
    name = base if k == 0 else f"{base}_{k + 1}"
    if not yaw_only:
        # 傾いた物（垂直尾翼）はワールドでの外接箱
        cs = np.array(np.meshgrid([c.frm[0], c.to[0]], [c.frm[1], c.to[1]], [c.frm[2], c.to[2]])).reshape(3, -1).T
        p, _ = cube_transform(c, cs, (0, 1, 0))
        a = to_aircraft(p)
        lo, hi = a.min(0), a.max(0)
        entry = {"name": name, "offset": r5((lo + hi) / 2), "size": r5(hi - lo)}
    else:
        centre_bb = [(c.frm[i] + c.to[i]) / 2 for i in range(3)]
        p, _ = cube_transform(c, np.array([centre_bb]), (0, 1, 0))
        centre = to_aircraft(p)[0]
        entry = {"name": name, "offset": r5(centre), "size": r5(size)}
        if abs(total_yaw) > 1e-9:
            # 定義の箱は機体座標（z 前）なので、bb の Ry と符号が逆になる
            entry["rotation"] = [0.0, round(-total_yaw, 3), 0.0]
    entry["offset"] = [round(v, 3) for v in entry["offset"]]
    entry["size"] = [round(v, 3) for v in entry["size"]]
    boxes.append(entry)


def wing_bottom(s):
    return WING_Y - WING.thick(s) / 2


def wing_mid(s):
    return (WING.zle(s) + WING.zte(s)) / 2


hardpoints = []
for i, s in enumerate((3.4, 4.9, 6.3), start=1):
    for sgn, side in ((-1, "left"), (1, "right")):
        hardpoints.append({"name": f"pylon{i}_{side}", "pos": [round(sgn * s, 3), round(wing_bottom(s) - 0.42, 3),
                                                                 round(wing_mid(s), 3)], "kind": "weapon"})
for sgn, side in ((-1, "left"), (1, "right")):
    hardpoints.append({"name": f"wingtip_{side}", "pos": [round(sgn * 8.30, 3), round(wing_bottom(8.35) - 0.28, 3),
                                                            round(wing_mid(8.35), 3)], "wingtip": True,
                       "kind": "weapon"})
for sgn, side in ((-1, "left"), (1, "right")):
    hardpoints.append({"name": f"intake_{side}", "pos": [round(sgn * NAC_X, 3), round(1.30 - 0.42, 3), 1.6],
                       "kind": "weapon"})
hardpoints.append({"name": "centre_fore", "pos": [0.0, round(1.80 - 0.42, 3), 1.2], "kind": "weapon"})
hardpoints.append({"name": "centre_aft", "pos": [0.0, round(1.84 - 0.42, 3), -3.0], "kind": "weapon"})
for sgn, side in ((-1, "left"), (1, "right")):
    hardpoints.append({"name": f"sensor_{side}", "pos": [round(sgn * NAC_X, 3), round(1.33 - 0.2, 3), 3.7],
                       "kind": "special"})
hardpoints.append({"name": "gun", "pos": [2.0, 2.55, 2.3], "fixed": "ashvehicles:gsh_30_1",
                   "ammunition": ["ashvehicles:autocannon_apfsds", "ashvehicles:autocannon_he",
                                  "ashvehicles:autocannon_heat"]})

# 速度: 実機 2200 km/h を艦隊の曲線へ（60.24 × 実値^0.35）
REAL_KMH = 2200.0
v_kmh = min(REAL_KMH, 60.24 * REAL_KMH ** 0.35)
v = v_kmh / 72.0
MAX_THRUST = 0.0271     # Su-30SM の 0.0218 を乾燥推力重量比（D-30F6 186 kN / 25 t 対 AL-31FP 149 kN / 24.9 t）で伸ばした
AB = 1.5
STALL = 1.50
STALL_ANGLE = 20
LIFT_SLOPE = 5.5
lift = 0.02453 / (STALL ** 2 * LIFT_SLOPE * math.radians(STALL_ANGLE))
drag = MAX_THRUST * AB / v ** 2
SEAT = [0.0, 2.60, 5.05]

definition = {
    "hitbox": {"width": 7, "height": 3.5, "tracking_range": 12, "boxes": boxes},
    "model": {
        "scale": 1.0,
        "bones": {
            "elevator_left": "LeftStabilizer", "elevator_right": "RightStabilizer",
            "aileron_left": "LeftAileron", "aileron_right": "RightAileron",
            "flap_left": "LeftFlap", "flap_right": "RightFlap",
            "rudder_left": "LeftRudder", "rudder_right": "RightRudder",
            "nose_gear": "FGear", "left_gear": "LGear", "right_gear": "RGear",
            "nose_gear_door": "FGearDoorL", "left_gear_door": "LGearDoor", "right_gear_door": "RGearDoor",
            "nose_wheel": "FWheel", "left_wheel": "LWheel", "right_wheel": "RWheel",
            "stick": "stick", "throttle": "throttle",
        },
    },
    "engine": {"max_thrust": MAX_THRUST, "throttle_rate": 0.012, "spool_rate": 0.085, "sea_level_density": 1,
               "afterburner": {"thrust": AB, "light_rate": 0.24, "heat": 5,
                               "nozzles": [[-NAC_X, 1.97, -9.5], [NAC_X, 1.97, -9.5]]}},
    "wing": {"max_speed": round(v, 2), "stall_speed": STALL, "lift": round(lift, 5), "lift_slope": LIFT_SLOPE,
             "stall_angle": STALL_ANGLE, "drag": round(drag, 7), "induced_drag": 0.00035, "lateral_drag": 0.15,
             "ground_effect": 0.3, "span": 16.7},
    "handling": {"pitch_rate": 1.4, "roll_rate": 3.4, "yaw_rate": 1.35, "control_lag": 0.15, "weathervane": 2.6,
                 "alpha_limit": 1, "aero_damping": 0.05},
    "airframe": {"nation": "ru", "cost": 320, "mass": 25000, "payload": 8000, "health": 320, "crash_speed": 2.5,
                 "explosion_power": 3.5, "seats": [SEAT], "max_g": 9, "ejection": {"speed": 1.5, "canopy": 22}},
    "landing_gear": {"cycle_ticks": 40, "drag_penalty": 0.6, "rolling_friction": 0.999, "brake_friction": 0.986,
                     "lateral_friction": 0.5, "steer_rate": 1, "steer_fade": 1.3},
    "flaps": {"cycle_ticks": 20, "drag_penalty": 0.5, "lift_bonus": 0.45},
    "camera": {"pos": [0.0, 4.4, -15.5], "cockpit": [0.0, round(SEAT[1] + 1.02, 3), round(SEAT[2] + 0.08, 3)]},
    "sound": {"volume": 1, "idle_volume": 0.35, "pitch_min": 0.7, "pitch_max": 1.25, "range": 1024},
    "radar": {"range": 4000, "arc": 70, "sweep_ticks": 10, "warning_range": 4500},
    "signature": {"radar": 0.8, "store": 0.3},
    "countermeasures": {"flares": 96, "chaff": 96, "salvo": 2, "spread": 100},
    "hardpoints": hardpoints,
}
with open(os.path.join(HERE, "su_47.json"), "w", encoding="utf-8") as fh:
    json.dump(definition, fh, ensure_ascii=False, indent=2)
print(f"top speed {v_kmh:.0f} km/h ({v:.2f} b/t), drag {drag:.7f}, lift {lift:.5f}, boxes {len(boxes)}")

# ---------------------------------------------------------------------------------------------
# 確認用レンダー（bb 座標は右手系なので right = forward × up）


def render(name, forward, up, pose=None, px_per_m=48, margin=1.0):
    f = np.array(forward, float)
    f /= np.linalg.norm(f)
    u = np.array(up, float)
    u -= f * (u @ f)
    u /= np.linalg.norm(u)
    r = np.cross(f, u)
    light = np.array([-0.4, 0.8, -0.45])
    light /= np.linalg.norm(light)
    pts_all, col_all = [], []
    for c in all_cubes():
        for fc in FACES:
            x0, y0, w, h = c.uv[fc]
            tex = sheet[y0:y0 + h, x0:x0 + w]
            d = face_density(c, fc)
            sx, sy, sz = (c.to[i] - c.frm[i] for i in range(3))
            k = max(2, int(math.ceil(px_per_m / U / d * 1.3)))
            ns, nt = w * k, h * k
            S, Tt = np.meshgrid((np.arange(ns) + 0.5) / ns, (np.arange(nt) + 0.5) / nt)
            P = face_points(c, fc, S, Tt).reshape(-1, 3)
            p, n = cube_transform(c, P, NORMALS[fc], pose=pose)
            texel = tex[np.minimum((Tt * h).astype(int), h - 1), np.minimum((S * w).astype(int), w - 1)].reshape(-1, 4)
            keep = texel[:, 3] > 0
            lam = 0.62 + 0.38 * abs(float(n @ light))
            pts_all.append(p[keep])
            col_all.append(texel[keep, :3] * lam)
    P = np.concatenate(pts_all) / U
    C = np.concatenate(col_all)
    sx, sy, depth = P @ r, P @ u, P @ f
    lo_x, hi_x, lo_y, hi_y = sx.min() - margin, sx.max() + margin, sy.min() - margin, sy.max() + margin
    W = int((hi_x - lo_x) * px_per_m)
    H = int((hi_y - lo_y) * px_per_m)
    ix = ((sx - lo_x) * px_per_m).astype(int)
    iy = ((hi_y - sy) * px_per_m).astype(int)
    order = np.argsort(-depth)
    idx = iy[order] * W + ix[order]
    img = np.full((H * W, 3), 236, np.uint8)
    img[idx] = np.clip(C[order], 0, 255).astype(np.uint8)
    out = img.reshape(H, W, 3)
    Image.fromarray(out).save(os.path.join(HERE, f"preview_{name}.png"))


GEAR_UP_POSE = {n: GEAR_UP[n] for n in GEAR_UP}
if os.environ.get("SU47_PREVIEW", "1") == "1":
    render("top", (0, -1, 0), (0, 0, -1), px_per_m=40)
    render("side", (1, 0, 0), (0, 1, 0), px_per_m=40)
    render("front", (0, 0, 1), (0, 1, 0), px_per_m=60)
    render("three_quarter", (0.62, -0.42, 0.66), (0, 1, 0), px_per_m=40)
    render("gear_up_side", (1, 0, 0), (0, 1, 0), pose=GEAR_UP_POSE, px_per_m=40)
    render("gear_up_front", (0, 0, 1), (0, 1, 0), pose=GEAR_UP_POSE, px_per_m=60)
