package com.ashvehicles.client;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 三人称カメラを機体のデータパックファイルが指定する位置に据え、その間ずっと機体全体を画面に収める。
 *
 * <p>バニラは搭乗者の目の4ブロック後ろに座ることしか知らない。15ブロックの機体ではそれは胴体内のどこかだし、
 * 戦車ではエンジンデッキ以外何も見えない近さだ。代わりにカメラを直接、機体中心から {@code camera.pos} の位置へ
 * 置く。
 *
 * <p>そのオフセットは機体軸ではなく<em>視点</em>軸で測る。x は視界の右、y は真上、z は視線方向で負が後方。機体
 * 自身の方位にぶら下げるのは自明な手だが誤りだ。マウスは見ることで照準するからで、機首を上げたり砲塔を回したり
 * すれば視界はそれに追随するのに、方位固定のカメラは車体の向きに留まる——乗員が最も見たい瞬間に、狙っている物を
 * 画面外へ落とすわけだ。視線に沿って測れば、機体が何をしていても画面内で静止する。
 *
 * <p>三人称視点を持つ全機体で共有する。2つの車両カメラハンドラの違いは角度の扱いであってカメラの最終位置では
 * ない。それらが視界に既に施した処理——機体では無し、戦車では数度の下向き——はこれが読む時点でカメラ自身の回転
 * に入っているので、オフセットは乗員が実際に見下ろしている軸に沿って測られる。
 */
public final class ChaseCamera {
    /** カメラと、さもなければ押し付けられるブロックとの間に確保する隙間。 */
    private static final double BLOCK_CLEARANCE = 0.25;

    /**
     * カメラを車両中心から {@code offset} の位置へ置く。{@link com.ashvehicles.mixin.CameraMixin} から、バニラが
     * カメラ配置を終えた後に呼ばれる。新しい位置が実際に定着する最初の瞬間がそこだ。
     */
    public static void place(Camera camera, Entity vehicle, Vec3 offset, float partialTick) {
        // 機体の方位ではなく視線に追従することが、上昇・降下・ロール・旋回を通して機体を中央に保つ。おまけに
        // 反転三人称視点も無料で正しくなる。あちらでは視線が反転するので、カメラは機体の前方に着き、機体を振り
        // 返って見ることになるからだ。
        Vec3 sight = Vec3.directionFromRotation(camera.getXRot(), camera.getYRot());
        Vec3 right = sight.cross(new Vec3(0.0, 1.0, 0.0));
        right = right.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();

        // 原点ではなく機体中心を使う。原点は車輪の位置にあるからだ。
        Vec3 middle = vehicle.getPosition(partialTick).add(0.0, vehicle.getBbHeight() * 0.5, 0.0);
        Vec3 target = middle
                .add(right.scale(offset.x))
                .add(0.0, offset.y, 0.0)
                .add(sight.scale(offset.z));

        camera.setPosition(clipToBlocks(vehicle, middle, target));
    }

    /**
     * 機体からカメラへの線が地形を貫くならカメラを手前へ引く。バニラも自分の4ブロックに対して同じことをする。
     * これが無いとカメラは平気で丘の内側に収まるし、後方に置くよう指定するほどその時間は長くなる。
     */
    private static Vec3 clipToBlocks(Entity vehicle, Vec3 anchor, Vec3 target) {
        Level level = vehicle.level();
        HitResult hit = level.clip(
                new ClipContext(anchor, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, vehicle));

        if (hit.getType() == HitResult.Type.MISS) {
            return target;
        }

        Vec3 blocked = hit.getLocation();
        Vec3 back = blocked.subtract(anchor);

        return back.lengthSqr() <= BLOCK_CLEARANCE * BLOCK_CLEARANCE
                ? anchor
                : blocked.subtract(back.normalize().scale(BLOCK_CLEARANCE));
    }

    private ChaseCamera() {
    }
}
