# -*- coding: utf-8 -*-
"""戦闘 AI の版を比べる。ゲームの外で、ゲームが書いた戦績と記録から。

    python tool/ai/evaluate.py [--root <フォルダ>] [--from-logs]
                               [--challenger <版> --champion <版>] [--min-battles 20] [--win-rate 0.55]

--root はゲームフォルダの ashvehicles_ai（既定は開発用の run/ashvehicles_ai）。

既定では stats/<版>.json（ゲームが戦闘ごとに足している累計）を読む。--from-logs を付けると
training/battles.jsonl と training/lives.jsonl から数え直す——統計ファイルと記録が食い違っていないかの答え合わせ。

--challenger と --champion を渡すと、ゲーム内の /tdm ai eval と同じ規則で採用してよいかを答える:
直接の対戦が --min-battles 以上あり、挑戦者の勝率（引き分けは半分）が --win-rate 以上。
採用なら終了コード 0、不採用なら 1。
"""
import argparse
import glob
import io
import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.normpath(os.path.join(HERE, os.pardir, os.pardir, "run", "ashvehicles_ai"))

COUNTS = ("battles", "wins", "losses", "draws", "lives", "kills", "deaths", "captures", "recaptures", "defenses")
AMOUNTS = ("damage", "reward", "survival_ticks")


class Tally(object):
    """ゲームの BattleStatistics.Tally と同じ形の累計。"""

    def __init__(self, data=None):
        data = data or {}
        for key in COUNTS:
            setattr(self, key, int(data.get(key, 0)))
        for key in AMOUNTS:
            setattr(self, key, float(data.get(key, 0.0)))

    def add(self, other):
        for key in COUNTS + AMOUNTS:
            setattr(self, key, getattr(self, key) + getattr(other, key))

    def win_rate(self):
        return 0.0 if self.battles == 0 else (self.wins + 0.5 * self.draws) / self.battles

    def per_life(self, value):
        return 0.0 if self.lives == 0 else value / float(self.lives)

    def per_battle(self, value):
        return 0.0 if self.battles == 0 else value / float(self.battles)


def read_json(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def read_lines(path):
    """JSON Lines を読む。壊れた行は飛ばして知らせる（書きかけでサーバーが落ちた最後の1行など）。"""
    if not os.path.exists(path):
        return []
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


def from_stats(root):
    overall = {}
    versus = defaultdict(dict)
    for path in sorted(glob.glob(os.path.join(root, "stats", "*.json"))):
        data = read_json(path)
        version = data.get("version") or os.path.basename(path)[:-5]
        overall[version] = Tally(data.get("overall"))
        for opponent, tally in (data.get("versus") or {}).items():
            versus[version][opponent] = Tally(tally)
    return overall, dict(versus)


def from_logs(root):
    overall = defaultdict(Tally)
    versus = defaultdict(lambda: defaultdict(Tally))
    battles = {}

    for row in read_lines(os.path.join(root, "training", "battles.jsonl")):
        if row.get("aborted"):
            continue
        teams = row.get("versions") or {}
        winner = row.get("winner")
        battles[row.get("battle")] = teams
        for team, version in teams.items():
            side = Tally({
                "battles": 1,
                "wins": 1 if winner == team else 0,
                "losses": 1 if winner is not None and winner != team else 0,
                "draws": 1 if winner is None else 0,
            })
            overall[version].add(side)
            for other, opponent in teams.items():
                if other != team:
                    versus[version][opponent].add(side)

    for row in read_lines(os.path.join(root, "training", "lives.jsonl")):
        teams = battles.get(row.get("battle"))
        if teams is None:
            continue
        if "end_tick" in row and "spawn_tick" in row:
            survival = max(0, int(row["end_tick"]) - int(row["spawn_tick"]))
        else:
            survival = float(row.get("survival_seconds", 0.0)) * 20.0
        life = Tally({
            "lives": 1,
            "kills": row.get("kills", 0),
            "deaths": row.get("deaths", 0),
            "captures": row.get("captures", 0),
            "recaptures": row.get("recaptures", 0),
            "defenses": row.get("defenses", 0),
            "damage": row.get("damage_dealt", 0.0),
            "reward": row.get("reward", 0.0),
            "survival_ticks": survival,
        })
        version = row.get("version")
        overall[version].add(life)
        for other, opponent in teams.items():
            if other != row.get("team"):
                versus[version][opponent].add(life)

    return dict(overall), dict((key, dict(value)) for key, value in versus.items())


def show(overall, versus):
    header = "%-16s %7s %6s %10s %11s %11s %11s %9s %11s %11s" % (
        "version", "battles", "win%", "kills/life", "deaths/life", "capture/btl", "defense/btl", "survive s",
        "damage/life", "reward/life")
    print(header)
    print("-" * len(header))
    for version in sorted(overall):
        t = overall[version]
        print("%-16s %7d %5.1f%% %10.2f %11.2f %11.2f %11.2f %9.0f %11.0f %11.1f" % (
            version, t.battles, t.win_rate() * 100.0, t.per_life(t.kills), t.per_life(t.deaths),
            t.per_battle(t.captures + t.recaptures), t.per_battle(t.defenses), t.per_life(t.survival_ticks) / 20.0,
            t.per_life(t.damage), t.per_life(t.reward)))
        for opponent in sorted(versus.get(version, {})):
            head = versus[version][opponent]
            print("  vs %-12s %7d %5.1f%%" % (opponent, head.battles, head.win_rate() * 100.0))


def verdict(versus, challenger, champion, min_battles, win_rate):
    """ゲームの learning/Evaluation.judge と同じ規則。"""
    head = versus.get(challenger, {}).get(champion) or Tally()
    rate = head.win_rate()
    if challenger == champion:
        return False, "same version", head
    if head.battles < min_battles:
        return False, "needs %d head-to-head battles, has %d" % (min_battles, head.battles), head
    if rate < win_rate:
        return False, "win rate %.1f%% is under %.1f%%" % (rate * 100.0, win_rate * 100.0), head
    return True, "win rate %.1f%% over %d battles" % (rate * 100.0, head.battles), head


def main():
    parser = argparse.ArgumentParser(description="Compare combat AI versions from recorded battles.")
    parser.add_argument("--root", default=DEFAULT_ROOT, help="the game's ashvehicles_ai folder")
    parser.add_argument("--from-logs", action="store_true", help="recount from training/*.jsonl instead of stats/")
    parser.add_argument("--challenger")
    parser.add_argument("--champion")
    parser.add_argument("--min-battles", type=int, default=20)
    parser.add_argument("--win-rate", type=float, default=0.55)
    args = parser.parse_args()

    if not os.path.isdir(args.root):
        sys.exit("no AI data at %s" % args.root)

    overall, versus = from_logs(args.root) if args.from_logs else from_stats(args.root)

    if not overall:
        print("no battles recorded yet")
    else:
        show(overall, versus)

    if not (args.challenger or args.champion):
        return 0

    if not (args.challenger and args.champion):
        parser.error("--challenger and --champion go together")

    approved, reason, head = verdict(versus, args.challenger, args.champion, args.min_battles, args.win_rate)
    print("")
    print("%s vs %s: %d battles, win %.1f%% -> %s (%s)" % (
        args.challenger, args.champion, head.battles, head.win_rate() * 100.0,
        "APPROVED" if approved else "not approved", reason))
    return 0 if approved else 1


if __name__ == "__main__":
    sys.exit(main())
