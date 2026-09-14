package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import com.ashvehicles.entity.AircraftEntity;

/**
 * パイロットに掛かる荷重。機体ではなく<em>中の人間</em>の側の限界。
 *
 * <p>荷重そのものは前からある。{@code AircraftEntity.checkStructuralLoad} が設計耐Gを超えた機体を歪ませ、
 * 翼端は {@code VORTEX_LOAD} を超えると蒸気を曳く。つまり値は既に毎tick出ている——読んでいなかったのは
 * 座っている人間だけだ。
 *
 * <p><b>これが「引く」を技能にする。</b>今のところ操縦桿は引けば引くほど良い。旋回は荷重を代償に半径を
 * 買う取引なのに、代償を払うのが機体だけなら、パイロットにとっては上限が「壊れる手前」しか無い。視界が
 * 狭まり始めれば、旋回はもう一つの資源——自分自身——を消費する物になる。旋回に入る前に緩める、上りで
 * 稼いで下りで引く、そういう判断が生まれる場所はここしかない。
 *
 * <h2>数値</h2>
 *
 * <p>耐えられる荷重は耐Gスーツ込みで見ている。{@link #TOLERANCE}——10G——までは何時間でも平気、そこから
 * 上は超えた分に比例して溜まっていき、満ちれば暗転。12G の引きっぱなしでおよそ2.5秒、15G なら1秒強。
 * 抜けるのは緩めてから3秒ほどで、そちらは変えていない。
 *
 * <p><b>10G は機体の耐Gより高い。</b>この艦隊で最も丈夫な F-15 と F-16 で 9G なので、ここへ届くのは
 * 機体を壊しながら引いている間だけになる。詳しくは {@link #TOLERANCE}。
 *
 * <p>負のGは別勘定にする。人間は下向きの荷重に遥かに弱く、限界は数字が小さいだけでなく症状も違う——
 * 血が頭へ抜けるので視界は暗くなるのではなく赤くなる。だから溜める袋も色も分けてある。
 *
 * <p><b>全部このクライアントの中だけで完結する。</b>荷重は操縦しているクライアントが自分の空力から
 * 算出しており（{@code AircraftEntity.getLoadFactor}）、暗転は絵とその人の操縦桿にしか影響しない。
 * サーバーへ送る物も、他人に見える物も無い。撃たれ方も飛び方も変わらないので、これは公平さの問題を
 * 持ち込まずに深さだけを足す種類の変更になる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class PilotLoad implements LayeredDraw.Layer {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "pilot_load");

    /**
     * これ以下の荷重なら何時間でも平気。ここを超えて初めて溜まり始める。
     *
     * <p>10（2026-09-06、5.5 から）。<b>この数字は機体の耐Gより高い。</b>艦隊の最高は F-15 と F-16 の
     * 9G で、{@code checkStructuralLoad} はそこを超えた分だけ機体を痛めつける。つまりパイロットの視界が
     * 曇るのは、機体を壊しながら引いている間だけになる——耐Gを超えて引き続けられる者にしか起きない症状
     * であり、通常の空戦機動では一度も現れない。それが意図であるなら正しい値だ。
     * 「引く」ことに人間側の代償を持たせ直したいなら、7〜8 あたりが機体の限界の手前に入る。
     */
    private static final float TOLERANCE = 10.0F;

    /**
     * これより下（負側）へ振ると赤い方が溜まり始める。人間は下向きの荷重に遥かに弱い。
     *
     * <p><b>これまで一度も成立していなかった。</b>{@code AircraftEntity.getLoadFactor} が絶対値を
     * 返していたので、押し込んでも読みは正のままで、赤化の条件に届く値が存在しなかった。2026-09-06 に
     * 荷重へ符号を戻して初めて生きた条件になっている。
     *
     * <p>−3（−1.5 から）。正側を 10 にしたので、そのままでは負側だけが日常の機動で発火する。実機の
     * 赤化（血が頭へ抜けて視界が赤らむ）の始まりは −2 から −3 あたりで、正側との比も人間の実際の
     * 耐性差に近い。宙返りの頂点を越える程度は 0 から −1 なので、ここへ届くのは意図して押し込んだ時だけだ。
     */
    private static final float NEGATIVE_TOLERANCE = -3.0F;

    /**
     * 超過1Gあたり1tickで溜まる量。
     *
     * <p>{@link #TOLERANCE} を 10 にしたので、超過が出るのは機体の耐Gを越えて引いている間だけになった。
     * その状況は長く続かない——機体が先に壊れる——ので、溜まる速さは上げてある。12G で約 2.5 秒、
     * 15G なら 1 秒強で塞がる。0.0042 のままだと 12G で 20 秒かかり、その前に主翼が外れる。
     */
    private static final float STRAIN_PER_G = 0.020F;

    /**
     * 負側の溜まる速さ。正側より速い——赤化は暗転より早く来る。
     *
     * <p>超過1G あたりで比べて {@link #STRAIN_PER_G} の 1.75 倍。−5G を押し続けて約 3 秒で満ちる。
     * 人間が下向きの荷重で参るのは上向きより早い、という差をここで表している。
     */
    private static final float RED_PER_G = 0.035F;

    /** 緩めてから1tickで抜ける量。約3秒で澄む。 */
    private static final float RELIEF = 0.017F;

    /** 視界が狭まり始める溜まり具合。ここまでは何も起きない。 */
    private static final float ONSET = 0.30F;

    /** 操縦桿が利かなくなり始める溜まり具合。完全暗転の手前で既に手は緩んでいる。 */
    private static final float SLUMP = 0.75F;

    /** 視野が絞られる限界。1で塞がるのではなく、隅に穴が残る——完全な黒は不具合に見える。 */
    private static final float TUNNEL_MOST = 0.86F;

    /** 縁から中心へ向けて描く帯の数。多いほど滑らか。 */
    private static final int BANDS = 14;

    private static final int BLACK = 0x000000;
    private static final int BLOOD = 0x8A0F12;

    /** 正のGで溜まる方。0で澄んだ視界、1で暗転。 */
    private static float strain;
    /** 負のGで溜まる方。0で澄んだ視界、1で赤で塞がる。 */
    private static float red;
    /** 描画が補間に使う前tickの値。tickの中で階段状に暗くならないように。 */
    private static float strainO;
    private static float redO;

    private PilotLoad() {
    }

    /**
     * 操縦桿の利き。1で完全、0で手が離れている。
     *
     * <p>{@link AircraftInputHandler} が舵の入力に掛ける。スロットルには掛けない——意識を失った人間は
     * 引くのをやめるのであって、エンジンを切るのではない。
     */
    public static float grip() {
        if (strain <= SLUMP) {
            return 1.0F;
        }

        return Mth.clamp(1.0F - (strain - SLUMP) / (1.0F - SLUMP), 0.0F, 1.0F);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        strainO = strain;
        redO = red;

        Minecraft minecraft = Minecraft.getInstance();

        // 操縦しているクライアントだけが荷重を持つ。同乗者も見物人も、自分の内耳で感じる物は無い。
        if (minecraft.isPaused() || minecraft.player == null
                || !(minecraft.player.getVehicle() instanceof AircraftEntity aircraft)
                || !aircraft.isControlledByLocalInstance() || aircraft.isWrecked()) {
            relax();

            return;
        }

        float load = aircraft.getLoadFactor(aircraft.getDeltaMovement());

        if (load > TOLERANCE) {
            strain = Math.min(strain + (load - TOLERANCE) * STRAIN_PER_G, 1.0F);
        } else {
            strain = Math.max(strain - RELIEF, 0.0F);
        }

        if (load < NEGATIVE_TOLERANCE) {
            red = Math.min(red + (NEGATIVE_TOLERANCE - load) * RED_PER_G, 1.0F);
        } else {
            red = Math.max(red - RELIEF, 0.0F);
        }
    }

    /** 機体から降りた、あるいは落ちた。抱えていた物は持ち歩かない。 */
    private static void relax() {
        strain = Math.max(strain - RELIEF, 0.0F);
        red = Math.max(red - RELIEF, 0.0F);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 計器の上。視界が狭まるのはパイロットの目に起きることであり、目が塞がれば計器も読めない。
        // 閃光（{@link BlastFlash}）が計器の下にいるのと逆の理由だ。
        event.registerAboveAll(ID, new PilotLoad());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float grey = Mth.lerp(partial, strainO, strain);
        float blood = Mth.lerp(partial, redO, red);

        if (grey > ONSET) {
            tunnel(graphics, (grey - ONSET) / (1.0F - ONSET), BLACK);
        }

        if (blood > ONSET) {
            tunnel(graphics, (blood - ONSET) / (1.0F - ONSET), BLOOD);
        }
    }

    /**
     * 視野を縁から絞る。
     *
     * <p>画面全体を一様に暗くするのではない。荷重で失われるのは<em>周辺</em>視野が先で、中心は最後まで
     * 残る——だから狭まっていく穴として描く。一様な暗転は「目が見えなくなっていく」ではなく「夜になった」
     * に見えるし、計器も同時に読めなくなって何が起きているのか分からなくなる。
     *
     * @param amount 0で何もせず、1で最も絞られた状態
     */
    private static void tunnel(GuiGraphics graphics, float amount, int colour) {
        float shut = Mth.clamp(amount, 0.0F, 1.0F);

        if (shut <= 0.0F) {
            return;
        }

        VertexConsumer buffer = graphics.bufferSource().getBuffer(RenderType.gui());
        PoseStack.Pose pose = graphics.pose().last();
        float wide = graphics.guiWidth();
        float tall = graphics.guiHeight();
        // 一番内側の帯がどこまで来るか。画面の中心までは決して閉じない。
        float reach = shut * TUNNEL_MOST;

        for (int band = 0; band < BANDS; band++) {
            // 外側の帯ほど濃く、内側ほど薄い。境目を作らずに滲ませるため。
            float outer = (float) band / BANDS;
            float inner = (float) (band + 1) / BANDS;
            int outerTint = tint(shut * fade(outer), colour);
            int innerTint = tint(shut * fade(inner), colour);

            if (shut * fade(outer) <= 0.002F) {
                continue;
            }

            float from = outer * reach;
            float to = inner * reach;

            // 上下左右の4本。角が二重に塗られるが、角は最も濃くあるべき場所なので都合がよい。
            //
            // <p>1本ごとに<b>外縁と内縁で違う濃さを渡す</b>。頂点色は面の上で補間されるので、帯の中が
            // 単色ではなく連続した勾配になる——これがグラデーションの滑らかさの全部だ。単色で塗って
            // いた頃は、どれだけ帯を増やしても縞は縞のまま残った。濃さの曲線が2次なので帯そのものは
            // 今も要るが、各帯は曲線を折れ線で近似する区間であって、段差ではなくなる。
            fadeY(buffer, pose, 0.0F, from * tall, wide, to * tall, outerTint, innerTint);
            fadeY(buffer, pose, 0.0F, tall - to * tall, wide, tall - from * tall, innerTint, outerTint);
            fadeX(buffer, pose, from * wide, 0.0F, to * wide, tall, outerTint, innerTint);
            fadeX(buffer, pose, wide - to * wide, 0.0F, wide - from * wide, tall, innerTint, outerTint);
        }
    }

    /**
     * 縁からの距離に対する濃さ。0（縁）で1、1（内端）で0。
     *
     * <p>2次にしてあるのは、線形だと内端の手前まで一様に濃く見えて「窓枠」になるからだ。周辺視野の
     * 失われ方は縁で急に、中心へ向かって緩やかになる。
     */
    private static float fade(float depth) {
        float left = 1.0F - Mth.clamp(depth, 0.0F, 1.0F);

        return left * left;
    }

    /** 縦に濃さが変わる帯。{@code y0} 側と {@code y1} 側で色を分ける。 */
    private static void fadeY(VertexConsumer buffer, PoseStack.Pose pose,
            float x0, float y0, float x1, float y1, int atY0, int atY1) {
        buffer.addVertex(pose, x0, y0, 0.0F).setColor(atY0);
        buffer.addVertex(pose, x0, y1, 0.0F).setColor(atY1);
        buffer.addVertex(pose, x1, y1, 0.0F).setColor(atY1);
        buffer.addVertex(pose, x1, y0, 0.0F).setColor(atY0);
    }

    /** 横に濃さが変わる帯。{@code x0} 側と {@code x1} 側で色を分ける。 */
    private static void fadeX(VertexConsumer buffer, PoseStack.Pose pose,
            float x0, float y0, float x1, float y1, int atX0, int atX1) {
        buffer.addVertex(pose, x0, y0, 0.0F).setColor(atX0);
        buffer.addVertex(pose, x0, y1, 0.0F).setColor(atX0);
        buffer.addVertex(pose, x1, y1, 0.0F).setColor(atX1);
        buffer.addVertex(pose, x1, y0, 0.0F).setColor(atX1);
    }

    private static int tint(float alpha, int colour) {
        return (Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24) | colour;
    }
}
