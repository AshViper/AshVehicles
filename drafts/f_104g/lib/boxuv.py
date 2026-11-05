"""Blockbench's box UV, written out as per-face rectangles (cube.js mapAutoUV, box_uv branch)."""
import math


def box_faces(e, floor=True):
    dx, dy, dz = [abs(e['to'][k] - e['from'][k]) for k in range(3)]
    if floor:
        dx, dy, dz = [math.floor(v + 1e-7) for v in (dx, dy, dz)]
    u, v = e.get('uv_offset', [0, 0])
    fl = {'east': ([0, dz], [dz, dy]), 'west': ([dz + dx, dz], [dz, dy]),
          'up': ([dz + dx, dz], [-dx, -dz]), 'down': ([dz + 2 * dx, 0], [-dx, dz]),
          'south': ([2 * dz + dx, dz], [dx, dy]), 'north': ([dz, dz], [dx, dy])}
    if e.get('mirror_uv'):
        fl['east'], fl['west'] = fl['west'], fl['east']
        fl = {k: ([a[0] + b[0], a[1]], [-b[0], b[1]]) for k, (a, b) in fl.items()}
    return {k: [u + a[0], v + a[1], u + a[0] + b[0], v + a[1] + b[1]] for k, (a, b) in fl.items()}
