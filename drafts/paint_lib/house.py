"""The house style of the bundled aircraft, as a library: UV layout for untextured models, and a painter
driven by a per-aircraft config (which part is what, colours, markings)."""
import sys, math, copy, uuid
sys.path.insert(0, r'C:\Users\nagis\AppData\Local\Temp\claude\F--Java-AshVehicles\e1f8066c-7d80-4a8d-aa9b-2b42ad22a84a\scratchpad')
import numpy as np
from PIL import Image, ImageDraw
import bb


def hexc(h):
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], np.float32) / 255


def hsh(*xs, seed=0):
    v = np.int64(seed * 2654435761 % (1 << 32))
    for k, x in enumerate(xs):
        x = np.asarray(x).astype(np.int64)
        v = (v ^ ((x * (73856093, 19349663, 83492791, 2971215073)[k % 4]) & 0xffffffff)) & 0xffffffff
        v = ((v ^ (v >> 13)) * 1274126177) & 0xffffffff
    v = v ^ (v >> 16)
    return (v & 0xffffff).astype(np.float64) / float(1 << 24)


class Noise3:
    def __init__(self, seed, n=64):
        self.g = np.random.default_rng(seed).random((n, n, n)).astype(np.float32); self.n = n

    def __call__(self, P):
        n = self.n; f = np.floor(P); t = P - f; t = t * t * (3 - 2 * t)
        i = f.astype(int) % n; j = (i + 1) % n; g = self.g
        def L(a, b, w): return a + (b - a) * w
        x0, y0, z0 = i.T; x1, y1, z1 = j.T; tx, ty, tz = t.T
        c00 = L(g[x0, y0, z0], g[x1, y0, z0], tx); c10 = L(g[x0, y1, z0], g[x1, y1, z0], tx)
        c01 = L(g[x0, y0, z1], g[x1, y0, z1], tx); c11 = L(g[x0, y1, z1], g[x1, y1, z1], tx)
        return L(L(c00, c10, ty), L(c01, c11, ty), tz)


def erode(a):
    b = a.copy()
    b[1:, :] &= a[:-1, :]; b[:-1, :] &= a[1:, :]; b[:, 1:] &= a[:, :-1]; b[:, :-1] &= a[:, 1:]
    return b


def dilate(a):
    b = a.copy()
    b[1:, :] |= a[:-1, :]; b[:-1, :] |= a[1:, :]; b[:, 1:] |= a[:, :-1]; b[:, :-1] |= a[:, 1:]
    return b


def distance(occ, steps=6):
    occ = erode(dilate(occ))
    d = np.full(occ.shape, steps, np.int16); d[~occ] = -1
    cur = occ
    for k in range(steps):
        nxt = erode(cur); d[cur & ~nxt] = k; cur = nxt
    return d


# ---------------------------------------------------------------------------------------
# UV layout for a model that has none: every face its own rectangle, density by material
# ---------------------------------------------------------------------------------------
TEXTURE_TEMPLATE = {
    'name': 'texture', 'path': '', 'folder': 'block', 'namespace': '', 'id': '0', 'group': '', 'width': 1024,
    'height': 1024, 'uv_width': 1024, 'uv_height': 1024, 'particle': False, 'use_as_default': False,
    'layers_enabled': False, 'sync_to_project': '', 'render_mode': 'default', 'render_sides': 'auto',
    'pbr_channel': 'color', 'frame_time': 1, 'frame_order_type': 'loop', 'frame_order': '', 'frame_interpolate': False,
    'visible': True, 'internal': True, 'saved': False}


def layout(m, density, size=1024, name='texture'):
    """density(e, face) -> pixels per unit. Rewrites every face's uv and points it at texture 0."""
    d = m.d
    d['meta']['box_uv'] = False
    d['resolution'] = {'width': size, 'height': size}
    todo = []
    for e in m.elements:
        e['box_uv'] = False
        e['autouv'] = 0
        e.pop('uv_offset', None)
        for f in e['faces']:
            k = density(e, f)
            w, h = bb.face_axes(e, f)
            todo.append((e, f, max(1, round(w * k)), max(1, round(h * k))))
    todo.sort(key=lambda t: (-t[3], -t[2]))
    x = y = shelf = 0
    for e, f, w, h in todo:
        if x + w > size:
            x, y, shelf = 0, y + shelf + 1, 0
        if y + h > size:
            raise SystemExit('does not fit in %d' % size)
        e['faces'][f] = {'uv': [x, y, x + w, y + h], 'texture': 0}
        x += w + 1
        shelf = max(shelf, h)
    tex = copy.deepcopy(TEXTURE_TEMPLATE)
    tex.update(name=name, width=size, height=size, uv_width=size, uv_height=size, uuid=str(uuid.uuid4()))
    d['textures'] = [tex]
    return y + shelf


# ---------------------------------------------------------------------------------------
# pixel art: font, insignia
# ---------------------------------------------------------------------------------------
FONT = {
    '0': ['111', '101', '101', '101', '111'], '1': ['010', '110', '010', '010', '111'],
    '2': ['111', '001', '111', '100', '111'], '3': ['111', '001', '011', '001', '111'],
    '4': ['101', '101', '111', '001', '001'], '5': ['111', '100', '111', '001', '111'],
    '6': ['111', '100', '111', '101', '111'], '7': ['111', '001', '010', '010', '010'],
    '8': ['111', '101', '111', '101', '111'], '9': ['111', '101', '111', '001', '111'],
    'A': ['010', '101', '111', '101', '101'], 'F': ['111', '100', '110', '100', '100'],
    'M': ['101', '111', '111', '101', '101'], 'S': ['111', '100', '111', '001', '111'],
    'U': ['101', '101', '101', '101', '111'], 'R': ['110', '101', '110', '101', '101'],
    'P': ['110', '101', '110', '100', '100'], '-': ['000', '000', '111', '000', '000'],
    'H': ['101', '101', '111', '101', '101'], 'O': ['111', '101', '101', '101', '111'],
    'L': ['100', '100', '100', '100', '111'],
}


def pixel_text(txt, fill, edge=None, cell=2):
    w = len(txt) * 4 - 1
    bm = np.zeros((5, w), bool)
    for k, ch in enumerate(txt):
        bm[:, k * 4:k * 4 + 3] = np.array([[c == '1' for c in row] for row in FONT[ch]])
    bm = np.kron(bm, np.ones((cell, cell), bool))
    pad = np.pad(bm, 1)
    out = np.zeros(pad.shape + (4,), np.float32)
    if edge is not None:
        grown = dilate(pad) | np.roll(np.roll(pad, 1, 0), 1, 1) | np.roll(np.roll(pad, -1, 0), -1, 1) \
            | np.roll(np.roll(pad, 1, 0), -1, 1) | np.roll(np.roll(pad, -1, 0), 1, 1)
        out[grown] = np.r_[edge, 1]
    out[pad] = np.r_[fill, 1]
    return out


def _rgba(c):
    return tuple(int(v * 255) for v in c) + (255,)


def _star(c, R, inner=0.382):
    return [(c[0] + (R if k % 2 == 0 else R * inner) * math.cos(-math.pi / 2 + k * math.pi / 5),
             c[1] + (R if k % 2 == 0 else R * inner) * math.sin(-math.pi / 2 + k * math.pi / 5)) for k in range(10)]


def _pixelate(draw_fn, w, h, s=8):
    big = Image.new('RGBA', (w * s, h * s), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(big), s)
    small = big.resize((w, h), Image.NEAREST)
    return np.asarray(small, np.float32) / 255


def soviet_star(size, red, white):
    """red star, white band, thin red rim, two-tone facets."""
    def draw(d, s):
        c = (size * s / 2, size * s / 2); R = size * s * 0.5
        d.polygon(_star(c, R), fill=_rgba(red))
        d.polygon(_star(c, R * 0.84), fill=_rgba(white))
        p = _star(c, R * 0.68)
        for k in range(10):
            d.polygon([c, p[k], p[(k + 1) % 10]], fill=_rgba(red if k % 2 == 0 else red * 0.72))
    return _pixelate(draw, size, size)


def us_insignia(h, blue, white, red):
    """star and bar: a white star on a blue disc, white bars with a red stripe, blue outline."""
    w = int(h * 2.3)
    def draw(d, s):
        cx, cy, r = w * s / 2, h * s / 2, h * s / 2 - s * 0.2
        bh = h * s * 0.42
        d.rectangle((s * 0.2, cy - bh / 2 - s, w * s - s * 0.2, cy + bh / 2 + s), fill=_rgba(blue))
        d.rectangle((s * 1.2, cy - bh / 2, w * s - s * 1.2, cy + bh / 2), fill=_rgba(white))
        d.rectangle((s * 1.2, cy - bh / 6, w * s - s * 1.2, cy + bh / 6), fill=_rgba(red))
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=_rgba(blue))
        d.polygon(_star((cx, cy), r * 0.92), fill=_rgba(white))
    return _pixelate(draw, w, h)


def ring_emblem(size, colour):
    def draw(d, s):
        d.ellipse((0, 0, size * s - 1, size * s - 1), outline=_rgba(colour), width=2 * s)
    return _pixelate(draw, size, size)


# ---------------------------------------------------------------------------------------
# the painter
# ---------------------------------------------------------------------------------------
N_CAMO = Noise3(11)
N_CAMO2 = Noise3(23)


class Painter:
    def __init__(self, model, cfg):
        self.m, self.cfg = model, cfg
        self.W, self.H = model.res
        self.faces = []
        for idx, e in enumerate(model.elements):
            chain = [g['name'] for g in model.chain(e)]
            last = chain[-1] if chain else ''
            mat = cfg['classify'](e, idx, last)
            for face, fd in e['faces'].items():
                u1, v1, u2, v2 = fd['uv']
                if u1 == u2 or v1 == v2:
                    continue
                xs = np.arange(int(math.floor(min(u1, u2))), int(math.ceil(max(u1, u2))))
                ys = np.arange(int(math.floor(min(v1, v2))), int(math.ceil(max(v1, v2))))
                xs = xs[(xs >= 0) & (xs < self.W)]; ys = ys[(ys >= 0) & (ys < self.H)]
                if len(xs) == 0 or len(ys) == 0:
                    continue
                X, Y = np.meshgrid(xs, ys)
                t = np.clip((X.ravel() + 0.5 - u1) / (u2 - u1), 0, 1)
                s = np.clip((Y.ravel() + 0.5 - v1) / (v2 - v1), 0, 1)
                P = model.to_world(e, bb.face_point(e, face, t, s))
                n = model.to_world_dir(e, np.array(bb.NORMALS[face], float))
                self.faces.append(dict(e=e, idx=idx, face=face, X=X.ravel(), Y=Y.ravel(), P=P, n=n, last=last,
                                       mat=mat, i=X.ravel() - xs[0], j=Y.ravel() - ys[0], nw=len(xs), nh=len(ys)))
        self.screen = {}
        for f in self.faces:
            k = f['idx']
            if f['mat'] in ('dial', 'interior') and (k not in self.screen or f['n'][2] > self.screen[k][1]):
                self.screen[k] = (f['face'], f['n'][2])
        self.build_maps()

    # -- planform maps ----------------------------------------------------------------
    GX0, GZ0, GNX, GNZ, GY0, GNY = -200, -260, 400, 520, -40, 220

    def grid(self, P, axes):
        if axes == 'xz':
            return np.floor(P[:, 0]).astype(int) - self.GX0, np.floor(P[:, 2]).astype(int) - self.GZ0
        return np.floor(P[:, 2]).astype(int) - self.GZ0, np.floor(P[:, 1]).astype(int) - self.GY0

    def plan(self, sel, axes):
        shape = (self.GNX, self.GNZ) if axes == 'xz' else (self.GNZ, self.GNY)
        occ = np.zeros(shape, bool)
        for f in self.faces:
            if sel(f):
                a, b = self.grid(f['P'], axes)
                ok = (a >= 0) & (a < shape[0]) & (b >= 0) & (b < shape[1])
                occ[a[ok], b[ok]] = True
        return distance(occ)

    def look(self, dmap, P, axes):
        a, b = self.grid(P, axes)
        return dmap[np.clip(a, 0, dmap.shape[0] - 1), np.clip(b, 0, dmap.shape[1] - 1)]

    def build_maps(self):
        skinny = lambda f: f['mat'] == 'skin'
        fin = self.cfg['fin']
        self.TOP = self.plan(lambda f: skinny(f) and f['n'][1] > 0.5, 'xz')
        self.BOTTOM = self.plan(lambda f: skinny(f) and f['n'][1] < -0.5, 'xz')
        self.SIDE = self.plan(lambda f: skinny(f) and abs(f['n'][0]) > 0.5 and fin(f['P']).mean() > 0.5, 'zy')
        self.GLASS_TOP = self.plan(lambda f: f['mat'] == 'glass' and f['n'][1] > 0.2, 'xz')
        self.SURF = {}
        for g in self.cfg.get('surfaces', ()):
            vertical = self.cfg.get('vertical_surfaces', ())
            if g in vertical:
                self.SURF[g] = (self.plan(lambda f, g=g: f['last'] == g and abs(f['n'][0]) > 0.5, 'zy'), 'zy')
            else:
                self.SURF[g] = (self.plan(lambda f, g=g: f['last'] == g and abs(f['n'][1]) > 0.5, 'xz'), 'xz')

    # -- panel layout -----------------------------------------------------------------
    def panels(self, C, P, n, top_surface, tint_amp=0.035, odd_dark=0.0, walkways=True):
        cfg = self.cfg
        horizontal = abs(n[1]) >= 0.5
        across = np.abs(P[:, 0]) if horizontal else P[:, 1]
        cuts = cfg['bands_plan'] if horizontal else cfg['bands_side']
        st = cfg['stations']

        def cell(v, c):
            k = np.clip(np.searchsorted(c, v, side='right') - 1, 0, len(c) - 2)
            return k, v - c[k], c[k + 1] - c[k]
        si, la, wa = cell(P[:, 2], st)
        bi, lb, wb = cell(across, cuts)
        orient = 0 if horizontal and n[1] > 0 else 1 if horizontal else 2
        keep_s = hsh(si, bi, orient, seed=21) > 0.18
        keep_b = hsh(si, bi, orient, seed=22) > 0.12
        line = ((la < 1) & keep_s) | ((lb < 1) & keep_b)
        lit = (((la >= 1) & (la < 2)) & keep_s) | (((lb >= 1) & (lb < 2)) & keep_b)
        tint = 1 + tint_amp * (hsh(si, bi, orient, seed=23) - 0.5) * 2
        if odd_dark:
            tint = tint * np.where(hsh(si, bi, orient, seed=29) < odd_dark, 0.9, 1.0)
        C = C * tint[:, None]
        r = hsh(si, bi, orient, seed=24)
        fa, fb = np.floor(la), np.floor(lb)
        big = (wa >= 12) & (wb >= 8)
        pw = np.minimum(4 + np.floor(hsh(si, bi, orient, seed=25) * 6), wa - 5)
        ph = np.minimum(3 + np.floor(hsh(si, bi, orient, seed=26) * 4), wb - 4)
        pa = 2 + np.floor(hsh(si, bi, orient, seed=27) * np.maximum(1, wa - pw - 3))
        pb = 2 + np.floor(hsh(si, bi, orient, seed=28) * np.maximum(1, wb - ph - 3))
        inside = (fa >= pa) & (fa < pa + pw) & (fb >= pb) & (fb < pb + ph) & big
        rim = inside & ((fa == pa) | (fa == pa + pw - 1) | (fb == pb) | (fb == pb + ph - 1))
        hatch = (r < 0.24) & rim
        C[hatch & ((fa == pa) | (fb == pb))] *= 1.16
        C[hatch & ~((fa == pa) | (fb == pb))] *= 0.80
        vent = (r >= 0.24) & (r < 0.31) & inside
        C[vent] = C[vent] * 0.62
        C[vent & ~rim & (fb % 2 == 1)] *= 1.45
        da, db = fa - pa - 1, fb - pb - 1
        plus = (r >= 0.31) & (r < 0.37) & big & (((da == 1) & (db >= 0) & (db <= 2)) | ((db == 1) & (da >= 0) & (da <= 2)))
        cross = (r >= 0.37) & (r < 0.42) & big & (da >= 0) & (da <= 2) & ((da == db) | (da == 2 - db))
        dot = (r >= 0.42) & (r < 0.47) & big & (da >= 0) & (da <= 1) & (db >= 0) & (db <= 1)
        C[plus | cross] = C[plus | cross] * 1.22
        C[dot] *= 0.6
        ring = (r >= 0.47) & (r < 0.51) & big & (np.abs(np.hypot(da - 2, db - 2) - 2) < 0.6)
        C[ring] *= 1.18
        stencil = (r >= 0.51) & (r < 0.56) & big & (fb == pb) & (fa >= pa) & (fa < pa + 3)
        C[stencil] = cfg['stencil']
        if top_surface and walkways:
            wk = (r >= 0.56) & (r < 0.66) & (wa >= 14) & (wb >= 10)
            edge = ((fa == 2) | (fa == np.floor(wa) - 3)) & (fb >= 2) & (fb <= np.floor(wb) - 3)
            edge |= ((fb == 2) | (fb == np.floor(wb) - 3)) & (fa >= 2) & (fa <= np.floor(wa) - 3)
            dash = ((fa + fb) % 3) != 2
            corner = (fa >= 4) & (fa <= 6) & (fb >= 4) & (fb <= 6) & (((fa - 4) == (fb - 4)) | ((fa - 4) == 2 - (fb - 4)))
            C[wk & edge & dash] = cfg['walkway']
            C[wk & corner] = cfg['walkway']
        C[line] *= 0.80
        C[lit & ~line] *= 1.07
        return C

    def edge_bands(self, C, f):
        P, n = f['P'], f['n']
        if abs(n[1]) >= 0.5:
            d = self.look(self.TOP if n[1] > 0 else self.BOTTOM, P, 'xz')
        elif abs(n[0]) >= 0.5 and self.cfg['fin'](P).mean() > 0.5:
            d = self.look(self.SIDE, P, 'zy')
        else:
            return C
        light = self.cfg['edge_light']
        C[d == 0] = C[d == 0] * 0.55 + light * 0.45
        C[d == 3] *= 0.80
        if f['last'] in self.SURF:
            dm, ax = self.SURF[f['last']]
            sd = self.look(dm, P, ax)
            C[(sd == 0) & (d > 0)] *= 0.62
        return C

    # -- materials --------------------------------------------------------------------
    def skin(self, f):
        cfg = self.cfg; P, n = f['P'], f['n']
        lower = bool(n[1] < -0.35)
        C = cfg['scheme'](P, n, lower)
        C = self.panels(C, P, n, top_surface=(n[1] > 0.5), tint_amp=cfg.get('tint_amp', 0.035),
                        odd_dark=cfg.get('odd_dark', 0.0), walkways=cfg.get('walkways', True))
        burn = cfg.get('burn')
        if burn:
            z0, z1, ymax, xmax = burn
            sel = (P[:, 2] > z0) & (P[:, 1] < ymax) & (np.abs(P[:, 0]) < xmax)   # the fuselage only, not the tailplanes
            if sel.any():
                k = np.floor(np.clip((P[sel, 2] - z0) / (z1 - z0), 0, 0.999) * 4)
                metal = cfg['burnt'][np.minimum(k.astype(int), 3)]
                C[sel] = metal * (1 + 0.04 * (hsh(np.floor(P[sel, 2] / 6), np.floor(P[sel, 1] / 5), seed=40) - 0.5))[:, None]
                ring = (np.floor(P[sel, 2]) % 9) == 0
                idx = np.flatnonzero(sel)[ring]
                C[idx] *= 0.78
        C = self.edge_bands(C, f)
        x, y0, y1, z0, z1 = cfg['tub']
        tub = (np.abs(P[:, 0]) < x) & (P[:, 1] > y0) & (P[:, 1] < y1) & (P[:, 2] > z0) & (P[:, 2] < z1) \
            & ((n[0] * np.sign(P[:, 0]) < -0.3) | (n[1] > 0.5))
        pal = cfg['interior']
        C[tub] = pal['tub']
        C[tub & (n[1] > 0.5) & (P[:, 1] > y1 - 6)] = pal['sill']
        return C

    def glass(self, f):
        P, n = f['P'], f['n']
        A = np.zeros((len(P), 4), np.float32)
        thin = min(f['nw'], f['nh']) <= 3
        if thin:
            A[:, :3] = self.cfg['frame_glass']; A[:, 3] = 1
            return A
        if n[1] > 0.2:
            d = self.look(self.GLASS_TOP, P, 'xz')
            A[d == 0, :3] = self.cfg['frame_glass']; A[d == 0, 3] = 1
        s = (P[:, 2] * 0.8 + np.abs(P[:, 0]) * 1.2 + P[:, 1]) % 17
        seg = (P[:, 2] % 11) < 4.5
        streak = (s < 0.8) & seg
        A[streak, :3] = hexc('#E9F4FA'); A[streak, 3] = 1
        return A

    def frame(self, f):
        C = self.cfg['scheme'](f['P'], f['n'], False) * 0.85
        i, j = f['i'], f['j']
        C[(i == 0) | (j == 0)] = self.cfg['frame_glass']
        C[(i == f['nw'] - 1) | (j == f['nh'] - 1)] *= 0.75
        return C

    def interior(self, f, kind):
        pal = self.cfg['interior']
        P, n = f['P'], f['n']
        N = len(P); i, j, nw, nh = f['i'], f['j'], f['nw'], f['nh']
        tl = (i == 0) | (j == 0); br = ((i == nw - 1) | (j == nh - 1)) & ~tl
        rim = tl | br

        def bev(col):
            C = np.tile(col, (N, 1))
            if min(nw, nh) >= 3:
                C[tl] = col * 1.25; C[br] = col * 0.72
            return C
        if kind == 'seat':
            w, h = bb.face_axes(f['e'], f['face'])
            if min(w, h) >= 3.5:
                C = np.tile(pal['seat'], (N, 1))
                C[((i // 4 + j // 4) % 2) == 0] = pal['seat2']
                C[rim] = pal['seat_rim']
                return C
            return bev(pal['seat_frame'])
        screen = self.screen.get(f['idx'], (None,))[0] == f['face']
        if kind == 'dial':
            if not screen:
                return bev(pal['panel_dark'])
            C = np.tile(pal['panel_dark'], (N, 1))
            c = (nw - 1) / 2, (nh - 1) / 2
            r = np.hypot(i - c[0], j - c[1]); R = min(nw, nh) / 2 - 0.5
            C[r <= R] = pal['dial']
            C[(np.abs(r - R + 0.5) < 0.6)] = pal['dial_ring']
            needle = (r < R - 1) & (np.abs((i - c[0]) + (j - c[1])) < 0.6) & (i >= c[0])
            C[needle] = hexc('#F0F0E8')
            return C
        C = bev(pal['panel'])
        w, h = bb.face_axes(f['e'], f['face'])
        if screen and n[2] > 0.6 and min(w, h) >= 5:      # an instrument panel facing the pilot: rows of dials
            gx, gy = i % 8, j % 8
            r = np.hypot(gx - 3.5, gy - 3.5)
            inner = (i >= 2) & (j >= 2) & (i < nw - 3) & (j < nh - 3)
            C[inner & (r < 3.2)] = pal['dial']
            C[inner & (np.abs(r - 3.0) < 0.5)] = pal['dial_ring']
        elif n[1] > 0.5 and min(w, h) >= 2:               # console tops: a few switches
            cellm = (i % 4 >= 1) & (i % 4 < 3) & (j % 4 >= 1) & (j % 4 < 3) & ~rim
            pick = hsh(i // 4, j // 4, f['idx'])
            C[cellm & (pick < 0.5)] = pal['panel'] * 0.7
            C[cellm & (pick >= 0.94)] = pal['switch']
        return C

    def nozzle(self, f):
        P, face, i, j = f['P'], f['face'], f['i'], f['j']
        pal = self.cfg['nozzle']
        C = np.tile(pal[0], (len(P), 1))
        if face in ('north', 'south'):
            return C * 0.7
        z = P[:, 2] - P[:, 2].min()
        block = (np.floor(z) % 6 >= 1) & (np.floor(z) % 6 < 4)
        C[block] = pal[1] * (1.06 if f['idx'] % 2 else 0.94)
        C[block & (np.floor(z) % 6 == 1)] = pal[2]
        C[(i == 0) | (i == f['nw'] - 1) | (j == 0) | (j == f['nh'] - 1)] = pal[0] * 0.8
        return C

    def hole(self, f):
        P = f['P']
        c = self.cfg.get('hole_centre', (0, 0))
        r = np.hypot(P[:, 0] - c[0], P[:, 1] - c[1])
        C = np.tile(hexc('#1C1E21'), (len(P), 1))
        C[(np.floor(r) % 3) == 0] *= 1.6
        return C

    def gear(self, f, kind):
        P, n, i, j = f['P'], f['n'], f['i'], f['j']
        N = len(P); g = self.cfg['gear']
        if kind == 'tyre':
            C = np.tile(hexc('#2A2A2A'), (N, 1))
            if abs(n[0]) < 0.5:
                C[i % 2 == 0] *= 0.75
            return C
        if kind == 'hub':
            C = np.tile(g['hub'], (N, 1)); C[(i == f['nw'] // 2) & (j == f['nh'] // 2)] = hexc('#1C1E21'); return C
        C = np.tile(g['strut'], (N, 1))
        C[(i == 0) | (j == 0)] *= 1.08
        C[(i == f['nw'] - 1) | (j == f['nh'] - 1)] *= 0.78
        return C

    def store(self, f, kind):
        P, n, i, j = f['P'], f['n'], f['i'], f['j']
        N = len(P)
        st = self.cfg['stores']
        if kind == 'missile':
            w, h = bb.face_axes(f['e'], f['face'])
            thin = min(np.abs(np.array(f['e']['to']) - f['e']['from'])) < 1.2
            C = np.tile(st['fin'] if thin else st['missile'], (N, 1))
            zmin = self.cfg.get('missile_nose', {}).get(f['last'])
            if zmin is not None:
                C[P[:, 2] < zmin] = st['seeker']
            C[(np.floor(P[:, 2]) % 14) == 0] *= 0.8
            return C
        if kind == 'tank':
            C = np.tile(st['tank'], (N, 1))
            C[(np.floor(P[:, 2]) % 12) == 0] *= 0.82
            C[(np.floor(P[:, 2]) % 12) == 1] *= 1.06
            return C
        if kind == 'pod':
            C = np.tile(st['pod'], (N, 1))
            C[(np.floor(P[:, 2]) % 8) == 0] *= 0.8
            return C
        if kind == 'probe':
            return np.tile(hexc('#C9CDD0'), (N, 1)) * (0.94 + 0.08 * (i % 2 == 0))[:, None]
        if kind == 'cone':
            C = np.tile(st['cone'], (N, 1))
            C[(i == 0) | (j == 0)] *= 1.05
            return C
        return np.tile(hexc('#1C1E21'), (N, 1)) * (1 + 0.25 * ((i + j) % 4 == 0))[:, None]

    # -- markings ---------------------------------------------------------------------
    def decals(self, C, P, n):
        for img, o, r, u, nok, rok in self.cfg['decals']:
            if not nok(n):
                continue
            D = P - np.array(o, float)
            a = np.floor(D @ np.array(r, float) + img.shape[1] / 2).astype(int)
            b = np.floor(img.shape[0] / 2 - D @ np.array(u, float)).astype(int)
            sel = (a >= 0) & (a < img.shape[1]) & (b >= 0) & (b < img.shape[0]) & rok(P)
            if sel.any():
                px = img[b[sel], a[sel]]
                on = px[:, 3] > 0.5
                C[np.flatnonzero(sel)[on]] = px[on, :3]
        return C

    # -- everything -------------------------------------------------------------------
    def paint(self):
        out = np.zeros((self.H, self.W, 4), np.float32)
        for f in self.faces:
            mat = f['mat']
            if mat == 'glass':
                C = self.glass(f)
            else:
                if mat == 'skin': C = self.skin(f)
                elif mat == 'frame': C = self.frame(f)
                elif mat in ('interior', 'seat', 'dial'): C = self.interior(f, mat)
                elif mat == 'nozzle': C = self.nozzle(f)
                elif mat == 'hole': C = self.hole(f)
                elif mat in ('tyre', 'hub', 'strut'): C = self.gear(f, mat)
                else: C = self.store(f, mat)
                if mat in ('skin', 'frame'):
                    C = self.decals(C, f['P'], f['n'])
                C = np.c_[C, np.ones(len(C), np.float32)]
            out[f['Y'], f['X']] = np.clip(C, 0, 1)
        return Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGBA')


INTERIOR_K = {'interior', 'seat', 'dial'}


def density_for(cfg, m):
    """pixels per unit by material: cockpit 4, glass and frames 2, everything else 1."""
    mats = {}
    for idx, e in enumerate(m.elements):
        chain = [g['name'] for g in m.chain(e)]
        mats[e['uuid']] = cfg['classify'](e, idx, chain[-1] if chain else '')
    return lambda e, f: 4 if mats[e['uuid']] in INTERIOR_K else 2 if mats[e['uuid']] in ('glass', 'frame') else 1
