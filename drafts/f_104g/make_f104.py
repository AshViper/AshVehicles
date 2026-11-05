"""The painted F-104G into a bundled bbmodel and a draft definition (boxes come from build3.js).
Strips the drawn pylons (racks draw their own) but keeps the tip tanks, which no station can carry; stands the model
on its wheels; moves the gear pivots to the strut tops; writes gear animations; measures seat, eye, nozzle and
station heights by sampling faces (the wing has anhedral, so bounding boxes read it low).
usage: python make_f104.py <painted.bbmodel>"""
import sys, json, uuid
sys.path.insert(0, r'C:\Users\nagis\AppData\Local\Temp\claude\F--Java-AshVehicles\c1f572e4-6100-4bd4-bd04-8835721b751a\scratchpad\lib')
import numpy as np
import bb

ROOT = 'F:/Java/AshVehicles/'
HERE = 'C:/Users/nagis/AppData/Local/Temp/claude/F--Java-AshVehicles/c1f572e4-6100-4bd4-bd04-8835721b751a/scratchpad/'
ID = 'f_104g'
S = 0.65                          # 16.66 m over the probe -> 16.6 blocks; span over the tip tanks 7.6
AMMO = ['ashvehicles:autocannon_apfsds', 'ashvehicles:autocannon_he', 'ashvehicles:autocannon_heat']
STRIP = {'bone34', 'bone35', 'bone36', 'bone37'}

m = bb.Model(sys.argv[1])
d = m.d
d['name'] = ID; d['model_identifier'] = ID
d['textures'][0]['name'] = ID


def last(e):
    ch = m.chain(e)
    return ch[-1]['name'] if ch else ''


def rebuild():
    m.elements = d['elements']; m.parent = {}

    def walk(nodes, chain):
        for n in nodes:
            if isinstance(n, dict):
                walk(n.get('children', []), chain + [m.groups.get(n['uuid'], n)])
            else:
                m.parent[n] = chain
    walk(d['outliner'], [])


gone = {e['uuid'] for e in d['elements'] if last(e) in STRIP}
d['elements'] = [e for e in d['elements'] if e['uuid'] not in gone]


def prune(nodes):
    out = []
    for n in nodes:
        if isinstance(n, dict):
            n['children'] = prune(n.get('children', []))
            if n.get('name') in STRIP or m.groups.get(n['uuid'], {}).get('name') in STRIP:
                continue
            out.append(n)
        elif n not in gone:
            out.append(n)
    return out
d['outliner'] = prune(d['outliner'])
d['groups'] = [g for g in d['groups'] if g['name'] not in STRIP]
m.groups = {g['uuid']: g for g in d['groups']}
rebuild()
print('stripped', len(gone), 'pylon cubes')


def corners(e):
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    return m.to_world(e, np.array([[x, y, z] for x in (x1, x2) for y in (y1, y2) for z in (z1, z2)], float))


def in_group(name, deep=True):
    return [e for e in d['elements'] if ((name in [g['name'] for g in m.chain(e)]) if deep else last(e) == name)]


# ---- stand it on its wheels ------------------------------------------------------------------
low = min(corners(e)[:, 1].min() for e in d['elements'])
for e in d['elements']:
    e['from'][1] -= low; e['to'][1] -= low
    if 'origin' in e: e['origin'][1] -= low
for g in d['groups']:
    g['origin'][1] -= low
print('lowered by', round(low, 3))

# ---- gear pivots at the strut tops -------------------------------------------------------------
by_name = {g['name']: g for g in d['groups']}
pivots = {}
for leg in ('fr_wheels', 'r_wheels', 'r_wheels2'):
    own = np.concatenate([corners(e) for e in in_group(leg, deep=False)])
    top = own[np.argmax(own[:, 1])]
    g = by_name[leg]
    assert not any(g.get('rotation', [0, 0, 0]))
    old = list(g['origin'])
    g['origin'] = [round(float(top[0] if leg != 'fr_wheels' else own[:, 0].mean()), 4), round(float(top[1]), 4),
                   round(float(own[:, 2].mean()), 4)]
    pivots[leg] = np.array(g['origin'])
    print('pivot', leg, [round(v, 1) for v in old], '->', g['origin'])


def wheel_centre(leg):
    tyre = {'fr_wheels': 'bone24', 'r_wheels': 'bone28', 'r_wheels2': 'bone30'}[leg]
    return np.concatenate([corners(e) for e in in_group(tyre)]).mean(0)


# ---- gear animations -----------------------------------------------------------------------------
# The mains splay out at ~57 degrees, so folding about z alone parks the wheel above the wing or under the other side.
# Rotate about x by 90 (forward), then about y so the wheel lands on the strut-top line: tan b = -vx / vy.
# Keyframe x and y are flipped against the cube convention (the nose's x -90 folds forward), z is not.
def kf(channel, t, x, y, z):
    return {'channel': channel, 'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}], 'uuid': str(uuid.uuid4()),
            'time': t, 'color': -1, 'interpolation': 'linear'}


UP = {'fr_wheels': (-90.0, 0.0, 0.0)}
for leg in ('r_wheels', 'r_wheels2'):
    v = wheel_centre(leg) - pivots[leg]
    b = np.degrees(np.arctan2(-v[0], v[1]))
    if abs(b) > 90:
        b -= 180 * np.sign(b)
    UP[leg] = (-90.0, round(float(-b), 2), 0.0)
    print('fold', leg, 'v', v.round(1), '-> x -90, y', round(float(-b), 1))

anims = []
for name, legs_at in (('gear_down', [(0.0, 1), (1.0, 0)]), ('gear_up', [(0.0, 0), (1.0, 1)])):
    animators = {}
    for g, val in UP.items():
        animators[by_name[g]['uuid']] = {'name': g, 'type': 'bone', 'keyframes':
                                         [kf('rotation', t, *(np.array(val) * w).round(3).tolist()) for t, w in legs_at]}
    anims.append({'uuid': str(uuid.uuid4()), 'name': name, 'loop': 'hold', 'override': False, 'length': 1.0,
                  'snapping': 20, 'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '',
                  'loop_delay': '', 'animators': animators})
d['animations'] = anims

out_bb = ROOT + 'src/main/resources/assets/ashvehicles/bbmodel/entity/%s.bbmodel' % ID
json.dump(d, open(out_bb, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('wrote', out_bb)


# ---- measurements, by sampling every face of the fixed airframe -----------------------------------
def to_def(p):
    p = np.asarray(p, float)
    return np.array([p[0], p[1], -p[2]]) * S / 16


moving = set()
for leg in ('fr_wheels', 'r_wheels', 'r_wheels2'):
    moving |= {e['uuid'] for e in in_group(leg)}
pts = []
for e in d['elements']:
    if e['uuid'] in moving:
        continue
    for f in e['faces']:
        w, h = bb.face_axes(e, f)
        t, s = np.meshgrid((np.arange(max(2, int(w) + 1)) + 0.5) / max(2, int(w) + 1),
                           (np.arange(max(2, int(h) + 1)) + 0.5) / max(2, int(h) + 1))
        pts.append(m.to_world(e, bb.face_point(e, f, t.ravel(), s.ravel())))
pts = np.concatenate(pts)


def column(x, z, r=1.5):
    c = pts[(np.abs(pts[:, 0] - x) <= r) & (np.abs(pts[:, 2] - z) <= r)]
    return c[:, 1].min(), c[:, 1].max()


def station(x, z):
    lo, _ = column(x, z)
    p = to_def([x, lo, z]); p[1] -= 0.42
    return p.round(3).tolist()


# seat: the flat pan of the seat, then "pan - 0.15" or "canopy top - 1.48", the lower
seat_els = [e for e in in_group('bone38') if e.get('color') == 2]
pan = max(seat_els, key=lambda e: (lambda c: np.ptp(c[:, 0]) * np.ptp(c[:, 2]) / max(np.ptp(c[:, 1]), 0.5))(corners(e)))
pc = corners(pan)
surface = pc[:, 1].max(); zc = pc[:, 2].mean() + 1.5
glass = np.concatenate([corners(e) for e in in_group('bone23')])
near = glass[np.abs(glass[:, 2] - zc) < 14]
canopy_top = (near if len(near) else glass)[:, 1].max() * S / 16
seat = to_def([0, surface, zc]); seat[1] = min(seat[1] - 0.15, canopy_top - 1.48)
eye = seat + np.array([0, 1.02, 0.08])
print('seat surface', round(surface * S / 16, 2), 'canopy', round(canopy_top, 2), '-> seat', seat.round(3))

hole = np.concatenate([corners(e) for e in in_group('octagon') if e.get('color') == 6])
nozzles = [to_def([0, hole[:, 1].mean(), hole[:, 2].max()]).round(2).tolist()]

gun_els = in_group('bone14')
gc = np.concatenate([corners(e) for e in gun_els]).mean(0)
gun = to_def(gc).round(2).tolist()

hardpoints = []
for name, x, z in (('pylon1', 46.7, 45.0), ('pylon2', 63.0, 58.0)):   # inner where the model drew one, outer
    for s, side in ((-1, 'left'), (1, 'right')):
        hardpoints.append({'name': '%s_%s' % (name, side), 'pos': station(s * x, z), 'kind': 'weapon'})
hardpoints.append({'name': 'centreline', 'pos': station(0, 10.0), 'kind': 'weapon'})
hardpoints.append({'name': 'gun', 'pos': gun, 'fixed': 'ashvehicles:m61', 'ammunition': AMMO})
for h in hardpoints:
    print(' ', h['name'], h['pos'])

span = (pts[:, 0].max() - pts[:, 0].min()) * S / 16
length = (pts[:, 2].max() - pts[:, 2].min()) * S / 16
print('span', round(span, 2), 'length', round(length, 2), 'height', round(pts[:, 1].max() * S / 16, 2))

draft = {
    'hitbox': {'width': 5, 'height': 3, 'tracking_range': 12, 'boxes': []},
    'model': {'scale': S, 'bones': {'nose_gear': 'fr_wheels', 'left_gear': 'r_wheels', 'right_gear': 'r_wheels2'}},
    # J79-GE-11A: 44.5 kN dry / 69.4 kN wet over 9365 kg, scaled from the MiG-21MF's 40.2 kN over 8212 kg
    'engine': {'max_thrust': 0.0157, 'throttle_rate': 0.012, 'spool_rate': 0.08, 'sea_level_density': 1,
               'afterburner': {'thrust': 1.56, 'light_rate': 0.22, 'heat': 5, 'nozzles': nozzles}},
    # Mach 2 (2137 km/h) -> 60.24 x 2137^0.35 = 881 km/h = 12.24 b/t; drag = thrust x 1.56 / 12.24^2
    # stall 2.0 (the hot one of the jets): lift = 0.02453 / (2.0^2 x 4.5 x rad 15)
    'wing': {'max_speed': 12.24, 'stall_speed': 2.0, 'lift': 0.005206, 'lift_slope': 4.5, 'stall_angle': 15,
             'drag': 0.0001635, 'induced_drag': 0.0005, 'lateral_drag': 0.15, 'ground_effect': 0.3, 'span': 6.68},
    'handling': {'pitch_rate': 1.0, 'roll_rate': 3.2, 'yaw_rate': 1.0, 'control_lag': 0.2, 'weathervane': 3,
                 'alpha_limit': 0.8, 'aero_damping': 0.06},
    'airframe': {'nation': 'us', 'cost': 150, 'mass': 9365, 'payload': 1814, 'health': 190, 'crash_speed': 2.5,
                 'explosion_power': 2.5, 'max_g': 7, 'ejection': {'speed': 1.4, 'canopy': 22},
                 'seats': [seat.round(3).tolist()]},
    'landing_gear': {'cycle_ticks': 40, 'drag_penalty': 0.6, 'rolling_friction': 0.999, 'brake_friction': 0.986,
                     'lateral_friction': 0.5, 'steer_rate': 1, 'steer_fade': 1.3},
    'flaps': {'cycle_ticks': 20, 'drag_penalty': 0.5, 'lift_bonus': 0.4},
    'camera': {'pos': [0.0, 3.5, -12.0], 'cockpit': eye.round(3).tolist()},
    'sound': {'volume': 1, 'idle_volume': 0.35, 'pitch_min': 0.7, 'pitch_max': 1.25, 'range': 1024},
    'radar': {'range': 1500, 'arc': 30, 'sweep_ticks': 12, 'warning_range': 2500},
    'signature': {'radar': 0.8, 'store': 0.25},
    'hardpoints': hardpoints,
}
json.dump(draft, open(HERE + '%s.draft.json' % ID, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
json.dump({}, open(HERE + '%s.buildroles.json' % ID, 'w'))
print('draft written')
