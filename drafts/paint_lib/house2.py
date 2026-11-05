"""The bundled aircraft's exterior habits, measured from F/A-18E, Su-33, MiG-31 and Su-25:
flat colour fields (8 colours cover ~90% of a sheet), 1-px structural lines that follow the outline
(edge band, a dark line 3 in with a light twin, a spar line further in), control surfaces outlined,
a few long fuselage lines and stations, rivet rows, sparse small details, big camouflage shapes."""
import sys, math
sys.path.insert(0, r'C:\Users\nagis\AppData\Local\Temp\claude\F--Java-AshVehicles\e1f8066c-7d80-4a8d-aa9b-2b42ad22a84a\scratchpad')
import numpy as np
import bb
import house
from house import hexc, hsh, erode, dilate


def distance(occ, steps=14):
    occ = erode(dilate(occ))
    d = np.full(occ.shape, steps, np.int16); d[~occ] = -1
    cur = occ
    for k in range(steps):
        nxt = erode(cur); d[cur & ~nxt] = k; cur = nxt
    return d


class Painter(house.Painter):
    """keep: materials whose texels are left as they are in `base` (an existing sheet)."""

    def __init__(self, model, cfg, base=None):
        self.base = None if base is None else np.asarray(base, np.float32) / 255
        super().__init__(model, cfg)

    def plan(self, sel, axes):
        shape = (self.GNX, self.GNZ) if axes == 'xz' else (self.GNZ, self.GNY)
        occ = np.zeros(shape, bool)
        for f in self.faces:
            if sel(f):
                a, b = self.grid(f['P'], axes)
                ok = (a >= 0) & (a < shape[0]) & (b >= 0) & (b < shape[1])
                occ[a[ok], b[ok]] = True
        return distance(occ)

    def build_maps(self):
        super().build_maps()
        cfg = self.cfg
        self.SIDE_ALL = {}
        for s in (1, -1):      # the fuselage seen from each side, for lines that follow its outline
            self.SIDE_ALL[s] = self.plan(lambda f, s=s: f['mat'] == 'skin' and f['n'][0] * s > 0.5
                                         and not cfg['fin'](f['P']).mean() > 0.5, 'zy')

    # -- the structural lines ----------------------------------------------------------------
    def lines(self, C, f):
        cfg = self.cfg; P, n = f['P'], f['n']
        N = len(P)
        dark = np.zeros(N, bool); light = np.zeros(N, bool); rivet = np.zeros(N, bool)
        ax = np.abs(P[:, 0])
        horizontal = abs(n[1]) >= 0.5
        fin = cfg['fin'](P)
        fus = ax < cfg['fus_half']
        if horizontal:
            d = self.look(self.TOP if n[1] > 0 else self.BOTTOM, P, 'xz')
            wing = ~fus
            # following the outline
            # one line inset from the outline; on a diagonal edge more than that turns ragged
            dark |= (d == 3)
            # span stations on the wing, inside the edge band
            for c in cfg['wing_cuts']:
                at = np.floor(ax) == c
                dark |= wing & at & (d > 4)
                light |= wing & (np.floor(ax) == c + 1) & (d > 4)
                rivet |= wing & (np.floor(ax) == c + 3) & (np.floor(P[:, 2]) % 3 == 0) & (d > 4) & cfg.get('rivets', True)
            # the fuselage: long lines either side of the spine, stations across
            for c in cfg['fus_lines']:
                dark |= fus & (np.floor(ax) == c) & (d > 3)
                rivet |= fus & (np.floor(ax) == c + 2) & (np.floor(P[:, 2]) % 3 == 0) & (d > 3) & cfg.get('rivets', True)
            st = cfg['stations']
            k = np.clip(np.searchsorted(st, P[:, 2], side='right') - 1, 0, len(st) - 1)
            seam = (P[:, 2] - st[k]) < 1
            keep = hsh(k, np.searchsorted(cfg['fus_lines'], ax), seed=31) > 0.25
            dark |= fus & seam & keep & (d > 3)
            light |= fus & ((P[:, 2] - st[k]) >= 1) & ((P[:, 2] - st[k]) < 2) & keep & (d > 3)
        elif abs(n[0]) >= 0.5:
            s = 1 if n[0] > 0 else -1
            if fin.mean() > 0.5:
                d = self.look(self.SIDE, P, 'zy')
                dark |= (d == 3)
            else:
                d = self.look(self.SIDE_ALL[s], P, 'zy')
                dark |= (d == 3) & (P[:, 2] < cfg['stations'][2])         # round the nose only
                for c in cfg['side_lines']:
                    dark |= (np.floor(P[:, 1]) == c)
                    rivet |= (np.floor(P[:, 1]) == c + 2) & (np.floor(P[:, 2]) % 3 == 0) & cfg.get('rivets', True)
                st = cfg['stations']
                k = np.clip(np.searchsorted(st, P[:, 2], side='right') - 1, 0, len(st) - 1)
                seam = (P[:, 2] - st[k]) < 1
                band = np.searchsorted(cfg['side_lines'], P[:, 1])
                keep = hsh(k, band, seed=32) > 0.25
                dark |= seam & keep
                light |= ((P[:, 2] - st[k]) >= 1) & ((P[:, 2] - st[k]) < 2) & keep
        C[light & ~dark] = C[light & ~dark] * cfg['light_k']
        C[rivet & ~dark] = C[rivet & ~dark] * cfg['rivet_k']
        C[dark] = C[dark] * cfg['dark_k']
        return C

    def details(self, C, f):
        """few, symmetric: dark slots, outlined hatches, a small light plate."""
        cfg = self.cfg; P, n = f['P'], f['n']
        ax = np.abs(P[:, 0])
        horizontal = abs(n[1]) >= 0.5
        across = ax if horizontal else P[:, 1]
        cuts = np.array(sorted(set([0] + list(cfg['fus_lines']) + list(cfg['wing_cuts']) + [400]))) if horizontal \
            else np.array([-50] + list(cfg['side_lines']) + [400.0])
        st = cfg['stations']
        si = np.clip(np.searchsorted(st, P[:, 2], side='right') - 1, 0, len(st) - 2)
        bi = np.clip(np.searchsorted(cuts, across, side='right') - 1, 0, len(cuts) - 2)
        la = P[:, 2] - st[si]; lb = across - cuts[bi]
        wa = st[si + 1] - st[si]; wb = cuts[bi + 1] - cuts[bi]
        o = 0 if horizontal and n[1] > 0 else 1 if horizontal else 2
        r = hsh(si, bi, o, seed=41)
        fa, fb = np.floor(la), np.floor(lb)
        a0 = 3 + np.floor(hsh(si, bi, o, seed=42) * np.maximum(1, wa - 10))
        b0 = 2 + np.floor(hsh(si, bi, o, seed=43) * np.maximum(1, wb - 7))
        room = (wa >= 10) & (wb >= 6)
        slot = room & (r < 0.14) & (fa >= a0) & (fa < a0 + 3) & (fb >= b0) & (fb < b0 + 2)
        C[slot] = cfg['slot']
        hw = 6 + np.floor(hsh(si, bi, o, seed=44) * 4); hh = 4 + np.floor(hsh(si, bi, o, seed=45) * 2)
        inside = room & (r >= 0.14) & (r < 0.30) & (fa >= a0) & (fa < a0 + hw) & (fb >= b0) & (fb < b0 + hh)
        edge = inside & ((fa == a0) | (fa == a0 + hw - 1) | (fb == b0) | (fb == b0 + hh - 1))
        lit = edge & ((fa == a0) | (fb == b0))
        C[edge & ~lit] = C[edge & ~lit] * cfg['dark_k']
        C[lit] = C[lit] * cfg['light_k']
        plate = room & (r >= 0.30) & (r < 0.36) & (fa >= a0) & (fa < a0 + 2) & (fb >= b0) & (fb < b0 + 2)
        C[plate] = C[plate] * cfg['light_k'] * 1.05
        if horizontal and n[1] > 0 and cfg.get('walkway') is not None:
            wk = room & (r >= 0.36) & (r < 0.42) & (wa >= 16) & (wb >= 10)
            e2 = ((fa == 2) | (fa == np.floor(wa) - 3)) & (fb >= 2) & (fb <= np.floor(wb) - 3)
            e2 |= ((fb == 2) | (fb == np.floor(wb) - 3)) & (fa >= 2) & (fa <= np.floor(wa) - 3)
            C[wk & e2 & (((fa + fb) % 3) != 2)] = cfg['walkway']
        if cfg.get('stencil') is not None:
            dots = room & (r >= 0.42) & (r < 0.47) & (fb == b0) & (fa >= a0) & (fa < a0 + 2)
            C[dots] = cfg['stencil']
        return C

    def outline(self, C, f):
        """the light band on the planform edge; control surfaces drawn round on their own shape."""
        P, n = f['P'], f['n']
        if abs(n[1]) >= 0.5:
            d = self.look(self.TOP if n[1] > 0 else self.BOTTOM, P, 'xz')
        elif abs(n[0]) >= 0.5 and self.cfg['fin'](P).mean() > 0.5:
            d = self.look(self.SIDE, P, 'zy')
        else:
            return C
        light = self.cfg['edge_light']
        C[d == 0] = C[d == 0] * 0.5 + light * 0.5
        if f['last'] in self.SURF:
            dm, axs = self.SURF[f['last']]
            sd = self.look(dm, P, axs)
            C[(sd == 0) & (d > 0)] = C[(sd == 0) & (d > 0)] * self.cfg['dark_k']
            C[(sd == 1) & (d > 1)] = C[(sd == 1) & (d > 1)] * self.cfg['light_k']
        return C

    def skin(self, f):
        cfg = self.cfg; P, n = f['P'], f['n']
        lower = bool(n[1] < -0.35)
        C = cfg['scheme'](P, n, lower, f).astype(np.float32)
        burn = cfg.get('burn')
        if burn:                                   # bare metal aft: two flat bands and a ring line each
            z0, z1, ymax, xmax = burn
            sel = (P[:, 2] > z0) & (P[:, 1] < ymax) & (np.abs(P[:, 0]) < xmax)
            if sel.any():
                k = (P[sel, 2] > (z0 + z1) / 2).astype(int)
                C[sel] = cfg['burnt'][k]
        C = self.lines(C, f)
        C = self.details(C, f)
        C = self.outline(C, f)
        x, y0, y1, z0, z1 = cfg['tub']
        tub = (np.abs(P[:, 0]) < x) & (P[:, 1] > y0) & (P[:, 1] < y1) & (P[:, 2] > z0) & (P[:, 2] < z1) \
            & ((n[0] * np.sign(P[:, 0]) < -0.3) | (n[1] > 0.5))
        pal = cfg['interior']
        C[tub] = pal['tub']
        C[tub & (n[1] > 0.5) & (P[:, 1] > y1 - 6)] = pal['sill']
        return C

    def frame(self, f):
        C = self.cfg['scheme'](f['P'], f['n'], False, f).astype(np.float32) * 0.85
        i, j = f['i'], f['j']
        C[(i == 0) | (j == 0)] = self.cfg.get('frame_edge', self.cfg['frame_glass'])
        C[(i == f['nw'] - 1) | (j == f['nh'] - 1)] *= 0.75
        return C

    def plain(self, f):
        """a cockpit floor: one dark colour with a bevelled rim, nothing scattered on it."""
        pal = self.cfg['interior']; i, j, nw, nh = f['i'], f['j'], f['nw'], f['nh']
        C = np.tile(pal['panel_dark'], (len(f['P']), 1))
        C[(i == 0) | (j == 0)] *= 1.3
        C[(i == nw - 1) | (j == nh - 1)] *= 0.75
        return C

    def nozzle(self, f):
        """dark, with light vertical stripes like the F/A-18E's."""
        P, face, i, j = f['P'], f['face'], f['i'], f['j']
        pal = self.cfg['nozzle']
        C = np.tile(pal[0], (len(P), 1))
        if face in ('north', 'south'):
            return C * 0.7
        C[(i % 3) == 1] = pal[1]
        C[(np.floor(P[:, 2]) % 8) == 0] = pal[2]
        return C

    def paint(self):
        out = np.zeros((self.H, self.W, 4), np.float32) if self.base is None else self.base.copy()
        keep = self.cfg.get('keep', ())
        for f in self.faces:
            if f['mat'] in keep:
                continue
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
                elif mat == 'radome': C = self.cfg['radome'](f)
                elif mat == 'plain': C = self.plain(f)
                else: C = self.store(f, mat)
                if mat in ('skin', 'frame', 'radome'):
                    C = self.decals(C, f['P'], f['n'])
                C = np.c_[C, np.ones(len(C), np.float32)]
            out[f['Y'], f['X']] = np.clip(C, 0, 1)
        from PIL import Image
        return Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGBA')
