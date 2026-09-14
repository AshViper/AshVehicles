# -*- coding: utf-8 -*-
"""実装済みの定義ファイルから Obsidian 用の一覧ノートを生成する。

    python tool/dump_definitions.py [--out <出力フォルダ>]

出力先の既定は Obsidian ヴォルトの データ/ フォルダ。生成されたノートは手で編集しない
（次の実行で上書きされる）。所見や補足は同じフォルダの手書きノート側に書くこと。
"""
import argparse
import glob
import io
import json
import math
import os

BLOCKS_PER_TICK_TO_KMH = 72.0     # 1 block/tick = 20 m/s = 72 km/h
BLOCKS_PER_TICK_TO_MS = 20.0

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, os.pardir, "src", "main", "resources")
DEFAULT_OUT = r"G:\Obsidian\AshVehicle Vault\仕様書\データ"

BANNER = (u"> [!warning] 自動生成\n"
          u"> `tool/dump_definitions.py` が定義ファイルから生成。**手で編集しない**（次回実行で消える）。\n"
          u"> 補足や所見は同じフォルダの手書きノートに書く。生成日時ではなく定義ファイルが正。\n")


def load(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def defs(kind):
    """data/ashvehicles/<kind>/*.json を id 順に返す。"""
    out = []
    for p in sorted(glob.glob(os.path.join(RES, "data", "ashvehicles", kind, "*.json"))):
        out.append((os.path.basename(p)[:-5], load(p)))
    return out


def lang():
    en = load(os.path.join(RES, "assets", "ashvehicles", "lang", "en_us.json"))
    ja = load(os.path.join(RES, "assets", "ashvehicles", "lang", "ja_jp.json"))
    return en, ja


def name_of(en, ja, prefix, key):
    k = u"%s.ashvehicles.%s" % (prefix, key)
    e = en.get(k, u"—")
    j = ja.get(k, u"")
    return e if (not j or j == e) else u"%s（%s）" % (e, j)


def num(v, digits=0):
    if v is None:
        return u"—"
    if digits == 0:
        return u"%d" % round(v)
    return (u"%." + str(digits) + u"f") % v


def top_speed_kmh(d):
    """固定翼の到達最高速度は推力と抗力の釣り合いで決まる（wing.max_speed は暴走クランプ）。"""
    wing = d.get("wing", {})
    eng = d.get("engine", {})
    drag = wing.get("drag", 0) * d.get("sweep", {}).get("drag", 1.0)
    thrust = eng.get("max_thrust", 0) * eng.get("afterburner", {}).get("thrust", 1.0)
    if drag <= 0 or thrust <= 0:
        return None
    return (thrust / drag) ** 0.5 * BLOCKS_PER_TICK_TO_KMH


def seats_of(d, key):
    return len(d.get(key, {}).get("seats", []) or [])


def hardpoints(d):
    weapon = sum(1 for h in d.get("hardpoints", []) if h.get("kind") == "weapon")
    special = sum(1 for h in d.get("hardpoints", []) if h.get("kind") == "special")
    other = len(d.get("hardpoints", [])) - weapon - special
    return weapon, special, other


def internal_stations(d):
    """機内（ウェポンベイ）に積む武装ステーションの数。外に吊らない＝RCS に乗らない分。"""
    return sum(1 for h in d.get("hardpoints", [])
               if h.get("kind") == "weapon" and h.get("internal"))


def rcs(d):
    """清浄形態の反射断面積と、それが探知距離に換算していくら残るか。

    断面積そのものは 0.0001 から 100 まで桁をまたぐので有効数字で出す。後ろの割合が実際に
    効く数値で、`AircraftDefinition.Signature.reach` と同じ4乗根・同じ上限1。
    """
    value = d.get("signature", {}).get("radar")
    if value is None:
        return u"—"
    if value >= 0.1:
        area = u"%.2f" % value
    elif value >= 0.01:
        area = u"%.3f" % value
    else:
        area = (u"%.4f" % value).rstrip(u"0") if value >= 0.0001 else u"%.5f" % value
    return u"%s（%d%%）" % (area, round(min(value ** 0.25, 1.0) * 100))


def table(head, rows):
    out = [u"| " + u" | ".join(head) + u" |",
           u"|" + u"|".join([u"---"] * len(head)) + u"|"]
    out += [u"| " + u" | ".join(r) + u" |" for r in rows]
    return u"\n".join(out)


def write(out_dir, filename, title, body):
    path = os.path.join(out_dir, filename)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(u"# %s\n\n%s\n%s\n" % (title, BANNER, body))
    print("wrote", path)


def aircraft_note(out_dir, en, ja):
    fixed, heli = [], []
    for vid, d in defs("aircraft"):
        af, wing = d.get("airframe", {}), d.get("wing", {})
        w, s, _ = hardpoints(d)
        row_common = [
            u"`%s`" % vid,
            name_of(en, ja, "entity", vid),
            af.get("nation", u"—") + (u"（+%s）" % u", ".join(af["stores_from"]) if af.get("stores_from") else u""),
        ]
        if d.get("type") == "helicopter":
            heli.append(row_common + [
                num(wing.get("max_speed", 0) * BLOCKS_PER_TICK_TO_KMH),
                num(af.get("mass")),
                num(af.get("health")),
                num(d.get("rotor", {}).get("lift"), 4),
                u"%d" % seats_of(d, "airframe"),
                u"%d + %d" % (w, s),
                u"%d" % internal_stations(d),
                num(d.get("radar", {}).get("range")) if d.get("radar") else u"—",
                rcs(d),
            ])
        else:
            fixed.append(row_common + [
                num(top_speed_kmh(d)),
                num(wing.get("stall_speed", 0) * BLOCKS_PER_TICK_TO_KMH),
                num(af.get("mass")),
                num(af.get("health")),
                num(af.get("max_g"), 1),
                u"%d" % seats_of(d, "airframe"),
                u"%d + %d" % (w, s),
                u"%d" % internal_stations(d),
                rcs(d),
            ])
    body = [
        u"## 固定翼 (%d)\n" % len(fixed),
        u"最高速度は `sqrt(engine.max_thrust × afterburner.thrust / wing.drag)` の釣り合い速度。"
        u"`wing.max_speed` は暴走クランプであって到達速度ではない（1 block/tick = 72 km/h）。\n",
        table([u"id", u"名称", u"国籍", u"最高速度 km/h", u"失速 km/h", u"質量 kg", u"耐久", u"最大G",
               u"座席", u"HP 武装+特殊", u"機内", u"RCS"], fixed),
        u"\n`機内` はウェポンベイに積む武装ステーションの数。そこに積んだ物は RCS に乗らない"
        u"（扉を持つ機体では扉を開けている間だけ乗る）。`RCS` の括弧内は探知距離の残り割合で、"
        u"反射断面積の4乗根・上限1。外に1つ吊れば `signature.store` がそのまま加算されるので、"
        u"F-22 にポッドを1つ提げるだけで 10% が 53% になる。\n",
        u"\n## 回転翼 (%d)\n" % len(heli),
        u"ヘリの到達速度はローターモデルが決めるため、`wing.max_speed` はクランプとして働く。\n",
        table([u"id", u"名称", u"国籍", u"上限 km/h", u"質量 kg", u"耐久", u"rotor.lift",
               u"座席", u"HP 武装+特殊", u"機内", u"レーダー", u"RCS"], heli),
    ]
    write(out_dir, u"10 航空機.md", u"航空機", u"\n".join(body))


def vehicle_note(out_dir, en, ja):
    ground, ships, turrets = [], [], []
    for vid, d in defs("vehicle"):
        pt, hull = d.get("powertrain", {}), d.get("hull", {})
        arm, coax = d.get("armament", {}), d.get("coaxial", {})
        short = lambda s: s.split(":")[-1] if s else u"—"
        row = [
            u"`%s`" % vid,
            name_of(en, ja, "entity", vid),
            num(pt.get("max_speed", 0) * BLOCKS_PER_TICK_TO_KMH),
            num(hull.get("health")),
            num(hull.get("armour")),
            (u"×%s" % num(hull.get("damage_taken"), 2)) if hull.get("damage_taken", 1) != 1 else u"—",
            u"`%s`" % short(arm.get("main")) if arm.get("main") else u"—",
            u"`%s`" % short(coax.get("gun")) if coax.get("gun") else u"—",
            u"%d" % len(arm.get("ammunition", []) or []),
            u"%d" % seats_of(d, "hull"),
        ]
        (ships if d.get("type") == "ship" else ground).append(row)
        for index, t in enumerate(d.get("turrets", []) or []):
            turrets.append([
                u"`%s`" % vid,
                u"%d" % index,
                t.get("name") or u"—",
                u"%d" % t.get("seat", 0),
                u"`%s`" % short(t.get("weapon")) if t.get("weapon") else u"—",
                num(t.get("traverse", 180)),
                num(t.get("elevation", 20)),
                num(t.get("depression", 8)),
                u"%d" % len(t.get("ammunition", []) or []),
            ])
    head = [u"id", u"名称", u"最高速度 km/h", u"耐久", u"装甲",
            u"被ダメージ", u"主砲", u"同軸", u"弾種数", u"座席"]
    body = [
        u"最高速度 0 は固定砲座（`powertrain.max_speed = 0`、意図的に移動しない）。"
        u"装甲の — は `hull.armour` を持たない定義。\n",
        u"被ダメージは `hull.damage_taken`——届いた打撃のうち実際に受け取る割合で、— は素通し（1）。\n",
        u"## 地上車両 (%d)\n" % len(ground), table(head, ground),
        u"\n## 艦 (%d)\n" % len(ships),
        u"`type: ship` は浮力を持つ地上車両として扱われる。\n",
        table(head, ships),
    ]
    if turrets:
        body += [
            u"\n## 独立砲塔 (%d)\n" % len(turrets),
            u"主砲塔とは別に、自分の席の乗員が据えて撃つ砲塔。空席なら運転手のものになる"
            u"（`turrets`、`TurretStations`）。旋回は指定方位から左右へ、180 で全周。\n",
            table([u"車両", u"番号", u"名称", u"座席", u"兵装", u"旋回±", u"仰角", u"俯角", u"弾種数"],
                  turrets),
        ]
    write(out_dir, u"11 地上車両・艦.md", u"地上車両・艦", u"\n".join(body))



def retained(pr):
    u"""抗力の生の値と、それが意味するもの。

    抗力係数は 1/m なので、速さは距離 x で exp(-k·x) になる。
    0.00045 という数字は読めないが「1km で 64%」なら読める。
    """
    k = pr.get("drag")
    if not k:
        return u"—"
    return u"%s（%d%%）" % (("%.6f" % k).rstrip("0"), round(math.exp(-k * 1000.0) * 100))


def weapon_note(out_dir, en, ja):
    groups = {}
    for wid, d in defs("weapon"):
        groups.setdefault(d.get("type", u"?"), []).append((wid, d))
    order = [(u"gun", u"機関砲・機関銃"), (u"missile", u"ミサイル"), (u"bomb", u"爆弾"),
             (u"rocket", u"ロケット"), (u"tank", u"増槽")]
    body = []
    for key, label in order:
        items = groups.pop(key, [])
        if not items:
            continue
        rows = []
        for wid, d in items:
            pr, fi, gu = d.get("projectile", {}), d.get("firing", {}), d.get("guidance", {})
            rows.append([
                u"`%s`" % wid,
                name_of(en, ja, "item", wid),
                d.get("nation", u"—"),
                d.get("gun_class", gu.get("seeker", u"—")),
                u"%s" % d.get("ammo", u"—"),
                num(fi.get("rounds_per_second"), 1) if fi.get("rounds_per_second") else u"—",
                num(pr.get("damage"), 1),
                num(pr.get("penetration")) if pr.get("penetration") else u"—",
                num(pr.get("speed", 0) * BLOCKS_PER_TICK_TO_MS),
                retained(pr),
                num(pr.get("range")) if pr.get("range") else u"—",
                num(pr.get("explosion"), 1) if pr.get("explosion") else u"—",
                num(pr.get("blast")) if pr.get("blast") else u"—",
                u"アイテム" if d.get("item") else u"内蔵",
            ])
        body.append(u"## %s (%d)\n" % (label, len(items)))
        if key == u"gun":
            body.append(u"名称が — のものは言語ファイルに表示名を持たない**内蔵兵装**。"
                        u"車両・機体の定義から id で参照されるだけで、アイテムにはならない。\n")
        body.append(table([u"id", u"名称", u"国籍", u"分類/シーカー", u"装弾", u"発/秒", u"威力", u"貫通 mm",
                           u"初速 m/s", u"抗力（1km 残速）", u"射程", u"爆発", u"爆風", u"入手"], rows))
        body.append(u"")
    for key, items in groups.items():
        body.append(u"## %s (%d)\n" % (key, len(items)))
        body.append(u", ".join(u"`%s`" % w for w, _ in items))
    body += cluster_section()
    write(out_dir, u"20 兵装.md", u"兵装", u"\n".join(body))


def cluster_section():
    """子弾を撒く兵装。開傘高度は cluster.open か guidance.proximity のどちらかから来る。"""
    rows = []
    for wid, d in defs("weapon"):
        cl = d.get("cluster")
        if not cl:
            continue
        open_at = cl.get("open", 0.0)
        prox = d.get("guidance", {}).get("proximity")
        rows.append([
            u"`%s`" % wid,
            u"`%s`" % cl.get("submunition", u"—").split(":")[-1],
            u"%d" % cl.get("count", 12),
            num(cl.get("spread", 0.3), 2),
            num(cl.get("inherit", 0.25), 2),
            (u"%s（`open`）" % num(open_at, 1)) if open_at else
            ((u"%s（`proximity`）" % num(prox, 1)) if prox else u"着弾"),
        ])
    if not rows:
        return []
    return [
        u"\n## クラスター弾頭 (%d)\n" % len(rows),
        u"子弾を撒く兵装。**撒布界の広さは `spread` と落下時間の積**なので、開く高さが無ければ何発撒いても"
        u"1点に落ちる。開く高さの出どころは2つあり、目標を持つ弾は `guidance.proximity`、投下されるだけの"
        u"爆弾は `cluster.open`（[[cluster-bomb-opens-by-height]]）。\n",
        table([u"id", u"子弾", u"数", u"横速度", u"引き継ぎ", u"開く高さ"], rows),
        u"",
    ]


def ammo_note(out_dir, en, ja):
    rows = []
    for aid, d in defs("ammunition"):
        pr = d.get("projectile", {})
        rows.append([
            u"`%s`" % aid,
            name_of(en, ja, "item", aid),
            d.get("gun_class", u"—"),
            u"%s" % d.get("rounds_per_item", u"—"),
            num(pr.get("damage"), 1),
            num(pr.get("penetration")) if pr.get("penetration") else u"—",
            num(pr.get("speed", 0) * BLOCKS_PER_TICK_TO_MS),
            retained(pr),
            num(pr.get("range")),
            num(pr.get("ricochet")) if pr.get("ricochet") else u"—",
            num(pr.get("explosion"), 1) if pr.get("explosion") else u"—",
        ])
    body = [u"弾種は `gun_class` が一致する兵装の projectile ブロックだけを差し替える。"
            u"車両側は `armament.ammunition` / `coaxial.ammunition` で搭載可能な弾種を列挙する。\n",
            table([u"id", u"名称", u"gun_class", u"1個あたり", u"威力", u"貫通 mm", u"初速 m/s",
                   u"抗力（1km 残速）", u"射程", u"跳弾角", u"爆発"], rows)]
    write(out_dir, u"21 弾種.md", u"弾種", u"\n".join(body))


def rack_note(out_dir, en, ja):
    rows = []
    for rid, d in defs("rack"):
        rows.append([
            u"`%s`" % rid,
            name_of(en, ja, "item", rid),
            num(d.get("mass")),
            u"%d" % len(d.get("stations", []) or []),
            u", ".join(d.get("accepts", []) or []) or u"—",
        ])
    write(out_dir, u"22 ラック.md", u"ラック",
          u"ハードポイントに挿して搭載数を増やす。`accepts` が兵装の `type` と一致する必要がある。\n\n"
          + table([u"id", u"名称", u"質量 kg", u"ステーション", u"受け入れる type"], rows))


def equipment_note(out_dir, en, ja):
    """撃つのではなく積む物。special ハードポイントに載り、載せた機体の能力を変える。"""
    rows = []
    for eid, d in defs("equipment"):
        rows.append([
            u"`%s`" % eid,
            name_of(en, ja, "item", eid),
            d.get("nation", u"—"),
            d.get("type", u"targeting_pod"),
            num(d.get("mass")),
            num(d.get("seeker_range", 1.0), 2),
            num(d.get("lock_rate", 1.0), 2),
            num(d.get("radar_gain", 1.0), 2),
            num(d.get("heat_gain", 1.0), 2),
            num(d.get("lock_delay", 1.0), 2),
        ])
    write(out_dir, u"23 装備ポッド.md", u"装備ポッド",
          u"`hardpoints[]` の `kind: special` に載る。倍率は1.0が「何もしない」で、"
          u"`radar_gain` / `heat_gain` は小さいほど探知されにくく、`lock_delay` は相手のロックを"
          u"引き延ばす。`camera` を持つポッドは視界も提供する（[[point-seeker-is-a-coordinate-not-a-target]]）。\n\n"
          + table([u"id", u"名称", u"国籍", u"type", u"質量 kg", u"シーカー距離", u"ロック速度",
                   u"レーダー被探知", u"熱被探知", u"ロック遅延"], rows))


def index_note(out_dir, en, ja):
    counts = [(u"aircraft", len(defs("aircraft"))), (u"vehicle", len(defs("vehicle"))),
              (u"weapon", len(defs("weapon"))), (u"ammunition", len(defs("ammunition"))),
              (u"rack", len(defs("rack"))), (u"equipment", len(defs("equipment"))),
              (u"recipe", len(glob.glob(os.path.join(RES, "data", "ashvehicles", "recipe", "*.json"))))]
    rows = [[u"`data/ashvehicles/%s/`" % k, u"%d" % n] for k, n in counts]
    body = [
        u"実装済みの定義ファイルを数えたもの。**登録の本体は Java ではなく定義ファイル**なので、"
        u"ここにある数がそのままゲーム内の実装数になる。\n",
        table([u"フォルダ", u"件数"], rows),
        u"\n## ノート\n",
        u"- [[10 航空機]] — 固定翼 23 / 回転翼 4、速度・質量・耐久・ハードポイント",
        u"- [[11 地上車両・艦]] — 戦車・IFV・自走砲・艦、主砲と同軸",
        u"- [[20 兵装]] — 機関砲・ミサイル・爆弾・ロケット・増槽",
        u"- [[21 弾種]] — `gun_class` ごとの弾種",
        u"- [[22 ラック]] — 多連装ラック",
        u"- [[23 装備ポッド]] — 撃たない搭載物（照準・妨害・デコイ）",
        u"- [[70 定義ファイル仕様]] — どのキーが何を決めるか（手書き、仕様書の側）",
        u"\n## 再生成\n",
        u"```\npython tool/dump_definitions.py\n```\n",
        u"定義ファイルを足したり数値を変えたらこれを実行する。10〜23 は毎回上書きされる。"
        u"手書きの仕様は `仕様書/` 側にあり、生成対象ではない。",
    ]
    write(out_dir, u"00 実装データ一覧.md", u"実装データ一覧", u"\n".join(body))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    args = ap.parse_args()
    if not os.path.isdir(args.out):
        os.makedirs(args.out)
    en, ja = lang()
    index_note(args.out, en, ja)
    aircraft_note(args.out, en, ja)
    vehicle_note(args.out, en, ja)
    weapon_note(args.out, en, ja)
    ammo_note(args.out, en, ja)
    rack_note(args.out, en, ja)
    equipment_note(args.out, en, ja)


if __name__ == "__main__":
    main()
