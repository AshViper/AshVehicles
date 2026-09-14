"""兵装のアイテムアイコンを、その兵装自身の geo モデルから起こす。

    python tool/make_weapon_icon.py r77 r37m lmur

`assets/ashvehicles/geo/weapon/<id>.geo.json` の全キューブを側面（Z 軸が長さ、Y 軸が上）へ投影し、
既存アイコンと同じ右上がりの対角に置いて 16x16 へ落とす。色は同じ兵装の
`textures/weapon/<id>.png` の中央値から 4 階調を作るので、インベントリの絵と 3D の機体色が揃う。

**これは手描きの代わりではなく、手描きの下地である。** アイコンが無い兵装はインベントリで
マゼンタの市松模様になる（記憶ノート what-a-new-weapon-needs）ので、まずこれで形の正しい 1 枚を
置き、必要なら Aseprite などで描き直す。同梱の aim9 / aim54 / agm114 は手描きで、こちらより
彩度もコントラストも高い。

出力は `assets/ashvehicles/textures/item/<id>_item.png`。既存ファイルは --force なしでは上書きしない。
"""

import argparse
import json
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "ashvehicles")
GEO = os.path.join(ASSETS, "geo", "weapon")
SKIN = os.path.join(ASSETS, "textures", "weapon")
ICON = os.path.join(ASSETS, "textures", "item")

SIZE = 16
"""アイコンの一辺。バニラのアイテムテクスチャと同じ。"""

SUPER = 16
"""ラスタライズの倍率。16 倍で描いてから BOX で縮めると、輪郭の階段が滑らかな中間調になる。"""

ANGLE = -45.0
"""対角へ倒す角度。同梱アイコンはどれも機首が右上、尾翼が左下にある。"""


def rotate(point, pivot, degrees):
    """geo のキューブ回転。GeckoLib と同じ Z -> Y -> X の順で pivot 周りに回す。"""
    x, y, z = point[0] - pivot[0], point[1] - pivot[1], point[2] - pivot[2]
    rx, ry, rz = [math.radians(a) for a in degrees]

    c, s = math.cos(rz), math.sin(rz)
    x, y = x * c - y * s, x * s + y * c
    c, s = math.cos(ry), math.sin(ry)
    x, z = x * c + z * s, -x * s + z * c
    c, s = math.cos(rx), math.sin(rx)
    y, z = y * c - z * s, y * s + z * c

    return (x + pivot[0], y + pivot[1], z + pivot[2])


def side_view(weapon):
    """全キューブの 8 隅を側面へ投影した点群。1 キューブ 1 リスト。"""
    with open(os.path.join(GEO, weapon + ".geo.json"), encoding="utf-8") as handle:
        model = json.load(handle)

    shapes = []

    for bone in model["minecraft:geometry"][0]["bones"]:
        fallback = bone.get("pivot", [0, 0, 0])

        for cube in bone.get("cubes", []):
            origin, size = cube["origin"], cube["size"]
            pivot = cube.get("pivot", fallback)
            spin = cube.get("rotation", [0, 0, 0])
            corners = []

            for dx in (0, size[0]):
                for dy in (0, size[1]):
                    for dz in (0, size[2]):
                        p = rotate((origin[0] + dx, origin[1] + dy, origin[2] + dz), pivot, spin)
                        # 側面図なので幅（X）は捨てる。長さ（Z）と高さ（Y）だけ。
                        corners.append((p[2], p[1]))

            shapes.append(hull(corners))

    return shapes


def hull(points):
    """凸包（Andrew の単調連鎖）。回った箱の影は凸なので、これで塗る形が決まる。"""
    points = sorted(set(points))

    if len(points) < 3:
        return points

    def half(sequence):
        out = []

        for p in sequence:
            while len(out) >= 2 and (out[-1][0] - out[-2][0]) * (p[1] - out[-2][1]) \
                    - (out[-1][1] - out[-2][1]) * (p[0] - out[-2][0]) <= 0:
                out.pop()

            out.append(p)

        return out

    return half(points)[:-1] + half(points[::-1])[:-1]


def body_colour(weapon):
    """その兵装のテクスチャの中央値。不透明な画素だけを明度で並べて真ん中を採る。"""
    skin = Image.open(os.path.join(SKIN, weapon + ".png")).convert("RGBA")
    opaque = [p[:3] for p in skin.getdata() if p[3] > 200]

    if not opaque:
        return (170, 170, 170)

    opaque.sort(key=lambda p: 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2])

    return opaque[len(opaque) // 2]


def tone(colour, factor):
    return tuple(max(0, min(255, int(v * factor))) for v in colour)


def draw(weapon, margin=0.94):
    shapes = side_view(weapon)
    xs = [p[0] for shape in shapes for p in shape]
    ys = [p[1] for shape in shapes for p in shape]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2

    # 対角へ倒し、X を反転して機首を右上へ持ってくる。
    angle = math.radians(ANGLE)
    ca, sa = math.cos(angle), math.sin(angle)
    turned = [[(-((p[0] - cx) * ca - (p[1] - cy) * sa), (p[0] - cx) * sa + (p[1] - cy) * ca)
               for p in shape] for shape in shapes]

    tx = [p[0] for shape in turned for p in shape]
    ty = [p[1] for shape in turned for p in shape]
    scale = SIZE * SUPER * margin / max(max(tx) - min(tx), max(ty) - min(ty))
    span = SIZE * SUPER

    canvas = Image.new("L", (span, span), 0)
    pen = ImageDraw.Draw(canvas)

    for shape in turned:
        pen.polygon([(span / 2 + p[0] * scale, span / 2 - p[1] * scale) for p in shape], fill=255)

    mask = canvas.resize((SIZE, SIZE), Image.BOX).load()
    solid = [[mask[x, y] >= 96 for y in range(SIZE)] for x in range(SIZE)]

    base = body_colour(weapon)
    high, light, mid, shadow = (tone(base, f) for f in (1.18, 0.96, 0.7, 0.42))
    icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out = icon.load()

    # 機体軸は右上がりの対角なので、その垂線（x + y）で 4 階調に分ける。左上が光、右下が影。
    for x in range(SIZE):
        for y in range(SIZE):
            if not solid[x][y]:
                continue

            across = ((x - (SIZE - 1) / 2) + (y - (SIZE - 1) / 2)) / (SIZE * 0.5)
            out[x, y] = (*(high if across < -0.42 else light if across < -0.05
                           else mid if across < 0.36 else shadow), 255)

    # 右下の縁を 1 段落とす。手描きの物が持っている輪郭線にあたる。
    for x in range(SIZE):
        for y in range(SIZE):
            if not solid[x][y]:
                continue

            inside = (y + 1 < SIZE and solid[x][y + 1]) and (x + 1 < SIZE and solid[x + 1][y])

            if not inside:
                out[x, y] = (*tone(base, 0.32), 255)

    # 機首の先端 2 画素をシーカーの暗色に。ここが無いと、どちらが前か分からない棒になる。
    nose = sorted([(x, y) for x in range(SIZE) for y in range(SIZE) if solid[x][y]],
                  key=lambda p: p[0] - p[1])[-2:]

    for x, y in nose:
        out[x, y] = (*tone(base, 0.3), 255)

    return icon


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weapons", nargs="+", help="兵装 id（r77 など）")
    parser.add_argument("--force", action="store_true", help="既存のアイコンを上書きする")
    args = parser.parse_args()

    for weapon in args.weapons:
        target = os.path.join(ICON, weapon + "_item.png")

        if os.path.exists(target) and not args.force:
            print("skip  %s（既にある。上書きするなら --force）" % target)

            continue

        draw(weapon).save(target)
        print("wrote %s" % target)

    return 0


if __name__ == "__main__":
    sys.exit(main())
