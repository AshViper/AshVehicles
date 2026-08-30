package com.ashvehicles.sensor;

import javax.annotation.Nullable;

import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.Team;

import io.netty.buffer.ByteBuf;

/**
 * スコープに載った物が味方か否か。
 *
 * <p><b>所属はバニラのスコアボードチームで決まる。</b> この MOD は陣営という概念を自前で持たない。
 * {@code /team add} と {@code /team join} が既にあり、サーバーが人をどう分けているかを知っているのは
 * MOD ではなくその設定だからだ。チームを一つも作っていないワールドでは全てが {@link #UNKNOWN} になり、
 * 計器は IFF を導入する前と1ピクセルも変わらない画を描く。それが正しい振る舞いで、区別すべき物が無い
 * 場所で計器が区別したふりをする理由は無い。
 *
 * <p><b>機体は自分の所属を持てるし、乗員から借りることもできる。</b> バニラのチームはプレイヤー名だけ
 * でなく任意のエンティティを UUID で入れられるので、{@code /team join blue @e[type=ashvehicles:...]}
 * は無人の対空陣地にも所属を与える。それが無い機体は乗っている者の所属を名乗る——普通に飛んでいる機体に
 * 所属を与えるのはこちらで、パイロットが乗り込んだ瞬間にその機体は青軍機になる。
 *
 * <p><b>どちらも答えなければ {@link #UNKNOWN}。</b> 実物の IFF は質問に対する応答であって、敵が「敵で
 * ある」と名乗る装置ではない。応答が返らない理由は、敵であること・装置が壊れていること・そもそも積んで
 * いないことのどれでもあり得る。ここでも同じで、片方でもチームに属していなければ判定は付かない。
 * {@link #HOSTILE} が出るのは<em>両方が名乗った上で敵対している</em>場合だけだ。
 *
 * <p>両側で同じ答えが出る。チーム所属はクライアントにも同期されるので、サーバーが接触に押した判定と、
 * クライアントが HMD の枠のために自分で出す判定は一致する。
 */
public enum Iff {
    /** 同じチーム、または同盟関係にある。撃つべきではない相手。 */
    FRIEND,
    /** どちらかが名乗っていない。ほとんどのワールドではこれが全部になる。 */
    UNKNOWN,
    /** 双方が名乗った上で別のチーム。 */
    HOSTILE;

    private static final Iff[] BY_ID = values();

    public static final StreamCodec<ByteBuf, Iff> STREAM_CODEC =
            ByteBufCodecs.idMapper(Iff::byId, Iff::ordinal);

    public static Iff byId(int id) {
        return BY_ID[Math.floorMod(id, BY_ID.length)];
    }

    /**
     * 質問する側から見た、質問された側の身元。
     *
     * @param interrogator 訊いている機体。あるいは訊いている者そのもの
     * @param contact スコープに載った相手
     */
    public static Iff between(Entity interrogator, Entity contact) {
        Team mine = allegiance(interrogator);

        if (mine == null) {
            return UNKNOWN;
        }

        Team theirs = allegiance(contact);

        if (theirs == null) {
            return UNKNOWN;
        }

        return mine.isAlliedTo(theirs) ? FRIEND : HOSTILE;
    }

    /**
     * そのエンティティが名乗るチーム。名乗らなければ null。
     *
     * <p>自分自身の所属が先。機体に直接与えた所属は、たまたま乗り込んだ者の所属より優先されるべきで、
     * それが無人の陣地に所属を与える唯一の方法でもあるからだ。無ければ乗員に訊く。座席順ではなく搭乗順に
     * なるが、1機の中で所属の割れた乗員が乗っているのはそもそも普通の状況ではない。
     */
    @Nullable
    private static Team allegiance(Entity entity) {
        Team own = entity.getTeam();

        if (own != null) {
            return own;
        }

        if (entity instanceof VehicleEntityBase vehicle) {
            for (Entity passenger : vehicle.getPassengers()) {
                Team crew = passenger.getTeam();

                if (crew != null) {
                    return crew;
                }
            }
        }

        return null;
    }
}
