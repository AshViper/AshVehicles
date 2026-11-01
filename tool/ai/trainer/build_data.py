# -*- coding: utf-8 -*-
"""ブラウザの学習器（tool/ai/trainer）が読む data.js を、ゲームのコードと定義ファイルから作る。

    python tool/ai/trainer/build_data.py

書き出す物:

- パラメータの名前と既定値。ai/ の Java から parameters.get("名前", 既定) を拾い、役割の目盛りは
  TacticalProfile.of の表から profile.<役割>.<名前> を作る。**既定値を手で写さない**——ゲームの
  AiVersions.defaults と同じく、読むコードの側から集める（写した一覧は必ずずれる）
- AI が出せる地上車両の数値（Bots.usable と Loadout.deployable の写し）と、Roles.classify の写しで読んだ役割
- AI に渡す航空機（AirLoadout.flyable の写し）と、吊る物の見積もり

ゲームの重みや車両を変えたら打ち直す。
"""
import glob
import io
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAVA = os.path.join(ROOT, "src", "main", "java", "com", "ashvehicles", "ai")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "ashvehicles", "ashvehicles")
OUT = os.path.join(HERE, "data.js")

GET = re.compile(r'\.get\("([a-z_]+(?:\.[a-z_0-9]+)+)",\s*(-?[0-9.]+(?:[eE]-?[0-9]+)?)\)')
PROFILE_CASE = re.compile(r"case\s+([A-Z_]+)\s*->\s*new TacticalProfile\(([^)]*)\)")
PROFILE_NAMES = ["aggression", "risk_tolerance", "preferred_range", "flank_bias", "cover_bias", "hold_bias",
                 "support_bias", "scout_bias", "anti_air_bias", "retreat_health", "objective_bias"]
RANGE_CAP = 1500.0


def read_json(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def parameters():
    found = {}
    for path in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        with io.open(path, encoding="utf-8") as f:
            text = f.read()
        for name, value in GET.findall(text):
            found[name] = float(value)
        if path.endswith("TacticalProfile.java"):
            for role, values in PROFILE_CASE.findall(text):
                numbers = [float(v) for v in values.split(",")]
                for name, value in zip(PROFILE_NAMES, numbers):
                    found["profile.%s.%s" % (role.lower(), name)] = value
    return dict(sorted(found.items()))


def id_path(ref):
    return ref.split(":", 1)[1] if ref else None


def weapon(ref, ammunition=None):
    if not ref:
        return None
    path = os.path.join(DATA, "weapon", id_path(ref) + ".json")
    if not os.path.exists(path):
        return None
    raw = read_json(path)
    projectile = dict(raw.get("projectile", {}))
    # 弾種の定義は projectile を上書きする。出撃の既定は一覧の先頭。
    if ammunition:
        ammo_path = os.path.join(DATA, "ammunition", id_path(ammunition[0]) + ".json")
        if os.path.exists(ammo_path):
            projectile.update(read_json(ammo_path).get("projectile", {}))
    guidance = raw.get("guidance") if isinstance(raw.get("guidance"), dict) else None
    if guidance is not None:
        # WeaponDefinition.Guidance の seeker の既定は HEAT。
        guidance = dict(guidance)
        guidance["seeker"] = guidance.get("seeker", "heat")
    rng = float(projectile.get("range", 0.0))
    rng = RANGE_CAP if rng <= 0.0 else min(rng, RANGE_CAP)
    return {
        "id": id_path(ref),
        "type": raw.get("type"),
        "gunClass": raw.get("gun_class"),
        "capacity": int(raw.get("ammo", 0)),
        "rps": float(raw.get("firing", {}).get("rounds_per_second", 1.0)),
        # 1回の発射で出る弾の数（WeaponReach.dps が掛ける）
        "salvo": max(int(raw.get("firing", {}).get("salvo", 1)), 1),
        "damage": float(projectile.get("damage", 0.0)),
        "range": rng,
        "ricochet": float(projectile.get("ricochet", 0.0)),
        "explosion": float(projectile.get("explosion", 0.0)),
        "guided": guidance is not None,
        "seeker": guidance.get("seeker") if guidance else None,
        "proximity": float(guidance.get("proximity", 0.0)) if guidance else 0.0,
        "blast": float(projectile.get("blast", 0.0)),
        "drag": float(projectile.get("drag", 0.0)),
        "cluster": bool(raw.get("cluster")),
        "nuclear": bool(raw.get("nuclear")),
        "nation": raw.get("nation"),
        "mass": float(raw.get("mass", 0.0)),
    }


def classify(hull, powertrain, main, missile, coax, radar):
    """Roles.classify の写し。"""
    gun = main["gunClass"] if main else None
    guided = missile is not None and missile["guided"]
    air_seeker = guided and missile["seeker"] in ("radar", "heat")
    if radar or air_seeker:
        return "aa"
    if gun == "howitzer" or (main is None and missile is not None and not guided):
        return "artillery"
    if gun == "tank_gun":
        return "tank"
    if gun == "autocannon":
        return "tank" if hull.get("armour", 0) >= 4.5 and hull.get("health", 0) >= 450 else "ifv"
    if gun == "machine_gun" or (main is None and coax is not None):
        return "scout" if powertrain.get("max_speed", 0) >= 1.05 and hull.get("health", 0) <= 260 else "apc"
    if guided:
        return "ifv"
    return "support"


def vehicles():
    out = []
    for path in sorted(glob.glob(os.path.join(DATA, "vehicle", "*.json"))):
        raw = read_json(path)
        vid = os.path.splitext(os.path.basename(path))[0]
        hull = raw.get("hull", {})
        powertrain = raw.get("powertrain", {})
        armament = raw.get("armament", {})
        launcher = raw.get("launcher", {})
        coaxial = raw.get("coaxial", {})
        # Loadout.deployable と Bots.usable の写し: 船・人が回す砲・走れない物・大きすぎる物・座標へ撃つだけの物を外す
        if raw.get("type") == "ship" or hull.get("crewed") or powertrain.get("max_speed", 0) <= 0:
            continue
        if raw.get("hitbox", {}).get("width", 0) > 8.0:
            continue
        main = weapon(armament.get("main"), armament.get("ammunition"))
        coax = weapon(coaxial.get("gun"), coaxial.get("ammunition"))
        missile = weapon(launcher.get("missile"))
        if main is None and coax is None and (missile is None or missile["seeker"] == "point"):
            continue
        if missile is not None and missile["seeker"] == "point":
            missile = None
        radar = bool(raw.get("radar"))
        air_missile = missile is not None and missile["seeker"] in ("radar", "heat")
        out.append({
            "id": vid,
            "role": classify(hull, powertrain, main, missile, coax, radar),
            "cost": int(hull.get("cost", 250)),
            "health": float(hull.get("health", 100)),
            "armour": float(hull.get("armour", 0.0)),
            "speed": float(powertrain.get("max_speed", 0.8)),
            "reverse": float(powertrain.get("reverse_speed", 0.2)),
            "acceleration": float(powertrain.get("acceleration", 0.005)),
            "braking": float(powertrain.get("braking", 0.03)),
            "steer": float(max(powertrain.get("steer_rate", 1.0), powertrain.get("pivot_rate", 0.0))),
            "traverse": float(raw.get("turret", {}).get("traverse_rate", 1.5)),
            "width": float(raw.get("hitbox", {}).get("width", 3.0)),
            "height": float(raw.get("hitbox", {}).get("height", 2.8)),
            "main": main,
            "coax": coax,
            "missile": missile,
            # Roles.defendsAir と Roles.airMissile
            "defendsAir": radar or air_missile or (main is not None and main["gunClass"] == "autocannon"),
            "airMissile": air_missile,
        })
    return out


# AirLoadout の写し ------------------------------------------------------------

GROUNDED = {"b_1", "b_2", "ac_130u", "c_130"}
DIRECT_HIT = 2.5
PAIR = 2


def store_of(w):
    """AirLoadout.storeOf。"""
    if w is None or w["nuclear"]:
        return None
    if w["type"] == "gun":
        return "gun"
    if w["type"] == "bomb":
        return None if w["guided"] else "bomb"
    if w["type"] == "missile" and w["guided"]:
        if w["seeker"] == "heat" or (w["seeker"] == "radar" and w["proximity"] > DIRECT_HIT):
            return "aam"
    return None


def worth(w):
    value = w["damage"] + w["explosion"] * 10.0
    if w["seeker"] == "heat":
        value += 1000.0
    return value


def all_weapons():
    out = []
    for path in sorted(glob.glob(os.path.join(DATA, "weapon", "*.json"))):
        w = weapon("ashvehicles:" + os.path.splitext(os.path.basename(path))[0])
        if w and w["mass"] > 0:
            out.append(w)
    return out


def best(weapons, wanted, nations):
    usable = [w for w in weapons if store_of(w) == wanted]
    usable.sort(key=lambda w: (w["nation"] not in nations, -worth(w)))
    return usable[0] if usable else None


def aircraft():
    """AI に渡す航空機（AirLoadout.flyable）と、出撃ごとに吊る物の見積もり（AirLoadout.equip）。

    吊る数は、機体の兵装パイロンを対にして1本あたり2発、搭載量の8割まで。どのラックが何を受けるかの細かい規則
    （Loadout.choicesFor）は写さない。
    """
    weapons = all_weapons()
    out = []
    for path in sorted(glob.glob(os.path.join(DATA, "aircraft", "*.json"))):
        raw = read_json(path)
        aid = os.path.splitext(os.path.basename(path))[0]
        airframe = raw.get("airframe", {})
        if aid in GROUNDED or not airframe.get("seats"):
            continue
        crewed = set()
        for station in raw.get("stations", []):
            crewed.update(station.get("pylons", []))
        hardpoints = raw.get("hardpoints", [])
        guns = [weapon(h["fixed"], h.get("ammunition")) for h in hardpoints
                if h.get("fixed") and h.get("name") not in crewed]
        guns = [g for g in guns if store_of(g) == "gun"]
        rotor = bool(raw.get("rotor"))
        pylons = [h for h in hardpoints if h.get("kind") == "weapon" and not h.get("wingtip")]
        tips = [h for h in hardpoints if h.get("kind") == "weapon" and h.get("wingtip")]
        if not guns and (rotor or not pylons):
            continue
        nations = {airframe.get("nation")} | set(airframe.get("stores_from") or [])
        bomb = None if rotor else best(weapons, "bomb", nations)
        aam = best(weapons, "aam", nations)
        payload = float(airframe.get("payload", 0.0)) * 0.8
        wing = raw.get("wing", {})
        radar = float(raw.get("radar", {}).get("range", 0)) > 0
        gun = None
        if guns:
            # 機関砲が複数なら1つにまとめる（毎秒の発射数を足す）。
            gun = dict(guns[0])
            gun["rps"] = sum(g["rps"] for g in guns)
            gun["capacity"] = sum(g["capacity"] for g in guns)
        out.append({
            "id": aid,
            "kind": "fighter" if radar and gun else "striker",
            "rotor": rotor,
            "cost": int(airframe.get("cost", 250)),
            "health": float(airframe.get("health", 250)),
            "maxSpeed": float(wing.get("max_speed", 8.0)),
            "stallSpeed": float(wing.get("stall_speed", 1.6)),
            "rollRate": float(raw.get("handling", {}).get("roll_rate", 1.0)),
            "radar": radar,
            "gun": gun,
            "bomb": bomb,
            "bombSlots": len(pylons) * PAIR,
            "bombPayload": payload,
            "aam": aam,
            "aamTips": len(tips),
            "aamPylons": len(pylons),
        })
    return out


def main():
    data = {"parameters": parameters(), "vehicles": vehicles(), "aircraft": aircraft()}
    text = ("// build_data.py が作る。手で直さない。\n"
            "function AVTrainerData(AV) {\n  AV.DATA = " + json.dumps(data, ensure_ascii=False, indent=1)
            + ";\n}\n")
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    roles = {}
    for vehicle in data["vehicles"]:
        roles.setdefault(vehicle["role"], []).append(vehicle["id"])
    print("%d parameters, %d vehicles -> %s" % (len(data["parameters"]), len(data["vehicles"]), OUT))
    for role, ids in sorted(roles.items()):
        print("  %-9s %s" % (role, ", ".join(ids)))
    for plane in data["aircraft"]:
        print("  %-9s %-8s gun=%s bomb=%s x%d aam=%s" % (plane["kind"], plane["id"],
              plane["gun"]["id"] if plane["gun"] else "-", plane["bomb"]["id"] if plane["bomb"] else "-",
              plane["bombSlots"], plane["aam"]["id"] if plane["aam"] else "-"))


if __name__ == "__main__":
    main()
