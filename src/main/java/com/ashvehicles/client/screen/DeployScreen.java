package com.ashvehicles.client.screen;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.ashvehicles.client.MatchView;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.match.AirPresets;
import com.ashvehicles.match.Costs;
import com.ashvehicles.match.Loadout;
import com.ashvehicles.network.DeployPayload;
import com.ashvehicles.registry.ModItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 出撃盤。左で機体を選び、右で搭載プリセットを1つ選び、下の1つを押して出る。
 *
 * <p><b>左の一覧は空・陸・海で割ってある。</b> 1枚に並べると50行を越え、戦車を1両選ぶのに爆撃機を
 * かき分けることになる。割り方は乗る場所そのもの——飛ぶ物、地面を走る物、浮かぶ物——で、工廠の棚
 * （{@code crafting/WorkbenchTab}）と違って定義ファイルには何も書かせない。どれかは機体ファイルが
 * 既に答えている（航空機の登録にあるか、車両の {@code type} が ship か）。
 *
 * <p><b>選ぶのはプリセットであって、ステーションごとの兵装ではない</b>（2026-09-14 の指定）。一覧と中身は
 * 機体ファイルと兵装ファイルから組む（{@code match/AirPresets}）——機体はまだ存在しないので、実在する1機には
 * 訊けない。中身の数は吊る前に数えてあり、搭載可能重量で切られた後の数だ。送るのは役割の名前だけで、
 * サーバーが同じ規則で組み直して吊る。
 *
 * <p>地上車両にプリセットは無い。積んでいる物は車両ファイルが決めていて誰も変えられないので、右側には
 * その旨の1行だけが出る。選べるのは車種だけだ。
 *
 * <p>ゲームを止めない。旗の前に立って開く盤であり、止めれば周りで起きていることが見えなくなる——試合中
 * にそれは危ない。
 */
public class DeployScreen extends Screen {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 240;

    private static final int FRAME = 0xFF080A07;
    private static final int EDGE = 0xFF1E2618;
    private static final int WELL = 0xFF0E120C;
    private static final int ACCENT = 0xFFA6D93B;
    private static final int ACCENT_FAINT = 0x40A6D93B;
    private static final int ACCENT_HOVER = 0x20A6D93B;
    private static final int TEXT = 0xFFD5E4BC;
    private static final int TEXT_DIM = 0xFF6C7A5E;

    /** 払えない値段。工廠が素材不足を赤で出すのと同じ読み方。 */
    private static final int SHORT = 0xFFD9553B;

    private static final int ROW_H = 18;
    private static final int LIST_W = 132;

    /** 左の一覧の上に並ぶ札。右のプリセット欄はその下端に揃える。 */
    private static final int TAB_Y = 26;
    private static final int TAB_H = 16;

    private static final int TOP = TAB_Y + TAB_H + 2;
    private static final int LIST_ROWS = 9;
    private static final int PANEL_H = LIST_ROWS * ROW_H;
    private static final int BUTTON_H = 20;

    /** 右のプリセット1行の高さ。役割は多くて7つなので、中身を描く場所がその下に残る。 */
    private static final int PRESET_H = 13;
    /** 中身1行の高さ。 */
    private static final int LINE_H = 10;

    /** 左の一覧の割り方。乗る場所そのもので、これ以上でも以下でもない。 */
    private enum Field {
        AIR("air"),
        LAND("land"),
        SEA("sea");

        static final Field[] VALUES = values();

        private final String id;

        Field(String id) {
            this.id = id;
        }

        Component label() {
            return Component.translatable("gui.ashvehicles.deploy.tab." + this.id);
        }
    }

    private final BlockPos flag;

    /** 札ごとの一覧。中身は登録順のまま。 */
    private final Map<Field, List<ResourceLocation>> byField = new EnumMap<>(Field.class);

    /** 開いている札。 */
    private Field field = Field.AIR;

    /** 選んでいる機体。 */
    @Nullable
    private ResourceLocation machine;

    /** 選んでいる機体のプリセット。地上車両と、吊れる場所を持たない機体では空。 */
    private List<AirPresets.Preset> presets = List.of();

    /** 選んでいるプリセット。 */
    private int presetIndex;

    private int machineScroll;

    private DeployScreen(BlockPos flag) {
        super(Component.translatable("gui.ashvehicles.deploy.title"));
        this.flag = flag;
    }

    /** 旗を触ったときにサーバーが開かせる。クライアントが自分で開く経路は無い。 */
    public static void open(BlockPos flag) {
        Minecraft.getInstance().setScreen(new DeployScreen(flag));
    }

    @Override
    protected void init() {
        for (Field candidate : Field.VALUES) {
            this.byField.put(candidate, new ArrayList<>());
        }

        // 乗れない物は一覧に出さない（{@code Loadout.deployable}）——無人機と牽引砲。
        for (ResourceLocation id : ModItems.aircraft().keySet()) {
            if (Loadout.deployable(id)) {
                this.byField.get(Field.AIR).add(id);
            }
        }

        // 浮くか走るかは車両ファイルの type が既に答えている（[[vehicle-type-field-and-ships]]）。
        for (ResourceLocation id : ModItems.vehicles().keySet()) {
            if (Loadout.deployable(id)) {
                this.byField.get(Definitions.VEHICLES.get(id).isShip() ? Field.SEA : Field.LAND).add(id);
            }
        }

        if (this.machine == null) {
            for (Field candidate : Field.VALUES) {
                if (!this.byField.get(candidate).isEmpty()) {
                    this.field = candidate;
                    this.select(this.byField.get(candidate).get(0));

                    break;
                }
            }
        }
    }

    /** 今開いている札に載っている機体。 */
    private List<ResourceLocation> machines() {
        return this.byField.getOrDefault(this.field, List.of());
    }

    /** 盤を止めない。旗の前は戦場の一部だ。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (this.width - WIDTH) / 2;
    }

    private int top() {
        return (this.height - HEIGHT) / 2;
    }

    // ------------------------------------------------------------------
    // 選択
    // ------------------------------------------------------------------

    /** 機体を選ぶ。プリセットは一覧の先頭——汎用があれば汎用——に戻す。 */
    private void select(ResourceLocation id) {
        this.machine = id;
        this.presets = AirPresets.of(id);
        this.presetIndex = 0;
    }

    private static ItemStack icon(ResourceLocation id) {
        if (ModItems.aircraft().containsKey(id)) {
            return new ItemStack(ModItems.aircraft().get(id).get());
        }

        if (ModItems.vehicles().containsKey(id)) {
            return new ItemStack(ModItems.vehicles().get(id).get());
        }

        if (ModItems.weapons().containsKey(id)) {
            return new ItemStack(ModItems.weapons().get(id).get());
        }

        if (ModItems.equipment().containsKey(id)) {
            return new ItemStack(ModItems.equipment().get(id).get());
        }

        return ItemStack.EMPTY;
    }

    private static Component label(ResourceLocation id) {
        ItemStack stack = icon(id);

        return stack.isEmpty() ? Component.literal(id.getPath()) : stack.getHoverName();
    }

    // ------------------------------------------------------------------
    // 描画
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int x = this.left();
        int y = this.top();

        graphics.fill(x, y, x + WIDTH, y + HEIGHT, FRAME);
        graphics.renderOutline(x, y, WIDTH, HEIGHT, EDGE);
        graphics.drawString(this.font, this.title, x + 8, y + 8, ACCENT, false);

        // 手持ちのポイント。一覧の値段と並べて読む物なので、同じ画面の同じ高さに置く。
        Component purse = Component.translatable("gui.ashvehicles.deploy.purse", MatchView.purse());

        graphics.drawString(this.font, purse, x + WIDTH - 8 - this.font.width(purse), y + 8, ACCENT, false);

        this.drawTabs(graphics, mouseX, mouseY);
        this.drawMachines(graphics, mouseX, mouseY);
        this.drawPresets(graphics, mouseX, mouseY);
        this.drawButton(graphics, mouseX, mouseY);
    }

    /**
     * 空・陸・海の札。開いている札は一覧と同じ薄い黄緑に上端の帯を足す——工廠の棚と同じ合図で、
     * 同じ読み方をさせる。
     */
    private void drawTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = this.top() + TAB_Y;

        for (int index = 0; index < Field.VALUES.length; index++) {
            int x = this.left() + 8 + tabLeft(index);
            int end = this.left() + 8 + tabLeft(index + 1) - 1;
            boolean open = Field.VALUES[index] == this.field;
            boolean over = mouseX >= x && mouseX < end && mouseY >= y && mouseY < y + TAB_H;

            graphics.fill(x, y, end, y + TAB_H, open ? ACCENT_FAINT : WELL);

            if (open) {
                graphics.fill(x, y, end, y + 2, ACCENT);
            } else if (over) {
                graphics.fill(x, y, end, y + TAB_H, ACCENT_HOVER);
            }

            // 中身の無い札は沈める。データパックが艦を1隻も持たない鯖で、押しても何も出ない札が
            // 押せるように見えていては困る。
            boolean stocked = !this.byField.getOrDefault(Field.VALUES[index], List.of()).isEmpty();
            String label = this.font.plainSubstrByWidth(Field.VALUES[index].label().getString(), end - x - 4);

            graphics.drawString(this.font, label, x + (end - x - this.font.width(label)) / 2, y + 4,
                    open ? ACCENT : (stocked ? TEXT : TEXT_DIM), false);
        }
    }

    /** 札 {@code index} の左端。一覧の幅を頭数で割るので、札を増やしても幅は自分で決まる。 */
    private static int tabLeft(int index) {
        return LIST_W * index / Field.VALUES.length;
    }

    private void drawMachines(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.left() + 8;
        int y = this.top() + TOP;
        List<ResourceLocation> shown = this.machines();

        graphics.fill(x, y, x + LIST_W, y + PANEL_H, WELL);

        if (shown.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.ashvehicles.deploy.none"),
                    x + 6, y + 8, TEXT_DIM, false);

            return;
        }

        for (int row = 0; row < LIST_ROWS; row++) {
            int index = this.machineScroll + row;

            if (index >= shown.size()) {
                break;
            }

            ResourceLocation id = shown.get(index);
            int top = y + row * ROW_H;
            boolean over = mouseX >= x && mouseX < x + LIST_W && mouseY >= top && mouseY < top + ROW_H;

            if (id.equals(this.machine)) {
                graphics.fill(x, top, x + LIST_W, top + ROW_H, ACCENT_FAINT);
            } else if (over) {
                graphics.fill(x, top, x + LIST_W, top + ROW_H, ACCENT_HOVER);
            }

            int cost = Costs.of(id);
            String price = String.valueOf(cost);
            boolean afford = MatchView.purse() >= cost;

            graphics.renderItem(icon(id), x + 1, top + 1);
            graphics.drawString(this.font,
                    this.font.plainSubstrByWidth(label(id).getString(), LIST_W - 28 - this.font.width(price)),
                    x + 21, top + 5, id.equals(this.machine) ? ACCENT : TEXT, false);
            // 値段は名前の後ろではなく右端に揃える。一覧で読むのは「どれが買えるか」で、それは縦に
            // 並んだ数字の列でしか読めない。
            graphics.drawString(this.font, price, x + LIST_W - 4 - this.font.width(price), top + 5,
                    afford ? TEXT_DIM : SHORT, false);
        }
    }

    /**
     * 右の欄。上にプリセットの一覧、区切りの下に選んでいるプリセットの中身、最下段に重さ。
     *
     * <p>中身は「何を何発」だけを書き、どのステーションかは書かない。選べない物の置き場所を並べても、読む
     * 手間が増えるだけだ。
     */
    private void drawPresets(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.panelLeft();
        int y = this.top() + TOP;
        int width = panelWidth();

        graphics.fill(x, y, x + width, y + PANEL_H, WELL);

        if (this.presets.isEmpty()) {
            Component note = this.machine != null && ModItems.aircraft().containsKey(this.machine)
                    ? Component.translatable("gui.ashvehicles.deploy.no_stores")
                    : Component.translatable("gui.ashvehicles.deploy.fixed_armament");

            graphics.drawWordWrap(this.font, note, x + 6, y + 8, width - 12, TEXT_DIM);

            return;
        }

        for (int index = 0; index < this.presets.size(); index++) {
            int top = y + index * PRESET_H;
            boolean chosen = index == this.presetIndex;
            boolean over = mouseX >= x && mouseX < x + width && mouseY >= top && mouseY < top + PRESET_H;

            if (chosen) {
                graphics.fill(x, top, x + width, top + PRESET_H, ACCENT_FAINT);
                graphics.fill(x, top, x + 2, top + PRESET_H, ACCENT);
            } else if (over) {
                graphics.fill(x, top, x + width, top + PRESET_H, ACCENT_HOVER);
            }

            String name = this.font.plainSubstrByWidth(this.presets.get(index).role().label().getString(),
                    width - 12);

            graphics.drawString(this.font, name, x + 6, top + 3, chosen ? ACCENT : TEXT, false);
        }

        AirPresets.Preset preset = this.presets.get(this.presetIndex);
        int bottom = y + PANEL_H - 2;
        int lineTop = y + this.presets.size() * PRESET_H + 3;

        graphics.fill(x + 4, lineTop - 2, x + width - 4, lineTop - 1, EDGE);

        // 重さは最下段に固定する。行が多いプリセットでも、それが最後まで読める行であるように。
        if (preset.payload() > 0.0F) {
            Component mass = Component.translatable("gui.ashvehicles.deploy.mass",
                    Math.round(preset.mass()), Math.round(preset.payload()));

            bottom -= LINE_H;
            graphics.drawString(this.font, mass, x + width - 4 - this.font.width(mass), bottom + 1, TEXT_DIM, false);
        }

        if (preset.lines().isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.ashvehicles.deploy.nothing_hung"),
                    x + 6, lineTop + 1, TEXT_DIM, false);

            return;
        }

        for (AirPresets.Line line : preset.lines()) {
            if (lineTop + LINE_H > bottom) {
                break;
            }

            String count = "×" + line.count();
            String name = this.font.plainSubstrByWidth(label(line.item()).getString(),
                    width - 16 - this.font.width(count));

            graphics.drawString(this.font, name, x + 6, lineTop + 1, TEXT, false);
            graphics.drawString(this.font, count, x + width - 4 - this.font.width(count), lineTop + 1, ACCENT, false);
            lineTop += LINE_H;
        }
    }

    private int panelLeft() {
        return this.left() + 8 + LIST_W + 8;
    }

    private static int panelWidth() {
        return WIDTH - LIST_W - 32;
    }

    private void drawButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.left() + 8;
        int y = this.top() + HEIGHT - BUTTON_H - 8;
        int width = WIDTH - 16;
        boolean over = this.overButton(mouseX, mouseY);
        boolean afford = this.affordable();

        graphics.fill(x, y, x + width, y + BUTTON_H, over && afford ? ACCENT_FAINT : WELL);
        graphics.renderOutline(x, y, width, BUTTON_H, EDGE);

        Component label = afford
                ? Component.translatable("gui.ashvehicles.deploy.launch")
                : Component.translatable("gui.ashvehicles.deploy.short",
                        this.machine == null ? 0 : Costs.of(this.machine));

        graphics.drawString(this.font, label, x + (width - this.font.width(label)) / 2, y + 6,
                afford ? ACCENT : SHORT, false);
    }

    /** 選んでいる機体を今の手持ちで出せるか。断るのはサーバーだが、押す前に読めた方がよい。 */
    private boolean affordable() {
        return this.machine != null && MatchView.purse() >= Costs.of(this.machine);
    }

    private boolean overButton(int mouseX, int mouseY) {
        int x = this.left() + 8;
        int y = this.top() + HEIGHT - BUTTON_H - 8;

        return mouseX >= x && mouseX < x + WIDTH - 16 && mouseY >= y && mouseY < y + BUTTON_H;
    }

    // ------------------------------------------------------------------
    // 入力
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (this.overButton((int) mouseX, (int) mouseY)) {
                if (this.affordable()) {
                    this.launch();
                }

                return true;
            }

            if (this.clickTabs(mouseX, mouseY) || this.clickMachines(mouseX, mouseY)
                    || this.clickPresets(mouseX, mouseY)) {
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 札を押す。**選んでいる機体は変えない。** 札は左の一覧の絞り込みでしかなく、押しただけで右の
     * プリセットが白紙に戻るのでは、他の機体を見に行くことすらできない。
     */
    private boolean clickTabs(double mouseX, double mouseY) {
        int y = this.top() + TAB_Y;

        if (mouseY < y || mouseY >= y + TAB_H) {
            return false;
        }

        for (int index = 0; index < Field.VALUES.length; index++) {
            int x = this.left() + 8 + tabLeft(index);
            int end = this.left() + 8 + tabLeft(index + 1) - 1;

            if (mouseX >= x && mouseX < end) {
                this.field = Field.VALUES[index];
                this.machineScroll = 0;
                this.click();

                return true;
            }
        }

        return false;
    }

    private boolean clickMachines(double mouseX, double mouseY) {
        int x = this.left() + 8;
        int y = this.top() + TOP;

        if (mouseX < x || mouseX >= x + LIST_W || mouseY < y || mouseY >= y + PANEL_H) {
            return false;
        }

        List<ResourceLocation> shown = this.machines();
        int index = this.machineScroll + ((int) mouseY - y) / ROW_H;

        if (index >= 0 && index < shown.size()) {
            this.select(shown.get(index));
            this.click();
        }

        return true;
    }

    /** プリセットの行を押す。中身の行は押しても何も起きない。 */
    private boolean clickPresets(double mouseX, double mouseY) {
        int x = this.panelLeft();
        int y = this.top() + TOP;

        if (mouseX < x || mouseX >= x + panelWidth() || mouseY < y || mouseY >= y + PANEL_H) {
            return false;
        }

        int index = ((int) mouseY - y) / PRESET_H;

        if (index < this.presets.size() && index != this.presetIndex) {
            this.presetIndex = index;
            this.click();
        }

        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX < this.left() + 8 + LIST_W) {
            this.machineScroll = clamp(this.machineScroll + (deltaY > 0 ? -1 : 1), this.machines().size());
        }

        return true;
    }

    private static int clamp(int scroll, int size) {
        return Math.max(0, Math.min(scroll, Math.max(0, size - LIST_ROWS)));
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** 役割の名前を送って閉じる。通ったかどうかはサーバーが1行で返す。 */
    private void launch() {
        if (this.machine == null) {
            return;
        }

        String preset = this.presets.isEmpty() ? "" : this.presets.get(this.presetIndex).role().id();

        PacketDistributor.sendToServer(new DeployPayload(this.flag, this.machine, preset));
        this.onClose();
    }
}
