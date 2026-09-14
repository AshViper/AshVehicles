package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleEntity.Crank;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 砲の脇に立っている者のための計器。乗員の計器（{@link GroundVehicleHud}）と同じ物を、乗っていない者へ。
 *
 * <p><b>外から操作する砲は、何も語らない。</b> 乗り込めば残弾も装填の進みも砲の向きも計器が出すが、脇に
 * 立っている者にはその全部が見えない。だから乗員が読む物のうち、車外に立つ1人に意味のある物だけをここへ
 * 出す——弾がどこへ落ちるか、そこまで何ブロックあるか、薬室に何が入っているか、砲が今どこを向いているか。
 *
 * <p><b>2つのハンドルはワールド上に描く。</b> 掴めるのは十字線に近い方（{@code GunCrewHandler.handle}）
 * なので、どちらが近いのかは画面に出ていなければ当てずっぽうになる。明るい輪が今掴める方で、暗い輪が
 * もう一方。輪は砲塔・砲と一緒に運ばれるので、砲を回せば輪も回る。
 *
 * <p><b>方位と仰角と射距離が主計器になる。</b> ハンドルで据える砲では、砲手が見ているのは目標ではなく手元だ。
 * 着弾点の印は砲身の先にあるので、ハンドルを見ている間は画面の外にある——牽引砲を数字で据えるのが本来で、
 * その数字はこの計器の側にある。
 *
 * <p><b>方位と仰角は、弾が何ブロック先に落ちるかを言わない。</b> 戦車なら要らない区別だ——狙う物が見えていて、
 * 弾はほぼ真っ直ぐそこへ行く。榴弾砲は違う。155mm は仰角 15 度で 724 m、45 度で 1388 m、70 度で 889 m へ落ちる。
 * <b>同じ距離へ届く据えが 2 つあり、どちらも仰角の数字からは読めない。</b> だから射距離は推し量る物ではなく
 * 読む物でなければならない。{@link GunReach} 参照。
 *
 * <p>操作の3行を常に出しておくのは、この砲に他の説明が存在しないため。回している方向だけ明るくするので、
 * その3行は説明であると同時に「今どちらへ回しているか」の表示でもある。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GunCrewHud implements LayeredDraw.Layer {
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "gun_crew_hud");

    /** ワールド上に置くハンドルの輪。掴める方が大きい。 */
    private static final int HANDLE_RING = 6;
    private static final int HANDLE_RING_IDLE = 4;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, ID, new GunCrewHud());
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        GroundVehicleEntity gun = GunCrewHandler.manned();

        if (minecraft.player == null || minecraft.options.hideGui || gun == null || gun.isRemoved()) {
            return;
        }

        float partialTick = delta.getGameTimeDeltaPartialTick(false);
        int centreX = graphics.guiWidth() / 2;
        int centreY = graphics.guiHeight() / 2;

        // 着弾点。乗員が覗いている物と同一で、砲が同じなら同じ場所に落ちる。山なりに撃つ砲では、そこは
        // 照準器ではなく GunReach が出す点だ——分岐は drawGunMark の中にある。
        GroundVehicleHud.drawGunMark(graphics, minecraft, gun, partialTick, centreX, centreY);
        drawHandles(graphics, minecraft, gun, partialTick, centreX, centreY);

        HudScale.push(graphics);
        panel(gun, partialTick).bottomRight(graphics, minecraft.font, HudScale.width(graphics) - 8,
                HudScale.height(graphics) - 8);
        HudScale.pop(graphics);
    }

    /**
     * 2つのハンドルを、実際にある場所へ。
     *
     * <p>掴めるのはどちらかであって、選ぶ操作は無い——十字線に近い方が掴まれる。だから輪の役割は「そこに
     * ハンドルがある」ことより「今どちらを掴むか」を言うことにあり、明暗はそのために付いている。
     */
    private static void drawHandles(GuiGraphics graphics, Minecraft minecraft, GroundVehicleEntity gun,
            float partialTick, int centreX, int centreY) {
        float focal = AircraftHud.focalLength(minecraft, graphics);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Crank chosen = GunCrewHandler.handle();

        for (Crank which : new Crank[] {Crank.TRAVERSE, Crank.ELEVATE}) {
            Vec3 at = gun.getHandle(which, partialTick);
            int[] mark = AircraftHud.project(minecraft, at.subtract(camera).normalize(), focal,
                    centreX, centreY);

            if (mark == null) {
                continue;
            }

            boolean picked = which == chosen;
            int colour = picked ? AircraftHud.GREEN : AircraftHud.DIM;
            String label = which == Crank.TRAVERSE ? "TRV" : "ELV";

            AircraftHud.circle(graphics, mark[0], mark[1], picked ? HANDLE_RING : HANDLE_RING_IDLE,
                    colour);

            // 掴んでいる間だけ中心を埋める。「指している」と「回している」は別のことで、押した瞬間に
            // 変わったのが分かる必要がある。
            if (picked && GunCrewHandler.isCranking()) {
                graphics.fill(mark[0] - 1, mark[1] - 1, mark[0] + 1, mark[1] + 1, colour);
            }

            graphics.drawString(minecraft.font, label,
                    mark[0] - minecraft.font.width(label) / 2, mark[1] + HANDLE_RING + 3, colour, true);
        }
    }

    /** 砲の名前、薬室、向き、そして今掴んでいるハンドル。 */
    private static HudPanel panel(GroundVehicleEntity gun, float partialTick) {
        HudPanel panel = new HudPanel();

        panel.title(gun.getType().getDescription().getString());
        GroundVehicleHud.gun(panel, gun);
        panel.divider();
        bearing(panel, gun, partialTick);
        GroundVehicleHud.reach(panel, gun);
        panel.divider();
        controls(panel);

        return panel;
    }

    /**
     * 砲身が今どこを向いているか。方位は世界基準、仰角は車体基準。
     *
     * <p>ハンドルで据える砲ではこれが照準器そのものだ。手元を見ている間、砲がどこを向いたかを言う物は
     * 他に無い。2つが別々の数字なのは、2つが別々の操作だからでもある。
     */
    private static void bearing(HudPanel panel, GroundVehicleEntity gun, float partialTick) {
        // 車体の向きに砲塔の旋回を足した物が砲身の方位。+180 は羅針盤の向きで、乗員の計器と同じ
        // ——Minecraft のヨーは南が 0 だ。
        int compass = Math.floorMod(Math.round(Attitude.heading(gun.getAttitude(partialTick))
                + gun.getTurretYaw(partialTick)) + 180, 360);
        int elevation = Math.round(gun.getGunPitch(partialTick));

        panel.pair("BRG", AircraftHud.DIM, compass + "  " + AircraftHud.cardinal(compass),
                AircraftHud.GREEN);
        panel.pair("ELV", AircraftHud.DIM, String.format("%+d", elevation), AircraftHud.GREEN);
    }

    /** 掴んでいるハンドルと、2つのボタンがそれを回す向き。回している方だけ明るい。 */
    private static void controls(HudPanel panel) {
        Crank handle = GunCrewHandler.handle();
        boolean cranking = GunCrewHandler.isCranking();
        int direction = GunCrewHandler.direction();

        if (handle == Crank.NONE) {
            panel.line("AIM AT A HANDWHEEL", AircraftHud.DIM);
            panel.line("LMB  FIRE", AircraftHud.DIM);

            return;
        }

        boolean traverse = handle == Crank.TRAVERSE;

        panel.line(traverse ? "TRAVERSE WHEEL" : "ELEVATION WHEEL",
                cranking ? AircraftHud.GREEN : AircraftHud.DIM);
        panel.line("RMB  " + (traverse ? "RIGHT" : "UP"),
                cranking && direction > 0 ? AircraftHud.GREEN : AircraftHud.DIM);
        panel.line("SHIFT+RMB  " + (traverse ? "LEFT" : "DOWN"),
                cranking && direction < 0 ? AircraftHud.GREEN : AircraftHud.DIM);
        panel.line("LMB  FIRE", AircraftHud.DIM);
    }
}
