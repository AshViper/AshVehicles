"""Real station layouts, one entry per aircraft. x is the distance from the centreline in blocks
(the machines are drawn at real size, so blocks are metres). Order of 'stations' does not decide
slot order: kept stations keep their slot, removed slots are refilled by new ones, extras go last.

W  wing pylon (z and y measured)      B  fuselage station at (x, z)
PW pod under the wing                 P  pod under the fuselage at (x, z)
T  wingtip rail
"""


def _n(names):
    return tuple(names) if isinstance(names, (tuple, list)) else (names,)


def W(names, x, **kw):
    return dict(names=_n(names), place='wing', x=x, **kw)


def PW(names, x, **kw):
    return dict(names=_n(names), place='podwing', x=x, **kw)


def B(names, x, z, **kw):
    return dict(names=_n(names), place='body', x=x, z=z, **kw)


def P(names, x, z, **kw):
    return dict(names=_n(names), place='pod', x=x, z=z, **kw)


def T(names, **kw):
    return dict(names=_n(names), place='tip', **kw)


def lr(base):
    return (base + '_left', base + '_right')


SPEC = {}

# A-10C: 11 stations. 6 on the centreline, 5/7 beside it, 4/8 on the centre wing inboard of the
# gear pods, 3/9 just outboard of them, 2/10 and 1/11 on the outer panels (1/11 = ECM / AIM-9 pod).
SPEC['a_10'] = dict(stations=[
    B('station6', 0.0, -1.0),
    B(('station5', 'station7'), 0.62, -1.0),
    W(('station4', 'station8'), 1.6, z=-2.35),
    W(('station3', 'station9'), 3.45),
    W(('station2', 'station10'), 4.45),
    PW(('station1', 'station11'), 5.45),
])

# AC-130U / C-130H: no store stations in the real aircraft beyond the tank pylons between the
# engines (the tank is part of the model). Pods go outboard of the outer engines, where the
# MC/HC-130 hang their pods.
SPEC['ac_130u'] = dict(stations=[PW(('sensor_left', 'sensor_right'), 12.5)])
SPEC['c_130'] = dict(stations=[PW(('sensor_left', 'sensor_right'), 12.5)])

# AH-64: two pylons per stub wing and the tip mount for air-to-air. Pods under the chin, inboard of
# the main gear legs, which are boxes in this hitbox.
SPEC['ah-64'] = dict(stations=[
    W(('inner_left', 'inner_right'), 1.5),
    W(('mid_left', 'mid_right'), 2.33),
    W(('outer_left', 'outer_right'), 2.83),
    P(('sensor_left', 'sensor_right'), 0.4, 2.73),
], skip_boxes=['tyre'])

# AV-8B: 3 pylons per wing (outer one outboard of the outrigger), centreline between the gun
# pods, targeting pod forward of it.
SPEC['av_8b'] = dict(stations=[
    W(lr('pylon1'), 1.75),
    W(lr('pylon2'), 2.45),
    W(lr('pylon3'), 3.55),
    B('centreline', 0.0, -0.2),
    P('pod', 0.0, 2.9),
])

# B-52H: one heavy pylon between the fuselage and the inboard engine pod. EVS turrets under the nose.
SPEC['b-52'] = dict(stations=[
    W(lr('pylon'), 6.76),
    P(lr('sensor'), 1.08, 17.15),
])

# B-1B: forward external hardpoints under the fuselage, Sniper pod ahead of them.
SPEC['b_1'] = dict(stations=[
    B(lr('pylon'), 1.47, 9.41),
    P(lr('sensor'), 1.08, 12.25),
])

SPEC['b_2'] = dict(stations=[P(lr('sensor'), 1.6, 6.0)])
SPEC['b_21'] = dict(stations=[P(lr('sensor'), 1.4, 5.2)])

# Typhoon: 4 per wing, 4 semi-conformal AMRAAM stations, centreline; pods under the intake.
SPEC['eurofighter'] = dict(stations=[
    W(lr('pylon1'), 2.1),
    W(lr('pylon2'), 2.95),
    W(lr('pylon3'), 3.8),
    W(lr('pylon4'), 4.65),
    B(lr('fuselage_fore'), 0.8, 0.8),
    B(lr('fuselage_aft'), 0.8, -3.2),
    B('centreline', 0.0, -1.0),
    P(lr('sensor'), 0.6, 3.2),
])

# F-100D: 3 pylons per wing and the centreline.
SPEC['f_100d'] = dict(stations=[
    W(lr('pylon1'), 1.33),
    W(lr('pylon2'), 2.25),
    W(lr('pylon3'), 3.1),
    B('centreline', 0.0, 0.5),
])

# F-104G: 2 pylons per wing (tip tanks are part of the airframe) and the centreline.
SPEC['f_104g'] = dict(stations=[
    W(lr('pylon1'), 1.9),
    W(lr('pylon2'), 2.56),
    B('centreline', 0.0, -0.41),
])

SPEC['f_117'] = dict(stations=[P('sensor', 0.0, 3.2)])

# F-14: glove pylon (AIM-9 shoulder rail + AIM-54/7 below), nacelle station (tank), 4 Phoenix
# pallets in the tunnel. Was 2 glove pylons on one line, no nacelle stations.
SPEC['f_14'] = dict(stations=[
    W(lr('pylon1'), 2.6),
    B(lr('nacelle'), 1.5, 0.6),
    B(lr('pylon3'), 0.5, 0.2),
    B(lr('pylon4'), 0.5, -3.4),
    P(lr('sensor'), 0.75, 2.6),
], remove=['pylon2_left', 'pylon2_right'])

# F-15C: one wing pylon per side (LAU-128 side rails = twin rail), 4 AIM-7/120 fuselage
# stations at the lower corners, centreline. Was 3 pylons per wing.
SPEC['f_15'] = dict(stations=[
    W(lr('pylon1'), 3.0),
    B(lr('pylon4'), 1.35, 0.0),
    B(lr('pylon5'), 1.35, -3.6),
    B('centreline', 0.0, -1.6),
    P(lr('sensor'), 1.25, 2.6),
], remove=['pylon2_left', 'pylon2_right', 'pylon3_left', 'pylon3_right'])

# F-16C: 9 stations + the two chin stations on the intake.
SPEC['f_16'] = dict(stations=[
    T(lr('wingtip')),
    W(lr('pylon1'), 3.75),
    W(lr('pylon2'), 2.85),
    W(lr('pylon3'), 1.95),
    B('centreline', 0.0, -1.6),
    P(lr('sensor'), 0.62, 0.9),
])

# F-2A: 13 stations: tips, 4 per wing, centreline, 2 intake pod stations. Was 3 per wing.
SPEC['f_2'] = dict(stations=[
    T(lr('wingtip')),
    W(lr('pylon1'), 4.35),
    W(lr('pylon2'), 3.55),
    W(lr('pylon3'), 2.75),
    W(lr('pylon4'), 1.95),
    B('centreline', 0.0, -2.3),
    P(lr('sensor'), 0.62, 0.9),
])

# F-4E: 2 pylons per wing (inboard with AIM-9 shoulder rails, outboard tank), 4 semi-recessed
# AIM-7 wells (front pair carries the pods, as the real ECM / Pave Spike did), centreline.
# Was 3 per wing and no centreline.
SPEC['f_4'] = dict(stations=[
    W(lr('pylon1'), 2.45),
    W(lr('pylon2'), 4.0),
    B(lr('pylon4'), 0.9, -2.6),
    B('centreline', 0.0, -0.9),
    P(lr('sensor'), 0.9, 1.0),
], remove=['pylon3_left', 'pylon3_right'])

# F/A-18E: 11 stations: tips, 3 per wing, 2 nacelle, centreline. The outer pair was a plain
# pylon at the tip; there was no centreline.
SPEC['fa_18e'] = dict(stations=[
    W(lr('pylon1'), 2.41),
    W(lr('pylon2'), 3.5),
    W(lr('pylon3'), 4.59),
    T(lr('pylon4')),
    B(lr('pylon5'), 0.85, -1.4),
    B('centreline', 0.0, -2.3),
    P(lr('sensor'), 0.5, 3.4),
])

# JAS 39C: tips, 2 per wing, centreline, pods under the intakes. Outer pair was a plain pylon at the tip.
SPEC['jas_39'] = dict(stations=[
    W(lr('pylon1'), 1.6),
    W(lr('pylon2'), 2.6),
    T(lr('pylon3')),
    B('centreline', 0.0, -0.4),
    P(lr('sensor'), 0.68, 1.36),
])

# Ju 87 D: crutch on the centreline, 2 racks per outer wing.
SPEC['ju_87d'] = dict(stations=[
    B('crutch', 0.0, 0.9),
    W(('pylon_left_inner', 'pylon_right_inner'), 2.6),
    W(('pylon_left_outer', 'pylon_right_outer'), 3.9),
])

SPEC['mh_60m'] = dict(stations=[P(lr('sensor'), 0.8, 4.4)])

SPEC['mig_19s'] = dict(stations=[
    W(lr('pylon1'), 1.82),
    W(lr('pylon2'), 2.57),
])

SPEC['mig_21mf'] = dict(stations=[
    W(lr('pylon1'), 1.92),
    W(lr('pylon2'), 2.57),
    B('centreline', 0.0, -0.25),
])

# MiG-29 (9.12): 3 per wing and the centreline between the engines.
SPEC['mig_29'] = dict(stations=[
    W(lr('pylon1'), 2.1),
    W(lr('pylon2'), 3.2),
    W(lr('pylon3'), 4.3),
    B('centreline', 0.0, -1.5),
    P(lr('sensor'), 0.9, 1.6),
])

# MiG-31: 4 R-33 under the belly in tandem pairs, 2 pylons per wing (inner R-40/tank, outer R-60).
# Was 1 per wing.
SPEC['mig_31'] = dict(stations=[
    B(lr('fuselage1'), 0.85, 1.2),
    B(lr('fuselage2'), 0.85, -2.6),
    W(lr('pylon1'), 3.1),
    W(lr('pylon2'), 4.7),
    P(lr('sensor'), 0.55, 3.6),
])

# MQ-9: 3 per wing (inboard 1500 lb, middle 600-750 lb, outboard 150 lb). MTS ball under the nose.
SPEC['mq_9'] = dict(stations=[
    W(lr('inner'), 1.6),
    W(lr('middle'), 2.7),
    W(lr('outer'), 3.8),
    P('sensor', 0.0, 4.1),
])

SPEC['p_51'] = dict(stations=[W(lr('pylon'), 2.0)])
SPEC['p_51d'] = dict(stations=[W(lr('pylon'), 2.2)])
SPEC['rah_66'] = dict(stations=[P(lr('sensor'), 0.4, 4.9)])

# Su-25: 5 pylons per wing (outer one for R-60). Was 4 per wing, asymmetric, with duplicate names.
SPEC['su_25'] = dict(stations=[
    W(lr('pylon1'), 1.85),
    W(lr('pylon2'), 2.9),
    W(lr('pylon3'), 3.95),
    W(lr('pylon4'), 4.95),
    W(lr('pylon5'), 5.85),
    P('sensor', 0.0, 1.8),
], rename_by_pos='pylon1')

# Su-27: tips, 2 per wing, 2 under the nacelles, 2 tandem in the tunnel.
SPEC['su_27'] = dict(stations=[
    W(lr('pylon1'), 3.0),
    W(lr('pylon2'), 4.6),
    T(lr('wingtip')),
    B(lr('intake'), 1.0, -3.8),
    B('centre_fore', 0.0, -2.0),
    B('centre_aft', 0.0, -6.0),
    P(lr('sensor'), 1.1, -0.6),
])

# Su-30SM: tips, 3 per wing, 2 nacelle, 2 tunnel.
SPEC['su_30sm'] = dict(stations=[
    W(lr('pylon1'), 2.46, z=-2.0),
    W(lr('pylon2'), 3.69),
    W(lr('pylon3'), 4.92),
    T(lr('wingtip')),
    B(lr('pylon5'), 1.0, -3.2),
    B('centre_fore', 0.0, -1.2),
    B('centre_aft', 0.0, -5.6),
    P(lr('sensor'), 1.0, 0.0),
])

# Su-33: tips, 3 per wing, 2 nacelle, 2 tunnel (12). Outer pair was a plain pylon at the tip, no tunnel.
SPEC['su_33'] = dict(stations=[
    W(lr('pylon1'), 2.6),
    W(lr('pylon2'), 3.75),
    W(lr('pylon3'), 4.9),
    T(lr('pylon4')),
    B(lr('pylon5'), 1.0, -3.2),
    B('centre_fore', 0.0, -1.2),
    B('centre_aft', 0.0, -5.6),
    P(lr('sensor'), 1.0, 0.0),
])

# Su-34: tips, 3 per wing, 2 nacelle, 2 centreline (12). Outer pair floated beyond the tip.
SPEC['su_34'] = dict(stations=[
    W(lr('pylon1'), 3.2),
    W(lr('pylon2'), 4.4),
    W(lr('pylon3'), 5.6),
    T(lr('pylon4')),
    B(lr('pylon5'), 1.0, -3.0),
    B('centre_fore', 0.0, -1.0),
    B('centre_aft', 0.0, -5.4),
    P(lr('sensor'), 1.0, 0.4),
])

# Su-57: 6 external stations, 2 under each wing and 2 under the intake ducts, outboard of the
# main bays. The bays are left alone.
SPEC['su_57'] = dict(stations=[
    W(lr('pylon4'), 4.0, z=-2.3),
    W(lr('pylon5'), 5.6),
    B(lr('intake'), 2.0, 1.2),
])

# Tu-95MS: inner pylon between the fuselage and the inner nacelle, outer between the nacelles.
SPEC['tu_95'] = dict(stations=[
    W(lr('pylon1'), 3.52),
    W(lr('pylon2'), 9.1, dz=0.9),
])

SPEC['x_47b'] = dict(stations=[P('sensor', 0.0, 3.2)])

SPEC['yak_9'] = dict(stations=[W(lr('pylon'), 1.5)])

# F-22: 4 underwing stations (ferry tanks, AIM-9 / AIM-120 when stealth does not matter), the
# inboard pair just outboard of the wing root. Bays untouched.
SPEC['f_22'] = dict(stations=[
    W(lr('wing1'), 3.6),
    W(lr('wing2'), 4.85),
    P(lr('sensor'), 0.64, 1.65),
])

# F-35B: 6 wing stations, real stations 3/9 (inboard), 2/10 and 1/11 (outboard, AIM-9X).
# The centreline station is the gun pod, already fixed. Bays untouched.
SPEC['f_35b'] = dict(stations=[
    W(lr('inner'), 2.75),
    W(lr('middle'), 3.7),
    W(lr('outer'), 4.6),
    P('sensor', 0.0, 1.28),
])
