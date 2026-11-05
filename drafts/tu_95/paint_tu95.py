"""Tu-95MS: lay out per-face UVs (cockpit 4 px/unit, glass 2, the rest 1) on a 2048 sheet and paint it in the
bundled aircraft's habits (house / house2). usage: python paint_tu95.py <in.bbmodel> <out.bbmodel> <sheet.png>"""
import sys, math
sys.path.insert(0, r'C:\Users\nagis\AppData\Local\Temp\claude\F--Java-AshVehicles\e1f8066c-7d80-4a8d-aa9b-2b42ad22a84a\scratchpad')
import numpy as np
import bb, house
from house import hexc, hsh, pixel_text, soviet_star
from house2 import Painter

SIZE = 2048
WHEELS = {'group60', 'group62', 'group45', 'group50', 'group55', 'group57', 'group59', 'group64', 'group41', 'group44'}
STRUTS = {'group40', 'group43', 'group53', 'group58'}
BLADES = {'group11', 'group12', 'group15', 'group16', 'group20', 'group21', 'group23', 'group24',
          'group74', 'group75', 'group77', 'group78', 'group80', 'group81', 'group83', 'group84'}
SPINNERS = {'LFPropeller1', 'LFPropeller2', 'LRPropeller1', 'LRPropeller2', 'RFPropeller1', 'RFPropeller2',
            'RRPropeller1', 'RRPropeller2', 'group10'}
NACELLES = {'group6': 106.66, 'group7': -106.66, 'group8': -206.8, 'group9': 206.8}
WINGS = {'group3', 'group4', 'group17', 'group5', 'group66', 'VBFlap'}
COCKPIT = (21.5, 66, 99, -316.5, -269.5)          # |x|, y0, y1, z0, z1 of the flight deck
PROP_AXIS_Y = 71.0

UPPER, LOWER = hexc('#ABB0B4'), hexc('#B7BBBE')
METAL, METAL2 = hexc('#8D9398'), hexc('#7B8186')
RADOME = hexc('#DADDDA')
RED, WHITE = hexc('#C8202A'), hexc('#F2F2EE')


def classify_all(m):
    mats = {}
    x, y0, y1, z0, z1 = COCKPIT
    for idx, e in enumerate(m.elements):
        ch = [g['name'] for g in m.chain(e)]
        last = ch[-1] if ch else ''
        col = e.get('color', 0)
        c = m.to_world(e, np.array([e['from'], e['to']], float)).mean(0)
        in_pit = abs(c[0]) < x and y0 < c[1] < y1 and z0 < c[2] < z1
        if last == 'group67': mat = 'glass'
        elif last == 'group68': mat = 'interior' if col == 0 else 'dial'
        elif last in ('group69', 'group73', 'group2', 'group72'): mat = 'interior'
        elif last in ('group70', 'group71'): mat = 'seat'
        elif last in WHEELS: mat = 'wheel'
        elif last in STRUTS: mat = 'strut'
        elif 'GearHatch' in last: mat = 'door'
        elif last in BLADES: mat = 'blade'
        elif last in SPINNERS: mat = 'spinner'
        elif last in NACELLES: mat = 'nacelle'
        elif last == 'main': mat = 'probe' if col == 0 else 'antenna'
        elif last in ('group', 'group17') and in_pit and (col == 2 or idx in (218, 597)): mat = 'interior'
        elif last == 'group' and col == 7: mat = 'gun'
        else: mat = 'skin'
        mats[e['uuid']] = (mat, last)
    return mats


def density(mats):
    def k(e, f):
        mat = mats[e['uuid']][0]
        return 4 if mat in ('interior', 'dial', 'seat') else 2 if mat == 'glass' else 1
    return k


def lead_edge(occ, axis_len):
    """per column of a planform map, the first occupied cell along the second axis (front edge), -1 when empty."""
    has = occ.any(1)
    first = np.where(has, occ.argmax(1), -1)
    return first


class TuPainter(Painter):
    GX0, GZ0, GNX, GNZ, GY0, GNY = -440, -410, 880, 860, -20, 250

    def build_maps(self):
        super().build_maps()
        # the bare-metal leading edges: the front of the wing, the tailplane and the fin
        wing = lambda f: f['mat'] == 'skin' and f['last'] in WINGS
        occ_w = np.zeros((self.GNX, self.GNZ), bool); occ_t = occ_w.copy(); occ_f = np.zeros((self.GNY, self.GNZ), bool)
        for f in self.faces:
            if not wing(f):
                continue
            P = f['P']
            if f['last'] == 'group5' or f['last'] == 'VBFlap' or f['last'] == 'group66':
                a, b = self.grid(P, 'zy')
                ok = (a >= 0) & (a < self.GNZ) & (b >= 0) & (b < self.GNY)
                occ_f[b[ok], a[ok]] = True
                continue
            a, b = self.grid(P, 'xz')
            ok = (a >= 0) & (a < self.GNX) & (b >= 0) & (b < self.GNZ)
            tgt = occ_t if f['last'] == 'group17' else occ_w
            tgt[a[ok], b[ok]] = True
        self.LEAD_W = lead_edge(occ_w, self.GNZ)
        self.LEAD_T = lead_edge(occ_t, self.GNZ)
        self.LEAD_F = lead_edge(occ_f, self.GNZ)

    def lead_band(self, f, width=6):
        P = f['P']
        if f['last'] in ('group5', 'group66', 'VBFlap'):
            yi = np.clip(np.floor(P[:, 1]).astype(int) - self.GY0, 0, self.GNY - 1)
            lead = self.LEAD_F[yi]
        else:
            xi = np.clip(np.floor(P[:, 0]).astype(int) - self.GX0, 0, self.GNX - 1)
            lead = (self.LEAD_T if f['last'] == 'group17' else self.LEAD_W)[xi]
        dz = np.floor(P[:, 2]) - self.GZ0 - lead
        return (lead >= 0) & (dz >= 0) & (dz < width)

    def look(self, dmap, P, axes):
        if self._fus and axes == 'xz':          # the round fuselage takes no outline lines from the planform
            return np.full(len(P), 10, np.int16)
        return super().look(dmap, P, axes)

    _fus = False

    def skin(self, f):
        self._fus = f['last'] == 'group'
        C = super().skin(f)
        self._fus = False
        P, n = f['P'], f['n']
        if f['last'] in WINGS and f['last'] != 'VBFlap':
            band = self.lead_band(f)
            if f['last'] == 'group17':
                band &= np.abs(P[:, 0]) > 8
            if f['last'] in ('group3', 'group4'):
                band &= np.abs(P[:, 0]) > 26
            C[band] = METAL
            edge = band & ~self.lead_band(f, 5)
            C[edge] = METAL2
        if f['last'] == 'group':
            nose = P[:, 2] < -342
            C[nose] = RADOME
            C[nose & (np.floor(P[:, 2]) == -343)] = RADOME * 0.78
            # the flight deck seen through the glazing: the shell's inner faces
            x, y0, y1, z0, z1 = COCKPIT
            c = P.mean(0)
            if z0 - 4 < c[2] < z1 + 2 and abs(c[0]) < 26:
                inward = n[0] * c[0] + n[1] * (c[1] - 73) < -0.3 * math.hypot(c[0], c[1] - 73)
                if inward:
                    C[:] = self.cfg['interior']['wall']
                    C[(f['i'] == 0) | (f['j'] == 0)] *= 1.15
        return C

    def nacelle(self, f):
        """bare-metal cowlings forward, painted aft, rings of panel lines with a light twin and rivets."""
        P, n = f['P'], f['n']
        cx = NACELLES[f['last']]
        z = P[:, 2]
        z0 = {'group6': -180, 'group7': -180, 'group8': -123, 'group9': -123}[f['last']]
        low = n[1] < -0.35
        C = np.tile(LOWER if low else UPPER, (len(P), 1))
        cowl = z < z0 + 66
        C[cowl] = hexc('#9CA2A7')
        C[z < z0 + 4] = METAL2
        rel = np.floor(z - z0)
        ring = ((rel % 16) == 0) & (rel > 0)
        C[ring] *= 0.8
        C[((rel % 16) == 1) & (rel > 0)] *= 1.08
        th = np.degrees(np.arctan2(P[:, 1] - PROP_AXIS_Y, P[:, 0] - cx))
        lon = (np.floor(th) % 45) < 1.6
        C[lon & cowl] *= 0.84
        C[((rel % 16) == 3) & (np.floor(P[:, 0] + P[:, 1]) % 3 == 0) & (rel > 0)] *= 0.88
        # soot from the exhausts, on the outboard flank behind the cowling
        out = np.sign(cx) * (P[:, 0] - cx) > 6
        soot = out & (z > z0 + 66) & (z < z0 + 110) & (P[:, 1] > 64) & (P[:, 1] < 76)
        C[soot] = hexc('#7A7E82')
        C[soot & (np.floor(z) % 4 == 0)] = hexc('#686C70')
        return C

    def blade(self, f):
        P = f['P']
        cx = 106.7 if abs(abs(P[:, 0].mean()) - 106.7) < 50 else 206.8
        r = np.hypot(np.abs(P[:, 0]) - cx, P[:, 1] - PROP_AXIS_Y)
        C = np.tile(hexc('#34373B'), (len(P), 1))
        C[(f['i'] == 0) | (f['j'] == 0)] *= 1.25
        C[r > 37.5] = hexc('#E3C12C')
        C[(r > 35.5) & (r <= 37.5)] = hexc('#34373B') * 0.85
        return C

    def spinner(self, f):
        P = f['P']
        C = np.tile(hexc('#C7CBCE'), (len(P), 1))
        C[(f['i'] == 0) | (f['j'] == 0)] *= 1.06
        C[(f['i'] == f['nw'] - 1) | (f['j'] == f['nh'] - 1)] *= 0.82
        return C

    def wheel(self, f):
        P, n = f['P'], f['n']
        e = f['e']
        Q = self.wheel_box[f['last']]
        cy, cz, R = Q
        C = np.tile(hexc('#262728'), (len(P), 1))
        if abs(n[0]) > 0.5:
            r = np.hypot(P[:, 1] - cy, P[:, 2] - cz)
            C[r < R * 0.55] = hexc('#5E7C48')
            C[np.abs(r - R * 0.55) < 0.6] = hexc('#3E5230')
            C[r < 1.5] = hexc('#BFC3C4')
        else:
            C[(f['j'] % 2) == 0] *= 0.78
        return C

    def strut(self, f):
        C = np.tile(hexc('#BFC3C4'), (len(f['P']), 1))
        C[(f['i'] == 0) | (f['j'] == 0)] *= 1.06
        C[(f['i'] == f['nw'] - 1) | (f['j'] == f['nh'] - 1)] *= 0.76
        return C

    def door(self, f):
        C = np.tile(LOWER, (len(f['P']), 1))
        if abs(f['n'][0]) > 0.5 and min(f['nw'], f['nh']) > 3:
            rim = (f['i'] == 1) | (f['j'] == 1) | (f['i'] == f['nw'] - 2) | (f['j'] == f['nh'] - 2)
            C[rim] *= 0.82
        return C

    def flat(self, f, col, k=0.8):
        C = np.tile(hexc(col), (len(f['P']), 1))
        C[(f['i'] == f['nw'] - 1) | (f['j'] == f['nh'] - 1)] *= k
        return C

    def glass(self, f):
        """clear panes in grey frames: a frame round each pane's outward face, nothing else drawn on the glass."""
        A = np.zeros((len(f['P']), 4), np.float32)
        i, j, nw, nh = f['i'], f['j'], f['nw'], f['nh']
        if min(nw, nh) <= 3:
            A[:, :3] = hexc('#8F959A'); A[:, 3] = 1
            return A
        rim = (i == 0) | (j == 0) | (i == nw - 1) | (j == nh - 1)
        A[rim, :3] = hexc('#8F959A'); A[rim, 3] = 1
        P = f['P']
        s = (P[:, 2] * 0.8 + np.abs(P[:, 0]) * 1.2 + P[:, 1]) % 13
        streak = (s < 0.6) & ~rim
        A[streak, :3] = hexc('#E9F4FA'); A[streak, 3] = 1
        return A

    def paint(self):
        from PIL import Image
        self.wheel_box = {}
        for g in WHEELS:
            Ps = np.concatenate([f['P'] for f in self.faces if f['last'] == g])
            lo, hi = Ps.min(0), Ps.max(0)
            self.wheel_box[g] = ((lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2, (hi[1] - lo[1]) / 2)
        out = np.zeros((self.H, self.W, 4), np.float32)
        for f in self.faces:
            mat = f['mat']
            if mat == 'glass':
                out[f['Y'], f['X']] = np.clip(self.glass(f), 0, 1)
                continue
            if mat == 'skin': C = self.skin(f)
            elif mat in ('interior', 'seat', 'dial'): C = self.interior(f, mat)
            elif mat == 'nacelle': C = self.nacelle(f)
            elif mat == 'blade': C = self.blade(f)
            elif mat == 'spinner': C = self.spinner(f)
            elif mat == 'wheel': C = self.wheel(f)
            elif mat == 'strut': C = self.strut(f)
            elif mat == 'door': C = self.door(f)
            elif mat == 'gun': C = self.flat(f, '#2F3236')
            elif mat == 'antenna': C = self.flat(f, '#6F757A')
            else: C = self.store(f, mat)
            if mat in ('skin', 'nacelle'):
                C = self.decals(C, f['P'], f['n'])
            out[f['Y'], f['X']] = np.c_[np.clip(C, 0, 1), np.ones(len(C), np.float32)]
        return Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGBA')


def fin_decal(img, y, z):
    out = []
    for s in (1, -1):
        out.append((img, (0, y, z), (0, 0, -1) if s > 0 else (0, 0, 1), (0, 1, 0),
                    (lambda s: lambda n: n[0] * s > 0.7)(s), lambda P: (np.abs(P[:, 0]) <= 8) & (P[:, 1] > 100)))
    return out


def wing_decal(img, x, z, below):
    region = (lambda s: lambda P: (np.sign(P[:, 0]) == s) & (np.abs(P[:, 0]) > 60) & (P[:, 2] < 250))(np.sign(x))
    nok = (lambda n: n[1] < -0.8) if below else (lambda n: n[1] > 0.8)
    return [(img, (x, 0, z), (1, 0, 0), (0, 0, -1), nok, region)]


def config(mats):
    star = soviet_star(33, RED, WHITE)
    wstar = soviet_star(31, RED, WHITE)
    reg = pixel_text('RF-94127', hexc('#3E4246'), None, 1)
    decals = fin_decal(star, 170, 356) + fin_decal(reg, 128, 322)
    for x in (-352, 352):
        for below in (False, True):
            decals += wing_decal(wstar, x, WING_Z[x], below)
    return dict(
        classify=lambda e, idx, last: mats[e['uuid']][0],
        scheme=lambda P, n, low, f: np.tile(LOWER if low else UPPER, (len(P), 1)),
        dark_k=0.82, light_k=1.08, rivet_k=0.9, slot=hexc('#3A3E42'), edge_light=hexc('#D3D7DA'),
        stencil=hexc('#D9C24A'), walkway=hexc('#B3262E'), rivets=True,
        fin=lambda P: (np.abs(P[:, 0]) < 8) & (P[:, 1] > 100) & (P[:, 2] > 200),
        surfaces=('VBFlap',), vertical_surfaces=('VBFlap',),
        fus_half=25, fus_lines=[7, 15], wing_cuts=[60, 150, 250, 300, 345, 390],
        stations=np.arange(-364.0, 430, 24.0), side_lines=[56, 64, 86, 94],
        tub=(0, 0, 0, 0, 0),
        interior=dict(tub=hexc('#000000'), sill=hexc('#000000'), wall=hexc('#5E8C86'),
                      panel=hexc('#3A9E96'), panel_dark=hexc('#26403E'), dial=hexc('#151819'), dial_ring=hexc('#C9D2CF'),
                      switch=hexc('#E0C24A'), seat=hexc('#5A4A3A'), seat2=hexc('#4E4032'), seat_rim=hexc('#2F2822'),
                      seat_frame=hexc('#4A5052')),
        stores=dict(), decals=decals)


WING_Z = {}

if __name__ == '__main__':
    src, dst, png = sys.argv[1:4]
    m = bb.Model(src)
    mats = classify_all(m)
    house.layout(m, density(mats), SIZE, name='tu_95')
    m.res = (SIZE, SIZE)
    # the wing chord's middle at the star stations, from the geometry
    for x in (-352, 352):
        zs = []
        for e in m.elements:
            if mats[e['uuid']][1] in ('group3', 'group4'):
                P = m.to_world(e, bb.face_point(e, 'up', *np.meshgrid(np.linspace(0, 1, 60), np.linspace(0, 1, 60))).reshape(-1, 3))
                zs.append(P[np.abs(P[:, 0] - x) < 2, 2])
        z = np.concatenate(zs)
        WING_Z[x] = float((z.min() + z.max()) / 2)
    print('wing star z', WING_Z)
    img = TuPainter(m, config(mats)).paint()
    img.save(png)
    m.set_texture(img)
    m.save(dst)
    print('painted', dst)
