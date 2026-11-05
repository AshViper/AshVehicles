"""Checks the seats and eyes written in the definition files (or in a folder of drafts).

    python tool/seats/audit.py [--file DIR] [id ...]

Per seat: how far the head top is under what closes the cockpit over it (negative: through
it), how close the eye is to any surface of the model (the near plane is 0.05), how much of the
HUD plate the game hangs off camera.cockpit is buried in the model, and which MFDs the seat
reads (camera.displays: its own by `seat`, otherwise those within 1.25 of its eye, as
GenericCockpit.displays decides).
"""
import sys, os, json
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import model as M
import measure as ME
import rider as RD
from crew import SPEC, P, C
from cockpit import seats_of, eye_of

REACH = 1.25


def readers(d, eyes):
    out = []
    for k, e in enumerate(eyes):
        n = 0
        for disp in d['camera'].get('displays', []) or []:
            if 'seat' in disp:
                n += disp['seat'] == k
            else:
                n += np.linalg.norm(np.array(disp['pos']) - np.array(e)) <= REACH
        out.append(n)
    return out


def audit(aid, d):
    seats = seats_of(d)
    if not seats:
        return []
    centre = np.mean([s[0] for s in seats], axis=0)
    skin = ME.Skin.of(aid, near=centre, radius=max(6.0, float(np.ptp([s[0] for s in seats], axis=0).max()) + 4))
    eyes = [eye_of(d, k) for k in range(len(seats))]
    reads = readers(d, eyes)
    rows = []
    roles = [s['role'] for s in SPEC.get(aid, [])] or ['?'] * len(seats)
    for k, ((pos, _), eye) in enumerate(zip(seats, eyes)):
        x, y, z = pos
        role = roles[k] if k < len(roles) else '?'
        # What is over the head from the neck up: a cabin seat from the head's middle, over the
        # frames of troop seats round the shoulders.
        lift = RD.NECK if role in (P, C) else RD.EYE
        ceil = ME.canopy(skin, x, z, y + lift)
        for side in ((-0.22, 0.22) if role in (P, C) else ()):
            c = ME.canopy(skin, x + side, z, y + lift + 0.15, dxs=(0.0,), dzs=(0.0, 0.1, 0.2), q=0.5)
            if c is not None and (ceil is None or c < ceil):
                ceil = c
        gap = None if ceil is None else ceil - (y + RD.HEAD_TOP)
        e = np.array(eye, float)
        near = skin.box(e - 0.3, e + 0.3)
        clear = float(np.min(np.linalg.norm(near - e, axis=1))) if len(near) else 0.3
        hud = None
        if k == 0 and role == P:
            c = e + np.array([0, -0.05, 0.55])
            inside = skin.box(c - [0.12, 0.10, 0.03], c + [0.12, 0.10, 0.03], solid_only=True)
            # share of the plate's area with solid model behind it, sampled on a grid
            g = 0
            for u in np.linspace(-0.11, 0.11, 6):
                for v in np.linspace(-0.09, 0.09, 5):
                    q = skin.box(c + [u - 0.02, v - 0.02, -0.03], c + [u + 0.02, v + 0.02, 0.03], solid_only=True)
                    g += len(q) > 0
            hud = g / 30
        rows.append((k, role, pos, eye, gap, clear, hud, reads[k]))
    return rows


if __name__ == '__main__':
    args = sys.argv[1:]
    src = None
    if '--file' in args:
        i = args.index('--file')
        src = args[i + 1]
        del args[i:i + 2]
    ids = args or sorted(k for k in SPEC if M.has_model(k))
    bad = 0
    for aid in ids:
        d = json.load(open(os.path.join(src, aid + '.json'), encoding='utf-8')) if src and os.path.exists(os.path.join(src, aid + '.json')) else M.definition(aid)
        for k, role, pos, eye, gap, clear, hud, reads in audit(aid, d):
            flags = []
            if gap is not None and gap < -0.01:
                flags.append('HEAD')
            if clear < 0.08:
                flags.append('EYE')
            if hud is not None and hud > 0.34:
                flags.append('HUD')
            bad += bool(flags)
            f = lambda v: '  -  ' if v is None else f'{v:5.2f}'
            print(f'{aid:11s} {k} {role:6s} headgap {f(gap)} eyeclear {clear:4.2f} hudburied {f(hud)} mfds {reads}  {" ".join(flags)}')
    print('flagged', bad)
