"""geo.json と png と animation.json を Blockbench のプロジェクト（.bbmodel）1つにまとめる。

ゲームは assets/<ns>/bbmodel/<種類>/<id>.bbmodel を、geo.json・png・animation.json の3つの代わりに読む
（記憶ノート bbmodel-files-stand-in-for-geo-texture-and-animation）。これはその逆向きで、書き出し済みの3つから
Blockbench で開ける bbmodel を組み直す。座標の約束はゲーム側の BbModelFile の逆: x にかかる物だけ符号を返し、
面 UV は up と down だけ半回転して書く。

    python tool/geo_to_bbmodel.py                      # 同梱の全模型を assets/ashvehicles/bbmodel/ へ
    python tool/geo_to_bbmodel.py <assets/<ns> の場所>   # 別の名前空間（コンテンツパック）を
"""

import base64
import json
import struct
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = ROOT / "src/main/resources/assets/ashvehicles"
FACES = ("north", "east", "south", "west", "up", "down")


def new_uuid():
    return str(uuid.uuid4())


def png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return struct.unpack(">II", data[16:24])


def neg_x(v):
    return [-v[0], v[1], v[2]]


def rot_back(r):
    return [-r[0], -r[1], r[2]]


def cube_element(cube, texture):
    origin = cube["origin"]
    size = cube["size"]
    to_x = -origin[0]
    frm = [to_x - size[0], origin[1], origin[2]]
    to = [to_x, origin[1] + size[1], origin[2] + size[2]]
    element = {
        "name": "cube",
        "type": "cube",
        "uuid": new_uuid(),
        "export": True,
        "from": frm,
        "to": to,
        "autouv": 0,
        "color": 0,
        "origin": neg_x(cube.get("pivot", [0, 0, 0])),
    }
    if cube.get("rotation") and any(cube["rotation"]):
        element["rotation"] = rot_back(cube["rotation"])
    if cube.get("inflate"):
        element["inflate"] = cube["inflate"]

    uv = cube.get("uv")
    if isinstance(uv, list):
        element["box_uv"] = True
        element["uv_offset"] = uv
        if cube.get("mirror"):
            element["mirror_uv"] = True
        # Blockbench は box UV の面も持つ。開くと自分で描き直すので形だけ置く。
        element["faces"] = {face: {"uv": [0, 0, 0, 0], "texture": texture} for face in FACES}
        return element

    element["box_uv"] = False
    faces = {}
    for face in FACES:
        written = (uv or {}).get(face)
        if written is None:
            faces[face] = {"uv": [0, 0, 0, 0], "texture": None}
            continue
        u, v = written["uv"]
        w, h = written.get("uv_size", [0, 0])
        if face in ("up", "down"):
            box = [u + w, v + h, u, v]
        else:
            box = [u, v, u + w, v + h]
        out = {"uv": box, "texture": texture}
        if written.get("uv_rotation"):
            out["rotation"] = written["uv_rotation"]
        faces[face] = out
    element["faces"] = faces
    return element


def keyframes(channel, timeline):
    if not isinstance(timeline, dict):
        timeline = {"0.0": timeline}
    out = []
    for time, value in timeline.items():
        interpolation = "linear"
        pre = None
        if isinstance(value, dict):
            interpolation = value.get("lerp_mode", "linear")
            if "vector" in value:
                post = value["vector"]
            else:
                post = value.get("post", value.get("pre"))
                if "pre" in value and "post" in value:
                    pre = value["pre"]
        else:
            post = value
        if not isinstance(post, list):
            post = [post, post, post]
        points = ([pre] if pre is not None else []) + [post]
        out.append({
            "channel": channel,
            "data_points": [{"x": str(p[0]), "y": str(p[1]), "z": str(p[2])} for p in points],
            "uuid": new_uuid(),
            "time": float(time),
            "color": -1,
            "interpolation": interpolation,
        })
    return out


def animations(path, groups):
    if not path.exists():
        return []
    written = json.loads(path.read_text(encoding="utf-8"))["animations"]
    out = []
    for name, animation in written.items():
        loop = animation.get("loop", False)
        animators = {}
        for bone, channels in animation.get("bones", {}).items():
            if bone not in groups:
                print(f"  {path.name}: {name} moves '{bone}', which the model does not have; dropped")
                continue
            frames = []
            for channel in ("rotation", "position", "scale"):
                if channel in channels:
                    frames += keyframes(channel, channels[channel])
            animators[groups[bone]] = {"name": bone, "type": "bone", "keyframes": frames}
        out.append({
            "uuid": new_uuid(),
            "name": name,
            "loop": "loop" if loop is True else "hold" if loop == "hold_on_last_frame" else "once",
            "override": False,
            "length": animation.get("animation_length", 0),
            "snapping": 24,
            "animators": animators,
        })
    return out


def convert(geo_path, texture_path, animation_path):
    geo = json.loads(geo_path.read_text(encoding="utf-8"))
    geometry = geo["minecraft:geometry"][0]
    description = geometry["description"]
    tw = description.get("texture_width", 16)
    th = description.get("texture_height", 16)

    textures = []
    if texture_path.exists():
        data = texture_path.read_bytes()
        width, height = png_size(data)
        textures.append({
            "name": texture_path.name, "folder": "", "namespace": "", "id": "0",
            "width": width, "height": height, "uv_width": tw, "uv_height": th,
            "particle": False, "render_mode": "default", "visible": True, "internal": True, "saved": True,
            "uuid": new_uuid(), "source": "data:image/png;base64," + base64.b64encode(data).decode("ascii"),
        })
    texture = 0 if textures else None

    elements = []
    groups = {}
    nodes = {}
    order = []
    for bone in geometry["bones"]:
        name = bone["name"]
        group_uuid = new_uuid()
        groups[name] = group_uuid
        children = []
        for cube in bone.get("cubes", []):
            element = cube_element(cube, texture)
            elements.append(element)
            children.append(element["uuid"])
        group = {
            "name": name, "uuid": group_uuid, "export": True, "isOpen": False,
            "origin": neg_x(bone.get("pivot", [0, 0, 0])),
            "rotation": rot_back(bone.get("rotation", [0, 0, 0])),
            "children": children,
        }
        nodes[name] = group
        order.append((name, bone.get("parent")))

    outliner = []
    for name, parent in order:
        if parent and parent in nodes:
            nodes[parent]["children"].append(nodes[name])
        else:
            outliner.append(nodes[name])

    identifier = description.get("identifier", "geometry.unknown")
    return {
        "meta": {"format_version": "4.5", "model_format": "animated_entity_model", "box_uv": False},
        "name": geo_path.name[:-len(".geo.json")],
        "model_identifier": identifier.removeprefix("geometry."),
        "visible_box": [description.get("visible_bounds_width", 4), description.get("visible_bounds_height", 4),
                        (description.get("visible_bounds_offset") or [0, 0, 0])[1]],
        "resolution": {"width": tw, "height": th},
        "elements": elements,
        "outliner": outliner,
        "textures": textures,
        "animations": animations(animation_path, groups),
    }


def geometry(project):
    """bbmodel を geo.json の中身（キューブだけ）へ。ゲームの BbModelFile の写しで、ツールが模型を読むのに使う。"""
    elements = {e.get("uuid"): e for e in project.get("elements", [])}
    listed = {g.get("uuid"): g for g in project.get("groups", [])}
    bones = {}

    def cube(e):
        frm, to = e["from"], e["to"]
        out = {"origin": [-to[0], frm[1], frm[2]], "size": [to[i] - frm[i] for i in range(3)]}
        rotation = e.get("rotation", [0, 0, 0])
        if any(rotation):
            origin = e.get("origin", [0, 0, 0])
            out["pivot"] = neg_x(origin)
            out["rotation"] = rot_back(rotation)
        return out

    def walk(node, parent):
        if isinstance(node, str):
            e = elements.get(node)
            if e and e.get("export", True) and e.get("type", "cube") == "cube":
                name = parent or "bb_main"
                bones.setdefault(name, {"name": name, "pivot": [0, 0, 0], "cubes": []})["cubes"].append(cube(e))
            return
        group = dict(listed.get(node.get("uuid"), {}), **node)
        name = group.get("name", "bone")
        bone = {"name": name, "pivot": neg_x(group.get("origin", [0, 0, 0])), "cubes": []}
        if parent:
            bone["parent"] = parent
        if any(group.get("rotation", [0, 0, 0])):
            bone["rotation"] = rot_back(group["rotation"])
        bones[name] = bone
        for child in group.get("children", []):
            walk(child, name)

    for node in project.get("outliner", []):
        walk(node, None)
    return {"format_version": "1.12.0", "minecraft:geometry": [{"bones": list(bones.values())}]}


def sheet(project):
    """bbmodel に埋め込まれた最初のテクスチャの PNG。無ければ None。"""
    for texture in project.get("textures", []):
        source = texture.get("source", "")
        if "base64," in source:
            return base64.b64decode(source.split("base64,", 1)[1])
    return None


def main():
    assets = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_ASSETS
    count = 0
    for geo_path in sorted((assets / "geo").rglob("*.geo.json")):
        kind = geo_path.parent.relative_to(assets / "geo").as_posix()
        name = geo_path.name[:-len(".geo.json")]
        texture = assets / "textures" / kind / (name + ".png")
        animation = assets / "animations" / kind / (name + ".animation.json")
        target = assets / "bbmodel" / kind / (name + ".bbmodel")
        target.parent.mkdir(parents=True, exist_ok=True)
        project = convert(geo_path, texture, animation)
        if not project["textures"]:
            print(f"  {kind}/{name}: no texture beside it")
        target.write_text(json.dumps(project, separators=(",", ":")), encoding="utf-8")
        count += 1
    print(f"wrote {count} bbmodel(s) to {assets / 'bbmodel'}")


if __name__ == "__main__":
    main()
