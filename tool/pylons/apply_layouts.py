"""Rebuild the external stations of aircraft from their hitbox and the real station layout.

    python tool/pylons/apply_layouts.py OUTDIR [id ...]   write OUTDIR/<id>.json to look at first
    python tool/pylons/apply_layouts.py --apply [id ...]  rewrite the definition files in place

The layout of each machine (which stations, how far out, fuselage x/z) is in layouts.py; this
measures the rest from the hitbox boxes: chord position and height of wing pylons, height of
fuselage stations and pods, the wingtip rail. Only the hardpoints block is rewritten. Stations
keep their slot when kept, removed slots are refilled by new ones, extras go at the end, so the
loads of aircraft already saved in a world move as little as possible.

Built from the committed file (git show HEAD:...) with --from-head, so a re-run replaces this
script's own edits instead of stacking on them. Check the result with audit.py.
"""
import sys, os, json, re, subprocess
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from hitbox import *
import place as PL
from layouts import SPEC

MIN_LEAD = 0.95


def skin(d, spec):
    """The hitbox as surface points, without boxes the spec names in skip_boxes (a fixed landing
    gear leg is in the AH-64's hitbox, and a pod measured against it hangs under the strut)."""
    skip = tuple(spec.get('skip_boxes', ()))
    pts = [box_points(b) for b in d['hitbox']['boxes'] if not any(s in b.get('name', '') for s in skip)]
    return np.concatenate(pts)


def r3(v):
    v = round(float(v), 3)
    return 0.0 if v == 0 else v


def wing_z(P, ax, hint, ymax, dz, f=None):
    pts = P if ymax is None else P[P[:, 1] < ymax]
    seg = PL.chord_at(pts, ax, hint)
    te, le = seg
    c = le - te
    lead = max(0.30 * c, MIN_LEAD) if f is None else f * c
    z = le - lead
    lo, hi = te + 1.05, le - 0.85
    if lo > hi:
        z = (lo + hi) / 2
    else:
        z = min(max(z, lo), hi)
    return z + dz, te, le


def under(P, x, z, ymax=None, pod=False):
    """Lowest skin the drawn thing may touch. A rack's top plate (0.30-0.44 up) runs +-0.72 along
    the chord and its lower beam (0.12-0.31) runs +-1.03, so skin over the beam ends may come
    0.13 lower than over the plate before anything goes through it."""
    pts = P if ymax is None else P[P[:, 1] < ymax]
    if pod:
        return PL.foot_underside(pts, x, z, 0.11, 0.9, 1.0)
    u1 = PL.foot_underside(pts, x, z, 0.10, 0.75, 0.75)
    u2 = PL.foot_underside(pts, x, z, 0.10, 1.05, 1.05)
    # Only the top plate is held to the skin. Flap and fence boxes near the trailing edge sit a
    # little lower than the wing box; letting the beam ends dip into them reads as the pylon
    # entering the wing, while holding the whole beam clear leaves a visible gap at the middle.
    return u1 if u1 is not None else u2


def rail_box(d):
    """The launch rail the model carries on the wingtip, if it has one: the outermost box when
    that box is thin and long."""
    best = None
    for b in d['hitbox']['boxes']:
        p = box_points(b, 0.1)
        m = float(p[:, 0].max())
        if best is None or m > best[0]:
            best = (m, b)
    b = best[1]
    sx, sy, sz = b['size']
    if sx <= 0.4 and sz >= 1.5 and not any(abs(r) > 5 for r in b.get('rotation', [0, 0, 0])):
        return b
    return None


def build(aid, d):
    spec = SPEC[aid]
    P = skin(d, spec)
    old = [dict(h) for h in d.get('hardpoints', [])]
    if spec.get('rename_by_pos'):
        # duplicate or meaningless names: rename the plain pylons by side and rank outward
        pre = spec['rename_by_pos']
        pyl = [h for h in old if 'fixed' not in h and not h.get('internal') and h.get('name', '').startswith(pre)]
        for side, sel in (('left', lambda h: h['pos'][0] < 0), ('right', lambda h: h['pos'][0] > 0)):
            ranked = sorted([h for h in pyl if sel(h)], key=lambda h: abs(h['pos'][0]))
            for i, h in enumerate(ranked):
                h['name'] = f'pylon{i + 1}_{side}'
    byname = {h.get('name'): h for h in old}
    wing_hints = [h['pos'][2] for h in old if 'fixed' not in h and not h.get('internal')]
    default_hint = float(np.median(wing_hints)) if wing_hints else 0.0
    made = {}
    notes = []
    # which side this file calls "left": files disagree (F-15, F-14, Su-33 put _left at +x)
    lefts = [h['pos'][0] for h in old if 'left' in h.get('name', '') and abs(h['pos'][0]) > 1e-6]
    left_sign = 1.0 if lefts and sum(1 for v in lefts if v > 0) > len(lefts) / 2 else -1.0
    for e in spec['stations']:
        names = e['names']
        pair = len(names) == 2
        place = e['place']
        ymax = e.get('ymax')
        if place == 'tip':
            rb = rail_box(d)
            if rb is not None:
                # the model carries the rail: put the rack on it, its middle on the rail's middle
                x, y, z = abs(rb['offset'][0]), rb['offset'][1] - 0.17, rb['offset'][2]
                notes.append(f'{names[0]:<18} tip  on model rail {rb["name"]} x {x:.2f} y {y:.2f} z {z:.2f}')
            else:
                x, y, z, info = PL.tip(P if ymax is None else P[P[:, 1] < ymax], e.get('hint'))
                notes.append(f'{names[0]:<18} tip  x {x:.2f} y {y:.2f} z {z:.2f} tipchord {info["chord"][0]:.2f}..{info["chord"][1]:.2f}')
        elif place in ('wing', 'podwing'):
            x = e['x']
            hint = e.get('hint', byname.get(names[0], {}).get('pos', [0, 0, default_hint])[2])
            z, te, le = wing_z(P, x, hint, ymax, e.get('dz', 0.0), e.get('f'))
            if 'z' in e:
                z = e['z']
            u = min(under(P, s * x, z, ymax, pod=place == 'podwing') for s in ((1, -1) if pair else (1,)))
            uc = PL.foot_underside(P if ymax is None else P[P[:, 1] < ymax], x, z, 0.1, 0.15, 0.15)
            y = u - (PL.POD_TOP if place == 'podwing' else PL.RACK_TOP)
            warn = '  <-- footprint reaches lower skin' if uc is not None and u < uc - 0.15 else ''
            notes.append(f'{names[0]:<18} {place:<4} x {x:.2f} chord {te:.2f}..{le:.2f} ({le-te:.2f}) z {z:.2f} under {u:.2f} centre {uc} y {y:.2f}{warn}')
        elif place in ('body', 'pod'):
            x, z = e['x'], e['z']
            u = min(under(P, s * x, z, ymax, pod=place == 'pod') for s in ((1, -1) if pair and x else (1,)))
            y = u - (PL.POD_TOP if place == 'pod' else PL.RACK_TOP)
            notes.append(f'{names[0]:<18} {place:<4} x {x:.2f} z {z:.2f} under {u:.2f} y {y:.2f}')
        else:
            raise ValueError(place)
        y += e.get('dy', 0.0)
        for i, n in enumerate(names):
            if pair:
                if n in byname:
                    s = 1.0 if byname[n]['pos'][0] > 0 else -1.0
                else:
                    s = left_sign if i == 0 else -left_sign
                    other = names[1 - i]
                    if other in byname:
                        s = -1.0 if byname[other]['pos'][0] > 0 else 1.0
                px = s * abs(x)
            else:
                px = 0.0 if abs(x) < 1e-6 else x
            h = dict(byname.get(n, {}))
            h['name'] = n
            h['pos'] = [r3(px), r3(y), r3(z)]
            h.pop('wingtip', None)
            kind = 'special' if place in ('pod', 'podwing') else 'weapon'
            if place == 'tip':
                h['wingtip'] = True
            h['kind'] = kind
            # keep key order: name, pos, wingtip, kind, rest
            order = ['name', 'pos', 'internal', 'wingtip', 'kind']
            made[n] = {k: h[k] for k in order if k in h} | {k: v for k, v in h.items() if k not in order}
    # new list: old order, removed slots refilled by new stations, extras appended
    mentioned = set(made)
    removed = set(spec.get('remove', []))
    for h in old:
        n = h.get('name')
        if 'fixed' in h or h.get('internal') or n in mentioned or n in removed:
            continue
        raise SystemExit(f'{aid}: station {n} neither placed nor removed')
    new_names = [n for e in spec['stations'] for n in e['names'] if n not in byname]
    out = []
    for h in old:
        n = h.get('name')
        if n in removed:
            if new_names:
                out.append(made[new_names.pop(0)])
            continue
        if n in made:
            out.append(made[n])
        else:
            out.append(h)
    for n in new_names:
        out.append(made[n])
    return out, notes


def fmt_num(v):
    if isinstance(v, float):
        s = repr(v)
        return s
    return json.dumps(v)


def ser(hps):
    lines = []
    for i, h in enumerate(hps):
        items = []
        for k, v in h.items():
            if k == 'pos':
                items.append(f'      "pos": [{fmt_num(float(v[0]))}, {fmt_num(float(v[1]))}, {fmt_num(float(v[2]))}]')
            elif isinstance(v, list) and all(isinstance(e, str) for e in v) and v:
                items.append(f'      {json.dumps(k)}: [\n        ' + ', '.join(json.dumps(e) for e in v) + '\n      ]')
            else:
                items.append(f'      {json.dumps(k)}: {json.dumps(v)}')
        lines.append('    {\n' + ',\n'.join(items) + '\n    }')
    return '[\n' + ',\n'.join(lines) + '\n  ]'


def splice(text, hps):
    i = text.index('"hardpoints"')
    j = text.index('[', i)
    depth, k, instr = 0, j, False
    while True:
        ch = text[k]
        if instr:
            if ch == '\\':
                k += 2
                continue
            if ch == '"':
                instr = False
        elif ch == '"':
            instr = True
        elif ch == '[':
            depth += 1
        elif ch == ']':
            depth -= 1
            if depth == 0:
                break
        k += 1
    return text[:j] + ser(hps) + text[k + 1:]


if __name__ == '__main__':
    args = sys.argv[1:]
    from_head = '--from-head' in args
    args = [a for a in args if a != '--from-head']
    apply = bool(args) and args[0] == '--apply'
    outdir = None if apply else args[0]
    ids = args[1:] or sorted(SPEC)
    for aid in ids:
        path = os.path.join(ROOT, aid + '.json')
        text = open(path, encoding='utf-8').read()
        if from_head:
            # The committed stations decide the list (names, slots, renames), as the first run
            # did; the hitbox is the same in both. Only each station's pos goes into the working
            # file, which also carries everything else done since the commit (seats, screens):
            # splicing into the committed text instead threw all of that away.
            rel = 'src/main/resources/data/ashvehicles/ashvehicles/aircraft/' + aid + '.json'
            head = subprocess.run(['git', '-C', REPO, 'show', 'HEAD:' + rel],
                                  capture_output=True, check=True).stdout.decode('utf-8')
            hps, notes = build(aid, json.loads(head))
            cur = json.loads(text).get('hardpoints', [])
            if [h.get('name') for h in cur] != [h.get('name') for h in hps]:
                raise SystemExit(f'{aid}: the working file lists other stations than a re-run from HEAD makes; '
                                 f'run without --from-head or look first')
            merged = []
            for c, h in zip(cur, hps):
                m = dict(c)
                m['pos'] = h['pos']
                other = {k for k in set(c) | set(h) if k != 'pos' and c.get(k) != h.get(k)}
                if other:
                    notes.append(f'{h["name"]:<18} keeps the working file\'s {sorted(other)}')
                merged.append(m)
            hps = merged
        else:
            hps, notes = build(aid, json.loads(text))
        new = splice(text, hps)
        assert json.loads(new)['hardpoints'] == hps
        before = json.loads(text)
        after = json.loads(new)
        before.pop('hardpoints', None)
        after.pop('hardpoints', None)
        assert before == after, 'only the stations may change'
        print(f'== {aid}')
        for n in notes:
            print('  ' + n)
        if apply:
            tmp = path + '.pylons.tmp'
            with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
                f.write(new)
            os.replace(tmp, path)
        else:
            os.makedirs(outdir, exist_ok=True)
            open(os.path.join(outdir, aid + '.json'), 'w', encoding='utf-8', newline='\n').write(new)
