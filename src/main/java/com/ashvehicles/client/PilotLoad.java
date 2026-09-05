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
 * 座っている人間だけだ。機体は9Gで壊れ始めるが、その前に意識を失うのは操縦している方である。
 *
 * <p><b>これが「引く」を技能にする。</b>今のところ操縦桿は引けば引くほど良い。旋回は荷重を代償に半径を
 * 買う取引なのに、代償を払うのが機体だけなら、パイロットにとっては上限が「壊れる手前」しか無い。視界が
 * 狭まり始めれば、旋回はもう一つの資源——自分自身——を消費する物になる。旋回に入る前に緩める、上りで
 * 稼いで下りで引く、そういう判断が生まれる場所はここしかない。
 *
 * <h2>数値</h2>
 *
 * <p>耐えられる荷重は耐Gスーツ込みで見ている。{@link #TOLERANCE} までは何時間でも平気、そこから上は
 * 超えた分に比例して溜まっていき、満ちれば暗転。9Gの引きっぱなしでおよそ4秒、6.5Gなら20秒以上かかる。
 * 抜けるのも同じくらいの速さで、緩めてから数秒で視界が戻る。
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

    /** これ以下の荷重なら何時間でも平気。耐Gスーツを着た人間の持続耐性。 */
    private static final float TOLERANCE = 5.5F;

    /** これより下（負側）へ振ると赤い方が溜まり始める。人間は下向きの荷重に遥かに弱い。 */
    private static final float NEGATIVE_TOLERANCE = -1.5F;

    /** 超過1Gあたり1tickで溜まる量。9Gの引きっぱなしで約4秒、6.5Gなら20秒以上。 */
    private static final float STRAIN_PER_G = 0.0042F;

    /** 負側の溜まる速さ。正側より速い——赤化は暗転より早く来る。 */
    private static final float RED_PER_G = 0.010F;

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
            float alpha = shut * (1.0F - outer) * (1.0F - outer);
            int tint = tint(alpha, colour);

            if (alpha <= 0.002F) {
                continue;
            }

            float from = outer * reach;
            float to = inner * reach;

            // 上下左右の4本。角が二重に塗られるが、角は最も濃くあるべき場所なので都合がよい。
            quad(buffer, pose, tint, 0.0F, from * tall, wide, to * tall);
            quad(buffer, pose, tint, 0.0F, tall - to * tall, wide, tall - from * tall);
            quad(buffer, pose, tint, from * wide, 0.0F, to * wide, tall);
            quad(buffer, pose, tint, wide - to * wide, 0.0F, wide - from * wide, tall);
        }
    }

    private static void quad(VertexConsumer buffer, PoseStack.Pose pose, int tint,
            float x0, float y0, float x1, float y1) {
        buffer.addVertex(pose, x0, y0, 0.0F).setColor(tint);
        buffer.addVertex(pose, x0, y1, 0.0F).setColor(tint);
        buffer.addVertex(pose, x1, y1, 0.0F).setColor(tint);
        buffer.addVertex(pose, x1, y0, 0.0F).setColor(tint);
    }

    private static int tint(float alpha, int colour) {
        return (Mth.clamp((int) (alpha * 255.0F), 0, 255) << 24) | colour;
    }
}
