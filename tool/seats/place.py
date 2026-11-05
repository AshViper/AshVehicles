"""Seats and eyes of the aircraft, rebuilt from the model and the real crew layout.

    python tool/seats/place.py OUTDIR [id ...]   write OUTDIR/<id>.json (and print what moved)
    python tool/seats/place.py --apply [id ...]  rewrite the definition files in place

Only airframe.seats and camera.cockpit are rewritten, as text, so the rest of the file stays
byte for byte. Reads the working tree (the pylon re-layout is not committed yet); the seat rules
below do not read the seats already written except as a first guess of where the cockpit is, so
a second run lands where the first did.

The rules, for a cockpit seat (pilot / crew), in the order a pilot straps in:
  1. The pan: the highest solid surface under the hip. Where that surface goes on flat under
     the feet it is the floor and the model has no seat, so the pan is SEAT_HEIGHT over it.
  2. Fore and aft: the rider's back (point z - 0.125) against the seat back, the front face of
     the nearest solid behind the rider 0.25-0.6 over the pan. Upright figure, reclined seat: it
     touches at the bottom.
  3. The design eye, 0.10 ahead of the head's middle: high enough that the glare shield is just
     under the HUD plate the game hangs 0.55 ahead and 0.05 below camera.cockpit
     (CockpitDisplays.drawHud), and that the sill of the windscreen is OVER_NOSE under the level
     line of sight; no lower than SEAT_TRAVEL under the eye of a rider on the pan, and 0.2 under
     the canopy at most. The eyes written by hand before this were within a few centimetres of
     where these two rules put them (B-52 4.73 / 4.75, F-15 3.64 / 3.70, A-10 3.51 / 3.55).
  4. The body under that eye, as a pilot winds the seat up or down to put the eyes there, but
     the head top (point + 1.40) 0.05 under the canopy over it and over its outer corners, and
     the hip no more than SEAT_RISE over the pan. The Minecraft head reaches 0.38 over the eye
     where a real one reaches 0.12, so in a cockpit drawn at real size the head usually decides:
     the rider sinks into the pan and the first-person eye ends up 0.1-0.3 over the model's eye.
For a cabin seat: the pan and the canopy only, eye = the rider's own. For a gunner: the same
body, the eye the file gives (it is at the gun).

Screens go with their crew (fix_displays): crew.DISPLAY_SEATS remaps `camera.displays[].seat`
when a station changes ends, and a single-seat machine whose screens fall out of the 1.25 reach
of the new eye gets them by `seat: 0`.
"""
import sys, os, json, re, math
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model as M
import measure as ME
import rider as RD
from crew import SPEC, DISPLAY_SEATS, P, C, G, B
from cockpit import seats_of, eye_of

HEAD_ROOM = 0.05      # head top under the canopy
EYE_AHEAD = 0.10      # eye ahead of the head's middle
EYE_ROOM = 0.20       # eye under the canopy
HUD_BELOW = 0.05 + 0.10 + 0.02   # HUD plate drop + half its height + a little: coaming under the plate
HUD_AHEAD = 0.55
SEAT_TRAVEL = 0.15   # how far under a natural sitting eye the design eye may be
SEAT_RISE = 0.30     # how far over the pan the rider may float to reach it
SEAT_HEIGHT = 0.35   # a seat pan over the floor, where the model has none
OVER_NOSE = 12.0     # degrees under the level line of sight that the sill must be


def r3(v):
    v = round(float(v), 3)
    return 0.0 if v == 0 else v


def support(skin, x, z, hip, up=0.35, down=0.45):
    """Highest solid surface under the hip: the seat pan, a bench, or the floor."""
    q = skin.box([x - 0.15, hip - down, z - 0.05], [x + 0.15, hip + up, z + 0.25], solid_only=True)
    return None if len(q) == 0 else float(q[:, 1].max())


def place_station(skin, role, x, y0, z0, st):
    log = {}
    hip0 = y0 + RD.HIP
    sup = support(skin, x, z0, hip0)
    z = z0
    if role in (P, C) and st.get('back', True) and sup is not None:
        bk = ME.back(skin, x, sup + 0.25, sup + 0.6, z0 + 0.05, reach=0.6, rx=0.15)
        log['back'] = bk
        if bk is not None:
            z = bk + 0.125 + 0.01
            sup2 = support(skin, x, z, hip0)
            if sup2 is not None:
                sup = sup2
    if 'z' in st and st.get('z_fixed'):
        z = st['z']
    log['support'] = sup
    # A floor and not a seat: the same surface goes on under the feet. The model has no pan, so
    # the rider gets one at an ejection seat's height over the floor.
    if role in (P, C) and sup is not None:
        tops = []
        for dx in (-0.1, 0.0, 0.1):
            q = skin.box([x + dx - 0.04, sup - 0.5, z + 0.45], [x + dx + 0.04, sup + 0.25, z + 0.8], solid_only=True)
            tops.append(None if len(q) == 0 else float(q[:, 1].max()))
        if all(t is not None and abs(t - sup) < 0.04 for t in tops):
            log['floor'] = sup
            sup = sup + SEAT_HEIGHT
    base = sup if sup is not None else hip0
    # Over the head: anything higher than the shoulders of a rider sunk well into the pan. A cabin
    # seat looks higher still: the frames of troop seats and door guns stand round the shoulders.
    ceil = ME.canopy(skin, x, z, base + (1.0 if role in (G, B) else 0.45))
    if role in (P, C):
        # The head's outer corners, where a canopy curves down over a seat off the centreline. Only
        # what is over a rider's shoulders counts, which leaves out the side frames of the seat.
        for side in (-0.22, 0.22):
            corner = ME.canopy(skin, x + side, z, base + 0.75, dxs=(0.0,), dzs=(0.0, 0.1, 0.2), q=0.5)
            if corner is not None and (ceil is None or corner < ceil):
                ceil = corner
                log['corner'] = side
    if 'ceiling' in st:
        ceil = st['ceiling']
    log['ceiling'] = ceil
    pan_y = (sup - RD.HIP) if sup is not None else y0      # the seat point of a rider on the pan
    head_max = None if ceil is None else ceil - RD.HEAD_TOP - HEAD_ROOM

    eye = None
    if role in (P, C):
        # The eye first, as a pilot sets the seat: up or down until the eyes are at the design eye.
        ze = z + EYE_AHEAD
        ceil_e = ME.canopy(skin, x, ze, base + 0.45, dxs=(-0.06, 0.0, 0.06), dzs=(-0.05, 0.0, 0.05), q=0.0)
        if 'ceiling' in st:
            ceil_e = st['ceiling'] if ceil_e is None else min(ceil_e, st['ceiling'])
        cap = None if ceil_e is None else ceil_e - EYE_ROOM
        natural = pan_y + RD.EYE
        below = (cap + 0.02) if cap is not None else natural + 0.4
        ye = natural - SEAT_TRAVEL
        co = ME.coaming(skin, x, ze + 0.3, ze + 0.85, below)
        if co is not None:
            ye = max(ye, co + HUD_BELOW)
        # Over the nose: the sill of the windscreen and the glare shield at least OVER_NOSE below
        # the level line of sight, as a design eye is set.
        need = None
        for zz, top in ME.profile(skin, x, ze + 0.3, ze + 1.8, below):
            v = top + (zz - ze) * math.tan(math.radians(OVER_NOSE))
            need = v if need is None else max(need, v)
        if need is not None:
            ye = max(ye, need)
        if cap is not None:
            ye = min(ye, cap)
        log['coaming'] = co
        log['over_nose'] = need
        log['eye_ceiling'] = ceil_e
        # Then the body under it: the rider's eye on the design eye, but the head under the canopy
        # and the hip no more than SEAT_RISE over the pan.
        y = ye - RD.EYE
        y = min(y, pan_y + SEAT_RISE)
        if head_max is not None:
            y = min(y, head_max)
        eye = [x, ye, ze]
    else:
        y = pan_y if head_max is None else min(pan_y, head_max)
    if 'y' in st:
        y = st['y']
    log['sunk'] = None if sup is None else pan_y - y
    if role == B:
        eye = [x, y + RD.EYE, z]
    if 'eye' in st:
        eye = st['eye']
    return [x, y, z], eye, log


def plan(aid, d):
    spec = SPEC.get(aid)
    if spec is None:
        raise SystemExit(f'{aid}: not in crew.py')
    old = seats_of(d)
    raw = d['airframe'].get('seats', [])
    if len(spec) != len(old):
        raise SystemExit(f'{aid}: crew.py has {len(spec)} seats, the file {len(old)}')
    if not old:
        return [], None, []
    centre = np.mean([o[0] for o in old], axis=0)
    skin = ME.Skin.of(aid, near=centre, radius=max(6.0, float(np.ptp([o[0] for o in old], axis=0).max()) + 4))
    out, logs = [], []
    for i, (st, (pos, eye)) in enumerate(zip(spec, old)):
        x = st.get('x', pos[0])
        z = st.get('z', pos[2])
        p, e, log = place_station(skin, st['role'], x, pos[1], z, st)
        if st['role'] == G:
            e = eye
        extra = {k: v for k, v in raw[i].items() if k not in ('pos', 'eye')} if isinstance(raw[i], dict) else {}
        out.append((st['role'], [r3(v) for v in p], None if e is None else [r3(v) for v in e], extra))
        logs.append(log)
    cockpit = out[0][2] if out[0][0] == P else None
    return out, cockpit, logs


# ---- writing ----

def seats_text(seats, indent='    '):
    lines = []
    for k, (role, pos, eye, extra) in enumerate(seats):
        if k == 0 and role == P and not extra:
            lines.append(f'{indent}  {json.dumps(pos)}')
            continue
        body = [f'"pos": {json.dumps(pos)}']
        if eye is not None:
            body.append(f'"eye": {json.dumps(eye)}')
        for key, v in extra.items():
            body.append(f'{json.dumps(key)}: {json.dumps(v)}')
        lines.append(f'{indent}  {{\n' + ',\n'.join(f'{indent}    {b}' for b in body) + f'\n{indent}  }}')
    if not lines:
        return '[]'
    return '[\n' + ',\n'.join(lines) + f'\n{indent}]'


def decimal(v):
    """A whole number written as 2.0, as the files have them."""
    s = json.dumps(v)
    return s if ('.' in s or 'e' in s) else s + '.0'


def vec(v):
    return '[' + ', '.join(decimal(x) for x in v) + ']'


def splice(text, seats, cockpit):
    m = re.search(r'("seats"\s*:\s*)\[', text)
    if not m:
        raise SystemExit('no seats block')
    start = m.end() - 1
    depth = 0
    for k in range(start, len(text)):
        if text[k] == '[':
            depth += 1
        elif text[k] == ']':
            depth -= 1
            if depth == 0:
                end = k + 1
                break
    line_start = text.rfind('\n', 0, m.start()) + 1
    indent = text[line_start:m.start()]
    block = seats_text([(r, [float(v) for v in p], e, x) for r, p, e, x in seats], indent)
    block = re.sub(r'\[(-?[\d.e-]+), (-?[\d.e-]+), (-?[\d.e-]+)\]',
                   lambda mm: vec([float(mm.group(i)) for i in (1, 2, 3)]), block)
    text = text[:start] + block + text[end:]
    if cockpit is not None:
        cm = re.search(r'("camera"\s*:\s*\{)', text)
        sub = text[cm.end():]
        km = re.search(r'"cockpit"\s*:\s*\[[^\]]*\]', sub)
        text = text[:cm.end()] + sub[:km.start()] + f'"cockpit": {vec(cockpit)}' + sub[km.end():]
    return text


REACH = 1.25   # GenericCockpit.REACH: a screen with no seat is read by the seats this close to it


def objects(text, start):
    """(begin, end) of each {...} directly inside the [...] that opens at text[start]."""
    out, depth, begin = [], 0, None
    for k in range(start, len(text)):
        ch = text[k]
        if ch in '[{':
            depth += 1
            if ch == '{' and depth == 2:
                begin = k
        elif ch in ']}':
            depth -= 1
            if ch == '}' and depth == 1:
                out.append((begin, k + 1))
            if depth == 0:
                return out, k + 1
    raise SystemExit('unbalanced')


def fix_displays(text, aid, seats, remap_seats=True):
    """Screens follow their crew: remap the seats written on them (DISPLAY_SEATS), and give a
    single-seat machine's screens to seat 0 when its new eye is out of reach of them."""
    m = re.search(r'"displays"\s*:\s*\[', text)
    if not m:
        return text
    spans, _ = objects(text, m.end() - 1)
    remap = DISPLAY_SEATS.get(aid, {}) if remap_seats else {}
    eye0 = np.array(seats[0][2], float) if seats and seats[0][2] is not None else None
    for b, e in reversed(spans):
        obj = text[b:e]
        d = json.loads(obj)
        if 'seat' in d and d['seat'] in remap:
            obj = re.sub(r'("seat"\s*:\s*)\d+', lambda mm: mm.group(1) + str(remap[d['seat']]), obj)
        elif 'seat' not in d and len(seats) == 1 and eye0 is not None \
                and np.linalg.norm(np.array(d['pos']) - eye0) > REACH:
            NL = chr(10)
            if NL in obj:
                body = obj.rstrip()[:-1].rstrip()          # up to the last property
                close = obj[len(body):]                     # the newline, indent and brace
                indent = re.search(NL + r'(\s*)"', obj).group(1)
                obj = body + ',' + NL + indent + '"seat": 0' + close
            else:
                obj = obj[:-1].rstrip() + ', "seat": 0 }'
        text = text[:b] + obj + text[e:]
    return text


def write(path, text, tries=5):
    """Beside the file and then over it, so a writer that fails to open (Windows answered
    EINVAL once while something held a.json) leaves the definition whole."""
    import time
    tmp = path + '.seats.tmp'
    for k in range(tries):
        try:
            with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
                f.write(text)
            os.replace(tmp, path)
            return
        except OSError:
            if k == tries - 1:
                raise
            time.sleep(1.0)


def run(ids, outdir=None, apply=False):
    for aid in ids:
        path = os.path.join(M.DEFS, aid + '.json')
        text = open(path, encoding='utf-8').read()
        d = json.loads(text)
        seats, cockpit, logs = plan(aid, d)
        if not seats:
            print(f'{aid:11s} no crew')
            continue
        old = seats_of(d)
        for i, ((role, p, e, _), (op, oe), log) in enumerate(zip(seats, old, logs)):
            oe = oe if oe is not None else d['camera']['cockpit']
            f = lambda v: '  -  ' if v is None else f'{v:5.2f}'
            print(f'{aid:11s} {i} {role:6s} pos {p} (was {op})  eye {e} (was {oe})  '
                  f'sup {f(log.get("support"))} ceil {f(log.get("ceiling"))} sunk {f(log.get("sunk"))} '
                  f'floor {f(log.get("floor"))} back {f(log.get("back"))} coam {f(log.get("coaming"))} nose {f(log.get("over_nose"))} eceil {f(log.get("eye_ceiling"))}')
        new = fix_displays(splice(text, seats, cockpit), aid, seats)
        json.loads(new)
        if apply:
            write(path, new)
        elif outdir:
            os.makedirs(outdir, exist_ok=True)
            open(os.path.join(outdir, aid + '.json'), 'w', encoding='utf-8', newline='\n').write(new)


def displays_only(ids):
    """Only the out-of-reach rule, on the files as they are: for after tool/mfd/place.py, which puts
    the screens back as committed (and remaps their seats itself) before laying its own."""
    for aid in ids:
        path = os.path.join(M.DEFS, aid + '.json')
        text = open(path, encoding='utf-8').read()
        d = json.loads(text)
        seats = [(None, s[0], eye_of(d, i), {}) for i, s in enumerate(seats_of(d))]
        new = fix_displays(text, aid, seats, remap_seats=False)
        if new != text:
            json.loads(new)
            write(path, new)
            print(f'{aid}: screens given to seat 0')


if __name__ == '__main__':
    args = sys.argv[1:]
    if '--displays-only' in args:
        displays_only([a for a in args if a != '--displays-only'] or sorted(k for k in SPEC if M.has_model(k)))
        raise SystemExit
    apply = '--apply' in args
    args = [a for a in args if a != '--apply']
    outdir = None
    if not apply:
        outdir, args = args[0], args[1:]
    ids = args or sorted(k for k in SPEC if M.has_model(k))
    run(ids, outdir, apply)
