package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.particle.Effects;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 起爆の閃光。爆発が起きた方向から差してくる光。
 *
 * <p>三つのうち一番早く着く物だ。光は待たない——{@link com.ashvehicles.client.sound.BlastSounds} の轟音も
 * {@link BlastShake} の揺れも音速で這ってくるが、閃光はパケットが着いたその瞬間に出る。300m 先の爆弾なら、
 * 光ってから1秒近く経って音と揺れが来る。三つを別々に着かせること自体が距離の表現になっている。
 *
 * <p><b>画面を白く塗らない。</b>全面を覆う白は「近い」以外の何も語らず、しかも一番語るべき瞬間に画面を消す。
 * 代わりに爆心が画面上で落ちる位置に光の玉を置く。だからこれは方向を持つ——左で光れば左が明るくなり、視界の
 * 外で光れば画面の端から差し込む。パイロットが「どこで」を知る手段が一つ増えるということだ。
 *
 * <p>そして<b>背後の爆発では何も描かない</b>。光は曲がらないからだ。振り返っていない爆発は、遅れて来る轟音と
 * 揺れで知ることになる。
 *
 * <p>強さは規模と距離、そして<em>その場の明るさ</em>で決まる。同じ爆発でも真昼の閃光はほとんど見えず、夜なら
 * 風景ごと浮かび上がる。光の量が変わるからではなく、目が見ているのが常に周囲との差だからだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class BlastFlash implements LayeredDraw.Layer {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "blast_flash");

    /** 光っている tick 数。火球が白熱している間だけで、それより長いと爆発ではなく照明弾になる。 */
    private static final int LIFE = 7;
    /** 消え方の指数。1より大きいので、点いた直後が最も明るく、あとは急に落ちる。 */
    private static final float FADES = 1.6F;

    /**
     * 核の閃光が光っている tick 数と、そのうち最大の明るさを保つ割合。
     *
     * <p>3.5秒。通常の10倍で、しかも最初の0.8秒は落ちない。核の閃光が核の閃光である理由の半分はこの長さだ
     * ——爆発の閃光は「一瞬光った」だが、これは「光り続けている」であり、見ている側が「まだ光っている」と
     * 気付く時間がある。
     */
    private static final int NUCLEAR_LIFE = 70;
    private static final float NUCLEAR_HOLDS = 0.22F;

    /**
     * 白熱が引いた後、世界がオレンジがかっている tick 数。
     *
     * <p>閃光の続きではなく、別の現象だ。閃光は火球そのものを見ている明るさだが、こちらは<b>火球に照らされた
     * 風景</b>——数百m上空で灼熱している雲が、地面も空も自分の色で染めている状態である。だから閃光の20倍近く
     * 長く、そして<b>背後の爆発でも出る</b>。火球そのものは振り返らなければ見えないが、それに照らされた地面は
     * どちらを向いていても目の前にある。
     *
     * <p>16秒。雲の内側が冷えて煙になるまでの時間とだいたい揃えてある
     * （{@link com.ashvehicles.client.particle.SmokeParticle} の {@code NUCLEAR}）ので、空の色が戻る頃に
     * 雲も光らなくなっている。
     */
    private static final int NUCLEAR_GLOW = 320;
    /** その色と、いちばん濃いときの不透明度。 */
    private static final int EMBER_SKY = 0xFF7A2E;
    private static final float GLOW_ALPHA = 0.30F;
    /** 引き方の指数。1に近いのでほぼ直線——じわじわ戻ってほしいので、急に落とさない。 */
    private static final float GLOW_FADES = 1.15F;

    /**
     * 閃光の色。弾頭の色ではない。
     *
     * <p>兵装ファイルの色は曳光と火球の色で、そちらは弾ごとに違ってよい。だが白熱した空気は何が燃えていても
     * 同じ色をしている。ここを弾頭色にすると、緑の曳光を持つ機関砲弾の着弾で画面が緑に光ることになる。
     */
    private static final int GLOW = 0xFFE6C2;

    /**
     * 距離に対する減光の基準（ブロック）。爆発力1あたりと、最小規模の分。この距離で明るさが半分になる。
     *
     * <p>逆二乗ではなく逆<em>一乗</em>で落とす。現実の逆二乗は数十mでほぼゼロに達し、そこから先は「見えるはず
     * の物が見えない」になる——300m 上空から落とした爆弾が光らないのでは、この演出の一番大事な場面が空になる。
     * かといって落ち方を緩めすぎると、30m の至近弾と 200m 先の着弾が同じ明るさになり、閃光が距離を語らなく
     * なる。逆一乗はその間で、近いほど明るく、遠くても消えない。ここに書いているのは光の量ではなく、目が実際に
     * 受け取る印象だ。
     */
    private static final double GLARE = 10.0;
    private static final double GLARE_PER_POWER = 2.3;

    /** 光の玉の半径。爆発力1あたりブロック。火球そのものより大きい。滲みも光のうちなので。 */
    private static final double HALO_PER_POWER = 1.6;
    /** 遠くの爆発でも、画面上でこれだけの大きさは保つ（ピクセル）。点は点として見える必要がある。 */
    private static final float LEAST_HALO = 9.0F;
    /** そして画面の何倍までしか広げない。至近弾でも計器が読めるように。 */
    private static final float MOST_HALO = 1.2F;

    /** 中心の最大不透明度。1ではない。閃光は視界を消す物ではなく、視界に載る物だ。 */
    private static final float MOST_ALPHA = 0.55F;
    /**
     * 核だけは別。
     *
     * <p>この MOD で画面を白く飛ばしてよい唯一の場面だ。他の全てで避けているのは、白飛びが「近い」以外の
     * 何も語らないからだが、核の至近距離ではそれが語るべき全てになる——何が起きたかを見る手段は残っていない、
     * というのが正確な描写である。
     */
    private static final float NUCLEAR_ALPHA = 0.92F;
    private static final float NUCLEAR_HALO = 3.0F;

    /** 真昼に失われる強さの割合。明るい所ほど閃光は目立たない。 */
    private static final float DAYLIGHT_FADE = 0.65F;
    /** 核はそれをほとんど受けない。太陽より明るい物に、昼か夜かは関係が無い。 */
    private static final float NUCLEAR_DAYLIGHT_FADE = 0.20F;

    /** 光の玉を作る扇形の数と、中心から外へ向かう帯の数。 */
    private static final int SEGMENTS = 24;
    private static final int BANDS = 5;
    /** 中心から縁への不透明度の落ち方。大きいほど芯が締まって縁が広く薄くなる。 */
    private static final float HALO_FALLOFF = 2.4F;

    /** 同時に抱える閃光の上限。クラスター弾の1発ごとに扇形を120枚描く必要は無い。 */
    private static final int MOST_AT_ONCE = 12;

    private static final List<Flash> LIVE = new ArrayList<>();

    /** 爆発が起きた。光は遅れないので、パケットが着いた時点がそのまま見えた時点。 */
    public static void seen(Vec3 at, float power) {
        // 満杯なら、いちばん終わりに近い物を落として席を空ける。新しい方を捨てると、斉射の最後の1発——
        // つまり一番近い1発かもしれないもの——が光らないことになる。核は16秒抱えるので、ここは実際に埋まる。
        if (LIVE.size() >= MOST_AT_ONCE) {
            LIVE.remove(mostSpent());
        }

        LIVE.add(new Flash(at, power));
    }

    /** 抱えているうち、寿命をいちばん使い切っている物の位置。 */
    private static int mostSpent() {
        int found = 0;
        float spent = -1.0F;

        for (int i = 0; i < LIVE.size(); i++) {
            Flash flash = LIVE.get(i);
            float used = (float) flash.age / flash.life;

            if (used > spent) {
                spent = used;
                found = i;
            }
        }

        return found;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (LIVE.isEmpty()) {
            return;
        }

        // ワールドを離れたら消す。前のワールドの閃光が次のワールドで光るのは妙な話だ。
        if (Minecraft.getInstance().level == null) {
            LIVE.clear();

            return;
        }

        Iterator<Flash> each = LIVE.iterator();

        while (each.hasNext()) {
            Flash flash = each.next();

            if (++flash.age >= flash.life) {
                each.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 全ての計器の下。閃光は世界の上に載る物であって、パイロットが読む数字を覆う物ではない。
        event.registerBelowAll(ID, new BlastFlash());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        if (LIVE.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            return;
        }

        Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
        float focal = AircraftHud.focalLength(minecraft, graphics);
        // その場の明るさは全員に共通だが、それをどれだけ受けるかは閃光ごとに違う。読むのは一度だけ。
        int light = minecraft.level.getMaxLocalRawBrightness(BlockPos.containing(eye));
        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        // 余韻は重ねない。2発分の空が2倍オレンジになる道理は無いし、そのまま足すと数発で画面が塗り潰される。
        // 見えるべきなのは、今いちばん強く風景を照らしている1発だ。
        wash(graphics, strongestGlow(eye, light, partialTick));

        for (Flash flash : LIVE) {
            draw(graphics, minecraft, flash, eye, focal, light, partialTick, centreX, centreY);
        }

        // 計器類はこの後に自分の分を描く。溜めたまま渡すと、こちらの扇形がそちらの描画順に紛れ込む。
        graphics.flush();
    }

    private static void draw(GuiGraphics graphics, Minecraft minecraft, Flash flash, Vec3 eye, float focal,
            int light, float partialTick, int centreX, int centreY) {
        Vec3 line = flash.at.subtract(eye);
        double away = line.length();

        if (away < 1.0E-4) {
            return;
        }

        int[] on = AircraftHud.project(minecraft, line.scale(1.0 / away), focal, centreX, centreY);

        // 背後。光は曲がらないので、ここには何も無い。
        if (on == null) {
            return;
        }

        float lived = Mth.clamp((flash.age + partialTick) / flash.burns, 0.0F, 1.0F);
        double glare = GLARE + flash.power * GLARE_PER_POWER;
        // 距離での減光。glare の距離でちょうど半分になり、その先も緩やかに残り続ける。
        float near = (float) (glare / (glare + away));
        float ambient = 1.0F - light / 15.0F
                * (flash.nuclear ? NUCLEAR_DAYLIGHT_FADE : DAYLIGHT_FADE);
        float alpha = Math.min(flash.power / Effects.BIGGEST, 1.0F) * near * ambient
                * (flash.nuclear ? NUCLEAR_ALPHA : MOST_ALPHA) * fading(flash, lived);

        if (alpha <= 0.0F) {
            return;
        }

        float widest = graphics.guiHeight() * (flash.nuclear ? NUCLEAR_HALO : MOST_HALO);
        float radius = Math.min(Math.max((float) (HALO_PER_POWER * flash.power / away * focal), LEAST_HALO),
                widest);

        // 画面から完全に外れた玉は描かない。中心が画面外でも縁が入っていれば描く——視界の端から差し込む光は
        // 「そちらで何かが起きた」を伝える一番大きな手掛かりなので、そこを切り落とさない。
        if (on[0] + radius < 0.0F || on[0] - radius > graphics.guiWidth()
                || on[1] + radius < 0.0F || on[1] - radius > graphics.guiHeight()) {
            return;
        }

        halo(graphics, on[0], on[1], radius, alpha);
    }

    /**
     * 今いちばん強く風景を照らしている核の、その強さ。無ければ0。
     *
     * <p>白熱が引いた後、世界はしばらくオレンジがかっている。閃光の続きではなく別の現象で、描いているのは
     * 光源そのものではなく<em>照らされた風景</em>だ。風景はどちらを向いていても目の前にあるので、これは位置を
     * 持たず、背後の爆発でも出る。
     */
    private static float strongestGlow(Vec3 eye, int light, float partialTick) {
        float ambient = 1.0F - light / 15.0F * NUCLEAR_DAYLIGHT_FADE;
        float strongest = 0.0F;

        for (Flash flash : LIVE) {
            if (!flash.nuclear) {
                continue;
            }

            float lived = Mth.clamp((flash.age + partialTick) / flash.life, 0.0F, 1.0F);
            double glare = GLARE + flash.power * GLARE_PER_POWER;
            float near = (float) (glare / (glare + flash.at.distanceTo(eye)));

            strongest = Math.max(strongest,
                    GLOW_ALPHA * near * ambient * (float) Math.pow(1.0F - lived, GLOW_FADES));
        }

        return strongest;
    }

    /** 画面全体にかける一様な色。 */
    private static void wash(GuiGraphics graphics, float alpha) {
        if (alpha <= 0.0F) {
            return;
        }

        int tint = tint(alpha, EMBER_SKY);
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        PoseStack.Pose pose = graphics.pose().last();
        float wide = graphics.guiWidth();
        float tall = graphics.guiHeight();

        buffer.addVertex(pose, 0.0F, 0.0F, 0.0F).setColor(tint);
        buffer.addVertex(pose, 0.0F, tall, 0.0F).setColor(tint);
        buffer.addVertex(pose, wide, tall, 0.0F).setColor(tint);
        buffer.addVertex(pose, wide, 0.0F, 0.0F).setColor(tint);
    }

    /**
     * 光の玉ひとつ。中心から縁へ向かって薄くなる同心の帯で作る。
     *
     * <p>三角扇ではなく四角形なのは {@code RenderType.gui()} が四角形しか受け付けないからで、内側の半径が
     * 0 の帯は自然に扇形になる。
     */
    private static void halo(GuiGraphics graphics, int x, int y, float radius, float alpha) {
        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        PoseStack.Pose pose = graphics.pose().last();

        for (int band = 0; band < BANDS; band++) {
            float inner = radius * band / BANDS;
            float outer = radius * (band + 1) / BANDS;
            int from = tint(alpha * falloff((float) band / BANDS));
            int to = tint(alpha * falloff((float) (band + 1) / BANDS));

            for (int step = 0; step < SEGMENTS; step++) {
                double a = step * Mth.TWO_PI / SEGMENTS;
                double b = (step + 1) * Mth.TWO_PI / SEGMENTS;
                float cosA = (float) Math.cos(a);
                float sinA = (float) Math.sin(a);
                float cosB = (float) Math.cos(b);
                float sinB = (float) Math.sin(b);

                buffer.addVertex(pose, x + cosA * inner, y + sinA * inner, 0.0F).setColor(from);
                buffer.addVertex(pose, x + cosB * inner, y + sinB * inner, 0.0F).setColor(from);
                buffer.addVertex(pose, x + cosB * outer, y + sinB * outer, 0.0F).setColor(to);
                buffer.addVertex(pose, x + cosA * outer, y + sinA * outer, 0.0F).setColor(to);
            }
        }
    }

    /**
     * 寿命のどこまで来たかに対する明るさ。
     *
     * <p>核だけ、最初のうち落ちない。爆発の閃光は点いた瞬間が最も明るくそこから落ちる一方だが、核の火球は
     * 数秒かけて膨らみながら光り続けるので、頭に平らな部分がある。この平らさが「長く光っている」を作る。
     */
    private static float fading(Flash flash, float lived) {
        if (!flash.nuclear) {
            return (float) Math.pow(1.0F - lived, FADES);
        }

        if (lived <= NUCLEAR_HOLDS) {
            return 1.0F;
        }

        return (float) Math.pow(1.0F - (lived - NUCLEAR_HOLDS) / (1.0F - NUCLEAR_HOLDS), FADES);
    }

    /** 中心からの距離（0〜1）に対する明るさ。 */
    private static float falloff(float out) {
        return (float) Math.pow(1.0F - out, HALO_FALLOFF);
    }

    private static int tint(float alpha) {
        return tint(alpha, GLOW);
    }

    private static int tint(float alpha, int colour) {
        return (Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24) | colour;
    }

    /** 進行中の閃光1件。 */
    private static final class Flash {
        private final Vec3 at;
        private final float power;
        /** 核かどうか。明るさ・長さ・広さ・昼夜の効きの4つがこれで変わる。 */
        private final boolean nuclear;
        /** 抱えておく tick 数。核はオレンジの余韻が消えるまでで、白熱の側はその中の最初の数十tick。 */
        private final int life;
        /** 白熱している tick 数。 */
        private final int burns;
        private int age;

        private Flash(Vec3 at, float power) {
            this.at = at;
            this.power = power;
            this.nuclear = power >= Effects.NUCLEAR;
            this.burns = this.nuclear ? NUCLEAR_LIFE : LIFE;
            this.life = this.nuclear ? NUCLEAR_GLOW : LIFE;
        }
    }
}
