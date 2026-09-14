# -*- coding: utf-8 -*-
"""判断の記録を学習用の表にする。battles/*/decisions.jsonl を1つの CSV へまとめる。

    python tool/ai/export_dataset.py --out dataset.csv [--version <版>] [--include-aborted] [--root <フォルダ>]

1行が判断1回: そのときの状態の要約と特徴量、選んだ行動、次の判断までに受け取った報酬、その陣営が勝ったか。
真偽は 1/0、無い値（最初の判断の previous、決着の無い試合の victory）は空欄。
将来の学習（ai/decision/DecisionPolicy の差し替え口）へ食わせる形で、ゲームはこれを読まない。

特徴量の列名は各戦闘の meta.json の features に f_ を付けた物（要約の列と名前が重なるので）。並びの違う
戦闘が混ざっていたら止める——特徴量の並びを変えたら版を分ける約束（ai/decision/BattleState#FEATURES）なので、
混ざっているのは読み違いの元だ。
"""
import argparse
import csv
import glob
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.normpath(os.path.join(HERE, os.pardir, os.pardir, "run", "ashvehicles_ai"))

COLUMNS = ["battle", "t", "duration", "bot", "team", "version", "role", "vehicle", "x", "y", "z", "action", "previous",
           "objective", "reward", "victory", "enemy_threat", "ally_support", "route_risk", "health",
           "distance_to_objective", "enemy_visible", "objective_contested"]


def read_json(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def read_lines(path):
    rows = []
    with io.open(path, encoding="utf-8") as f:
        for number, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except ValueError:
                sys.stderr.write("%s:%d: skipped a broken line\n" % (path, number))
    return rows


def main():
    parser = argparse.ArgumentParser(description="Export recorded AI decisions as one CSV for training.")
    parser.add_argument("--root", default=DEFAULT_ROOT, help="the game's ashvehicles_ai folder")
    parser.add_argument("--out", required=True)
    parser.add_argument("--version", help="only this AI version")
    parser.add_argument("--include-aborted", action="store_true", help="also battles the server stopped mid-way")
    args = parser.parse_args()

    features = None
    written = 0
    battles = 0
    skipped = 0

    with io.open(args.out, "w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out)

        for folder in sorted(glob.glob(os.path.join(args.root, "battles", "*"))):
            meta_path = os.path.join(folder, "meta.json")
            summary_path = os.path.join(folder, "summary.json")
            decisions_path = os.path.join(folder, "decisions.jsonl")

            if not (os.path.exists(meta_path) and os.path.exists(summary_path) and os.path.exists(decisions_path)):
                continue

            summary = read_json(summary_path)

            if summary.get("aborted") and not args.include_aborted:
                continue

            names = read_json(meta_path).get("features") or []

            if features is None:
                features = names
                writer.writerow(COLUMNS + ["f_" + name for name in features])
            elif names != features:
                sys.exit("%s uses a different feature layout; export those versions separately" % folder)

            winner = summary.get("winner")
            battles += 1

            for row in read_lines(decisions_path):
                if args.version and row.get("version") != args.version:
                    continue

                vector = row.get("features") or []

                if len(vector) != len(features):
                    skipped += 1
                    continue

                values = dict((key, int(value) if isinstance(value, bool) else value) for key, value in row.items())
                values["victory"] = "" if winner is None else (1 if row.get("team") == winner else 0)
                writer.writerow([values.get(column, "") for column in COLUMNS] + vector)
                written += 1

    print("wrote %d decisions from %d battles to %s%s" % (
        written, battles, args.out, "" if skipped == 0 else " (skipped %d with a wrong feature count)" % skipped))
    return 0


if __name__ == "__main__":
    sys.exit(main())
