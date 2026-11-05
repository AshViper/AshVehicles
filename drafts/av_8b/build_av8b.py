"""Turn the painted Harrier (av_8b.bbmodel next to this file) into the bundled model and a draft definition.
- the four nozzles become four bones, each turning about its own middle (nozzle_left/right and _2)
- the right tailplane gets its own bone so both halves can be elevators; both pivot at the root
- the outrigger doors ride on their legs; stores and pylons drawn into the model are removed
- the model stands on its wheels; gear_down/gear_up animations: nose forward, mains and outriggers aft
usage: python build_av8b.py <out.bbmodel> <draft.json>"""
import os, sys, json, uuid
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'paint_lib'))
import numpy as np
import bb

HERE = os.path.dirname(os.path.abspath(__file__))
S = 0.67                                   # 14.1 m long
AMMO = ['ashvehicles:autocannon_apfsds', 'ashvehicles:autocannon_he', 'ashvehicles:autocannon_heat']

m = bb.Model(os.path.join(HERE, 'av_8b.bbmodel'))
d = m.d
groups = {g['name']: g for g in d['groups']}


def last(e):
    ch = m.chain(e)
    return ch[-1]['name'] if ch else ''


def corners(e):
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    return m.to_world(e, np.array([[x, y, z] for x in (x1, x2) for y in (y1, y2) for z in (z1, z2)], float))


def rewalk():
    m.elements = d['elements']
    m.groups = {g['uuid']: g for g in d['groups']}
    m.parent = {}
    def walk(nodes, chain):
        for n in nodes:
            if isinstance(n, dict):
                walk(n.get('children', []), chain + [m.groups.get(n['uuid'], n)])
            else:
                m.parent[n] = chain
    walk(d['outliner'], [])


def node_of(name):
    uid = groups[name]['uuid']
    def find(nodes):
        for n in nodes:
            if isinstance(n, dict):
                if n['uuid'] == uid:
                    return n
                r = find(n.get('children', []))
                if r:
                    return r
    return find(d['outliner'])


def detach(uuids):
    uuids = set(uuids)
    def prune(nodes):
        out = []
        for n in nodes:
            if isinstance(n, dict):
                n['children'] = prune(n.get('children', []))
                out.append(n)
            elif n not in uuids:
                out.append(n)
        return out
    d['outliner'] = prune(d['outliner'])


def new_group(name, parent, origin, members):
    g = dict(groups['group5'])
    g.update(uuid=str(uuid.uuid4()), name=name, origin=[round(float(v), 4) for v in origin], rotation=[0, 0, 0],
             children=[])
    d['groups'].append(g); groups[name] = g
    detach(members)
    node_of(parent)['children'].append({'uuid': g['uuid'], 'isOpen': False, 'children': list(members)})


def move_group(name, new_parent):
    uid = groups[name]['uuid']
    holder = []
    def pull(nodes):
        out = []
        for n in nodes:
            if isinstance(n, dict) and n['uuid'] == uid:
                holder.append(n); continue
            if isinstance(n, dict):
                n['children'] = pull(n.get('children', []))
            out.append(n)
        return out
    d['outliner'] = pull(d['outliner'])
    node_of(new_parent)['children'].append(holder[0])


# ---- the stores, tanks and pylons drawn into the model: the loadout and the racks draw their own ------------
gone = {e['uuid'] for e in d['elements'] if last(e) in ('bone24', 'bone21') or (last(e) == 'main' and e.get('color') == 2)}
d['elements'] = [e for e in d['elements'] if e['uuid'] not in gone]
detach(gone)
rewalk()
print('stripped', len(gone), 'store and pylon cubes')

# ---- four nozzles, four bones, each about its own middle ---------------------------------------------------
noz = [e for e in d['elements'] if last(e) == 'group5']
for name, side, front in (('NozzleFL', -1, True), ('NozzleFR', 1, True), ('NozzleRL', -1, False), ('NozzleRR', 1, False)):
    mem = [e for e in noz if np.sign(corners(e)[:, 0].mean()) == side and (corners(e)[:, 2].mean() < -30) == front]
    c = np.concatenate([corners(e) for e in mem])
    new_group(name, 'group5', (c.min(0) + c.max(0)) / 2, [e['uuid'] for e in mem])
    print(name, len(mem), 'cubes, pivot', np.round((c.min(0) + c.max(0)) / 2, 1))
rewalk()

# ---- the right tailplane gets its own bone; both turn about their roots --------------------------------------
right_tail = [e for e in d['elements'] if last(e) == 'main' and e.get('color') == 4 and corners(e).mean(0)[2] > 100
              and corners(e).mean(0)[0] > 3]
lt = np.concatenate([corners(e) for e in d['elements'] if last(e) == 'group6'])
root = [-float(np.abs(lt[:, 0]).min()) - 2, float(lt[:, 1].mean()), float((lt[:, 2].min() + lt[:, 2].max()) / 2)]
groups['group6']['origin'] = [round(v, 4) for v in root]
new_group('RTail', 'main', (-root[0], root[1], root[2]), [e['uuid'] for e in right_tail])
print('tailplanes', len(right_tail), 'right cubes, pivots', groups['group6']['origin'], groups['RTail']['origin'])

# ---- the outrigger doors fold away with their legs ---------------------------------------------------------
move_group('LGearHatch', 'LGear')
move_group('RGearHatch', 'RGear')
rewalk()

# ---- stand it on its wheels ----------------------------------------------------------------------------------
low = min(corners(e)[:, 1].min() for e in d['elements'])
for e in d['elements']:
    e['from'][1] -= low; e['to'][1] -= low
    if 'origin' in e: e['origin'][1] -= low
for g in d['groups']:
    g['origin'][1] -= low
rewalk()
print('lowered by', round(low, 3))

# ---- gear animations ----------------------------------------------------------------------------------------
def kf(channel, t, x, y, z):
    return {'channel': channel, 'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}], 'uuid': str(uuid.uuid4()),
            'time': t, 'color': -1, 'interpolation': 'linear'}

UP = {'FGear': (-90, 0, 0),            # file x is flipped: -90 swings the leg forward
      'BGear': (90, 0, 0), 'LGear': (90, 0, 0), 'RGear': (90, 0, 0)}   # the bicycle main and the outriggers fold aft
DOORS = ('FLGearHatch', 'FRGearHatch', 'BGearHatch')
anims = []
for name, legs_at, doors_at in (('gear_down', [(0.0, 1), (0.3, 1), (1.0, 0)], [(0.0, 0), (0.3, 1)]),
                                ('gear_up', [(0.0, 0), (0.7, 1)], [(0.0, 1), (0.7, 1), (1.0, 0)])):
    animators = {}
    for g, val in UP.items():
        animators[groups[g]['uuid']] = {'name': g, 'type': 'bone', 'keyframes':
            [kf('rotation', t, *(np.array(val) * w).round(3).tolist()) for t, w in legs_at]}
    for g in DOORS:
        animators[groups[g]['uuid']] = {'name': g, 'type': 'bone', 'keyframes':
            [kf('scale', t, *([round(0.01 + 0.99 * w, 3)] * 3)) for t, w in doors_at]}
    anims.append({'uuid': str(uuid.uuid4()), 'name': name, 'loop': 'hold', 'override': False, 'length': 1.0,
                  'snapping': 20, 'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '',
                  'loop_delay': '', 'animators': animators})
d['animations'] = anims
d['model_identifier'] = 'av_8b'
d['name'] = 'av_8b'
json.dump(d, open(sys.argv[1], 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))

# ---- measurements in the definition frame (x as the file, z flipped, model units -> blocks) -------------------
def to_def(p):
    p = np.asarray(p, float)
    return np.array([p[0], p[1], -p[2]]) * S / 16


def skin_points():
    """every face of the airframe as 1-unit points: the underside a station hangs from (rotated wing cubes
    are measured on their faces, not their bounding boxes)."""
    pts = []
    moving = {'LFlap', 'RFlap', 'VBFlap', 'group6', 'RTail', 'FGear', 'BGear', 'LGear', 'RGear', 'LGearHatch',
              'RGearHatch', 'FLGearHatch', 'FRGearHatch', 'BGearHatch'}
    for e in d['elements']:
        names = {g['name'] for g in m.chain(e)}
        if names & moving or names & {'group5', 'cockpit', 'group14'}:
            continue
        for face in e['faces']:
            w, h = bb.face_axes(e, face)
            t, s = np.meshgrid(np.linspace(0, 1, max(2, int(w) + 2)), np.linspace(0, 1, max(2, int(h) + 2)))
            pts.append(m.to_world(e, bb.face_point(e, face, t.ravel(), s.ravel())))
    return np.concatenate(pts)


P = skin_points()


def underside(x, z, r=1.5):
    near = P[(np.abs(P[:, 0] - x) < r) & (np.abs(P[:, 2] - z) < r)]
    return float(near[:, 1].min())


hardpoints = []
for name, x, z in (('pylon1', 32, -10), ('pylon2', 46, 0), ('pylon3', 64, 12)):
    for s, side in ((-1, 'left'), (1, 'right')):
        y = underside(s * x, z) * S / 16 - 0.42
        p = to_def([s * x, 0, z]); p[1] = y
        hardpoints.append({'name': '%s_%s' % (name, side), 'pos': p.round(3).tolist(), 'kind': 'weapon'})
for name, z, kind, gap in (('centreline', -12, 'weapon', 0.42), ('pod', -44, 'special', 0.2)):
    p = to_def([0, 0, z]); p[1] = underside(0, z) * S / 16 - gap
    hardpoints.append({'name': name, 'pos': p.round(3).tolist(), 'kind': kind})
gz = 4
g = to_def([0, 0, gz]); g[1] = underside(0, gz) * S / 16 + 0.15
hardpoints.append({'name': 'gun', 'pos': g.round(3).tolist(), 'fixed': 'ashvehicles:gau_22', 'ammunition': AMMO})
for h in hardpoints:
    print(' ', h['name'], h['pos'])

# the seat: the cushion's top, a little aft; low enough that the head clears the canopy
pan = next(e for e in d['elements'] if last(e) == 'cockpit' and e.get('color') == 6
           and max(np.ptp(corners(e)[:, 0]), np.ptp(corners(e)[:, 2])) > 9 and corners(e)[:, 1].mean() < 46)
pc = corners(pan)
glass = np.concatenate([corners(e) for e in d['elements'] if last(e) == 'group14'])
zc = pc[:, 2].mean() + 1.5
canopy_top = glass[np.abs(glass[:, 2] - zc) < 14][:, 1].max() * S / 16
seat = to_def([0, pc[:, 1].max(), zc]); seat[1] = min(seat[1] - 0.15, canopy_top - 1.48)
eye = seat + np.array([0, 1.02, 0.08])
print('seat', seat.round(3), 'eye', eye.round(3), 'canopy top', round(canopy_top, 3))

A = np.concatenate([corners(e) for e in d['elements']])
span = (A[:, 0].max() - A[:, 0].min()) * S / 16
length = (A[:, 2].max() - A[:, 2].min()) * S / 16
print('span', round(span, 2), 'length', round(length, 2), 'height', round(A[:, 1].max() * S / 16, 2))

# top speed on the fleet's curve: 60.24 x real^0.35 (AV-8B+ 1083 km/h at sea level)
v = min(1083, 60.24 * 1083 ** 0.35) / 72
thrust = round(0.01153 * (105.0 / (10.41 * 9.81)) / (88.4 / (14.6 * 9.81)), 5)   # Su-25 scaled by thrust/weight
stall = 1.8
lift = 0.02453 / (stall ** 2 * 5.5 * np.radians(15))
draft = {
    'hitbox': {'width': 5, 'height': 3, 'tracking_range': 12, 'boxes': []},
    'model': {'scale': S, 'bones': {
        'elevator_left': 'group6', 'elevator_right': 'RTail', 'aileron_left': 'LFlap', 'aileron_right': 'RFlap',
        'rudder': 'VBFlap', 'nose_gear': 'FGear', 'left_gear': 'LGear', 'right_gear': 'RGear',
        'nozzle_left': 'NozzleFL', 'nozzle_right': 'NozzleFR', 'nozzle_left_2': 'NozzleRL', 'nozzle_right_2': 'NozzleRR'}},
    'engine': {'max_thrust': thrust, 'throttle_rate': 0.014, 'spool_rate': 0.07, 'sea_level_density': 1},
    'wing': {'max_speed': round(v, 2), 'stall_speed': stall, 'lift': round(float(lift), 6), 'lift_slope': 5.5,
             'stall_angle': 15, 'drag': round(thrust / v ** 2, 7), 'induced_drag': 0.00039, 'lateral_drag': 0.15,
             'ground_effect': 0.28, 'span': round(float(span), 2)},
    'handling': {'pitch_rate': 1.15, 'roll_rate': 2.9, 'yaw_rate': 1.0, 'control_lag': 0.2, 'weathervane': 3,
                 'alpha_limit': 0.88, 'aero_damping': 0.06},
    'airframe': {'nation': 'us', 'cost': 270, 'mass': 10410, 'payload': 6000, 'health': 250, 'crash_speed': 2.6,
                 'explosion_power': 3, 'max_g': 7, 'ejection': {'speed': 1.4, 'canopy': 22},
                 'seats': [seat.round(3).tolist()]},
    'vtol': {'max_angle': 90, 'rate': 2, 'lift_thrust': 0.031, 'authority': 0.9, 'hover_drag': 0.06,
             'conversion_speed': 2.2},
    'landing_gear': {'cycle_ticks': 40, 'drag_penalty': 0.6, 'rolling_friction': 0.999, 'brake_friction': 0.986,
                     'lateral_friction': 0.5, 'steer_rate': 1.1, 'steer_fade': 1.3},
    'flaps': {'cycle_ticks': 20, 'drag_penalty': 0.5, 'lift_bonus': 0.4},
    'camera': {'pos': [0.0, 3.5, -12.0], 'cockpit': eye.round(3).tolist()},
    'sound': {'volume': 1, 'idle_volume': 0.35, 'pitch_min': 0.7, 'pitch_max': 1.25, 'range': 1024},
    'radar': {'range': 3500, 'arc': 60, 'sweep_ticks': 8, 'warning_range': 4500},
    'signature': {'radar': 1.0, 'store': 0.2, 'heat': 0.6},
    'countermeasures': {'flares': 60, 'chaff': 60, 'interval_ticks': 6, 'reload_ticks': 300, 'speed': 0.35,
                        'salvo': 2, 'spread': 100},
    'hardpoints': hardpoints,
}
json.dump(draft, open(sys.argv[2], 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('top speed', round(v * 72), 'km/h, thrust', thrust, 'drag', draft['wing']['drag'], 'lift', draft['wing']['lift'])
