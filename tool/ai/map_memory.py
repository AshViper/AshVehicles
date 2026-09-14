# -*- coding: utf-8 -*-
"""試合を重ねて AI が覚えた地図（ai/learning/MapMemory）を画像にする。

    python tool/ai/map_memory.py <ワールド>/ashvehicles_ai/map_memory/minecraft/overworld.json --out map.png
        [--centre X Z --radius ブロック] [--scale 画素] [--mark X Z ...]

1マス（4×4 ブロック）が1つの四角で、北（-Z）が上。
  緑   すんなり抜けられる所（flow）
  青   水に沈む所（water）
  赤   詰まる所（trap）
  黒   倒される所（death）
濃さはゲームと同じ割合（見えない経験2回を足して割る。ai/learning/MapKnowledge）で、経験の少ないマスは薄い。
一度も入られていないマスは白。--mark で拠点や旗の位置に黄色の十字を描ける（複数可）。

詰まる所・沈む所・倒される所の上位を、ブロック座標で表にも出す。
ゲームはこの画像を読まない。見るためだけの物で、Pillow などは要らない（PNG を自分で書く）。
"""
import argparse
import io
import json
import struct
import sys
import zlib

PRIOR = 2.0
KINDS = ("visits", "passes", "stalls", "wet", "deaths")
MOST_PIXELS = 4096


def read(path):
    with io.open(path, encoding="utf-8") as f:
        data = json.load(f)

    names = data.get("columns") or ["x", "z"] + list(KINDS)
    where = dict((name, index) for index, name in enumerate(names))
    cells = []

    for row in data.get("cells") or []:
        values = {}

        for name in KINDS:
            index = where.get(name)
            values[name] = float(row[index]) if index is not None and index < len(row) else 0.0

        seen = values["visits"] + PRIOR
        stalls = values["stalls"]
        passes = values["passes"]
        cells.append({
            "x": int(row[0]),
            "z": int(row[1]),
            "values": values,
            "trap": min(1.0, stalls / (stalls + passes + PRIOR)),
            "water": min(1.0, values["wet"] / seen),
            "death": min(1.0, values["deaths"] / seen),
            "flow": min(1.0, passes / seen),
        })

    return data, cells


def blend(colour, target, amount):
    amount = max(0.0, min(1.0, amount))
    return tuple(int(round(c + (t - c) * amount)) for c, t in zip(colour, target))


def colour_of(cell):
    colour = (255, 255, 255)
    colour = blend(colour, (225, 225, 225), cell["values"]["visits"] / 4.0)
    colour = blend(colour, (40, 170, 60), cell["flow"] * 0.7)
    colour = blend(colour, (40, 90, 230), cell["water"])
    colour = blend(colour, (220, 30, 30), cell["trap"])
    colour = blend(colour, (0, 0, 0), cell["death"] * 0.85)
    return colour


def chunk(kind, payload):
    return (struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))


def write_png(path, width, height, rows):
    raw = b"".join(b"\x00" + bytes(row) for row in rows)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description="Draw what the combat AI learned about a map.")
    parser.add_argument("memory", help="<world>/ashvehicles_ai/map_memory/<namespace>/<dimension>.json")
    parser.add_argument("--out", required=True, help="PNG to write")
    parser.add_argument("--centre", nargs=2, type=float, metavar=("X", "Z"), help="centre of the picture, in blocks")
    parser.add_argument("--radius", type=float, default=256.0, help="half the width of the picture with --centre")
    parser.add_argument("--scale", type=int, default=4, help="pixels per cell")
    parser.add_argument("--mark", nargs=2, type=float, action="append", metavar=("X", "Z"), default=[],
                        help="draw a cross at this block position (points, flags); repeatable")
    parser.add_argument("--top", type=int, default=8, help="rows in each printed list")
    args = parser.parse_args()

    data, cells = read(args.memory)
    size = int(data.get("cell", 4))

    if not cells:
        sys.exit("%s has no cells yet (battles: %s)" % (args.memory, data.get("battles", 0)))

    if args.centre:
        low_x = int((args.centre[0] - args.radius) // size)
        high_x = int((args.centre[0] + args.radius) // size)
        low_z = int((args.centre[1] - args.radius) // size)
        high_z = int((args.centre[1] + args.radius) // size)
    else:
        low_x = min(cell["x"] for cell in cells)
        high_x = max(cell["x"] for cell in cells)
        low_z = min(cell["z"] for cell in cells)
        high_z = max(cell["z"] for cell in cells)

    across = high_x - low_x + 1
    down = high_z - low_z + 1
    scale = max(1, min(args.scale, MOST_PIXELS // max(across, down)))
    width = across * scale
    height = down * scale
    rows = [bytearray(b"\xff" * (width * 3)) for _ in range(height)]

    def paint(cell_x, cell_z, colour):
        if not (low_x <= cell_x <= high_x and low_z <= cell_z <= high_z):
            return

        left = (cell_x - low_x) * scale
        top = (cell_z - low_z) * scale
        pixel = bytes(colour)

        for y in range(top, top + scale):
            row = rows[y]

            for x in range(left, left + scale):
                row[x * 3:x * 3 + 3] = pixel

    for cell in cells:
        paint(cell["x"], cell["z"], colour_of(cell))

    for mark_x, mark_z in args.mark:
        cell_x = int(mark_x // size)
        cell_z = int(mark_z // size)

        for offset in range(-3, 4):
            paint(cell_x + offset, cell_z, (250, 200, 0))
            paint(cell_x, cell_z + offset, (250, 200, 0))

    write_png(args.out, width, height, rows)

    print("%s: %s battles, %d cells, %dx%d cells drawn at %d px per cell -> %s" % (
        args.memory, data.get("battles", 0), len(cells), across, down, scale, args.out))

    for key, label, amount in (("trap", "stuck", "stalls"), ("water", "under water", "wet"),
                               ("death", "destroyed", "deaths")):
        ranked = sorted((cell for cell in cells if cell[key] > 0.0),
                        key=lambda cell: -cell[key] * (1.0 + cell["values"][amount]))

        if not ranked:
            continue

        print("\nwhere machines get %s:" % label)

        for cell in ranked[:args.top]:
            print("  block %7d, %7d  %s %.2f  (%s %.1f, entered %.1f, passed %.1f)" % (
                cell["x"] * size + size // 2, cell["z"] * size + size // 2, key, cell[key], amount,
                cell["values"][amount], cell["values"]["visits"], cell["values"]["passes"]))

    return 0


if __name__ == "__main__":
    sys.exit(main())
