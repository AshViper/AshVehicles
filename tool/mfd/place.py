"""Put the MFDs of aircraft that write none onto their instrument panel.

    python tool/mfd/place.py OUTDIR [id ...]    write OUTDIR/<id>.json to look at first
    python tool/mfd/place.py --apply [id ...]   rewrite the definition files in place

A machine without camera.displays gets GenericCockpit's three screens at a fixed offset from the
eye, and no cockpit is that shape: in the F-16, F-2, F-4, AH-64, RAH-66 and AV-8B they sit inside
the panel, in the F-14, F-15, F-117 and Su-27 they float up to 0.8 in front of it, and the cabin
seats of the UH-60 and MH-60M see them hanging in the air.

For each crew eye this writes the screens the panel actually has:

  * the model's own screen cubes, when it has them (the editor's autoDisplays: flat cubes within
    reach facing the eye, the smallest first, with the little square cubes round each as buttons);
  * otherwise GenericCockpit's three, laid on the hitbox. The panel is a box of the hitbox (the
    raw pass keeps it; only the small cubes are dropped): the centre screen goes where the eye's
    line to the generic one first meets a box, turned to that face, and each side screen where
    the line to its place on that face meets one. Tilt and yaw come from the face it lies on.

Seats whose eye has no panel in reach (cabins, door guns) get none: the file then says
generic_cockpit false, or GenericCockpit would hang its three in front of them again.

Every screen gets buttons, without which CockpitControls.layout gives it none and its page can
never change: the model's button cubes round it when there are 4 or more, else soft keys (a row
of 6 page keys along the top and 6 down each side, just inside the edge, under their labels).
A screen the file already had without buttons (the Eurofighter's) gets them the same way, and
takes the tilt of the model's screen cube under it when the file's differs.

Only camera.displays and camera.generic_cockpit are written, as text, so the rest of the file
stays byte for byte. They are first put back as the commit has them, so a re-run (after the
seats or the hitbox move) replaces this script's own screens instead of adding to them, and the
screens a file already had (the MiG-31's front seat) stay as written. Check with audit.py.
"""
import sys, os, json, math, subprocess
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import frame as F
from frame import seats_of, eye_of

REPO = F.REPO
ROOT = F.DEFS

# GenericCockpit: where its screens are, measured in a model whose eye is G_EYE (Blockbench pixels).
G_EYE = np.array([0.0, 14.5, 13.0])
G_SCREENS = [((-3.446, 10.384, 2.641), 3.9, 3.1, 'nav'),
             ((-0.004, 9.414, 2.883), 2.6, 4.6, 'pfd'),
             ((3.446, 10.384, 2.641), 3.9, 3.1, 'sms')]
G_TILT = 14.0
REACH = 1.25        # GenericCockpit.REACH: a screen without a seat belongs to every eye this close
PANEL_REACH = 1.2   # how far in front of the eye a panel may be
LIFT = 0.02         # CockpitDisplays.LIFT: the screen is drawn this far off its plane
PAD = 0.005         # what the screen's own plane may sink into a box (rounding of the face)


def generic(eye):
    """GenericCockpit's three screens for this eye: [(pos, width, height, page)]."""
    out = []
    for c, w, h, page in G_SCREENS:
        c = np.array(c)
        pos = np.array([eye[0] + (c[0] - G_EYE[0]) / 16, eye[1] + (c[1] - G_EYE[1]) / 16,
                        eye[2] + (G_EYE[2] - c[2]) / 16])
        out.append((pos, w / 16, h / 16, page))
    return out


# ---- the screen's frame, the way CockpitDisplays.frame makes it ----

def _rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def _ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def axes(tilt, yaw):
    """right, up, normal (towards the eye) of a screen, in the file frame (x right, y up, z fwd)."""
    R = np.diag([1, 1, -1]) @ _ry(math.radians(-yaw)) @ _rx(math.radians(-tilt))
    return R[:, 0], R[:, 1], R[:, 2]


def angles(n):
    """tilt and yaw of a screen whose face looks along n."""
    n = n / np.linalg.norm(n)
    return math.degrees(math.asin(max(-1.0, min(1.0, n[1])))), math.degrees(math.atan2(-n[0], -n[2]))


# ---- rays against boxes ----

def enter(cube, a, d, far):
    """(t, outward normal) where the ray a + t d (0 <= t <= far) enters the cube, or None."""
    p = (a - cube.mid) @ cube.axes.T
    v = d @ cube.axes.T
    t0, t1, k0, s0 = 0.0, far, -1, 0.0
    for k in range(3):
        if abs(v[k]) < 1e-12:
            if abs(p[k]) > cube.half[k]:
                return None
            continue
        ta = (-cube.half[k] - p[k]) / v[k]
        tb = (cube.half[k] - p[k]) / v[k]
        sa = -1.0
        if ta > tb:
            ta, tb, sa = tb, ta, 1.0
        if ta > t0:
            t0, k0, s0 = ta, k, sa
        t1 = min(t1, tb)
        if t0 > t1:
            return None
    if k0 < 0:
        return None     # starts inside
    return t0, s0 * cube.axes[k0]


def first(boxes, a, d, far=PANEL_REACH):
    best = None
    for c in boxes:
        h = enter(c, a, d, far)
        if h is not None and (best is None or h[0] < best[0]):
            best = (h[0], h[1], c)
    return best


def in_seat(p, seats):
    """Whether p is in the room another crew member's seat and body take.

    Behind the seat point it reaches 0.35: the head's back is 0.25 behind it and the seat back's
    rear face (UH-60) 0.30. Not further, because a tandem cockpit's rear panel is on the back of
    the front seat (the RAH-66's at 0.40 behind it since tool/seats put the pilot against the
    seat back), and that is the panel the rear seat reads."""
    for s in seats:
        if abs(p[0] - s[0]) < 0.35 and s[1] - 0.3 < p[1] < s[1] + 1.45 and s[2] - 0.35 < p[2] < s[2] + 0.35:
            return True
    return False


def turned(d, degrees):
    """The line d pitched by `degrees` (+ up) about its own sideways axis."""
    side = np.cross(d, [0, 1, 0])
    side /= np.linalg.norm(side)
    k = math.radians(degrees)
    return d * math.cos(k) + np.cross(side, d) * math.sin(k) + side * (side @ d) * (1 - math.cos(k))


def on_panel(boxes, eye, aim, seats=()):
    """Where the eye's line towards `aim` meets the panel, and the face there.

    A panel face looks back at the eye. The coaming's top looks up and the underside of a panel
    looks down; a line that lands on either is pitched down first (the panel under the lip),
    then up (a helicopter's panel leans back over its lower edge). A line that lands on the
    seat in front (a cabin behind the pilots) has no panel."""
    d0 = (aim - eye) / np.linalg.norm(aim - eye)
    for pitch in [0] + [-2 * i for i in range(1, 9)] + [2 * i for i in range(1, 9)]:
        d = turned(d0, pitch) if pitch else d0
        h = first(boxes, eye, d)
        if h is None:
            continue
        t, n, c = h
        if in_seat(eye + d * t, seats):
            return None
        tilt, yaw = angles(n)
        if n[2] < -0.2 and -5 < tilt < 60 and abs(yaw) < 50:
            return eye + d * t, n, c
    return None


def angle(a, b):
    return math.degrees(math.acos(max(-1.0, min(1.0, a @ b / np.linalg.norm(a) / np.linalg.norm(b)))))


def buried(boxes, pts):
    return sum(any(c.contains(p, -PAD)[0] for c in boxes) for p in pts)


def corners(s, lift=LIFT):
    r, u, n = axes(s['tilt'], s['yaw'])
    return [s['pos'] + n * lift + r * a * s['width'] / 2 + u * b * s['height'] / 2 for a in (-1, 1) for b in (-1, 1)]


def support(boxes, eye, s, k=5):
    """The share of the screen with panel right behind it: lines from the eye through a grid on the
    screen must meet a box within 0.04 of the screen's plane, neither short of it (something in
    front hides the screen) nor past it (the screen hangs over an edge)."""
    r, u, n = axes(s['tilt'], s['yaw'])
    good = 0
    for a in np.linspace(-0.85, 0.85, k):
        for b in np.linspace(-0.85, 0.85, k):
            p = s['pos'] + r * a * s['width'] / 2 + u * b * s['height'] / 2
            d = p - eye
            dist = float(np.linalg.norm(d))
            h = first(boxes, eye, d / dist, dist + 0.1)
            good += h is not None and abs(h[0] - dist) <= 0.04
    return good / (k * k)


def lay(boxes, eye, shrink=1.0, slide=0.0, seats=(), spread=1.0):
    """GenericCockpit's three on the hitbox, or None when there is no panel in front of the eye.

    shrink scales the layout and the screens about the centre one; slide moves the whole row
    along the face (+ up), to clear a glare shield over the panel or a console under it; spread
    pushes the side screens out to panels either side of a narrow middle one (a gunsight)."""
    g = generic(eye)
    pfd = g[1]
    hit = on_panel(boxes, eye, pfd[0], seats)
    if hit is None:
        return None
    centre, n, _ = hit
    tilt, yaw = angles(n)
    r, u, _ = axes(tilt, yaw)
    if slide:
        # slid along this face, the middle screen may come to rest on another (the shelf over a
        # panel set deeper in): it takes that one's turn
        aim = centre + u * slide
        hit = on_panel(boxes, eye, aim, seats)
        if hit is None or angle(hit[0] - eye, aim - eye) > 2.5:
            return []
        centre, n, _ = hit
        tilt, yaw = angles(n)
        r, u, _ = axes(tilt, yaw)
    gr, gu, _ = axes(G_TILT, 0.0)
    out = []
    for pos, w, h, page in g:
        if page == 'pfd':
            out.append(dict(pos=centre, width=w * shrink, height=h * shrink, tilt=tilt, yaw=yaw, page=page))
            continue
        # the generic layout, measured in the generic screen's own plane, laid in this face's plane
        off = pos - pfd[0]
        aim = centre + (r * (off @ gr) * spread + u * (off @ gu)) * shrink
        side = on_panel(boxes, eye, aim, seats)
        # on the line to its place, or nearly: a side panel set further in than the middle one
        # (the AH-64's front seat) still holds it, at its own depth
        if side is None or angle(side[0] - eye, aim - eye) > 4.5:
            return []
        st, sy = angles(side[1])
        out.append(dict(pos=side[0], width=w * shrink, height=h * shrink, tilt=st, yaw=sy, page=page))
    return out


def fits(boxes, eye, screens, need=0.9):
    """No corner of any screen inside a box, no two screens overlapping, each one on the panel."""
    for s in screens:
        if buried(boxes, corners(s)):
            return False
    for i, a in enumerate(screens):
        for b in screens[i + 1:]:
            ra, ua, _ = axes(a['tilt'], a['yaw'])
            d = b['pos'] - a['pos']
            if abs(d @ ra) < (a['width'] + b['width']) / 2 and abs(d @ ua) < (a['height'] + b['height']) / 2:
                return False
    return all(support(boxes, eye, s) >= need for s in screens)


SHRINKS = (1.0, 0.9, 0.8, 0.7, 0.6, 0.5)
SLIDES = (0.0, -0.02, 0.02, -0.04, 0.04, -0.06, 0.06, -0.08, -0.1, -0.12, -0.14, -0.16)


def on_hitbox(boxes, eye, seats):
    """The biggest layout that fits, sliding before shrinking. (screens, how) or (None, why)."""
    if lay(boxes, eye, 1.0, 0.0, seats) is None:
        return None, 'no panel in reach'
    # 90% of each screen on the panel if any layout manages it, else three quarters (a helicopter's
    # panel and the AH-64 gunner's sight are narrower than the row)
    for need in (0.9, 0.75):
        for spread in (1.0, 1.3, 1.6):
            for shrink in SHRINKS:
                for slide in SLIDES:
                    laid = lay(boxes, eye, shrink, slide * shrink, seats, spread)
                    if laid and fits(boxes, eye, laid, need):
                        return laid, (f'hitbox, size x{shrink:.1f}, slid {slide * shrink:+.2f}'
                                      + (f', spread x{spread:.1f}' if spread != 1.0 else '')
                                      + f', {need:.0%} on the panel')
    return None, 'a panel, but three screens do not fit on it'


# ---- the model's own screens (the editor's findScreens / findButtons / pagesFor) ----

def model_screens(cubes, eye, reach=REACH):
    found = []
    for c in cubes:
        size = 2 * c.half
        thin, a, b = np.argsort(size)
        if size[a] < 0.03 or size[thin] > 0.5 * size[a]:
            continue
        n = c.axes[thin].copy()
        to_eye = eye - c.mid
        dist = float(np.linalg.norm(to_eye))
        if dist > reach or dist < 0.15:
            continue
        if n @ to_eye < 0:
            n = -n
        if n @ (to_eye / dist) < 0.55 or c.mid[2] < eye[2] + 0.1 or c.mid[1] > eye[1]:
            continue
        area = size[a] * size[b]
        if area < 0.002 or area > 0.25 or size[b] / size[a] > 2.5:
            continue
        tilt, yaw = angles(n)
        if abs(yaw) > 60:
            continue
        across = a if abs(c.axes[a][0]) >= abs(c.axes[b][0]) else b
        up = b if across == a else a
        found.append(dict(pos=c.mid + n * size[thin] / 2, width=float(size[across]), height=float(size[up]),
                          tilt=tilt, yaw=yaw, area=area))

    def holds(s, t):
        return t is not s and t['area'] < s['area'] * 0.7 \
            and abs(t['pos'][0] - s['pos'][0]) < s['width'] / 2 \
            and abs(t['pos'][1] - s['pos'][1]) < s['height'] / 2 + 0.05 \
            and abs(t['pos'][2] - s['pos'][2]) < s['height'] / 2 + 0.05

    leaves = [s for s in found if not any(holds(s, t) for t in found)]
    leaves.sort(key=lambda s: s['area'])
    kept = []
    for s in leaves:
        if any(np.linalg.norm(k['pos'] - s['pos']) < (k['width'] + s['width']) / 2 for k in kept):
            continue
        kept.append(s)
        if len(kept) >= 6:
            break
    for s in kept:
        s['buttons'] = model_buttons(cubes, s)
    # a screen has a bezel of buttons; a flat cube without one is a window or a placard
    bezel = [s for s in kept if len(s['buttons']) >= 4]
    if len(bezel) >= 2:
        kept = bezel
    kept.sort(key=lambda s: s['pos'][0])
    upright = [s for s in kept if s['tilt'] < 40]
    row = {1: ['pfd'], 2: ['radar', 'sms'], 3: ['radar', 'pfd', 'sms']}.get(
        len(upright), [['radar', 'nav', 'pfd', 'sms', 'eng', 'map'][i % 6] for i in range(len(upright))])
    for s in kept:
        s['page'] = row[upright.index(s)] if s['tilt'] < 40 else 'eng'
    return kept


def local(s, p):
    """A point as (across, up, off the face) of screen s."""
    r, u, n = axes(s['tilt'], s['yaw'])
    d = p - s['pos']
    return d @ r, d @ u, d @ n


def model_buttons(cubes, s):
    out = []
    for c in cubes:
        side = np.sort(2 * c.half)
        if side[1] > max(0.05, s['width'] * 0.25) or side[2] > max(0.07, s['width'] * 0.4):
            continue
        u, v, n = local(s, c.mid)
        if abs(n) > 0.07:
            continue
        ou, ov = abs(u) / (s['width'] / 2), abs(v) / (s['height'] / 2)
        if ou > 1.45 or ov > 1.45 or (ou < 0.85 and ov < 0.85):
            continue
        out.append([r3(x) for x in c.mid])
    return out


# ---- soft keys: buttons for a screen whose model has none ----

PX = 128        # CockpitDisplays.PX: the screen is laid out 128 pixels across
KEY_EDGE = 5.0  # how far in from the edge a soft key sits (pixels)
KEY_ROW = 6     # keys in the top row: CockpitControls makes 5 of them pages and the 6th MORE


def height_px(s):
    """CockpitDisplays.heightOf."""
    return max(16, int(math.floor(s['height'] / (s['width'] / PX) + 0.5)))


def at_pixel(s, x, y):
    """A pixel of the screen (x right, y down from its top left) in the file frame."""
    r, u, _ = axes(s['tilt'], s['yaw'])
    k = s['width'] / PX
    return s['pos'] + r * (x - PX / 2) * k + u * (height_px(s) / 2 - y) * k


def soft_keys(s):
    """Buttons for a screen the model drew no buttons round: a row of page keys along the top and a
    column of keys down each side, set just inside the edge, under the labels.

    CockpitControls.layout sorts a button onto the edge it is nearest (edgeOf takes the edge it
    sticks out past most, which for a point inside is the closest one), takes the top row as the
    page keys and fills the rest with the page's verbs, right side first: 6 + 6 + 6 holds every
    page's verbs (ENG and MAP have up to 10). Inside rather than on a bezel round the screen,
    because there is no bezel: the screens sit 0.01 apart on a panel that can end at their edge.
    A screen too short for side columns gets a bottom row instead."""
    h = height_px(s)
    keys = [(PX * (i + 0.5) / KEY_ROW, KEY_EDGE) for i in range(KEY_ROW)]
    side = min(6, int((h - 20) / 12))
    if side >= 3:
        top, bottom = 14.0, h - 8.0
        for x in (KEY_EDGE, PX - KEY_EDGE):
            keys += [(x, top + (bottom - top) * (i + 0.5) / side) for i in range(side)]
    else:
        keys += [(PX * (i + 0.5) / KEY_ROW, h - KEY_EDGE) for i in range(KEY_ROW)]
    return [[r3(v) for v in at_pixel(s, x, y)] for x, y in keys]


def edge_of(s, p):
    """CockpitControls.edgeOf for a point of the file frame, to check the keys land where meant."""
    r, u, _ = axes(s['tilt'], s['yaw'])
    k = s['width'] / PX
    d = np.array(p) - s['pos']
    x = d @ r / k + PX / 2
    y = height_px(s) / 2 - d @ u / k
    out = {'TOP': -y, 'BOTTOM': y - height_px(s), 'LEFT': -x, 'RIGHT': x - PX}
    return max(out, key=out.get)


def as_screen(raw):
    """A camera.displays entry as written, with the codec's defaults (VehicleChassis.Display)."""
    s = dict(pos=np.array(raw['pos'], float), width=raw.get('width', 0.22), height=raw.get('height', 0.22),
             tilt=raw.get('tilt', 20.0), yaw=raw.get('yaw', 0.0), page=raw.get('page', 'pfd'))
    if raw.get('buttons'):
        s['buttons'] = raw['buttons']
    if 'seat' in raw:
        s['seat'] = raw['seat']
    return s


def give_buttons(s, cubes):
    """The model's button cubes round the screen if it has them, else soft keys. (how, count)"""
    found = model_buttons(cubes, s)
    if len(found) >= 4:
        s['buttons'] = found
        return 'model'
    s['buttons'] = soft_keys(s)
    return 'soft'


# ---- the machine ----

def r3(v):
    v = round(float(v), 3)
    return 0.0 if v == 0 else v


def r1(v):
    v = round(float(v), 1)
    return int(v) if v == int(v) else v


def served_by(existing, seat_eyes):
    """The seats the file's own screens already serve (GenericCockpit.displays' rule)."""
    out = set()
    for i, e in enumerate(seat_eyes):
        for s in existing:
            if 'seat' in s:
                mine = s['seat'] == i
            elif len(seat_eyes) == 1:
                # a single seat's own screens are its own however far they are; tool/seats writes
                # them seat 0 when its eye moves out of reach (the Eurofighter's, 1.26 away)
                mine = True
            else:
                mine = np.linalg.norm(np.array(s['pos']) - e) <= REACH
            if mine:
                out.add(i)
    return out


def build(aid, d):
    """(new displays, generic_cockpit, notes): screens for every crew eye the file serves none of."""
    hb = F.hitbox_cubes(d)
    cubes = F.model_cubes(aid, d)
    n_seats = max(1, len(seats_of(d)))
    seat_eyes = [eye_of(d, i) for i in range(n_seats)]
    done = served_by(d['camera'].get('displays', []), seat_eyes)
    eyes = []
    for i, e in enumerate(seat_eyes):
        if i not in done and not any(np.allclose(e, x) for x in eyes):
            eyes.append(e)
    notes, displays, served = [], [], []
    for eye in eyes:
        # not the boxes this eye sits in (the canopy, the tub), nor the seat in front of it
        boxes = [c for c in hb if not c.contains(eye)[0]]
        others = [np.array(s[0], float) for s, e in zip(seats_of(d), seat_eyes)
                  if not np.allclose(e, eye) and s[0][2] > eye[2]]
        mine = model_screens(cubes, eye)
        how = "the model's own screens"
        if len(mine) < 2:
            mine, how = on_hitbox(boxes, eye, others)
        notes.append(f'eye {np.round(eye, 3).tolist()}: ' + (f'{len(mine)} screens, {how}' if mine else how))
        if not mine:
            continue
        served.append(eye)
        for s in mine:
            s['eye'] = eye
            s['support'] = support(boxes, eye, s)
            if not s.get('buttons'):
                s['buttons'] = soft_keys(s)
        displays += mine
    # Whose screens are they: an untagged screen is every eye's within REACH, which must not reach
    # another eye's screens (side-by-side seats are 1.1 apart).
    for s in displays:
        near = [i for i, e in enumerate(seat_eyes) if np.linalg.norm(s['pos'] - e) <= REACH]
        owners = [i for i, e in enumerate(seat_eyes) if np.allclose(e, s['eye'])]
        if set(near) != set(owners):
            if len(owners) == 1:
                s['seat'] = owners[0]
            else:
                notes.append(f"  {s['page']} at {np.round(s['pos'], 3).tolist()} is in reach of seats {near}, "
                             f"owned by {owners}")
    unserved = [i for i, e in enumerate(seat_eyes)
                if i not in done and not any(np.allclose(e, x) for x in served)]
    generic_cockpit = not unserved
    if unserved:
        notes.append(f'seats {unserved} have no panel: generic_cockpit false')
    return displays, generic_cockpit, notes


# ---- writing ----

def ser_display(s):
    parts = [f'"pos": [{r3(s["pos"][0])}, {r3(s["pos"][1])}, {r3(s["pos"][2])}]',
             f'"width": {r3(s["width"])}', f'"height": {r3(s["height"])}',
             f'"tilt": {r1(s["tilt"])}']
    if abs(r1(s['yaw'])) > 0:
        parts.append(f'"yaw": {r1(s["yaw"])}')
    parts.append(f'"page": "{s["page"]}"')
    if s.get('buttons'):
        parts.append('"buttons": [' + ', '.join('[' + ', '.join(str(r3(x)) for x in b) + ']'
                                                 for b in s['buttons']) + ']')
    if 'seat' in s:
        parts.append(f'"seat": {s["seat"]}')
    return '{ ' + ', '.join(parts) + ' }'


def block_end(text, j):
    """Index of the bracket closing the one at j."""
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
        elif ch in '[{':
            depth += 1
        elif ch in ']}':
            depth -= 1
            if depth == 0:
                return k
        k += 1


def display_spans(text):
    """(start, end) of each entry of camera.displays, in order."""
    j = text.index('{', text.index('"camera"'))
    a = text.index('[', text.index('"displays"', j))
    b = block_end(text, a)
    out, k = [], a + 1
    while True:
        k = text.find('{', k, b)
        if k < 0:
            return out
        e = block_end(text, k)
        out.append((k, e + 1))
        k = e + 1


def splice(text, displays, generic_cockpit, refit=None):
    """text with the new screens after the file's own, and the file's own screens named in refit
    ({index: screen}) written over; the others stay as they were written."""
    if refit:
        spans = display_spans(text)
        for i in sorted(refit, reverse=True):
            a, b = spans[i]
            text = text[:a] + ser_display(refit[i]) + text[b:]
    if not displays and generic_cockpit:
        return text
    i = text.index('"camera"')
    j = text.index('{', i)
    k = block_end(text, j)
    cam = json.loads(text[j:k + 1])
    indent = '    '
    lines = ',\n'.join(f'{indent}  ' + ser_display(s) for s in displays)
    if not displays:
        pass
    elif 'displays' in cam:
        # the file's own screens stay as written; these go after them
        a = text.index('[', text.index('"displays"', j))
        b = block_end(text, a)
        text = text[:a + 1] + text[a + 1:b].rstrip() + ',\n' + lines + '\n' + indent + text[b:]
    else:
        body = text[j + 1:k].rstrip()
        text = text[:j + 1] + body + f',\n{indent}"displays": [\n' + lines + f'\n{indent}]\n  ' + text[k:]
    if not generic_cockpit and 'generic_cockpit' not in cam and '"displays"' in text[j:]:
        a = text.index('"displays"', j)
        text = text[:a] + '"generic_cockpit": false,\n' + indent + text[a:]
    return text


WS = ' \t\r\n'


def camera_span(text):
    """(start, end) of the camera object's braces."""
    j = text.index('{', text.index('"camera"'))
    return j, block_end(text, j)


def entry(text, j, k, key):
    """(start, end) of '"key": value' directly inside the object text[j..k], or None."""
    depth, i, instr = 0, j, False
    while i < k:
        ch = text[i]
        if instr:
            if ch == '\\':
                i += 2
                continue
            if ch == '"':
                instr = False
        elif ch == '"':
            if depth == 1 and text.startswith(f'"{key}"', i):
                v = i + len(key) + 2
                while text[v] in WS + ':':
                    v += 1
                if text[v] in '[{':
                    return i, block_end(text, v) + 1
                end = v
                while text[end] not in ',}' + WS:
                    end += 1
                return i, end
            instr = True
        elif ch in '[{':
            depth += 1
        elif ch in ']}':
            depth -= 1
        i += 1
    return None


def without(text, key):
    """text with camera.<key> taken out, with its comma and its line."""
    j, k = camera_span(text)
    e = entry(text, j, k, key)
    if e is None:
        return text
    a, b = e
    before = text[:a].rstrip(WS)
    if before.endswith(','):
        return before[:-1] + text[b:]
    # the first entry: the comma after it goes instead
    after = text[b:].lstrip(WS)
    if after.startswith(','):
        after = after[1:]
    return before + text[len(before):a] + after.lstrip(WS)


def as_committed(text, head):
    """The working file with camera.displays and generic_cockpit as the commit has them, so a run
    puts back what an earlier run of this script wrote instead of adding to it."""
    for key in ('displays', 'generic_cockpit'):
        text = without(text, key)
    if head is None:
        return text
    hj, hk = camera_span(head)
    for key in ('generic_cockpit', 'displays'):
        e = entry(head, hj, hk, key)
        if e is None:
            continue
        j, k = camera_span(text)
        body = text[j + 1:k].rstrip(WS)
        text = text[:j + 1] + body + ',\n    ' + head[e[0]:e[1]] + '\n  ' + text[k:]
    return text


def committed(aid):
    rel = 'src/main/resources/data/ashvehicles/ashvehicles/aircraft/' + aid + '.json'
    r = subprocess.run(['git', '-C', REPO, 'show', 'HEAD:' + rel], capture_output=True)
    return r.stdout.decode('utf-8') if r.returncode == 0 else None


def write_beside(path, text, tries=5):
    """Write next to the file and replace it, retrying a few times."""
    import time
    tmp = path + '.mfd.tmp'
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


def source(aid):
    text = open(os.path.join(ROOT, aid + '.json'), encoding='utf-8').read()
    return reseat(aid, as_committed(text, committed(aid)))


def reseat(aid, text):
    """The committed screens follow their crew when tool/seats moved a station to the other end
    (the RAH-66's pilot went forward, so the rear panel's seat 0 screens are seat 1's)."""
    sys.path.insert(0, os.path.join(REPO, 'tool', 'seats'))
    from crew import DISPLAY_SEATS
    remap = DISPLAY_SEATS.get(aid)
    if not remap or '"displays"' not in text:
        return text
    j, k = camera_span(text)
    e = entry(text, j, k, 'displays')
    if e is None:
        return text
    a, b = e
    block = text[a:b]
    import re
    block = re.sub(r'("seat"\s*:\s*)(\d+)', lambda m: m.group(1) + str(remap.get(int(m.group(2)), int(m.group(2)))), block)
    return text[:a] + block + text[b:]


def candidates():
    out = []
    for p in sorted(os.listdir(ROOT)):
        aid = p[:-5]
        d = json.loads(source(aid))
        cam = d['camera']
        if cam.get('generic_cockpit', True) is False and not cam.get('displays'):
            continue   # analogue gauges, by choice
        if not seats_of(d):
            continue   # a drone's eye is its nose camera, there is no panel
        if not F.has_model(aid):
            continue
        eyes = [eye_of(d, i) for i in range(len(seats_of(d)))]
        bare = any(not s.get('buttons') for s in cam.get('displays', []))
        if len(served_by(cam.get('displays', []), eyes)) == len(eyes) and not bare:
            continue
        out.append(aid)
    return out


def without_screens(d):
    """The definition minus what this script may change."""
    d = json.loads(json.dumps(d))
    d['camera'].pop('displays', None)
    d['camera'].pop('generic_cockpit', None)
    return d


if __name__ == '__main__':
    args = sys.argv[1:]
    apply = bool(args) and args[0] == '--apply'
    outdir = None if apply else args[0]
    ids = args[1:] or candidates()
    for aid in ids:
        path = os.path.join(ROOT, aid + '.json')
        text = source(aid)
        d = json.loads(text)
        displays, gc, notes = build(aid, d)
        # the file's own screens without buttons (the Eurofighter's) get them too
        refit = {}
        if any(not raw.get('buttons') for raw in d['camera'].get('displays', [])):
            cubes = F.model_cubes(aid, d)
            drawn = [m for i in range(max(1, len(seats_of(d)))) for m in model_screens(cubes, eye_of(d, i))]
            for i, raw in enumerate(d['camera']['displays']):
                if not raw.get('buttons'):
                    s = as_screen(raw)
                    # turned the way the model's screen cube under it is (the Eurofighter's three left
                    # tilt out and were drawn at the codec's 20 degrees on upright screens)
                    under = [m for m in drawn if np.linalg.norm(m['pos'] - s['pos']) < 0.01]
                    if under and abs(under[0]['tilt'] - s['tilt']) > 1.0:
                        notes.append(f"own {s['page']} screen: tilt {r1(s['tilt'])} -> {r1(under[0]['tilt'])}, "
                                     "as the model's screen")
                        s['tilt'], s['yaw'] = under[0]['tilt'], under[0]['yaw']
                    how = give_buttons(s, cubes)
                    refit[i] = s
                    notes.append(f"own {s['page']} screen: {len(s['buttons'])} buttons from the "
                                 + ('model' if how == 'model' else 'soft keys'))
        print(f'== {aid}')
        for n in notes:
            print('  ' + n)
        for s in displays:
            print(f"    support {s['support']:.2f}  " + ser_display(s)[:140])
        new = splice(text, displays, gc, refit)
        after = json.loads(new)
        assert len(after['camera'].get('displays', [])) == len(d['camera'].get('displays', [])) + len(displays)
        # nothing outside the screens may change
        assert without_screens(after) == without_screens(json.load(open(path, encoding='utf-8')))
        if apply:
            # beside it and then over it: Windows has answered EINVAL to open(path, 'w') on these
            # files (2026-11-04, twice), and a failed open must not leave the definition empty
            write_beside(path, new)
        else:
            os.makedirs(outdir, exist_ok=True)
            open(os.path.join(outdir, aid + '.json'), 'w', encoding='utf-8', newline='\n').write(new)
