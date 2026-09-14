package com.ashvehicles.ai.team;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.ashvehicles.ai.decision.ParameterSet;

/**
 * 陣営が次に出す AI の種類——戦場に今足りない物（2026-09-13 の指示「状況に合わせてスポーンする車両を選ぶ」）。
 *
 * <p>それまでは一覧からくじを引くだけで、空に敵が群れていても戦車が、取れる拠点が並んでいても砲兵が出ていた。種類
 * ごとに「足りなさ」を点にし、その2乗に比べてくじを引く（{@link #choose}）——一番の不足が大抵選ばれ、それでも毎回
 * 同じ種類にはならない。点は3つから作る:
 *
 * <ul>
 * <li><b>敵の編成に対して、それに当たる味方が足りない分</b>（敵の航空機 − 味方の防空、敵の戦車 − 味方の戦車…）
 * <li><b>拠点</b>——取りに行く拠点は軽装甲を、取られかけている拠点は戦車を呼ぶ
 * <li><b>最近何に倒されたか</b>（{@link #lost}、直近 {@value #MEMORY} tick）——空から倒されていれば防空を呼び、
 *     防空に落とされていれば攻撃機を控える
 * <li><b>チケットの余裕</b>（{@link #spare}）——残りが多いうちは航空機（戦闘機と攻撃機）を呼ぶ（2026-09-13 の指示「チケットに
 *     余裕があれば航空機を出す」）。航空機は拠点を取らず、防空に落とされやすく、落ちれば1枚持っていく。余裕のあるうちに空を
 *     使い、減ってきたら拠点を取って守れる地上に戻す。余裕が無くても、敵機や敵の戦車に応じて呼ぶ分はそのまま
 * </ul>
 *
 * <pre>
 * 戦車   = base + armour × 足りない戦車 + hold × 取られかけの拠点 + lost × 戦車に倒された − have × 味方の戦車
 * 軽装甲 = base + capture × 取りたい拠点 + soft × 足りない軽い物 − have × 味方の軽装甲
 * 防空   = airDefence × 防空に当たられていない敵機 + lost × 空から倒された
 * 砲兵   = support − 2 × have × 味方の砲兵
 * 戦闘機 = intercept × 戦闘機に当たられていない敵機 + lost ÷ 2 × 空から倒された − flak × 敵の防空 + spare × 余裕
 * 攻撃機 = strike + spare × 余裕 + armour ÷ 2 × 敵の戦車 − have × 味方の攻撃機 − flak × 敵の防空 − lost × 防空に落とされた
 * 余裕   = (残りのチケット ÷ 始めのチケット − 0.3) ÷ 0.4 を 0〜1 に収めた物（7割以上残っていれば1、3割以下は0）
 * </pre>
 *
 * <p>数は陣営 {@value #SQUAD} 両あたりに揃える——20両の陣営で敵機3機は、5両の陣営の3機ほど急ではない。重みは版の
 * パラメータ（{@code spawn.*}）。数えるのと、一覧と航空機の枠で絞るのは {@code match/Bots.pick}。
 */
public final class Reinforcements {
    /** 数を揃える陣営の大きさ。 */
    private static final double SQUAD = 5.0;

    /** 損失を覚えている長さ（tick）。 */
    private static final long MEMORY = 2400L;

    /** 陣営ごとに覚えている損失の数の上限。 */
    private static final int MOST_LOSSES = 64;

    /** チケットの残り（始めに対する割合）がこれ以下なら余裕は0、{@link #PLENTY} 以上なら1。 */
    private static final double RESERVE = 0.3;
    private static final double PLENTY = 0.7;

    /** 陣営ごとの損失。サーバースレッドだけが触る。 */
    private static final Map<String, Deque<Loss>> LOSSES = new HashMap<>();

    /** 出す物の種類。 */
    public enum Kind {
        /** 戦車（役割 {@code TANK}）。 */
        TANK,
        /** 軽装甲（歩兵戦闘車・装甲兵員輸送車・偵察車）。 */
        LIGHT,
        /** 防空（役割 {@code AA}。一覧に無ければ空を撃てる他の地上車両）。 */
        AIR_DEFENCE,
        /** 砲兵（榴弾砲・多連装ロケット）。 */
        ARTILLERY,
        /** 戦闘機（レーダーとパイロットの機関砲を持つ航空機）。 */
        FIGHTER,
        /** 攻撃機（それ以外の航空機）。 */
        STRIKER;

        /** 航空機か。陣営の航空機の枠に数える物。 */
        public boolean flies() {
            return this == FIGHTER || this == STRIKER;
        }
    }

    /** 味方が何に倒されたか。 */
    public enum Cause {
        /** 航空機。 */
        AIR,
        /** 戦車。 */
        ARMOUR,
        /** 空を撃てる地上車両に落とされた航空機。 */
        AIR_DEFENCE,
        /** 砲兵。 */
        ARTILLERY,
        /** それ以外（人・軽装甲・墜落）。 */
        OTHER
    }

    private record Loss(Cause cause, long at) {
    }

    /** 重み。{@link #of} が版のパラメータ（{@code spawn.*}）から読む。 */
    public record Weights(double base, double armour, double capture, double hold, double soft, double airDefence,
            double intercept, double flak, double lost, double have, double support, double strike, double spare) {
        public static Weights of(ParameterSet parameters) {
            return new Weights(
                    parameters.get("spawn.base", 1.0),
                    parameters.get("spawn.armour", 0.6),
                    parameters.get("spawn.capture", 0.8),
                    parameters.get("spawn.hold", 0.4),
                    parameters.get("spawn.soft", 0.3),
                    parameters.get("spawn.air_defence", 0.9),
                    parameters.get("spawn.intercept", 0.7),
                    parameters.get("spawn.flak", 0.5),
                    parameters.get("spawn.lost", 0.5),
                    parameters.get("spawn.have", 0.25),
                    parameters.get("spawn.support", 0.4),
                    parameters.get("spawn.strike", 0.6),
                    parameters.get("spawn.spare", 1.0));
        }
    }

    /**
     * 陣営から見た戦場。数は機体の数で、空を撃てる防空以外の地上車両は防空に半分、歩いている人は軽い物に半分。
     *
     * @param own        味方の数（人の乗る物も AI も）
     * @param capturable 取りに行く拠点（自陣が握っていない物と、奪還が要る物）
     * @param threatened 取られかけている自陣の拠点
     * @param spare      チケットの余裕（{@link #spare}、0〜1）
     */
    public record Situation(double own, double enemyAir, double enemyArmour, double enemyLight,
            double enemyAirDefence, double ownTanks, double ownLight, double ownAirDefence, double ownArtillery,
            double ownFighters, double ownStrikers, double capturable, double threatened, double lostToAir,
            double lostToArmour, double lostToAirDefence, double spare) {
    }

    private Reinforcements() {
    }

    /** 種類ごとの足りなさ。 */
    public static Map<Kind, Double> needs(Situation s, Weights w) {
        double per = SQUAD / Math.max(s.own(), SQUAD);
        Map<Kind, Double> needs = new EnumMap<>(Kind.class);

        needs.put(Kind.TANK, w.base() + w.armour() * Math.max(0.0, s.enemyArmour() - s.ownTanks()) * per
                + w.hold() * s.threatened() + w.lost() * s.lostToArmour() - w.have() * s.ownTanks() * per);
        needs.put(Kind.LIGHT, w.base() + w.capture() * s.capturable()
                + w.soft() * Math.max(0.0, s.enemyLight() - s.ownLight()) * per - w.have() * s.ownLight() * per);
        needs.put(Kind.AIR_DEFENCE, w.airDefence()
                * Math.max(0.0, s.enemyAir() - s.ownAirDefence() - 0.5 * s.ownFighters()) * per
                + w.lost() * s.lostToAir());
        needs.put(Kind.ARTILLERY, w.support() - 2.0 * w.have() * s.ownArtillery() * per);
        needs.put(Kind.FIGHTER, w.intercept()
                * Math.max(0.0, s.enemyAir() - s.ownFighters() - 0.5 * s.ownAirDefence()) * per
                + 0.5 * w.lost() * s.lostToAir() - w.flak() * s.enemyAirDefence() * per + w.spare() * s.spare());
        needs.put(Kind.STRIKER, w.strike() + w.spare() * s.spare() + 0.5 * w.armour() * s.enemyArmour() * per
                - w.have() * s.ownStrikers() * per - w.flak() * s.enemyAirDefence() * per
                - w.lost() * s.lostToAirDefence());

        return needs;
    }

    /**
     * チケットの余裕（0〜1）。残りが始めの {@value #PLENTY} 以上なら1、{@value #RESERVE} 以下なら0、その間は真っ直ぐ。
     * 始めより多く持っていても1。
     */
    public static double spare(int tickets, int start) {
        double share = (double) tickets / Math.max(start, 1);

        return Math.max(0.0, Math.min(1.0, (share - RESERVE) / (PLENTY - RESERVE)));
    }

    /**
     * 種類を1つ選ぶ。出せる種類のうち、足りなさの2乗に比べたくじ（{@code roll} は0以上1未満）。足りない物が1つも無ければ
     * 出せる種類から均等に。出せる種類が無ければ null。
     */
    @Nullable
    public static Kind choose(Map<Kind, Double> needs, Set<Kind> available, double roll) {
        if (available.isEmpty()) {
            return null;
        }

        double total = 0.0;

        for (Kind kind : Kind.values()) {
            if (available.contains(kind)) {
                total += weight(needs, kind);
            }
        }

        if (total <= 0.0) {
            int index = Math.min((int) (roll * available.size()), available.size() - 1);
            int at = 0;

            for (Kind kind : Kind.values()) {
                if (available.contains(kind) && at++ == index) {
                    return kind;
                }
            }
        }

        double mark = roll * total;
        Kind last = null;

        for (Kind kind : Kind.values()) {
            double weight = available.contains(kind) ? weight(needs, kind) : 0.0;

            if (weight <= 0.0) {
                continue;
            }

            last = kind;

            if (mark < weight) {
                return kind;
            }

            mark -= weight;
        }

        return last;
    }

    /** 味方の1両が倒された。{@code match/Deathmatch} の撃破の出口から。 */
    public static void lost(String team, Cause cause, long now) {
        Deque<Loss> losses = LOSSES.computeIfAbsent(team, key -> new ArrayDeque<>());

        losses.addLast(new Loss(cause, now));

        while (losses.size() > MOST_LOSSES) {
            losses.removeFirst();
        }
    }

    /** 直近 {@value #MEMORY} tick に、その原因で倒された味方の数。 */
    public static int lostTo(String team, Cause cause, long now) {
        Deque<Loss> losses = LOSSES.get(team);

        if (losses == null) {
            return 0;
        }

        losses.removeIf(loss -> now - loss.at() > MEMORY);

        int counted = 0;

        for (Loss loss : losses) {
            if (loss.cause() == cause) {
                counted++;
            }
        }

        return counted;
    }

    /** 覚えている損失を捨てる。試合が止まったとき。 */
    public static void clear() {
        LOSSES.clear();
    }

    private static double weight(Map<Kind, Double> needs, Kind kind) {
        double need = needs.getOrDefault(kind, 0.0);

        return need > 0.0 ? need * need : 0.0;
    }
}
