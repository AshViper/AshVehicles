package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.weapon.WeaponDefinition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 燃えているモーターが周りを照らす分。
 *
 * <p>Minecraft には動く光源が無い。松明が照らせるのはブロックを置いた時にその周りの明るさを焼き直すからで、
 * 毎tick数十ブロック動く物にそれはできない。そこで<b>照らす対象を絞る</b>。飛んでいるミサイルの周りで実際に
 * 光る価値がある物は2つしかない——<em>自分が今出した煙</em>と、<em>それを見ている者の目に映る風景全体</em>だ。
 * 前者はこちらが描いている粒なので、世界に訊かずここで足せる（{@link #lit}）。後者は画面に薄く乗せる色で、
 * それを実際に描くのは {@link BlastFlash} の1枚（{@link #glow}）。地形のブロック光そのものは最後まで
 * 変わらない。変わるのは、見ている者がそれをどう受け取るかだけである。
 *
 * <p>だから発射の閃光を送るパケットは無いし、どこにも「撃った」という通知が無い。ここが毎tick見ているのは
 * 「今この世界でモーターが燃えているか、それはどこか」だけで、発射の瞬間が一番明るいのは、その瞬間が
 * ミサイルの最も近い瞬間だからにすぎない。ミサイルが上がっていけば風景の色は勝手に引いていく。
 *
 * <p><b>煙を照らすのは燃えているモーター全部</b>だが、<b>画面を染めるのは兵装ファイルが {@code flame.wash}
 * を書いた物だけ</b>だ（{@link com.ashvehicles.weapon.WeaponDefinition.Flame}）。前者は「火があるか」の話で、
 * 後者は「どれだけの規模か」の話——空対空ミサイルのモーターで風景の色が変わったら、それは光ではなく演出に
 * なる。今それを持っているのは弾道弾（{@code grim_2_missile}）だけである。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class MotorLight {
    /** 同時に見る光源の数。斉射でも、風景を照らしているのは結局いちばん近い1本だ。 */
    private static final int MOST_AT_ONCE = 8;

    /** ブロック光の最大値。{@code LightTexture.pack} が受け取る詰める前の値で、240 ではない。 */
    private static final int BRIGHTEST = 15;

    /** 風景に乗る色のいちばん濃いときの不透明度。薄い。ここで描くのは光源ではなく照り返しなので。 */
    private static final float GLOW_ALPHA = 0.18F;
    /**
     * その距離での減光の基準（ブロック）。この距離で半分になる。
     *
     * <p>{@link BlastFlash} の閃光と同じ逆一乗で落とす。理由も同じで、逆二乗は数十メートルでゼロに達し、
     * そこから先は「見えるはずの物が見えない」になる。ただし基準は桁違いに近い——弾頭が一度に出す光と、
     * モーターが十数秒かけて出し続ける光は、明るさとして比べる物ではない。
     */
    private static final double GLARE = 26.0;
    /** 真昼に失われる割合。昼の発射で風景が染まるほど、モーターは明るくない。 */
    private static final float DAYLIGHT_FADE = 0.60F;
    /**
     * 画面の色が毎tickどれだけ目標へ寄るか。
     *
     * <p>これが「じんわり」の全部だ。距離から直に描くと、点火のtickで色が立ち上がり燃焼終了のtickで消える
     * ——どちらも1/20秒で、光が点いたのではなくスイッチが入ったように見える。0.18 は落ち着くまで約0.4秒。
     */
    private static final float SETTLES = 0.18F;

    private static final List<Plume> LIVE = new ArrayList<>();

    /** 画面に乗っている色の濃さ。目標へ寄っていく側と、その1tick前。フレームの間はこの2つを混ぜる。 */
    private static float glow;
    private static float glowWas;

    /**
     * 粒が受け取る明るさに、近くで燃えているモーターの分を足す。
     *
     * <p>足すのはブロック光の成分だけだ。空の光に触らないので、昼の航跡は昼のままで内側だけが明るくなる。
     * そしてブロック光そのものが暖色なので、色を混ぜなくても煙はオレンジに寄る——バニラの光テクスチャが
     * 松明の色をそこに持っているからで、こちらが決めているのは明るさだけである。
     *
     * @param light 世界が答えた明るさ、あるいは世界が無いときの代わり
     */
    public static int lit(int light, double x, double y, double z) {
        if (LIVE.isEmpty()) {
            return light;
        }

        float strongest = 0.0F;

        for (Plume plume : LIVE) {
            strongest = Math.max(strongest, plume.reaches(x, y, z));
        }

        if (strongest <= 0.0F) {
            return light;
        }

        int block = Math.round(Mth.lerp(strongest, LightTexture.block(light), BRIGHTEST));

        return LightTexture.pack(block, LightTexture.sky(light));
    }

    /** 今このフレームで風景に乗っている色の濃さ。{@link BlastFlash} が描く。 */
    public static float glow(float partialTick) {
        return Mth.lerp(partialTick, glowWas, glow);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        LIVE.clear();

        if (minecraft.level == null || minecraft.player == null) {
            glow = 0.0F;
            glowWas = 0.0F;

            return;
        }

        // 光源を探すのに世界へは訊かない。クライアントが既に抱えているエンティティを見るだけで、そこには
        // ロード済みチャンクの外を飛んでいる物も入っている（mixin/EntityTrackingMixin 参照）。
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (LIVE.size() >= MOST_AT_ONCE) {
                break;
            }

            if (entity instanceof VehicleProjectile round) {
                WeaponDefinition.Flame flame = round.plume();

                if (flame != null && flame.glow() > 0.0F) {
                    LIVE.add(new Plume(new Vec3(round.xo, round.yo, round.zo), round.position(),
                            flame.glow(), flame.wash()));
                }
            }
        }

        glowWas = glow;
        glow += (target(minecraft) - glow) * SETTLES;
    }

    /** 今の位置関係から、風景に乗るべき色の濃さ。 */
    private static float target(Minecraft minecraft) {
        if (LIVE.isEmpty()) {
            return 0.0F;
        }

        Vec3 eye = minecraft.player.getEyePosition();
        int light = minecraft.level.getMaxLocalRawBrightness(BlockPos.containing(eye));
        float ambient = 1.0F - light / 15.0F * DAYLIGHT_FADE;
        float strongest = 0.0F;

        for (Plume plume : LIVE) {
            // 画面を染めるのは、そう書かれた物だけ。書いていない炎は煙を照らすところで仕事を終える。
            if (plume.wash() <= 0.0F) {
                continue;
            }

            // 光源の強さは、その炎が照らす半径そのもの。大きなモーターは近くの煙を広く照らし、同じだけ
            // 遠くの風景も染める。
            double away = Math.sqrt(plume.awaySqr(eye.x, eye.y, eye.z));
            double glare = GLARE * plume.reach() / Plume.NOMINAL;

            strongest = Math.max(strongest, (float) (glare / (glare + away) * plume.wash()));
        }

        return GLOW_ALPHA * strongest * ambient;
    }

    /**
     * 燃えている1本。点ではなく線だ。
     *
     * <p>ミサイルは1tickに数十ブロック進むので、その間に照らされたのは「今いる場所の周り」ではなく
     * 「通り過ぎた線の周り」である。点として持つと、高速のミサイルが自分の航跡を1tickおきに飛び飛びで
     * 照らすことになり、光っている煙と光っていない煙が縞になる。
     *
     * @param from この tick の始まりの位置
     * @param to 終わりの位置
     * @param reach 煙を照らす半径（ブロック）
     * @param wash 見ている者の画面をどれだけ染めるか。0なら染めない
     */
    private record Plume(Vec3 from, Vec3 to, float reach, float wash) {
        /**
         * 画面を染める強さを測る基準の半径（ブロック）。
         *
         * <p>これだけの炎が {@code wash: 1} で {@code GLARE} どおりに染める。より大きな炎は同じ
         * {@code wash} でもう少し遠くまで届く——半径は「そのモーターがどれだけの物か」を既に述べている
         * 値なので、そこから引けば規模と到達距離が勝手に揃う。
         */
        private static final double NOMINAL = 26.0;

        /** この点がどれだけ強く照らされているか。0で届いていない、1で炎のただ中。 */
        private float reaches(double x, double y, double z) {
            double awaySqr = this.awaySqr(x, y, z);

            if (awaySqr >= this.reach * (double) this.reach) {
                return 0.0F;
            }

            float near = (float) (1.0 - Math.sqrt(awaySqr) / this.reach);

            // 二乗。縁を柔らかくするためで、そうしないと照らされた煙と照らされていない煙の境目に線が出る。
            return near * near;
        }

        /** この点から、この tick に燃えた線までの距離の二乗。 */
        private double awaySqr(double x, double y, double z) {
            double dx = this.to.x - this.from.x;
            double dy = this.to.y - this.from.y;
            double dz = this.to.z - this.from.z;
            double lengthSqr = dx * dx + dy * dy + dz * dz;
            double along = lengthSqr < 1.0E-8 ? 0.0
                    : Mth.clamp(((x - this.from.x) * dx + (y - this.from.y) * dy + (z - this.from.z) * dz)
                            / lengthSqr, 0.0, 1.0);
            double ox = x - (this.from.x + dx * along);
            double oy = y - (this.from.y + dy * along);
            double oz = z - (this.from.z + dz * along);

            return ox * ox + oy * oy + oz * oz;
        }
    }

    private MotorLight() {
    }
}
