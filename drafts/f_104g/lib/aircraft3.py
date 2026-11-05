"""Configs for the MiG-21MF, MiG-19S and F-100D, and the driver: lay out UVs, paint, write the model.
usage: python aircraft3.py <id> <in.bbmodel> <out.bbmodel> <out.png>"""
import sys
sys.path.insert(0, r'C:\Users\nagis\AppData\Local\Temp\claude\F--Java-AshVehicles\c1f572e4-6100-4bd4-bd04-8835721b751a\scratchpad\lib')
import numpy as np
import bb
from house import hexc, Painter, layout, density_for, pixel_text, soviet_star, us_insignia, N_CAMO, N_CAMO2


def centres(m):
    out = []
    for e in m.elements:
        (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
        C = m.to_world(e, np.array([[x, y, z] for x in (x1, x2) for y in (y1, y2) for z in (z1, z2)], float))
        out.append((C.mean(0), C.min(0), C.max(0), float(np.max(np.abs(np.array(e['to']) - e['from'])))))
    return out


RED, WHITE, BLACK = hexc('#C8202A'), hexc('#F2F2EE'), hexc('#1E1E1E')

SOVIET_COCKPIT = dict(tub=hexc('#3E9AA0'), sill=hexc('#6CC3C6'), panel=hexc('#3A8F95'), panel_dark=hexc('#1F2326'),
                      dial=hexc('#121417'), dial_ring=hexc('#CFD3D0'), switch=hexc('#E2E2DA'), seat=hexc('#5B6156'),
                      seat2=hexc('#4C5148'), seat_rim=hexc('#2E322C'), seat_frame=hexc('#3A3D40'))
US_COCKPIT = dict(tub=hexc('#4F5356'), sill=hexc('#6A6E71'), panel=hexc('#3C3F42'), panel_dark=hexc('#1D1F21'),
                  dial=hexc('#111214'), dial_ring=hexc('#D9D9D2'), switch=hexc('#D8D8D0'), seat=hexc('#5C5C3B'),
                  seat2=hexc('#4D4D30'), seat_rim=hexc('#2E2E1E'), seat_frame=hexc('#3E4042'))
BURNT = np.array([hexc('#A3A29E'), hexc('#8F8C88'), hexc('#7D7B7F'), hexc('#6A6B74')])


def metal(upper, lower):
    U, L = hexc(upper), hexc(lower)
    return lambda P, n, low: np.tile(L if low else U, (len(P), 1))


def sea(P, n, low):
    """Southeast Asia: tan and two greens over light grey, blotches in 3-pixel steps."""
    if low:
        return np.tile(hexc('#CFD0C8'), (len(P), 1))
    Q = np.floor(P / 3) * 3 / np.array([36.0, 26.0, 36.0])
    v = N_CAMO(Q + np.array([1.7, 0, 4.3])); w = N_CAMO2(Q * 1.2 + np.array([5.1, 2, 0.4]))
    C = np.tile(hexc('#A08A68'), (len(P), 1))
    C[w > 0.6] = hexc('#5C6646')
    C[v > 0.62] = hexc('#3D4734')
    return C


def side_decal(img, x, y, z, xmin, xmax, ymin, ymax, zmin, zmax):
    """an image on both sides of the fuselage (or fin), reading nose-left on each side."""
    out = []
    for s in (1, -1):
        region = (lambda s: lambda P: (np.sign(P[:, 0]) == s) & (np.abs(P[:, 0]) >= xmin) & (np.abs(P[:, 0]) <= xmax)
                  & (P[:, 1] > ymin) & (P[:, 1] < ymax) & (P[:, 2] > zmin) & (P[:, 2] < zmax))(s)
        out.append((img, (s * x, y, z), (0, 0, -1) if s > 0 else (0, 0, 1), (0, 1, 0),
                    (lambda s: lambda n: n[0] * s > 0.55)(s), region))
    return out


def fin_decal(img, y, z, half):
    """on both faces of a centreline fin: each face reads nose-left."""
    out = []
    for s in (1, -1):
        region = lambda P: np.abs(P[:, 0]) <= half
        out.append((img, (0, y, z), (0, 0, -1) if s > 0 else (0, 0, 1), (0, 1, 0), (lambda s: lambda n: n[0] * s > 0.7)(s), region))
    return out


def wing_decal(img, x, y, z, below):
    region = (lambda s: lambda P: np.sign(P[:, 0]) == s)(np.sign(x))
    nok = (lambda n: n[1] < -0.8) if below else (lambda n: n[1] > 0.8)
    return [(img, (x, y, z), (1, 0, 0), (0, 0, -1), nok, region)]


def common(**kw):
    base = dict(edge_light=hexc('#E2E6E9'), stencil=hexc('#3C3F43'), walkway=hexc('#A8242C'),
                frame_glass=hexc('#CFE3EE'), nozzle=(hexc('#3A3B3E'), hexc('#6B6E72'), hexc('#9A9EA2')),
                burnt=BURNT, gear=dict(hub=hexc('#5E7C48'), strut=hexc('#B8BEBE')),
                stores=dict(missile=hexc('#DCDCD4'), fin=hexc('#9A9EA0'), seeker=hexc('#3A4A55'), tank=hexc('#C6CACC'),
                            pod=hexc('#8E9396'), cone=hexc('#A9B0A4')),
                tint_amp=0.035, odd_dark=0.0, walkways=True)
    base.update(kw)
    return base


def mig21(m):
    cs = centres(m)

    def classify(e, idx, last):
        col = e.get('color'); size = cs[idx][3]
        if last == 'bone12': return 'glass'
        if last == 'bone46': return 'frame'
        if last == 'bone30': return 'seat'
        if last == 'bone29': return 'interior'
        if last == 'main' and col == 6: return 'dial' if size <= 2.6 else 'interior'
        if last in ('bone13', 'bone15', 'bone17'): return 'tyre'
        if last in ('bone14', 'bone16', 'bone18'): return 'hub'
        if last in ('fr_wheels', 'LGear', 'RGear') or (last == 'main' and col == 0): return 'strut'
        if last.startswith('octagon'): return 'cone'
        if last == 'main' and col == 9: return 'probe'
        if last == 'bone24': return 'tank'
        if last.startswith('missile') or last in ['bone%d' % k for k in (32, 34, 35, 36, 37, 38, 39, 40)]: return 'missile'
        if last in ('bone47', 'bone48', 'bone53', 'bone54', 'bone55', 'bone59'): return 'nozzle'
        if last == 'bone8': return 'hole'
        return 'skin'
    star, wstar = soviet_star(15, RED, WHITE), soviet_star(15, RED, WHITE)
    num = pixel_text('54', RED, WHITE)
    decals = fin_decal(star, 57, 118, 3) + side_decal(num, 10.9, 31, -100, 6, 12, 22, 41, -113, -86) \
        + wing_decal(wstar, -56, 30, 47, True) + wing_decal(wstar, 56, 30, 47, True)
    return common(
        classify=classify, scheme=metal('#B7BBBE', '#C3C7CA'), tint_amp=0.06, odd_dark=0.12,
        stations=np.arange(-152, 150, 18.0), bands_plan=np.array([0, 4, 9, 15, 22, 30, 40, 50, 60, 72, 90.0]),
        bands_side=np.array([0, 10, 20, 26, 32, 38, 44, 50, 60, 70, 80.0]),
        fin=lambda P: (np.abs(P[:, 0]) < 3) & (P[:, 1] > 42), tub=(7.5, 27, 42, -80, -40), burn=(60, 92, 41, 11),
        interior=SOVIET_COCKPIT, hole_centre=(0, 31), decals=decals, missile_nose={'missile1': -13, 'missile2': -13},
        surfaces=('BLFlap', 'BRFLap', 'LFlap', 'RFlap', 'VBFlap'), vertical_surfaces=('VBFlap',))


def mig19(m):
    cs = centres(m)

    def classify(e, idx, last):
        col = e.get('color'); c, lo, hi, size = cs[idx]
        if last == 'bone22': return 'glass' if col == 4 else 'frame'
        if last == 'bone30': return 'seat'
        if last == 'main' and col == 6: return 'dial' if size <= 3.1 else 'interior'
        if last in ('bone15', 'bone17', 'bone19'): return 'tyre'
        if last in ('bone16', 'bone18', 'bone20'): return 'hub'
        if last in ('fr_wheels', 'LGear', 'RGear'): return 'strut'
        if last in ('bone21', 'bone24'): return 'tank'
        if last in ('bone23', 'bone25', 'octagon', 'octagon2'): return 'pod'
        if last in ('bone4', 'bone8') and col == 6: return 'dark'
        if last in ('bone', 'bone7') and col == 6: return 'nozzle'
        if last in ('bone', 'bone7') and col == 0 and c[2] > 98 and abs(c[0]) < 4.5 and c[1] < 38: return 'dark'
        return 'skin'
    star, wstar = soviet_star(15, RED, WHITE), soviet_star(13, RED, WHITE)
    num = pixel_text('17', RED, WHITE)
    decals = fin_decal(star, 55, 106, 3) + side_decal(num, 13.5, 30, -84, 9, 15, 22, 39, -100, -66) \
        + wing_decal(wstar, -70, 24, 52, True) + wing_decal(wstar, 70, 24, 52, True)
    return common(
        classify=classify, scheme=metal('#B5B9BC', '#C1C5C8'), tint_amp=0.06, odd_dark=0.12,
        stations=np.arange(-101, 140, 18.0), bands_plan=np.array([0, 4, 9, 15, 22, 30, 40, 52, 64, 76, 92.0]),
        bands_side=np.array([0, 10, 20, 26, 32, 38, 44, 52, 62, 72, 80.0]),
        fin=lambda P: (np.abs(P[:, 0]) < 3) & (P[:, 1] > 40), tub=(8, 27, 44, -80, -38), burn=(85, 132, 40, 15),
        interior=SOVIET_COCKPIT, decals=decals,
        surfaces=('BLFlap', 'BRFlap', 'LFlap', 'RFlap', 'VBFlap'), vertical_surfaces=('VBFlap',))


def f100(m):
    cs = centres(m)

    def classify(e, idx, last):
        col = e.get('color'); c, lo, hi, size = cs[idx]
        if last == 'main' and col == 4 and c[2] < -30: return 'glass'
        if last in ('bone3', 'bone9') and col == 4 and c[2] > 140: return 'hole'
        if last in ('bone37', 'bone39'): return 'dark'
        if last == 'main' and col == 5 and -95 < c[2] < -55 and c[1] > 50: return 'frame'
        if col == 2 and last in ('bone3', 'bone9', 'bone10', 'bone4'): return 'interior'
        if col == 9: return 'dial'
        if last == 'bone41': return 'seat'
        if idx == 371: return 'interior'
        if last in ('bone21', 'bone23', 'bone26'): return 'tyre'
        if last in ('bone22', 'bone27', 'bone24', 'bone25'): return 'hub'
        if last in ('FGear', 'LGear', 'RGear'): return 'strut'
        if last == 'main' and col == 3: return 'probe'
        if last in ('bone30', 'bone31'): return 'tank'
        if last == 'bone18' and col == 2: return 'probe'
        if col == 5 and lo[2] > 150 and c[1] < 55 and abs(c[0]) < 12.5: return 'nozzle'
        return 'skin'
    ins = us_insignia(13, hexc('#1F3F86'), WHITE, hexc('#B22234'))
    code, serial = pixel_text('SM', BLACK, None, cell=2), pixel_text('63221', BLACK, None, cell=1)
    decals = side_decal(ins, 13, 46, 92, 7, 20, 34, 60, 76, 110) + fin_decal(code, 97, 143, 3.5) \
        + fin_decal(serial, 84, 141, 3.5) + wing_decal(ins, -78, 37, 66, False) + wing_decal(ins, 78, 37, 66, True)
    return common(
        classify=classify, scheme=sea, tint_amp=0.03, stencil=hexc('#1E1E1E'), walkway=hexc('#2A2A2A'),
        edge_light=hexc('#C9C4B2'), gear=dict(hub=hexc('#B9BDBF'), strut=hexc('#C9CCCD')),
        stores=dict(missile=hexc('#DCDCD4'), fin=hexc('#9A9EA0'), seeker=hexc('#3A4A55'), tank=hexc('#BFC3C5'),
                    pod=hexc('#8E9396'), cone=hexc('#A9B0A4')),
        stations=np.arange(-178, 170, 20.0), bands_plan=np.array([0, 5, 11, 18, 26, 36, 48, 62, 78, 96, 116.0]),
        bands_side=np.array([0, 20, 28, 34, 40, 46, 52, 58, 66, 78, 92, 106, 120.0]),
        fin=lambda P: (np.abs(P[:, 0]) < 3.5) & (P[:, 1] > 60), tub=(9, 41, 58, -95, -30), burn=(112, 161, 56, 17),
        interior=US_COCKPIT, hole_centre=(0, 45), decals=decals,
        surfaces=('BLFlap', 'BRFlap', 'LFlap', 'RFlap', 'VBFlap', 'group2'), vertical_surfaces=('VBFlap', 'group2'))


CONFIGS = {'mig21mf': mig21, 'mig19s': mig19, 'f100d': f100}

if __name__ == '__main__':
    ident, src, out_model, out_png = sys.argv[1:5]
    m = bb.Model(src)
    cfg = CONFIGS[ident](m)
    used = layout(m, density_for(cfg, m), 1024, name=ident)
    print(ident, 'uv layout uses', used, 'rows of 1024')
    m.res = (1024, 1024)
    img = Painter(m, cfg).paint()
    img.save(out_png)
    m.set_texture(img)
    m.save(out_model)
    print('painted', out_model)
