"""Write stowed angles into the bbmodels' gear_up and gear_down.

    python tool/gear/apply.py SOLVE.json [id ...]

For each bone in SOLVE.json (solve.py --json, the `chosen` angle), every rotation keyframe of that bone in both
animations that holds the old stowed angle gets the new one. gear_down therefore stays the exact reverse of
gear_up (memory geckolib-gear-animation: every mismatch between the two halves in the old files was a hand edit
of one side). A keyframe holding neither rest nor the old stowed angle is a hand-made in-between; such a bone is
reported and left alone.
"""
import sys, os, json
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import Model
from ground import write_project


def fmt(v):
    v = round(float(v), 3)
    if v == 0:
        v = 0.0
    s = ('%.3f' % v).rstrip('0').rstrip('.')
    return s if s not in ('-0', '') else '0'


def vec(dp):
    return np.array([float(dp[c]) for c in 'xyz'])


def apply(aid, chosen, out=print):
    m = Model(aid)
    d = m.project
    changed = 0
    for bone, new in chosen.items():
        new = np.array(new, float)
        old = m.pose('gear_up', float(m.animations['gear_up'].get('length', 1.0)))[bone][0]
        if np.abs(old - new).max() < 1e-6:
            continue
        frames = []
        for anim in d['animations']:
            if anim['name'] not in ('gear_up', 'gear_down'):
                continue
            for uuid, an in anim['animators'].items():
                g = m.groups.get(uuid)
                if (g.name if g else an['name']) != bone:
                    continue
                frames += [(anim['name'], k) for k in an['keyframes'] if k['channel'] == 'rotation']
        odd = [(a, k['time']) for a, k in frames for dp in k['data_points']
               if np.abs(vec(dp)).max() > 1e-6 and np.abs(vec(dp) - old).max() > 1e-3]
        if odd:
            out(f'  {aid} {bone}: in-between keyframes {odd}, left alone')
            continue
        for a, k in frames:
            for dp in k['data_points']:
                if np.abs(vec(dp) - old).max() <= 1e-3 and np.abs(old).max() > 1e-6:
                    dp['x'], dp['y'], dp['z'] = fmt(new[0]), fmt(new[1]), fmt(new[2])
                    changed += 1
        out(f'  {aid:12} {bone:12} {[fmt(x) for x in old]} -> {[fmt(x) for x in new]}')
    if changed:
        write_project(m.path, d, m.raw)
    return changed


def reopen(aid, doors, out=print):
    """Doors modelled open but animated as if modelled closed: stowed 0 becomes the closing angle and the old
    deployed angle becomes 0 (open as built), in both animations."""
    m = Model(aid)
    d = m.project
    changed = 0
    for bone, (closed, deployed) in doors.items():
        closed = np.array(closed, float); deployed = np.array(deployed, float)
        for anim in d['animations']:
            if anim['name'] not in ('gear_up', 'gear_down'):
                continue
            for uuid, an in anim['animators'].items():
                g = m.groups.get(uuid)
                if (g.name if g else an['name']) != bone:
                    continue
                for k in an['keyframes']:
                    if k['channel'] != 'rotation':
                        continue
                    for dp in k['data_points']:
                        v = vec(dp)
                        if np.abs(v).max() <= 1e-6:
                            new = closed
                        elif np.abs(v - deployed).max() <= 1e-3:
                            new = np.zeros(3)
                        else:
                            raise RuntimeError(f'{aid} {bone}: keyframe {v} is neither 0 nor the deployed angle')
                        dp['x'], dp['y'], dp['z'] = fmt(new[0]), fmt(new[1]), fmt(new[2])
                        changed += 1
        out(f'  {aid:12} {bone:12} modelled open: stowed 0 -> {[fmt(x) for x in closed]}, deployed {[fmt(x) for x in deployed]} -> 0')
    if changed:
        write_project(m.path, d, m.raw)
    return changed


if __name__ == '__main__':
    args = sys.argv[1:]
    doors = '--doors' in args
    args = [a for a in args if a != '--doors']
    solved = json.load(open(args[0]))
    ids = args[1:] or sorted(solved)
    for aid in ids:
        entries = solved.get(aid, {})
        if doors:
            flip = {b: (e['chosen'], e['deployed']) for b, e in entries.items() if e.get('inverted')}
            plain = {b: e['chosen'] for b, e in entries.items() if not e.get('inverted')}
            if plain:
                apply(aid, plain)
            if flip:
                reopen(aid, flip)
        else:
            chosen = {b: e['chosen'] for b, e in entries.items() if 'chosen' in e}
            if chosen:
                apply(aid, chosen)
