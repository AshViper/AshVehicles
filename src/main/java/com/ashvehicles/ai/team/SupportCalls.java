package com.ashvehicles.ai.team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.world.phys.Vec3;

/**
 * 陣営の支援要請（2026-09-14 の指示「人数不利だったら近くにいる味方に支援を要請するようにしてサポートを受けてもらったり、味方に
 * ＣＡＳのできる航空勢力がいれば航空支援を要求したり」）。
 *
 * <p><b>出すのは押されている AI、誰が行くかを決めるのは陣営の頭。</b> 自分を撃てる敵の数が近くの味方と自分を足した数より多いか、
 * 自分の弾が効かない相手に撃たれている地上の AI が、判断のたびに要請を出し直す（{@link #raise}、{@code GroundPilot}）。陣営の頭
 * （{@link TeamBrain}）は周期ごとに、手の空いている近くの味方を、要請ごとに足りない数＋1両まで割り当てる（{@link #assign}）。
 * 割り当てられた AI は判断の中で支援を大きく数え（{@code decision/RuleBasedPolicy} の call）、要請の主の脇へ出て、撃ってきて
 * いる相手を狙う（{@code tactics/Tactics.support}）。
 *
 * <p>各 AI が撃たれている味方を見かけて行くかを決める形（{@code GroundPilot.buildState} の近くの味方）も残っている。ただしあれは
 * 誰も数を合わせないので、同じ味方へ3両が向かい、隣で押されている味方へは誰も行かないことが起きる。数を合わせるのはこちら。
 *
 * <p><b>航空支援は印を付けるだけ。</b> 要請の主を撃っている敵を、陣営の航空機が狙う相手の点数に足す（{@link #airSupport} →
 * {@code ai/air/KillScore} の support）。地上を撃てない機体（爆弾も機関砲も尽きた）はその相手をそもそも選べない
 * （{@code AirPilot.choose}）ので、応えるのは CAS のできる機体だけになる。
 *
 * <p>サーバースレッド専用。試合の外では陣営の頭ごと作り直される。
 */
public final class SupportCalls {
    /** 要請を覚えている長さ（tick）。出した AI が判断のたびに（1秒ほどで）出し直す。 */
    public static final long LIFETIME = 100L;

    /** 支援に行かせる味方を探す距離（ブロック）。 */
    public static final double REACH = 300.0;

    /** 1つの要請に向かわせる味方の上限。 */
    public static final int MOST_RESPONDERS = 3;

    /**
     * 支援の要請1つ。
     *
     * @param caller    出した AI の車両のエンティティ ID
     * @param at        出した位置
     * @param deficit   足りない数（自分を撃てる敵 − 近くの味方 − 自分）。弾の効かない相手に撃たれているだけなら1
     * @param urgency   急ぎ（0〜1、{@link #urgency}）
     * @param attackers 撃ってきている敵のエンティティ ID
     * @param raised    出し直した時刻
     */
    public record Call(int caller, Vec3 at, int deficit, double urgency, int[] attackers, long raised) {
    }

    /** 支援に回せる味方1両。 */
    public record Candidate(int id, Vec3 at) {
    }

    /** 新しく割り当てた支援。記録のため。 */
    public record Answer(int responder, Call call, double distance) {
    }

    private final Int2ObjectOpenHashMap<Call> calls = new Int2ObjectOpenHashMap<>();

    /** 支援に行く AI → 行き先の要請の主。 */
    private final Map<Integer, Integer> answers = new HashMap<>();

    /**
     * 要請を出す（出し直す）。
     *
     * @return 新しく出した要請なら true
     */
    public boolean raise(Call call) {
        return this.calls.put(call.caller(), call) == null;
    }

    /** 要請を取り下げる。もう押されていない。 */
    public void withdraw(int caller) {
        this.calls.remove(caller);
    }

    /** 生きている AI の出した要請だけを残す。 */
    public void retain(IntSet alive) {
        this.calls.int2ObjectEntrySet().removeIf(entry -> !alive.contains(entry.getIntKey()));
    }

    /**
     * 古い要請を捨て、支援に行く味方を割り当て直す。陣営の頭が周期ごとに呼ぶ。
     *
     * @param candidates 手の空いている味方（{@code GroundPilot.canAnswer}）
     * @return 前の割り当てと行き先の違う支援
     */
    public List<Answer> assign(List<Candidate> candidates, long now) {
        this.calls.values().removeIf(call -> now - call.raised() > LIFETIME);

        List<Candidate> free = new ArrayList<>(candidates.size());

        for (Candidate candidate : candidates) {
            // 要請を出している AI は、自分が助けを待っている。
            if (!this.calls.containsKey(candidate.id())) {
                free.add(candidate);
            }
        }

        Map<Integer, Call> matched = match(this.calls.values(), free, REACH, this.answers);
        List<Answer> fresh = new ArrayList<>();

        for (Candidate candidate : free) {
            Call call = matched.get(candidate.id());

            if (call != null && this.answers.getOrDefault(candidate.id(), -1) != call.caller()) {
                fresh.add(new Answer(candidate.id(), call, horizontal(candidate.at(), call.at())));
            }
        }

        this.answers.clear();
        matched.forEach((responder, call) -> this.answers.put(responder, call.caller()));

        return fresh;
    }

    /** その AI に割り当てられた要請（要請の主の今の要請）。無いか切れていれば null。 */
    @Nullable
    public Call answerFor(int responder, long now) {
        Integer caller = this.answers.get(responder);

        if (caller == null) {
            return null;
        }

        Call call = this.calls.get(caller.intValue());

        return call == null || now - call.raised() > LIFETIME ? null : call;
    }

    /** 航空機にとって、その敵を撃つ急ぎ（0〜1）。要請の主を撃っている相手なら、その要請の急ぎ。 */
    public double airSupport(int enemy, long now) {
        double most = 0.0;

        for (Call call : this.calls.values()) {
            if (now - call.raised() > LIFETIME || call.urgency() <= most) {
                continue;
            }

            for (int attacker : call.attackers()) {
                if (attacker == enemy) {
                    most = call.urgency();
                    break;
                }
            }
        }

        return most;
    }

    /** 要請の急ぎ（0〜1）。足りない数3で1。体力が減るほど、弾の効かない相手に撃たれていれば上がる。下限は 0.2。 */
    public static double urgency(int deficit, double health, boolean helpless) {
        double value = Math.max(deficit, 0) / 3.0 + (1.0 - health) * 0.5 + (helpless ? 0.3 : 0.0);

        return Math.max(0.2, Math.min(1.0, value));
    }

    /**
     * 割り当て。急ぎの高い要請から、届く距離の内の味方を、前からその要請に行っている物・近い物の順に、足りない数＋1
     * （上限 {@value #MOST_RESPONDERS}）まで。1両は1つの要請にだけ行き、要請の主は自分の要請に応えない。
     *
     * @param previous 前の割り当て（支援に行く AI → 要請の主）。行き先がちらつかないように、続けて行っている物を先にする
     * @return 支援に行く AI → 要請
     */
    public static Map<Integer, Call> match(Collection<Call> calls, List<Candidate> candidates, double reach,
            Map<Integer, Integer> previous) {
        List<Call> ordered = new ArrayList<>(calls);

        ordered.sort(Comparator.comparingDouble((Call call) -> -call.urgency()).thenComparingInt(Call::caller));

        Map<Integer, Call> taken = new HashMap<>();

        for (Call call : ordered) {
            int wanted = Math.min(MOST_RESPONDERS, Math.max(call.deficit(), 0) + 1);
            List<Candidate> near = new ArrayList<>();

            for (Candidate candidate : candidates) {
                if (candidate.id() != call.caller() && !taken.containsKey(candidate.id())
                        && horizontal(candidate.at(), call.at()) <= reach) {
                    near.add(candidate);
                }
            }

            near.sort(Comparator.comparingInt((Candidate candidate) ->
                            previous.getOrDefault(candidate.id(), -1) == call.caller() ? 0 : 1)
                    .thenComparingDouble(candidate -> horizontal(candidate.at(), call.at()))
                    .thenComparingInt(Candidate::id));

            for (int i = 0; i < Math.min(wanted, near.size()); i++) {
                taken.put(near.get(i).id(), call);
            }
        }

        return taken;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;

        return Math.sqrt(dx * dx + dz * dz);
    }
}
