# -*- coding: utf-8 -*-
"""次に試す AI の版の候補を作る。基にする版のパラメータを少しずつ揺らして versions/<名前>.json に書く。

    python tool/ai/propose_version.py --base rule_v1 --name rule_v2
                                      [--sigma 0.1] [--seed 1] [--only policy.,profile.] [--root <フォルダ>] [--force]

基にするパラメータは versions/<base>.json、無ければ exports/<base>.json から読む。組み込みの rule_v1 は
ファイルを持たないので、先にゲームで /tdm ai versions export rule_v1 を打つ（既定値を補った全部が exports/ に出る）。

揺らし方は掛け算の対数正規（値 × exp(N(0, sigma))）で、符号は変えない。profile.* は 0〜1 に収め、*_ticks と
min_dwell は整数に丸める。0 の値は揺れない（掛け算なので）。

これは学習の最小の形——「少し変えた版を作り、自己対戦で比べ、勝った方だけを採用する」を回すための道具で、
強化学習ではない。ゲームの中では推論（版のパラメータで判断すること）しかしない。回し方:

    /tdm ai versions export rule_v1
    python tool/ai/propose_version.py --base rule_v1 --name rule_v2
    /tdm ai versions reload
    /tdm ai selfplay start 40 rule_v2 rule_v1
    python tool/ai/evaluate.py --challenger rule_v2 --champion rule_v1
    /tdm ai promote rule_v2
"""
import argparse
import io
import json
import math
import os
import random
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.normpath(os.path.join(HERE, os.pardir, os.pardir, "run", "ashvehicles_ai"))
NAME = re.compile(r"^[a-z0-9_.\-]{1,48}$")
BUILT_IN = "rule_v1"


def load_base(root, base):
    for folder in ("versions", "exports"):
        path = os.path.join(root, folder, base + ".json")
        if os.path.exists(path):
            with io.open(path, encoding="utf-8") as f:
                return json.load(f), path
    return None, None


def flatten(prefix, value, into):
    """ゲームの ParameterSet.fromJson と同じく、入れ子をドットで繋いで平らにする。数でない値は捨てる。"""
    for key, item in value.items():
        name = key if not prefix else prefix + "." + key
        if isinstance(item, dict):
            flatten(name, item, into)
        elif isinstance(item, (int, float)) and not isinstance(item, bool):
            into[name] = float(item)


def perturb(key, value, sigma, rng):
    if value == 0.0:
        return value
    moved = value * math.exp(rng.gauss(0.0, sigma))
    if key.startswith("profile."):
        moved = min(1.0, max(0.0, moved))
    if key.endswith("_ticks") or key.endswith("min_dwell"):
        moved = float(max(1, int(round(moved))))
    return round(moved, 6)


def main():
    parser = argparse.ArgumentParser(description="Propose a challenger AI version by perturbing a base version.")
    parser.add_argument("--root", default=DEFAULT_ROOT, help="the game's ashvehicles_ai folder")
    parser.add_argument("--base", required=True, help="version to start from")
    parser.add_argument("--name", required=True, help="name of the new version")
    parser.add_argument("--sigma", type=float, default=0.1, help="log-normal spread of each change")
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--only", default="", help="comma-separated key prefixes to vary, e.g. policy.,profile.")
    parser.add_argument("--show", type=int, default=20, help="how many changes to print")
    parser.add_argument("--force", action="store_true", help="overwrite an existing version file")
    args = parser.parse_args()

    if not NAME.match(args.name):
        sys.exit("version names are lower case letters, digits and _ . - (at most 48)")

    if args.name == BUILT_IN:
        sys.exit("%s is the built-in version; the game skips a file with that name" % BUILT_IN)

    data, source = load_base(args.root, args.base)

    if data is None:
        sys.exit("no versions/%s.json or exports/%s.json under %s -- run /tdm ai versions export %s in the game first"
                 % (args.base, args.base, args.root, args.base))

    parameters = {}
    flatten("", data.get("parameters") or {}, parameters)

    if not parameters:
        sys.exit("%s has no parameters to vary (export it with /tdm ai versions export)" % source)

    prefixes = [prefix for prefix in args.only.split(",") if prefix]
    rng = random.Random(args.seed)
    changed = {}

    for key in sorted(parameters):
        if prefixes and not any(key.startswith(prefix) for prefix in prefixes):
            continue
        value = perturb(key, parameters[key], args.sigma, rng)
        if value != parameters[key]:
            changed[key] = (parameters[key], value)
            parameters[key] = value

    target = os.path.join(args.root, "versions", args.name + ".json")

    if os.path.exists(target) and not args.force:
        sys.exit("%s exists (use --force to overwrite)" % target)

    version = {
        "id": args.name,
        "policy": data.get("policy", "rule_based"),
        "parent": data.get("id", args.base),
        "description": "perturbed from %s (sigma %s, seed %s)" % (args.base, args.sigma, args.seed),
        "parameters": dict(sorted(parameters.items())),
    }

    if not os.path.isdir(os.path.dirname(target)):
        os.makedirs(os.path.dirname(target))

    with io.open(target, "w", encoding="utf-8") as f:
        f.write(json.dumps(version, indent=2, ensure_ascii=False))
        f.write(u"\n")

    print("wrote %s (%d of %d parameters changed, from %s)" % (target, len(changed), len(parameters), source))

    for key in sorted(changed)[:args.show]:
        print("  %-42s %10.4f -> %10.4f" % (key, changed[key][0], changed[key][1]))

    return 0


if __name__ == "__main__":
    sys.exit(main())
