package com.ashvehicles.client.particle;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;

import org.joml.Quaternionf;

/**
 * 雲を1枚の水平な円盤として描く。これを高さと大きさを変えて重ねると、キノコ雲になる。
 *
 * <p>実際の高収量核実験の映像を見ると、雲は塊の集まりではなく<b>層</b>でできている。上がっていく柱の周りに
 * 水平な凝結の棚が何段も並び、その上に平たい傘が乗る。粒を撒いて作ろうとすると、この構造だけは出てこない
 * ——数百個の球はどう並べても層にならず、煙の玉に見えるからだ。層は層として描くしかない。
 *
 * <p>だから使うテクスチャは1枚だけで、変えるのは位置・大きさ・色だけにしてある。キノコ雲全体で40枚ほど。
 * 粒で同じ体積を埋めるより桁で安く、しかも形が出る。
 *
 * <h2>寝かせるが、消えはしない</h2>
 *
 * <p>{@link ShockwaveParticle} と同じく地面に平行に寝かせる。ただしあちらと違い、これは<em>真横から見られる</em>。
 * 衝撃波は足元にあるので見下ろす物だが、キノコ雲は200ブロック上空にあり、同じ高度を飛んでいるパイロットは
 * 真横から見る。完全に寝かせた円盤はその角度で線になって消える。
 *
 * <p>よって、視線が層の面に近づいたぶんだけカメラへ倒す。真上・真下から見ている間は完全に水平のまま——
 * そこでは倒す必要が無く、倒せば円盤が円盤に見えなくなる。横から見ている間だけ {@link #MOST_TILT} まで起き
 * 上がるので、線に潰れることは無い。実際の雲も、見る角度によって厚みの見え方が変わる物なので、これは嘘を
 * ついているわけではない。
 */
public class CloudLayerParticle extends WeaponParticle {
    /**
     * テクスチャの円が枠の何割を占めているか。
     *
     * <p>絵の縁まで雲が届いていると、隣り合う層の四角い境目が見える。余白を持たせてある分、渡された半径を
     * ここで割り戻して、指定した半径が<em>見える</em>半径になるようにしている。
     */
    private static final float VISIBLE = 0.72F;

    /**
     * 水平からどこまで起き上がってよいか（ラジアン）。
     *
     * <p>90度まで許すとただのビルボードで、どの角度から見ても正面を向く球になる——層が層に見えなくなる。
     * 55度なら、真横から見ても十分な面積が残りつつ、まだ「寝ている円盤を斜めから見ている」に読める。
     */
    private static final float MOST_TILT = 0.96F;

    /** 現れ切るまでの、寿命に対する割合。大きい板が突然現れると、雲ではなく板に見える。 */
    private static final float FADES_IN = 0.06F;

    /** 漂って流れていく雲。通常のキノコ雲と、核の凝結の棚。どちらも光らない。 */
    public static final Look DRIFT = new Look(220, 140, 1.55F, 0.96F, -0.006F, 0.58F, NEVER_GLOWS, 0.0F);
    /**
     * 核の柱と傘。生まれた時点で内側が灼熱していて、十数秒かけてただの煙になる。
     *
     * <p>{@link #DRIFT} より遥かに長生きするのは、核の演出が柱の立ち上がりだけで10秒近くかかるからだ。
     * 短い寿命では、傘が完成する頃に柱の根元が消えていて雲が地面と繋がらない。
     */
    public static final Look BURNING =
            new Look(400, 220, 1.60F, 0.97F, -0.004F, 0.62F, Effects.FURNACE, 0.45F);

    private final Look look;
    /**
     * 寝かせ方。粒ごとに1つ持つ。
     *
     * <p>{@code FacingCameraMode} はカメラしか受け取らないので、層自身がどこにいるかは呼ばれた側からは
     * 分からない。粒に紐付いた実体にして、自分の座標を閉じ込めている。
     */
    private final SingleQuadParticle.FacingCameraMode lying =
            (quaternion, camera, partialTick) -> this.lie(quaternion, camera);

    private CloudLayerParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOption options, SpriteSet sprites, Look look) {
        super(level, x, y, z, options);
        this.look = look;
        this.lifetime = look.life() + this.random.nextInt(Math.max(look.lifeJitter(), 1));
        this.quadSize = options.scale() / VISIBLE;
        this.friction = look.friction();
        this.gravity = look.gravity();
        // 雲は物質ではない。地形で跳ね返る筋合いが無い。
        this.hasPhysics = false;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        // 自分の面の中で回す。円なので向きは見えないが、同じ絵が並ぶのは見える。
        this.roll = this.random.nextFloat() * Mth.TWO_PI;
        this.oRoll = this.roll;
        this.glowsWhileHot(look.hot(), look.cools());
        this.setSprite(sprites.get(this.random));
    }

    @Override
    public void tick() {
        super.tick();

        float lived = this.lived(0.0F);

        // 現れるときは急がず、消えるときは最後まで粘る。層が一斉に薄くなると雲全体が点滅して見える。
        this.alpha = this.look.opacity()
                * Math.min(lived / FADES_IN, 1.0F)
                * (1.0F - lived * lived * lived);
        this.coolTowards(lived);
    }

    @Override
    public float getQuadSize(float partialTick) {
        return this.quadSize * (1.0F + (this.look.growth() - 1.0F) * this.lived(partialTick));
    }

    @Override
    protected int getLightColor(float partialTick) {
        return this.litWhileHot(super.getLightColor(partialTick), this.lived(partialTick));
    }

    @Override
    public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
        return this.lying;
    }

    /**
     * この層の向き。基本は水平で、真横から見られている間だけカメラへ起き上がる。
     *
     * <p>クアッドは XY 平面で組まれるので、X 軸周りに90度回すと XZ 平面へ寝る。そこから戻す量が起き上がり分だ。
     * 先に Y 軸で回してあるのは、起き上がる向きをカメラの方角へ合わせるため——どちらへ倒すかを決めるのが
     * この回転で、円なので回転自体は見た目に出ない。
     */
    private void lie(Quaternionf quaternion, Camera camera) {
        double dx = camera.getPosition().x - this.x;
        double dy = camera.getPosition().y - this.y;
        double dz = camera.getPosition().z - this.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        // 層の面から測ったカメラの高さの角。真上なら90度、真横なら0度。
        float elevation = (float) Math.atan2(Math.abs(dy), flat);
        float tilt = Math.min(Mth.HALF_PI - elevation, MOST_TILT);

        quaternion.rotationY((float) Math.atan2(dx, dz)).rotateX(-Mth.HALF_PI + tilt);
    }

    /** 寿命のどこまで来たか。0〜1。 */
    private float lived(float partialTick) {
        return Mth.clamp(((float) this.age + partialTick) / (float) this.lifetime, 0.0F, 1.0F);
    }

    /**
     * @param life 最低限もつtick数
     * @param lifeJitter さらに何tickもちうるか。層が一斉に消えないための値
     * @param growth 終端で初期の何倍に広がるか
     * @param friction 毎tick残る速度の割合
     * @param gravity 落ちる速さ。昇る雲では負値
     * @param opacity 最も濃いときの不透明度。層は何枚も重なるので、1枚あたりは控えめにしてある——
     *                濃い板を重ねると雲ではなく塗り潰しになる
     * @param hot 生まれた時点の色。{@link WeaponParticle#NEVER_GLOWS} なら光らない
     * @param cools 本来の色へ移りきる、寿命に対する割合
     */
    public record Look(int life, int lifeJitter, float growth, float friction, float gravity,
            float opacity, int hot, float cools) {
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites, Look look) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new CloudLayerParticle(level, x, y, z, xd, yd, zd, options, sprites, look);
    }
}
