"""F-104G in USAF natural metal, after the reference photo: UV layout from scratch (exterior 1 px/unit,
cockpit 4), house2 painter, US markings, buzz number, red arrows, dark anti-glare and the heat-stained tail.
usage: python f104.py <in.bbmodel> <out.bbmodel> <out.png>"""
import sys
sys.path.insert(0, r'C:\Users\nagis\AppData\Local\Temp\claude\F--Java-AshVehicles\c1f572e4-6100-4bd4-bd04-8835721b751a\scratchpad\lib')
import numpy as np
import bb
from house import hexc, layout, density_for, us_insignia, _pixelate, _rgba
from house2 import Painter
from aircraft3 import centres, US_COCKPIT

BLACK, RED, WHITE = hexc('#1E2022'), hexc('#C8302A'), hexc('#F2F2EE')
UPPER, LOWER = hexc('#B6BEC5'), hexc('#C2C8CD')

GLYPH = {
    'U': ['101', '101', '101', '101', '111'], 'S': ['111', '100', '111', '001', '111'],
    'A': ['010', '101', '111', '101', '101'], 'I': ['1', '1', '1', '1', '1'],
    'R': ['110', '101', '110', '101', '101'], 'F': ['111', '100', '110', '100', '100'],
    'O': ['111', '101', '101', '101', '111'], 'C': ['111', '100', '100', '100', '111'],
    'E': ['111', '100', '110', '100', '111'], 'G': ['111', '100', '101', '101', '111'],
    '.': ['0', '0', '0', '0', '1'], ' ': ['0', '0', '0', '0', '0'], '-': ['000', '000', '111', '000', '000'],
    '0': ['111', '101', '101', '101', '111'], '1': ['010', '110', '010', '010', '111'],
    '2': ['111', '001', '111', '100', '111'], '3': ['111', '001', '011', '001', '111'],
    '4': ['101', '101', '111', '001', '001'], '5': ['111', '100', '111', '001', '111'],
    '6': ['111', '100', '111', '101', '111'], '7': ['111', '001', '010', '010', '010'],
    '8': ['111', '101', '111', '101', '111'], '9': ['111', '101', '111', '001', '111'],
}


def text(txt, fill, cell=1, tall=None):
    cols = []
    for k, ch in enumerate(txt):
        g = np.array([[c == '1' for c in row] for row in GLYPH[ch]])
        cols.append(g)
        if k < len(txt) - 1:
            cols.append(np.zeros((5, 1), bool))
    bm = np.kron(np.hstack(cols), np.ones((tall or cell, cell), bool))
    out = np.zeros(bm.shape + (4,), np.float32)
    out[bm] = np.r_[fill, 1]
    return out


def arrow(w, h, colour):
    """the red arrow on the nose and fin, pointing to +u (right in the image)."""
    def draw(d, s):
        d.polygon([(0, h * s * 0.5), (w * s * 0.35, 0), (w * s * 0.35, h * s * 0.3), (w * s, h * s * 0.42),
                   (w * s, h * s * 0.58), (w * s * 0.35, h * s * 0.7), (w * s * 0.35, h * s)], fill=_rgba(colour))
    return _pixelate(draw, w, h)[:, ::-1]


def side(img, y, z, region, forward=False, facing=0.45):
    """on both sides; text reads left to right on each side, an image with `forward` points at the nose."""
    out = []
    for s in (1, -1):
        im = img[:, ::-1] if forward and s < 0 else img
        rok = (lambda s: lambda P: (np.sign(P[:, 0]) == s) & region(P))(s)
        out.append((im, (0, y, z), (0, 0, -1) if s > 0 else (0, 0, 1), (0, 1, 0), (lambda s: lambda n: n[0] * s > facing)(s), rok))
    return out


def wing(img, x, y, z, below):
    """tops toward the leading edge, unmirrored from where it is seen: above, +x is the viewer's right; below, -x."""
    s = np.sign(x)
    r = (-1, 0, 0) if below else (1, 0, 0)
    nok = (lambda n: n[1] < -0.8) if below else (lambda n: n[1] > 0.8)
    rok = lambda P: (np.sign(P[:, 0]) == s) & (np.abs(P[:, 0]) > 22) & (np.abs(P[:, 0]) < 72)
    return [(img, (x, y, z), r, (0, 0, -1), nok, rok)]


def config(m):
    cs = centres(m)

    def classify(e, idx, last):
        col = e.get('color')
        if last == 'bone23': return 'frame'
        if last == 'bone38':
            w = abs(e['to'][0] - e['from'][0])
            if abs(w - 12.71) < 0.05: return 'plain'                 # the bulkhead behind the seat
            return 'seat' if col == 2 or abs(w - 6.84) < 0.05 else 'interior'
        if last == 'bone12': return 'probe'
        if last in ('bone24', 'bone28', 'bone30'): return 'tyre'
        if last in ('bone25', 'bone29', 'bone31'): return 'hub'
        if last in ('fr_wheels', 'r_wheels', 'r_wheels2'): return 'strut'
        if last in ('bone32', 'bone33'): return 'tank'
        if last in ('bone34', 'bone35', 'bone36', 'bone37'): return 'pod'
        if last.startswith('octagon') and last != 'octagon': return 'cone'
        if last == 'octagon': return 'hole' if col == 6 else 'nozzle'
        return 'skin'

    def scheme(P, n, low, f):
        C = np.tile(LOWER if low else UPPER, (len(P), 1))
        ax = np.abs(P[:, 0]); z = P[:, 2]
        # anti-glare panel ahead of the windscreen, narrowing toward the nose
        top = 30.9 + (z + 190) * 0.214                      # the nose's ridge line, measured
        glare = (n[1] > -0.2) & (z > -178) & (z < -133) & (ax < 2 + (z + 178) * 0.1) & (P[:, 1] > top - 3.5)
        C[glare] = hexc('#2A2C2B')
        # the red band round the aft fuselage
        ring = (z >= 126) & (z < 128) & (P[:, 1] < 60) & (ax < 20)
        C[ring] = RED
        return C

    star = us_insignia(11, hexc('#1F3F86'), WHITE, hexc('#B22234'))
    big_star = us_insignia(13, hexc('#1F3F86'), WHITE, hexc('#B22234'))
    usaf = text('USAF', BLACK, cell=2, tall=3)
    nose = lambda P: (P[:, 2] > -140) & (P[:, 2] < -84) & (P[:, 1] > 26) & (P[:, 1] < 42)
    aft = lambda P: (P[:, 2] > 74) & (P[:, 2] < 125) & (P[:, 1] > 30) & (P[:, 1] < 58)
    ins = lambda P: (P[:, 2] > -75) & (P[:, 2] < -40) & (P[:, 1] > 24) & (P[:, 1] < 48)
    tip = lambda P: (P[:, 2] > -190) & (P[:, 2] < -170)
    fin = lambda P: np.abs(P[:, 0]) < 1.5
    decals = side(text('U.S. AIR FORCE', BLACK), 33.5, -112, nose, facing=0.75) \
        + side(text('FG-786', BLACK, cell=2), 42, 100, aft, facing=0.9) \
        + side(star, 36, -58, ins) \
        + side(arrow(9, 4, RED), 31.5, -180, tip, forward=True) \
        + side(text('37786', BLACK), 79, 170, fin) \
        + side(arrow(13, 5, RED), 70, 166, fin, forward=True) \
        + wing(usaf, 50, 31, 67, below=False) + wing(usaf, -50, 31, 67, below=True) \
        + wing(big_star, -48, 31, 67, below=False) + wing(big_star, 48, 31, 67, below=True)

    return dict(
        classify=classify, scheme=scheme, keep=(),
        dark_k=0.80, light_k=1.08, rivet_k=0.88, slot=hexc('#2E3236'), stencil=None, walkway=None, rivets=True,
        edge_light=hexc('#E6EAEE'), frame_glass=hexc('#CFE3EE'), frame_edge=hexc('#DDE3E8'),
        nozzle=(hexc('#3A3B3E'), hexc('#6B6E72'), hexc('#8E9296')),
        burn=(128, 176, 58, 17), burnt=np.array([hexc('#8E8A7C'), hexc('#6F6D6A')]),
        gear=dict(hub=hexc('#B9BDBF'), strut=hexc('#C9CCCD')),
        stores=dict(missile=hexc('#DCDCD4'), fin=hexc('#9A9EA0'), seeker=hexc('#3A4A55'), tank=UPPER,
                    pod=UPPER * 0.95, cone=hexc('#C8CDD1')),
        fin=lambda P: (np.abs(P[:, 0]) < 1.5) & (P[:, 1] > 57),
        tub=(8.6, 30, 45.5, -134, -88), interior=US_COCKPIT, hole_centre=(-0.2, 44.6), decals=decals,
        surfaces=(), vertical_surfaces=(),
        fus_half=24, fus_lines=[3, 8, 14], wing_cuts=[34, 50, 64], stations=np.arange(-196, 210, 22.0),
        side_lines=[28, 37])


LEFTOVERS = (452, 453, 457)     # an older right wing under the mirrored one: same surfaces, so they z-fight


def drop_leftovers(m):
    gone = {m.elements[i]['uuid'] for i in LEFTOVERS}
    assert all(m.chain(m.elements[i])[-1]['name'] == 'bone22' for i in LEFTOVERS)
    m.d['elements'] = m.elements = [e for e in m.elements if e['uuid'] not in gone]

    def walk(nodes):
        return [dict(n, children=walk(n.get('children', []))) if isinstance(n, dict) else n for n in nodes if isinstance(n, dict) or n not in gone]
    m.d['outliner'] = walk(m.d['outliner'])
    m.parent = {k: v for k, v in m.parent.items() if k not in gone}


if __name__ == '__main__':
    src, out_model, out_png = sys.argv[1:4]
    m = bb.Model(src)
    drop_leftovers(m)
    cfg = config(m)
    used = layout(m, density_for(cfg, m), 1024, name='f_104g')
    print('uv layout uses', used, 'rows of 1024')
    m.res = (1024, 1024)
    img = Painter(m, cfg).paint()
    img.save(out_png)
    m.set_texture(img)
    m.save(out_model)
    print('painted', out_model)
