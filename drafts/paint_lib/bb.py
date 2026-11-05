"""bbmodel geometry: texel <-> world mapping and a point-splat preview renderer."""
import json, base64, io, math
import numpy as np
from PIL import Image


def rot_matrix(r):
    rx, ry, rz = [math.radians(a) for a in r]
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx  # three.js Euler order 'ZYX'


class Model:
    def __init__(self, path):
        self.path = path
        self.d = json.load(open(path, encoding='utf-8'))
        d = self.d
        self.groups = {g['uuid']: g for g in d.get('groups', [])}
        self.elements = d['elements']
        self.parent = {}      # element uuid -> list of group dicts, outermost first
        def walk(nodes, chain):
            for n in nodes:
                if isinstance(n, dict):
                    g = self.groups.get(n['uuid'], n)
                    walk(n.get('children', []), chain + [g])
                else:
                    self.parent[n] = chain
        walk(d['outliner'], [])
        self.res = (d['resolution']['width'], d['resolution']['height'])

    def texture(self, i=0):
        src = self.d['textures'][i]['source']
        return Image.open(io.BytesIO(base64.b64decode(src.split(',', 1)[1]))).convert('RGBA')

    def set_texture(self, img, i=0):
        buf = io.BytesIO(); img.save(buf, 'PNG')
        self.d['textures'][i]['source'] = 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()

    def save(self, path):
        json.dump(self.d, open(path, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))

    def chain(self, e):
        return self.parent.get(e['uuid'], [])

    def to_world(self, e, P):
        """P: (N,3) local cube-space points -> world."""
        o = np.array(e.get('origin', [0, 0, 0]), float)
        P = (P - o) @ rot_matrix(e.get('rotation', [0, 0, 0])).T + o
        for g in reversed(self.chain(e)):
            r = g.get('rotation', [0, 0, 0])
            if any(r):
                o = np.array(g.get('origin', [0, 0, 0]), float)
                P = (P - o) @ rot_matrix(r).T + o
        return P

    def to_world_dir(self, e, n):
        n = rot_matrix(e.get('rotation', [0, 0, 0])) @ n
        for g in reversed(self.chain(e)):
            r = g.get('rotation', [0, 0, 0])
            if any(r):
                n = rot_matrix(r) @ n
        return n


NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}


def face_point(e, face, t, s):
    """t: fraction along u (0 at uv[0]), s: fraction along v. returns local points (N,3)."""
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    t = np.asarray(t, float); s = np.asarray(s, float)
    dx, dy, dz = x2 - x1, y2 - y1, z2 - z1
    one = np.ones_like(t)
    if face == 'north':
        return np.stack([x2 - t * dx, y2 - s * dy, z1 * one], -1)
    if face == 'south':
        return np.stack([x1 + t * dx, y2 - s * dy, z2 * one], -1)
    if face == 'east':
        return np.stack([x2 * one, y2 - s * dy, z2 - t * dz], -1)
    if face == 'west':
        return np.stack([x1 * one, y2 - s * dy, z1 + t * dz], -1)
    if face == 'up':
        return np.stack([x1 + t * dx, y2 * one, z1 + s * dz], -1)
    if face == 'down':
        return np.stack([x1 + t * dx, y1 * one, z2 - s * dz], -1)


def face_axes(e, face):
    """world-ish extents (width along u, height along v) of the face, in model units"""
    (x1, y1, z1), (x2, y2, z2) = e['from'], e['to']
    dx, dy, dz = abs(x2 - x1), abs(y2 - y1), abs(z2 - z1)
    return {'north': (dx, dy), 'south': (dx, dy), 'east': (dz, dy), 'west': (dz, dy), 'up': (dx, dz), 'down': (dx, dz)}[face]


def view_matrix(yaw, pitch):
    """camera rotation: yaw about Y then pitch about X (degrees). Returns R so that cam = R @ world."""
    a, b = math.radians(yaw), math.radians(pitch)
    Ry = np.array([[math.cos(a), 0, -math.sin(a)], [0, 1, 0], [math.sin(a), 0, math.cos(a)]])
    Rx = np.array([[1, 0, 0], [0, math.cos(b), -math.sin(b)], [0, math.sin(b), math.cos(b)]])
    return Rx @ Ry


def render(model, tex, yaw, pitch, scale=2.0, size=(900, 700), step=0.35, centre=(0, 0, 0), light=(0.4, 0.8, -0.45), bg=(28, 30, 36)):
    """orthographic point-splat render. +z of camera points away from viewer."""
    T = np.asarray(tex.convert('RGBA'), np.float32) / 255.0
    th, tw = T.shape[:2]
    R = view_matrix(yaw, pitch)
    L = np.array(light, float); L /= np.linalg.norm(L)
    W, H = size
    zbuf = np.full((H, W), np.inf, np.float32)
    img = np.zeros((H, W, 3), np.float32); img[:] = np.array(bg) / 255
    for e in model.elements:
        if e.get('visibility') is False:
            continue
        for face, fd in e['faces'].items():
            if fd.get('texture') is None:
                continue
            fw, fh = face_axes(e, face)
            nu = max(2, int(fw / step) + 1); nv = max(2, int(fh / step) + 1)
            t, s = np.meshgrid((np.arange(nu) + 0.5) / nu, (np.arange(nv) + 0.5) / nv)
            t = t.ravel(); s = s.ravel()
            P = model.to_world(e, face_point(e, face, t, s))
            n = model.to_world_dir(e, np.array(NORMALS[face], float))
            u1, v1, u2, v2 = fd['uv']
            U = (u1 + t * (u2 - u1)) * tw / model.res[0]
            V = (v1 + s * (v2 - v1)) * th / model.res[1]
            ui = np.clip(U.astype(int), 0, tw - 1); vi = np.clip(V.astype(int), 0, th - 1)
            C = T[vi, ui]
            keep = C[:, 3] > 0.5
            if not keep.any():
                continue
            C = C[keep, :3]; P = P[keep]
            cam = (P - np.array(centre, float)) @ R.T
            nc = R @ n
            # back-face: camera looks toward +z; visible if normal z < 0
            if nc[2] > 0.05:
                continue
            shade = 0.45 + 0.55 * max(0.0, float(np.dot(n, L)))
            X = (-cam[:, 0] * scale + W / 2).astype(int)  # viewer looks down +z, so +x is screen left
            Y = (-cam[:, 1] * scale + H / 2).astype(int)
            Z = cam[:, 2]
            ok = (X >= 0) & (X < W - 1) & (Y >= 0) & (Y < H - 1)
            X, Y, Z, C = X[ok], Y[ok], Z[ok], C[ok]
            for ox, oy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                xx, yy = X + ox, Y + oy
                order = np.argsort(-Z)  # far first so near ones overwrite
                xx, yy, zz, cc = xx[order], yy[order], Z[order], C[order]
                closer = zz < zbuf[yy, xx] + 0.01
                xx, yy, zz, cc = xx[closer], yy[closer], zz[closer], cc[closer]
                zbuf[yy, xx] = zz
                img[yy, xx] = cc * shade
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))


def sheet(model, tex, path, views=((0, 90), (0, -90), (90, 0), (35, 25), (215, 20), (0, 0)), scale=1.7, centre=(0, 0, 0)):
    imgs = [render(model, tex, y, p, scale=scale, centre=centre) for y, p in views]
    w, h = imgs[0].size
    out = Image.new('RGB', (w * 2, h * ((len(imgs) + 1) // 2)))
    for i, im in enumerate(imgs):
        out.paste(im, ((i % 2) * w, (i // 2) * h))
    out.save(path)


MARKER = [(0x7b, 0xd4, 0xff), (0xff, 0xf8, 0x99), (0xf4, 0xa2, 0x4d), (0xf4, 0x86, 0x86), (0xc3, 0x8a, 0xf4),
          (0x5b, 0x7b, 0xf4), (0x6d, 0xe8, 0x8d), (0xb7, 0xff, 0x4f), (0xff, 0xa7, 0xe8), (0xc0, 0xc0, 0xc0)]


def render_flat(model, colour, yaw, pitch, scale=2.0, size=(900, 700), step=0.5, centre=(0, 0, 0),
                light=(0.4, 0.8, -0.45), bg=(28, 30, 36)):
    """like render(), but each element is one flat colour: colour(element) -> (r,g,b) 0..255 or None to skip."""
    R = view_matrix(yaw, pitch)
    L = np.array(light, float); L /= np.linalg.norm(L)
    W, H = size
    zbuf = np.full((H, W), np.inf, np.float32)
    img = np.zeros((H, W, 3), np.float32); img[:] = np.array(bg) / 255
    for e in model.elements:
        c = colour(e)
        if c is None:
            continue
        c = np.array(c, np.float32) / 255
        for face in e['faces']:
            fw, fh = face_axes(e, face)
            nu = max(2, int(fw / step) + 1); nv = max(2, int(fh / step) + 1)
            t, s = np.meshgrid((np.arange(nu) + 0.5) / nu, (np.arange(nv) + 0.5) / nv)
            P = model.to_world(e, face_point(e, face, t.ravel(), s.ravel()))
            n = model.to_world_dir(e, np.array(NORMALS[face], float))
            cam = (P - np.array(centre, float)) @ R.T
            if (R @ n)[2] > 0.05:
                continue
            shade = 0.45 + 0.55 * max(0.0, float(np.dot(n, L)))
            X = (-cam[:, 0] * scale + W / 2).astype(int); Y = (-cam[:, 1] * scale + H / 2).astype(int); Z = cam[:, 2]
            ok = (X >= 0) & (X < W - 1) & (Y >= 0) & (Y < H - 1)
            X, Y, Z = X[ok], Y[ok], Z[ok]
            for ox, oy in ((0, 0), (1, 0), (0, 1), (1, 1)):
                xx, yy = X + ox, Y + oy
                o = np.argsort(-Z); xx, yy, zz = xx[o], yy[o], Z[o]
                cl = zz < zbuf[yy, xx] + 0.01
                zbuf[yy[cl], xx[cl]] = zz[cl]; img[yy[cl], xx[cl]] = c * shade
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
