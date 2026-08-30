package com.ashvehicles.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;

/**
 * 機体が、自分を構成する箱のために答えられなければならないこと。
 *
 * <p>{@link VehiclePart} はそれ自体が独立したエンティティで、ゲームは被弾・クリック・視線判定の結果を
 * 「機体そのもの」であるかのようにパーツへ渡す。その大半はパーツが所属先へそのまま流し、所属先へは
 * {@code Entity} 自身のインターフェースで辿り着く。ここにあるのは機体にしか答えられない短い一覧であり、
 * 1つの箱が飛行機にも戦車にも等しく属せる理由でもある。
 *
 * <p>形状の話はもうここには無い。パーツは機体が配置した瞬間から自分の
 * {@link com.ashvehicles.vehicle.Hitbox} を持ち、自分で答える。
 *
 * <p>実装するのは {@link AircraftEntity} と {@link GroundVehicleEntity}。実装する物は {@code Entity}
 * でもなければならない——両方を要求しているのは {@link VehiclePart} のコンストラクタ。
 */
public interface PartHost {
    /**
     * 特定のパイロン1本へのクリック。そのパイロンだけを指す。
     *
     * <p>パイロンを持つのは機体だけ。それ以外はクリックをそのまま下へ通し、普段通りの意味にさせる。
     */
    default InteractionResult interactPylon(Player player, InteractionHand hand, int slot) {
        return InteractionResult.PASS;
    }

    /**
     * そのパイロンに手を伸ばす価値があるか。できることが何も無いパイロンは脇へ退き、クリックを後ろの機体へ
     * 通す。
     */
    default boolean isLoadablePylon(int slot) {
        return false;
    }
}
