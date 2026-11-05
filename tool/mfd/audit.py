"""Every MFD of every aircraft against its hitbox: is it on the panel, and whose is it.

    python tool/mfd/audit.py [id ...] [--file DIR]   (DIR: read <id>.json there first)

For each screen a seat sees (GenericCockpit.displays' rule: its `seat`, else every eye within
1.25; a seat that sees none gets GenericCockpit's three unless generic_cockpit is false):

  on    share of the screen with a box face right behind it, seen from that eye (place.support)
  sunk  corners inside a box (the screen is drawn into the panel and hidden)
  gap   how far the panel is behind the screen's middle (a screen hanging in the air)
  buttons  how many; under 4 the page can not be changed (CockpitControls.layout needs them)

Flags a screen under 75% on the panel, with a sunk corner, a gap over 0.05 or no buttons, and a
seat that gets the generic three. Screens the file wrote from the model's own screen cubes sit on cubes the
hitbox leaves out, so a hand-placed screen can read low here and be right; look at those in the
editor before moving them.
"""
import sys, os, json, glob
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import frame as F
import place as P


def seats_screens(d):
    """[(seat, eye, [screen dicts], generic)] the way the client picks them."""
    cam = d['camera']
    own = cam.get('displays', [])
    n = max(1, len(F.seats_of(d)))
    out = []
    for i in range(n):
        eye = F.eye_of(d, i)
        mine = [s for s in own if (s['seat'] == i if 'seat' in s
                                   else np.linalg.norm(np.array(s['pos']) - eye) <= P.REACH)]
        generic = False
        if not mine and cam.get('generic_cockpit', True):
            generic = True
            mine = [dict(pos=list(p), width=w, height=h, tilt=P.G_TILT, page=page) for p, w, h, page in P.generic(eye)]
        out.append((i, eye, mine, generic))
    return out


def check(aid, d):
    hb = F.hitbox_cubes(d)
    rows = []
    for seat, eye, screens, generic in seats_screens(d):
        boxes = [c for c in hb if not c.contains(eye)[0]]
        for raw in screens:
            s = raw
            s = dict(pos=np.array(s['pos'], float), width=s.get('width', 0.22), height=s.get('height', 0.22),
                     tilt=s.get('tilt', 20.0), yaw=s.get('yaw', 0.0), page=s.get('page', 'pfd'))
            on = P.support(boxes, eye, s)
            sunk = P.buried(boxes, P.corners(s))
            d_ = s['pos'] - eye
            dist = float(np.linalg.norm(d_))
            h = P.first(boxes, eye, d_ / dist, dist + 1.0)
            gap = None if h is None else h[0] - dist
            keys = 0 if generic else len(raw.get('buttons', []))
            bad = on < 0.75 or sunk or gap is None or gap > 0.05 or keys < 4
            rows.append((seat, s['page'], generic, on, sunk, gap, bad, keys))
    return rows


if __name__ == '__main__':
    args = sys.argv[1:]
    src = None
    if '--file' in args:
        k = args.index('--file')
        src = args[k + 1]
        del args[k:k + 2]
    ids = args or sorted(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(F.DEFS, '*.json')))
    flagged = 0
    for aid in ids:
        path = os.path.join(src, aid + '.json') if src and os.path.exists(os.path.join(src, aid + '.json')) \
            else os.path.join(F.DEFS, aid + '.json')
        d = json.load(open(path, encoding='utf-8'))
        if not F.seats_of(d):
            continue
        rows = check(aid, d)
        bad = [r for r in rows if r[6]]
        generic = sorted({r[0] for r in rows if r[2]})
        flagged += bool(bad) or bool(generic)
        status = 'ok' if not bad and not generic else 'CHECK'
        print(f'{aid:12s} {status:5s} screens {len(rows):2d}' + (f'  generic three for seats {generic}' if generic else ''))
        for seat, page, gen, on, sunk, gap, b, keys in rows:
            if b:
                g = 'none' if gap is None else f'{gap:+.3f}'
                print(f'    seat {seat} {page:5s} on {on:.0%} sunk {sunk} gap {g} buttons {keys}'
                      + ('  (generic)' if gen else ''))
    print(f'{flagged} machine(s) to look at')
