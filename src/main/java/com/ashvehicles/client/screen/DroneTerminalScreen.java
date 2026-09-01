package com.ashvehicles.client.screen;

import java.util.List;
import java.util.Locale;

import com.ashvehicles.client.AircraftHud;
import com.ashvehicles.client.DroneTerminal;
import com.ashvehicles.client.VehicleDismountHandler;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.network.DroneLinkPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 地上管制局。繋げる無人機を並べ、1機選んで接続する。
 *
 * <p><b>一覧はサーバーに訊いていない。</b>手元に既にある——{@link DroneTerminal#reachable} 参照。だから
 * 距離も、方位も、高度も、速度も、燃料も、開いた瞬間から本物であり、毎フレーム更新される。飛んでいる機体の
 * 行を見ていれば、距離の数字がその場で減っていく。
 *
 * <p><b>選べない機体も並べる。</b>他人が繋いでいる機体は、灰色にして理由を書いた上でその場に残す。消して
 * しまえば「一覧に出ない」が「そこに無い」と「今使われている」の両方を意味することになり、操作者はどちらか
 * を知る手段を失う。
 *
 * <p>盤の後ろだけを暗くする理由は {@link LaunchConsoleScreen} と同じで、ゲームも止めない。端末を開いている
 * 間も世界は動いており、開いたまま眺めるのは正当な使い方だ——離陸させた機体が基地の視界から出ていくのを
 * 一覧の距離で見ていられる。
 */
public final class DroneTerminalScreen extends Screen {
    private static final int PANEL_WIDE = 300;
    private static final int MARGIN = 12;
    private static final int LINE = 12;
    /** 1機分の行の高さ。2行の文字と、その上下の余白。 */
    private static final int ROW = 27;
    /** 一度に見せる行数。これを超えるとホイールで送る。 */
    private static final int ROWS_SHOWN = 6;

    private static final int PANEL = 0xD0101412;
    private static final int EDGE = 0xFF3BE86A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFFA0A8A4;
    private static final int LIVE = 0xFF3BE86A;
    private static final int WARN = 0xFFFF5A3B;
    private static final int HOVER = 0x303BE86A;

    private List<AircraftEntity> drones = List.of();
    private int scrolled;

    private DroneTerminalScreen() {
        super(Component.translatable("screen.ashvehicles.drone_terminal.title"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new DroneTerminalScreen());
    }

    /**
     * 開いている間ずっと数え直す。
     *
     * <p>機体は飛んでいるので、一覧も動く。開いた瞬間の写真を見せて、その後も同じ順に並んでいるふりを
     * するより、毎tick数え直す方が安い上に正しい——数えているのは既に手元にあるエンティティだけだ。
     */
    @Override
    public void tick() {
        this.drones = DroneTerminal.reachable();
        this.scrolled = Mth.clamp(this.scrolled, 0, Math.max(0, this.drones.size() - ROWS_SHOWN));
    }

    @Override
    protected void init() {
        this.drones = DroneTerminal.reachable();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int wide = Math.min(PANEL_WIDE, this.width - MARGIN * 2);
        int rows = Math.max(1, Math.min(ROWS_SHOWN, this.drones.size()));
        int tall = MARGIN + LINE + 6 + rows * ROW + 6 + LINE + MARGIN;
        int left = (this.width - wide) / 2;
        int top = (this.height - tall) / 2;

        panel(graphics, left, top, wide, tall);

        graphics.drawCenteredString(this.font, this.title, left + wide / 2, top + MARGIN, TEXT);

        int y = top + MARGIN + LINE + 6;

        if (this.drones.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("screen.ashvehicles.drone_terminal.none"),
                    left + wide / 2, y + ROW / 2 - 4, DIM);
        } else {
            for (int at = 0; at < rows; at++) {
                this.row(graphics, this.drones.get(this.scrolled + at),
                        left + 6, y + at * ROW, wide - 12, mouseX, mouseY);
            }
        }

        String hint = this.drones.size() > ROWS_SHOWN
                ? "screen.ashvehicles.drone_terminal.hint_scroll"
                : "screen.ashvehicles.drone_terminal.hint";

        graphics.drawCenteredString(this.font,
                Component.translatable(hint, VehicleDismountHandler.dismountKeyName()),
                left + wide / 2, top + tall - MARGIN - LINE + 3, DIM);
    }

    /** 1機分。上段が機種と距離、下段が方位・高度・速度・燃料、あるいは繋げない理由。 */
    private void row(GuiGraphics graphics, AircraftEntity drone, int left, int top, int wide,
            int mouseX, int mouseY) {
        boolean taken = drone.getOperator() != null;
        boolean over = !taken && inside(mouseX, mouseY, left, top, wide, ROW);

        if (over) {
            graphics.fill(left, top, left + wide, top + ROW - 3, HOVER);
        }

        graphics.drawString(this.font, drone.getType().getDescription(), left + 4, top + 3,
                taken ? DIM : TEXT);

        LocalPlayer player = Minecraft.getInstance().player;
        double away = player == null ? 0.0
                : drone.position().subtract(player.position()).horizontalDistance();
        String range = String.format(Locale.ROOT, "%.1fkm", away / 1000.0);

        graphics.drawString(this.font, range, left + wide - 4 - this.font.width(range), top + 3,
                taken ? DIM : LIVE);

        if (taken) {
            graphics.drawString(this.font,
                    Component.translatable("screen.ashvehicles.drone_terminal.taken"),
                    left + 4, top + 3 + LINE, WARN);

            return;
        }

        int bearing = player == null ? 0 : (int) Math.round(Math.toDegrees(Math.atan2(
                player.getX() - drone.getX(), drone.getZ() - player.getZ())));

        graphics.drawString(this.font, Component.translatable(
                "screen.ashvehicles.drone_terminal.state",
                String.format(Locale.ROOT, "%03d", Math.floorMod(bearing, 360)),
                AircraftHud.cardinal(bearing),
                (int) Math.round(drone.getY()),
                (int) Math.round(drone.getVelocity().length() * 20.0),
                Math.round(drone.getFuelFraction() * 100.0F)),
                left + 4, top + 3 + LINE, DIM);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || this.drones.isEmpty()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        int wide = Math.min(PANEL_WIDE, this.width - MARGIN * 2);
        int rows = Math.max(1, Math.min(ROWS_SHOWN, this.drones.size()));
        int tall = MARGIN + LINE + 6 + rows * ROW + 6 + LINE + MARGIN;
        int left = (this.width - wide) / 2 + 6;
        int y = (this.height - tall) / 2 + MARGIN + LINE + 6;

        for (int at = 0; at < rows; at++) {
            AircraftEntity drone = this.drones.get(this.scrolled + at);

            if (drone.getOperator() == null
                    && inside((int) mouseX, (int) mouseY, left, y + at * ROW, wide - 12, ROW)) {
                PacketDistributor.sendToServer(DroneLinkPayload.to(drone));
                this.onClose();

                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        this.scrolled = Mth.clamp(this.scrolled - (int) Math.signum(dy), 0,
                Math.max(0, this.drones.size() - ROWS_SHOWN));

        return true;
    }

    /**
     * 盤の後ろだけを暗くする。画面全体ではない。
     *
     * <p>{@link LaunchConsoleScreen} と同じ判断だ。端末を開いている間も操作者は世界の中に立っており、
     * 近付いてくる物が見えなくなる理由が無い。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** 世界は止めない。飛んでいる機体を一覧で眺めるのが、この画面の使い方の半分だ。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void panel(GuiGraphics graphics, int left, int top, int wide, int tall) {
        graphics.fill(left, top, left + wide, top + tall, PANEL);
        graphics.fill(left, top, left + wide, top + 1, EDGE);
        graphics.fill(left, top + tall - 1, left + wide, top + tall, EDGE);
    }

    private static boolean inside(int x, int y, int left, int top, int wide, int tall) {
        return x >= left && x < left + wide && y >= top && y < top + tall;
    }
}
