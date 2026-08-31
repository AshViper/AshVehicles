package com.ashvehicles.client.screen;

import java.util.Locale;

import javax.annotation.Nullable;

import com.ashvehicles.client.AircraftHud;
import com.ashvehicles.client.LaunchPoint;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 発射機の射撃指揮盤。地図を押して目標を決め、座標として装填する。
 *
 * <p><b>なぜ十字線ではないのか。</b>この種の兵器は見える物を撃たない。射程は地平線の遥か向こうで、乗員が
 * 目標を目にすることは一度も無い——だから照準の手順は「向ける」ではなく「地図の上でここだと指す」ことになる。
 * {@link com.ashvehicles.weapon.WeaponDefinition.Guidance.Seeker#POINT} 参照。
 *
 * <p><b>地図と数字は同じ1つの点だ。</b>盤面を押せば入力欄がその座標になり、入力欄に打てば地図がそこを見る。
 * 大まかに決めるのは地図の仕事で、1ブロック単位で詰めるのは数字の仕事だからだ。どちらか片方しか無い盤は、
 * 必ずもう片方の仕事ができない。
 *
 * <p><b>入れるのは X と Z だけ。</b>高さは入れさせない。弾道弾が狙うのは地面であって空中の一点ではないし、
 * 誰も見ていない列の標高を乗員が知っているはずもない。マークは自分の下に地面が現れた時点でそこへ<em>真下へ
 * </em>降りる（{@link com.ashvehicles.entity.DesignationEntity} 参照）ので、指した2つの数は最後まで守られる。
 *
 * <p><b>射程外は押せない。</b>盤面には射程の環が出ており、その外の点では LOCK が沈む。サーバーも同じ判断を
 * するので（{@link GroundVehicleEntity#designate}）、ここの表示は先に見せているだけだ。
 *
 * <p>ゲームを止めない。車両の中の盤であり、止めれば周りで起きていることが見えなくなる。
 */
public class LaunchConsoleScreen extends net.minecraft.client.gui.screens.Screen {
    private static final int FIELD_WIDE = 78;
    private static final int FIELD_TALL = 20;
    private static final int GAP = 8;
    private static final int LINE = 12;
    /** 盤面の一辺の上限。画面が低ければこれより小さくなる。 */
    private static final int MAP_MOST = 240;
    /** 盤の左右の余白。 */
    private static final int MARGIN = 12;

    /** 入力欄に入れてよい文字。座標は整数で、負にもなる。 */
    private static final String DIGITS = "-0123456789";

    private final GroundVehicleEntity vehicle;
    private final LaunchMap map;

    private EditBox eastward;
    private EditBox southward;
    private Button lock;

    /** 入力欄を自分で書き換えている間だけ true。書き換えが「打ち込まれた」と読まれないように。 */
    private boolean writing;

    private LaunchConsoleScreen(GroundVehicleEntity vehicle) {
        super(Component.translatable("screen.ashvehicles.launch_console.title"));
        this.vehicle = vehicle;
        this.map = new LaunchMap(vehicle.getDesignatedPoint() != null
                ? vehicle.getDesignatedPoint() : vehicle.position());
    }

    /** クライアントからのみ。{@link LaunchPoint} が発射機の乗員のために開く。 */
    public static void open(GroundVehicleEntity vehicle) {
        Minecraft.getInstance().setScreen(new LaunchConsoleScreen(vehicle));
    }

    private int mapSide() {
        // 盤の上下に要るのは、見出し1行・入力欄・読み上げ2行・ボタン、そして余白。残りは全部地図に渡す。
        return Mth.clamp(Math.min(MAP_MOST, this.height - 150), 96, MAP_MOST);
    }

    private int panelTop() {
        return (this.height - (this.mapSide() + LINE * 2 + GAP * 4 + FIELD_TALL * 2 + LINE * 3)) / 2;
    }

    @Override
    protected void init() {
        int middle = this.width / 2;
        int side = this.mapSide();
        int top = this.panelTop();
        int mapTop = top + LINE + GAP;
        int fields = mapTop + side + GAP;

        this.map.place(middle - side / 2, mapTop, side);

        this.eastward = this.field(middle - FIELD_WIDE - GAP / 2, fields);
        this.southward = this.field(middle + GAP / 2, fields);

        // 今入っている座標から始める。据え直しは大抵、前の座標の隣を撃つことだからだ。何も入っていなければ
        // 車両自身の位置——狙う場所ではないが、乗員にどの桁の数を打つのかを教える。
        Vec3 from = this.vehicle.getDesignatedPoint() != null
                ? this.vehicle.getDesignatedPoint()
                : this.vehicle.position();

        this.write(from.x, from.z);

        int buttons = fields + FIELD_TALL + LINE * 3 + GAP;

        this.lock = this.addRenderableWidget(Button
                .builder(Component.translatable("screen.ashvehicles.launch_console.lock"),
                        button -> this.send())
                .bounds(middle - FIELD_WIDE - GAP / 2, buttons, FIELD_WIDE, FIELD_TALL)
                .build());

        this.addRenderableWidget(Button
                .builder(Component.translatable("screen.ashvehicles.launch_console.clear"),
                        button -> {
                            LaunchPoint.clear();
                            this.onClose();
                        })
                .bounds(middle + GAP / 2, buttons, FIELD_WIDE, FIELD_TALL)
                .build());
    }

    private EditBox field(int x, int y) {
        EditBox box = new EditBox(this.font, x, y, FIELD_WIDE, FIELD_TALL, Component.empty());

        box.setMaxLength(8);
        box.setFilter(text -> text.chars().allMatch(c -> DIGITS.indexOf(c) >= 0));
        // 打ち込まれたら地図がそこを見る。数字で詰めた1ブロックが盤面の外だったら、詰めた意味が無い。
        box.setResponder(text -> {
            Vec3 typed = this.typed();

            if (!this.writing && typed != null) {
                this.map.look(typed.x, typed.z);
            }
        });

        return this.addRenderableWidget(box);
    }

    /** 入力欄へ書き込む。地図を押した時と、開いた時。 */
    private void write(double x, double z) {
        this.writing = true;
        this.eastward.setValue(String.valueOf(Math.round(x)));
        this.southward.setValue(String.valueOf(Math.round(z)));
        this.writing = false;
    }

    /** 指されている座標。どちらかが数でなければ null。 */
    @Nullable
    private Vec3 typed() {
        try {
            return new Vec3(Integer.parseInt(this.eastward.getValue()) + 0.5, this.vehicle.getY(),
                    Integer.parseInt(this.southward.getValue()) + 0.5);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void send() {
        Vec3 point = this.typed();

        if (point == null || !LaunchPoint.withinReach(this.vehicle, point)) {
            return;
        }

        LaunchPoint.lock(this.vehicle, point);
        this.onClose();
    }

    // ------------------------------------------------------------------
    // 地図の操作
    // ------------------------------------------------------------------

    /** 盤面を押せばそこが目標になる。数字も同時に動くので、押した後に1ブロック単位で詰められる。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Vec3 at = button == 0 ? this.map.at(mouseX, mouseY, this.vehicle.getY()) : null;

        if (at != null) {
            this.write(at.x, at.z);

            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 掴んで送る。右ボタンでも左ボタンでも同じ。地図はどちらでも動かす物だ。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 1 && this.map.holds(mouseX, mouseY)) {
            this.map.drag(dragX, dragY);

            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.map.holds(mouseX, mouseY)) {
            this.map.zoom(mouseX, mouseY, scrollY);

            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------------
    // 盤面
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int middle = this.width / 2;
        int side = this.mapSide();
        int top = this.panelTop();

        graphics.drawCenteredString(this.font, this.title, middle, top, 0xFFFFFFFF);

        Vec3 point = this.typed();
        boolean reachable = point != null && LaunchPoint.withinReach(this.vehicle, point);

        // 地図が先。widget はその上に乗る。
        this.map.draw(graphics, this.vehicle.level(), this.vehicle.position(),
                LaunchPoint.reachOf(this.vehicle), point);

        super.render(graphics, mouseX, mouseY, partialTick);

        this.lock.active = reachable;

        int fields = top + LINE + GAP + side + GAP;

        // 入力欄の見出しは欄の左肩に。何を打つ欄かは、打ち始める前に分かっていなければならない。
        graphics.drawString(this.font, "X", middle - FIELD_WIDE - GAP / 2, fields - LINE + 2, 0xFFA0A8A4);
        graphics.drawString(this.font, "Z", middle + GAP / 2, fields - LINE + 2, 0xFFA0A8A4);

        // カーソルの下の座標。盤面の上にいる間だけ、見出しの右へ。押す前にどこを指しているかが読める。
        String under = this.map.under(mouseX, mouseY);

        if (under != null) {
            graphics.drawString(this.font, under,
                    middle + FIELD_WIDE + GAP / 2 - this.font.width(under), fields - LINE + 2, 0xB0E8ECEA);
        }

        graphics.drawCenteredString(this.font, this.solution(point, reachable), middle,
                fields + FIELD_TALL + 4, reachable ? 0xFF3BE86A : 0xFFFF5A3B);
        graphics.drawCenteredString(this.font, this.loaded(), middle,
                fields + FIELD_TALL + 4 + LINE, 0xFFA0A8A4);

        // 操作の一行。地図は押す物だと分かるが、送れることも拡大できることも、書かなければ誰も試さない。
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.ashvehicles.launch_console.hint"), middle,
                fields + FIELD_TALL + 4 + LINE * 2, 0x80A0A8A4);
    }

    /** 指されている座標がどこを指すか。方位、方角、距離。 */
    private Component solution(@Nullable Vec3 point, boolean reachable) {
        if (point == null) {
            return Component.translatable("screen.ashvehicles.launch_console.no_solution");
        }

        Vec3 line = point.subtract(this.vehicle.position());
        int bearing = Math.floorMod(
                Math.round((float) (Mth.atan2(-line.x, line.z) * (180.0 / Math.PI))), 360);
        String range = String.format(Locale.ROOT, "%.0f", line.horizontalDistance());

        return Component.translatable(reachable
                        ? "screen.ashvehicles.launch_console.solution"
                        : "screen.ashvehicles.launch_console.out_of_range",
                String.format(Locale.ROOT, "%03d", bearing), AircraftHud.cardinal(bearing), range);
    }

    /** 筒の中身。撃てる数が0なら座標を入れても意味が無いので、そう言う。 */
    private Component loaded() {
        return Component.translatable("screen.ashvehicles.launch_console.tubes",
                this.vehicle.getMissiles(), this.vehicle.getMissileCapacity());
    }

    /**
     * 盤の後ろだけを暗くする。画面全体ではない。
     *
     * <p>バニラの既定は世界の上に一枚暗幕を掛けるが、これは車両の中の盤だ——地図を見ている間に近づいて
     * くる物、燃えている物は見えていなければならない。だから暗くするのは盤の矩形の分だけにして、その外は
     * 世界のまま残す。止めないのと同じ理由である。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int side = this.mapSide();
        int wide = Math.max(side, FIELD_WIDE * 2 + GAP) + MARGIN * 2;
        int left = this.width / 2 - wide / 2;
        int top = this.panelTop() - GAP;
        int bottom = this.panelTop() + LINE + GAP + side + GAP + FIELD_TALL + LINE * 3 + GAP
                + FIELD_TALL + GAP;

        graphics.fill(left, top, left + wide, bottom, 0xD0101412);
        graphics.fill(left, top, left + wide, top + 1, 0xFF3BE86A);
        graphics.fill(left, bottom - 1, left + wide, bottom, 0xFF3BE86A);
    }

    /** 入力欄を開いたまま乗員が降りた、あるいは車両が消えた場合に閉じる。 */
    @Override
    public void tick() {
        if (!this.vehicle.isAlive() || Minecraft.getInstance().player == null
                || Minecraft.getInstance().player.getVehicle() != this.vehicle) {
            this.onClose();
        }
    }

    /**
     * 止めない。車両の中の盤であり、止めれば盤の外で起きていること——近づいてくる物、燃えている物——が
     * 見えなくなる。{@link BlastWandScreen} が止めないのと同じ理由だ。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
