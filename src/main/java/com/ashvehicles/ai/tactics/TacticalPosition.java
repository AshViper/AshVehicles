package com.ashvehicles.ai.tactics;

import net.minecraft.world.phys.Vec3;

/**
 * 戦術的な意味を持った1点。遮蔽・射撃位置・側面・撤退先。
 *
 * @param pos        その点（高さは地面）
 * @param kind       何のための点か
 * @param score      探した側が付けた点数。比べるためだけの値で、単位は無い
 * @param protection 脅威から身を隠せる度合い（0〜1）。見込んだ脅威のうち、視線を遮られる物の重みの割合
 * @param fire       そこから目標を撃てる見込み（0〜1）。目標を持たない探索では 0.5
 */
public record TacticalPosition(Vec3 pos, Kind kind, double score, double protection, double fire) {
    /** 点の種類。 */
    public enum Kind {
        /** 身を隠す位置。撃てるとは限らない。 */
        COVER,
        /** 身を隠しながら撃てる位置。戦車の稜線射撃、建物の角。 */
        FIRING,
        /** 目標の側面か後ろ。 */
        FLANK,
        /** 下がる先。 */
        RETREAT
    }
}
