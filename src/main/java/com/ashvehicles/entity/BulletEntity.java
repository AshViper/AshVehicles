package com.ashvehicles.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 機体の機銃から出る弾1発。
 *
 * <p>速く、真っ直ぐで、短命。砲口で全速度を与えられ、その後は落ちる以外に何もしない。弾がすることはそれが
 * 全部で、必要な残りは {@link VehicleProjectile} にある。
 */
public class BulletEntity extends VehicleProjectile {
    public BulletEntity(EntityType<? extends BulletEntity> type, Level level) {
        super(type, level);
    }

    /** 弾を誘導する物は無い。重力は他と同じく移動後に適用される。 */
    @Override
    protected void steer() {
    }

    /**
     * <b>機銃弾にバニラのエンティティ tick は要らない。</b>
     *
     * <p>{@code Entity.baseTick} が数えるのはポータル、流体、火、水しぶき、そして息継ぎで、そのどれもが
     * 弾の箱が覆うブロックを引くところから始まる。0.2ブロックの曳光にはどれも起こらない。捨てるのは
     * 毎tick 3〜10回のブロック・流体読みと、その手前の chunk 参照と、{@code Projectile.tick} が
     * 「まだ撃った本人の中にいるか」を確かめるために撃つ全長53ブロックのエンティティ問い合わせだ。
     *
     * <p>ロケットと爆弾はこれを返さない。あちらは水に落ちれば跳ねるべきだし、寿命も桁違いに長い。
     *
     * <p><b>自機に当たらないという保証は失わない。</b> あれを担保しているのは
     * {@code Projectile.canHitEntity} の {@code leftOwner} ではなく、その手前の
     * {@code WeaponMounts.isPartOf(firedFrom(), target)}——撃った機体そのものと、それに乗っている物と、
     * その箱を全部除く1行——だ。{@link VehicleProjectile#canHitEntity} 参照。
     *
     * <p>失うのは、スカルクセンサーが機銃の発砲を聞くことだけ。毎秒100発で震動を撒く曳光は、そもそも
     * その仕組みが想定している音ではない。着弾の {@code PROJECTILE_LAND} は今まで通り鳴る。
     */
    @Override
    protected boolean usesVanillaTick() {
        return false;
    }
}
