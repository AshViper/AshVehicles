"""AV-8B Harrier II (USMC, gunship grey low-vis): lay out per-face UVs (cockpit 4 px/unit, glass 2, the rest 1)
on one sheet and paint it in the bundled aircraft's habits (house / house2).
usage: python paint_av8b.py <in.bbmodel> <out.bbmodel> <sheet.png>"""
import os, sys, math
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'paint_lib'))
import numpy as np
from PIL import Image
import bb, house
from house import hexc, hsh, pixel_text, us_insignia, _pixelate, _rgba, _star
from house2 import Painter
from aircraft3 import side_decal, fin_decal, wing_decal, US_COCKPIT

SIZE = 1024
TYRES = {'bone5', 'bone17', 'bone9', 'bone6', 'bone15', 'bone18'}
HUBS = {'bone7', 'bone12'}
STRUTS = {'group11', 'group12', 'bone10', 'bone3', 'bone16', 'bone19'}
STORES = {'bone24', 'bone21'}
INTAKES = {'group2', 'group8'}
PIT = (9.6, 37, 63, -119, -72)                # |x|, y0, y1, z0, z1 of the cockpit

UPPER, LOWER = hexc('#4B5158'), hexc('#555B62')
MARK = hexc('#8C939A')                        # low-vis markings, light grey on the gunship grey
MARK2 = hexc('#6C737A')
RED = hexc('#C8202A')

# letters the house font lacks
house.FONT.update({
    'I': ['111', '010', '010', '010', '111'], 'N': ['101', '111', '111', '111', '101'],
    'E': ['111', '100', '110', '100', '111'], 'Y': ['101', '101', '010', '010', '010'],
    'X': ['101', '101', '010', '101', '101'],
})


def centre(m, e):
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    C = m.to_world(e, np.array([[x, y, z] for x in (x1, x2) for y in (y1, y2) for z in (z1, z2)], float))
    return C.mean(0)


def classify_all(m):
    mats = {}
    for idx, e in enumerate(m.elements):
        ch = [g['name'] for g in m.chain(e)]
        last = ch[-1] if ch else ''
        col = e.get('color', 0)
        size = max(abs(b - a) for a, b in zip(e['from'], e['to']))
        if last == 'group14': mat = 'glass'
        elif last == 'cockpit':
            if col == 0: mat = 'hud'
            elif col == 7: mat = 'mfd' if size >= 2.9 else 'button'
            elif col == 6: mat = 'seat'
            elif col == 2: mat = 'grip'
            elif col == 5: mat = 'knob'
            else: mat = 'interior'
        elif last == 'group7' and idx in (633, 730, 732, 734, 629, 631): mat = 'pit_wall'
        elif last in TYRES: mat = 'tyre'
        elif last in HUBS: mat = 'hub'
        elif last in STRUTS: mat = 'strut'
        elif last in STORES: mat = 'tank'
        elif last == 'group5': mat = 'nozzle'
        elif last in INTAKES and col == 7: mat = 'hole'
        else: mat = 'skin'
        mats[e['uuid']] = (mat, last)
    return mats


def density(mats):
    def k(e, f):
        mat, last = mats[e['uuid']]
        if mat in ('interior', 'seat', 'mfd', 'button', 'grip', 'knob', 'hud', 'pit_wall'):
            return 4
        # glass, and the wings: their cubes are swept 39 deg, so the insignia needs a finer grid to keep its shape
        return 2 if mat == 'glass' or last in ('group9', 'group10') else 1
    return k


def shield(w, h, fill, edge):
    """a small squadron shield: outline, a light diagonal band."""
    def draw(d, s):
        W, H = w * s, h * s
        poly = [(0, 0), (W - 1, 0), (W - 1, H * 0.55), (W / 2, H - 1), (0, H * 0.55)]
        d.polygon(poly, fill=_rgba(edge))
        k = s * 1.0
        inner = [(k, k), (W - 1 - k, k), (W - 1 - k, H * 0.55 - k * 0.4), (W / 2, H - 1 - k * 1.6), (k, H * 0.55 - k * 0.4)]
        d.polygon(inner, fill=_rgba(fill))
        d.line([(k, H * 0.45), (W - k, H * 0.15)], fill=_rgba(edge), width=int(s))
    return _pixelate(draw, w, h)


def small_star(size, col):
    def draw(d, s):
        c = (size * s / 2, size * s / 2)
        d.polygon(_star(c, size * s * 0.5), fill=_rgba(col))
    return _pixelate(draw, size, size)


def tilted_fin_decal(img, y, z, tilt=13):
    """on both faces of the fin, leaning back with the fin's cubes (rotated 13 deg about x) so the 1-px letters
    land on whole texels instead of breaking up across a rotated grid; each face reads nose-left."""
    a = math.radians(tilt)
    up = (0, math.cos(a), math.sin(a))
    out = []
    for s in (1, -1):
        right = (0, math.sin(a), -math.cos(a)) if s > 0 else (0, -math.sin(a), math.cos(a))
        out.append((img, (0, y, z), right, up, (lambda s: lambda n: n[0] * s > 0.7)(s),
                    lambda P: (np.abs(P[:, 0]) <= 3) & (P[:, 1] > 60)))
    return out


class HarrierPainter(Painter):
    GX0, GZ0, GNX, GNZ, GY0, GNY = -120, -190, 240, 380, -10, 130

    def skin(self, f):
        C = super().skin(f)
        P, n = f['P'], f['n']
        C[P[:, 2] < -167.5] = hexc('#1E2226')                         # the nose sensor window
        # anti-collision beacons on the spine and the belly
        top = (np.abs(P[:, 0]) < 1.5) & (np.abs(P[:, 2] - 18) < 1.5) & (n[1] > 0.7) & (P[:, 1] > 60)
        bot = (np.abs(P[:, 0]) < 1.5) & (np.abs(P[:, 2] + 8) < 1.5) & (n[1] < -0.7) & (P[:, 1] < 30)
        C[top | bot] = RED
        # the cockpit shell seen through the canopy: its inner faces take the tub colour
        x, y0, y1, z0, z1 = PIT
        c = P.mean(0)
        if f['last'] in ('group', 'group7') and z0 < c[2] < z1 and abs(c[0]) < x + 1 and y0 < c[1] < y1:
            inward = n[0] * c[0] < -0.3 * abs(c[0])
            if inward:
                C[:] = self.cfg['interior']['tub']
                C[(f['i'] == 0) | (f['j'] == 0)] *= 1.15
        return C

    def glass(self, f):
        """clear glass: frames only at the windscreen arch, the canopy's rear bow and the sills; thin sides opaque."""
        P = f['P']
        A = np.zeros((len(P), 4), np.float32)
        i, j, nw, nh = f['i'], f['j'], f['nw'], f['nh']
        frame = hexc('#3E4349')
        z, y = P[:, 2], P[:, 1]
        bow = (np.abs(z + 101) < 1.2) | (z < -118.3) | (z > -75.2)
        sill = y < 51.5
        A[bow | sill, :3] = frame; A[bow | sill, 3] = 1
        A[(np.abs(z + 101) < 0.5) & ~sill, :3] = frame * 1.25
        # one glint per pane, on the outer face only (the slabs' inner faces and thin sides stay clear)
        c = P.mean(0)
        outward = f['n'][0] * c[0] + f['n'][1] * (c[1] - 50) > 0.5 * math.hypot(c[0], c[1] - 50)
        if outward and min(nw, nh) > 2:
            s = (z * 0.8 + y * 1.6) % 21
            streak = (s < 0.7) & ~(bow | sill) & (np.abs(z + 88) > 9)
            A[streak, :3] = hexc('#E9F4FA'); A[streak, 3] = 1
        return A

    def hole(self, f):
        """the intake face: dark, fan blades round each intake's centre."""
        P = f['P']
        cx = 17.5 * np.sign(P[:, 0].mean())
        th = np.degrees(np.arctan2(P[:, 1] - 44, P[:, 0] - cx))
        r = np.hypot(P[:, 1] - 44, P[:, 0] - cx)
        C = np.tile(hexc('#2A2D31'), (len(P), 1))
        C[(np.floor(th / 1) % 24) < 6] = hexc('#454A50')
        C[r < 2.5] = hexc('#5A6067')
        return C

    def nozzle(self, f):
        """swivelling nozzles: worn dark metal, vane lines along the cube."""
        i, j, nw, nh = f['i'], f['j'], f['nw'], f['nh']
        C = np.tile(hexc('#4E5257'), (len(f['P']), 1))
        C[(i % 4) == 0] = hexc('#3B3E42')
        C[(i % 4) == 1] = hexc('#5D6167')
        C[(j == 0) | (j == nh - 1)] = hexc('#34373B')
        return C

    def wheel(self, f, mat):
        P, n = f['P'], f['n']
        cy, cz, R = self.wheel_box[f['last']]
        if mat == 'hub':
            C = np.tile(hexc('#B9BDBF'), (len(P), 1))
            C[(f['i'] == 0) | (f['j'] == 0)] *= 0.85
            return C
        C = np.tile(hexc('#232425'), (len(P), 1))
        if abs(n[0]) > 0.5:
            r = np.hypot(P[:, 1] - cy, P[:, 2] - cz)
            C[r < R * 0.6] = hexc('#B9BDBF')
            C[np.abs(r - R * 0.6) < 0.6] = hexc('#7E8386')
            C[r < 1.2] = hexc('#4A4E52')
        else:
            C[(f['i'] % 2) == 0] *= 0.75
        return C

    def strut(self, f):
        C = np.tile(hexc('#C9CCCD'), (len(f['P']), 1))
        C[(f['i'] == 0) | (f['j'] == 0)] *= 1.04
        C[(f['i'] == f['nw'] - 1) | (f['j'] == f['nh'] - 1)] *= 0.78
        return C

    def tank(self, f):
        P = f['P']
        C = np.tile(hexc('#5E646B'), (len(P), 1))
        rel = np.floor(P[:, 2] + 50)
        C[(rel % 14) == 0] *= 0.8
        C[(rel % 14) == 1] *= 1.08
        C[P[:, 2] < -47] = hexc('#4A5056')
        return C

    def cockpit(self, f, mat):
        pal = self.cfg['interior']
        i, j, nw, nh = f['i'], f['j'], f['nw'], f['nh']
        N = len(f['P'])
        tl = (i == 0) | (j == 0); br = ((i == nw - 1) | (j == nh - 1)) & ~tl
        if mat == 'mfd':
            C = np.tile(hexc('#2B2E31'), (N, 1))
            if f['face'] != self.screen_face.get(f['idx']):
                return C
            inner = (i >= 2) & (j >= 2) & (i < nw - 2) & (j < nh - 2)
            C[inner] = hexc('#14203A')
            # stepped diagonal and a cyan cross on the screen
            cx, cy = (nw - 1) / 2, (nh - 1) / 2
            C[inner & ((np.abs(i - cx) < 0.6) | (np.abs(j - cy) < 0.6))] = hexc('#3FB7C9')
            C[inner & (np.floor((i + j) / 2) == np.floor((nw + nh) / 4) + 1) & (j > cy)] = hexc('#2F6FA0')
            ring = ~inner & ((i == 0) | (j == 0) | (i == nw - 1) | (j == nh - 1))
            btn = ring & (((i + j) % 3) == 0)
            C[btn] = hexc('#CFCBB8')
            return C
        if mat == 'button':
            C = np.tile(hexc('#C9C6B4'), (N, 1))
            C[br] *= 0.7
            return C
        if mat == 'grip':
            C = np.tile(hexc('#1E1F21'), (N, 1))
            C[tl] = hexc('#3A3C3F')
            return C
        if mat == 'knob':
            return np.tile(hexc('#D8C64A'), (N, 1))
        if mat == 'hud':
            A = np.zeros((N, 4), np.float32)
            rim = tl | br
            A[rim, :3] = hexc('#2E3135'); A[rim, 3] = 1
            A[(~rim) & (np.abs(i - nw / 2) < 0.6), :3] = hexc('#7FE08A')
            A[(~rim) & (np.abs(i - nw / 2) < 0.6), 3] = 1
            return A
        if mat == 'pit_wall':
            C = np.tile(pal['tub'], (N, 1))
            C[tl] *= 1.15
            return C
        return None

    def paint(self):
        self.wheel_box = {}
        for g in TYRES | HUBS:
            Ps = np.concatenate([f['P'] for f in self.faces if f['last'] == g])
            lo, hi = Ps.min(0), Ps.max(0)
            self.wheel_box[g] = ((lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2, (hi[1] - lo[1]) / 2)
        # the face of each screen and the HUD that looks at the pilot (towards +z, the seat)
        eye = np.array([0, 58, -84.0])
        self.screen_face = {}
        best = {}
        for f in self.faces:
            if f['mat'] != 'mfd':
                continue
            to_eye = eye - f['P'].mean(0)
            s = float(np.dot(f['n'], to_eye / np.linalg.norm(to_eye)))
            if s > best.get(f['idx'], -2):
                best[f['idx']] = s; self.screen_face[f['idx']] = f['face']
        out = np.zeros((self.H, self.W, 4), np.float32)
        for f in self.faces:
            mat = f['mat']
            if mat == 'glass':
                out[f['Y'], f['X']] = np.clip(self.glass(f), 0, 1)
                continue
            if mat == 'hud':
                out[f['Y'], f['X']] = np.clip(self.cockpit(f, mat), 0, 1)
                continue
            if mat == 'skin': C = self.skin(f)
            elif mat in ('interior', 'seat'): C = self.interior(f, mat)
            elif mat in ('mfd', 'button', 'grip', 'knob', 'pit_wall'): C = self.cockpit(f, mat)
            elif mat == 'nozzle': C = self.nozzle(f)
            elif mat == 'hole': C = self.hole(f)
            elif mat in ('tyre', 'hub'): C = self.wheel(f, mat)
            elif mat == 'strut': C = self.strut(f)
            elif mat == 'tank': C = self.tank(f)
            else: raise SystemExit('no paint for ' + mat)
            if mat == 'skin':
                C = self.decals(C, f['P'], f['n'])
            out[f['Y'], f['X']] = np.c_[np.clip(C, 0, 1), np.ones(len(C), np.float32)]
        return Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGBA')


def config(mats):
    # low-vis: light bars and star in a mid-grey rim, no 1-px stripe (the wing cubes are swept 39 deg and a thin
    # stripe breaks up on their rotated texel grid)
    ins = us_insignia(9, MARK2, MARK, MARK)
    wins = us_insignia(13, MARK2, MARK, MARK)
    num = pixel_text('54', MARK, None, 2)
    code = pixel_text('YX', MARK, None, 2)
    serial = pixel_text('164142', MARK, None, 1)
    marines = pixel_text('MARINES', MARK, None, 1)
    decals = side_decal(num, 10, 41, -140, 3, 14, 30, 55, -155, -125) \
        + side_decal(shield(7, 8, UPPER * 0.92, MARK), 10, 45, -116, 3, 14, 36, 54, -122, -110) \
        + side_decal(ins, 14, 47, 78, 6, 22, 38, 58, 64, 92) \
        + tilted_fin_decal(code, 79, 134) + tilted_fin_decal(serial, 69.5, 131) \
        + side_decal(marines, 8, 58, 141, 3, 16, 52, 64, 120, 156) \
        + wing_decal(wins, -58, 0, 16, False) + wing_decal(wins, 58, 0, 16, True)
    fin = lambda P: ((np.abs(P[:, 0]) < 3) & (P[:, 1] > 66) & (P[:, 2] > 70)) \
        | ((np.abs(P[:, 0]) < 2.5) & (P[:, 1] < 44) & (P[:, 2] > 100))
    return dict(
        classify=lambda e, idx, last: mats[e['uuid']][0],
        scheme=lambda P, n, low, f: np.tile(LOWER if low else UPPER, (len(P), 1)),
        dark_k=0.8, light_k=1.13, rivet_k=0.88, slot=hexc('#2C3034'), edge_light=hexc('#7C838B'),
        stencil=hexc('#2E3237'), walkway=hexc('#33373C'), rivets=True,
        fin=fin, surfaces=('LFlap', 'RFlap', 'VBFlap'), vertical_surfaces=('VBFlap',),
        fus_half=22, fus_lines=[5, 12], wing_cuts=[40, 60, 80],
        stations=np.arange(-170.0, 190, 20.0), side_lines=[34, 46, 58],
        tub=(PIT[0], PIT[1], PIT[2] - 6, PIT[3], PIT[4]),
        interior=dict(US_COCKPIT, tub=hexc('#3A3D40'), sill=hexc('#2E3135')),
        stores=dict(), decals=decals)


if __name__ == '__main__':
    src, dst, png = sys.argv[1:4]
    m = bb.Model(src)
    mats = classify_all(m)
    rows = house.layout(m, density(mats), SIZE, name='av_8b')
    print('uv layout uses', rows, 'rows of', SIZE)
    m.res = (SIZE, SIZE)
    img = HarrierPainter(m, config(mats)).paint()
    img.save(png)
    m.set_texture(img)
    m.save(dst)
    print('painted', dst)
