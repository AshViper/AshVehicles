"""Who sits where in each aircraft: the real crew stations, in seat order.

Seat order is the file's and does not change: stations (`hardpoints`-side `stations[].seat`)
and displays (`camera.displays[].seat`) refer to seats by index. A station that moves to where
the real crew member sits keeps its index.

Roles
    pilot    seat 0, at the controls. Its eye is camera.cockpit (the HUD plate hangs off that)
    crew     another cockpit seat (RIO, WSO, CPG, co-pilot): measured like the pilot, own eye
    gunner   a seat that works a gun station and looks through it: the body is placed, the
             eye written in the file is kept (it sits at the gun, not in the head)
    cabin    a passenger or a rear-facing gunner: the body is placed, the eye is the rider's own

Keys of a station (all optional; anything left out comes from the file as it is)
    x, z     where the real seat is. x is the file's: + is the right
    y        the seat point, when measuring cannot be trusted
    eye      [x, y, z] written as it is
    back     False: do not slide the rider onto the seat back (no seat back in the model)
    ceiling  the height the head must stay under, when the model has no glass over the head
    note     why, for the person who reads this next

Real layouts (checked 2026-11-04): side by side the captain sits on the left in every fixed
wing type here (B-1B, B-2, B-52, C-130, AC-130U, Tu-95, Su-34); tandem, the pilot is in front
except in the AH-64, and the RAH-66's pilot is in front with the weapons officer behind (the
Apache the other way round). The UH-60 and MH-60M keep seat 0 on the left although an Army pilot
in command usually takes the right: both are pilot seats, and the models put their screens (and
the MH-60M its seat-0 screen) on the left panel.

DISPLAY_SEATS moves `camera.displays[].seat` with the people: when a station changes ends, the
screens of the panel in front of it go with it.
"""

DISPLAY_SEATS = {
    'rah_66': {0: 1},   # the rear panel was the pilot's while the pilot sat behind
}

P, C, G, B = 'pilot', 'crew', 'gunner', 'cabin'

SPEC = {
    # single seat, on the centreline
    'a_10': [dict(role=P)],
    'av_8b': [dict(role=P)],
    'eurofighter': [dict(role=P)],
    'f_100d': [dict(role=P)],
    'f_104g': [dict(role=P, ceiling=2.27, note='the model has the canopy bows (2.30 behind, 2.19 in front) and no glass')],
    'f_117': [dict(role=P)],
    'f_15': [dict(role=P)],
    'f_16': [dict(role=P)],
    'f_2': [dict(role=P)],
    'f_22': [dict(role=P)],
    'f_35b': [dict(role=P)],
    'fa_18e': [dict(role=P)],
    'jas_39': [dict(role=P)],
    'mig_19s': [dict(role=P)],
    'mig_21mf': [dict(role=P)],
    'mig_29': [dict(role=P)],
    'p_51': [dict(role=P)],
    'p_51d': [dict(role=P)],
    'su_25': [dict(role=P)],
    'su_27': [dict(role=P)],
    'su_33': [dict(role=P)],
    'su_57': [dict(role=P)],
    'yak_9': [dict(role=P)],

    # tandem
    'f_14': [dict(role=P), dict(role=C, note='RIO behind the pilot')],
    'f_4': [dict(role=P), dict(role=C, note='WSO behind the pilot')],
    'mig_31': [dict(role=P), dict(role=C, note='navigator behind the pilot')],
    'su_30sm': [dict(role=P), dict(role=C, note='WSO behind the pilot')],
    'ah-64': [dict(role=P, note='pilot in the raised rear seat'),
              dict(role=C, eye=[0.0, 2.6, 4.453],
                   note='CPG in front; works the M230 (stations seat 1). Eye as placed by hand: the CPG '
                        'looks into the sight, and at the design-eye 2.69 the three screens no longer fit '
                        'the panel under it (tool/mfd)')],
    'rah_66': [dict(role=P, z=3.45, note='pilot in front, as built (the file had him behind)'),
               dict(role=C, z=2.3, note='WSO behind; works the 20 mm (stations seat 1)')],
    'ju_87d': [dict(role=P), dict(role=B, y=1.95, note='radio operator / rear gunner, facing aft, on a seat the model does not have')],

    # side by side: captain on the left
    'su_34': [dict(role=P, x=-0.30, note='pilot left, navigator right (the file had them swapped); the pans are at +-0.30'),
              dict(role=C, x=0.30)],
    'b_1': [dict(role=P, x=-0.35, note='aircraft commander front left (the file had the right seat)')],
    'b_2': [dict(role=P), dict(role=C, note='mission commander, right seat')],
    'b_21': [dict(role=P, x=-0.81, note='the pans are at +-0.81; +-0.45 sat both on the centre console'),
             dict(role=C, x=0.81)],
    'b-52': [dict(role=P)],
    'tu_95': [dict(role=P), dict(role=C, note='co-pilot, right seat')],
    'c_130': [dict(role=P), dict(role=C, note='co-pilot, right seat')] + [dict(role=B)] * 4
             + [dict(role=B, z=-1.9, note='on the troop bench, which ends at z -2.43 (was -3.8, off it)')] * 2,
    'ac_130u': [dict(role=P), dict(role=G, note='30 mm (stations seat 1)'),
                dict(role=G, note='105 mm (stations seat 2)')],
    'uh_60': [dict(role=P, x=-0.66, note='left seat (the screens are on the left panel); the pans are at +-0.66'),
              dict(role=C, x=0.66),
              dict(role=G, note='left door gun (stations seat 2)'),
              dict(role=G, note='right door gun (stations seat 3)')] + [dict(role=B)] * 4,
    'mh_60m': [dict(role=P, x=-0.66, note='left seat, as the screen given to seat 0 is; the pans are at +-0.66'),
               dict(role=C, x=0.66)] + [dict(role=B)] * 8,

    # no crew aboard
    'mq_9': [],
    'x_47b': [],
}
