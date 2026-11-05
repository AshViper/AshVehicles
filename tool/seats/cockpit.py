"""What the definition says about crew, and which model cubes are the canopy."""
import re
import numpy as np

CANOPY = re.compile(r'canopy|cockpit|cokpit|hatch|window|glass|fonar|kabina', re.I)
NOT_CANOPY = re.compile(r'tyre|gear|wheel|whell|bay|vtol|nozzle|ramp|door', re.I)


def is_canopy(c):
    """Whether a model cube belongs to the canopy, by its bone (and failing that its own name)."""
    for n in (c.bone, c.name):
        if n and CANOPY.search(n) and not NOT_CANOPY.search(n):
            return True
    return False


def seats_of(d):
    """[(pos, eye or None), ...] from airframe.seats."""
    out = []
    for s in d.get('airframe', {}).get('seats', []):
        if isinstance(s, dict):
            out.append((s['pos'], s.get('eye')))
        else:
            out.append((s, None))
    return out


def eye_of(d, i):
    s = seats_of(d)[i]
    return s[1] if s[1] is not None else d['camera']['cockpit']
