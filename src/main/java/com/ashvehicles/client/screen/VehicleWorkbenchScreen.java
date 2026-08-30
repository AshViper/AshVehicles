package com.ashvehicles.client.screen;

import java.util.List;

import com.ashvehicles.crafting.MaterialPool;
import com.ashvehicles.crafting.VehicleWorkbenchRecipe;
import com.ashvehicles.menu.VehicleWorkbenchMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/**
 * 工廠の画面。左に組めるものの一覧、右に選んだ1機と、それに要る素材と、押すボタン。
 *
 * <p>絵は1枚も使わず、全部その場の塗りで描く。黒地に黄緑という配色は、この MOD の中で唯一「機械が
 * 人に情報を返してくる面」だからで、機体の HUD と同じ読み方——足りていれば黄緑、足りなければ赤、
 * 関係ないものは沈む——をここでも通す。
 *
 * <p>一覧の各行は機体の絵と名前を並べる。工廠に来た人が最初に知りたいのは配置ではなく「何が作れるか」
 * で、それは名前と姿で答えるのが一番早い。
 */
public class VehicleWorkbenchScreen extends AbstractContainerScreen<VehicleWorkbenchMenu> {
    private static final int WIDTH = 264;
    private static final int HEIGHT = 242;

    /** 黒。外枠と、その内側に沈めた面。 */
    private static final int FRAME = 0xFF080A07;
    private static final int EDGE = 0xFF1E2618;
    private static final int WELL = 0xFF0E120C;
    private static final int SLOT = 0xFF161C12;

    /** 黄緑。作れるもの、足りている数、押せるボタン。 */
    private static final int ACCENT = 0xFFA6D93B;
    private static final int ACCENT_DIM = 0xFF4E6B1E;
    private static final int ACCENT_FAINT = 0x40A6D93B;

    private static final int TEXT = 0xFFD5E4BC;
    private static final int TEXT_DIM = 0xFF6C7A5E;
    private static final int SHORT = 0xFFD9553B;

    private static final int LIST_X = 8;
    private static final int LIST_Y = 18;
    private static final int LIST_W = 104;
    private static final int ROW_H = 18;
    private static final int ROWS = 7;
    private static final int LIST_H = ROWS * ROW_H;

    private static final int BAR_X = LIST_X + LIST_W;
    private static final int BAR_W = 8;

    private static final int DETAIL_X = 126;
    private static final int DETAIL_Y = LIST_Y;
    private static final int DETAIL_W = 130;
    private static final int DETAIL_H = LIST_H;

    private static final int PREVIEW = 48;
    private static final int PREVIEW_X = DETAIL_X + (DETAIL_W - PREVIEW) / 2;
    private static final int PREVIEW_Y = DETAIL_Y + 18;

    /** 素材の枠。1機に要る素材の種類は7種を越えない（素材が7種しか無い）。 */
    private static final int MATERIAL_SLOT = 18;
    private static final int MATERIAL_MAX = 7;
    private static final int MATERIAL_Y = DETAIL_Y + 72;

    private static final int BUTTON_X = DETAIL_X + 6;
    private static final int BUTTON_Y = DETAIL_Y + 98;
    private static final int BUTTON_W = DETAIL_W - 12;
    private static final int BUTTON_H = 22;

    /** 選んでいる1機。一覧の頭から始める。 */
    private int selected;

    /** 一覧の一番上に出ている行。 */
    private int scroll;

    private boolean draggingBar;

    /** 各機体が今の持ち物で作れるか。毎フレームではなく毎ティック数え直す。 */
    private boolean[] affordable = new boolean[0];

    /** 選んだ1機の素材ごとの所持数。 */
    private int[] held = new int[0];

    public VehicleWorkbenchScreen(VehicleWorkbenchMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);

        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.titleLabelX = LIST_X;
        this.titleLabelY = 6;
        this.inventoryLabelX = VehicleWorkbenchMenu.INVENTORY_X;
        this.inventoryLabelY = VehicleWorkbenchMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();

        refresh();
    }

    @Override
    public void containerTick() {
        super.containerTick();

        refresh();
    }

    /** 持ち物を数え直す。作れるかどうかは持ち物が動くたび変わる。 */
    private void refresh() {
        List<RecipeHolder<VehicleWorkbenchRecipe>> recipes = this.menu.recipes();
        MaterialPool pool = MaterialPool.of(this.minecraft.player.getInventory());

        if (this.affordable.length != recipes.size()) {
            this.affordable = new boolean[recipes.size()];
        }

        for (int i = 0; i < recipes.size(); i++) {
            this.affordable[i] = pool.has(recipes.get(i).value().materials());
        }

        this.selected = Mth.clamp(this.selected, 0, Math.max(recipes.size() - 1, 0));

        VehicleWorkbenchRecipe chosen = selected();

        if (chosen == null) {
            this.held = new int[0];

            return;
        }

        List<SizedIngredient> materials = chosen.materials();

        if (this.held.length != materials.size()) {
            this.held = new int[materials.size()];
        }

        for (int i = 0; i < materials.size(); i++) {
            this.held[i] = pool.count(materials.get(i));
        }
    }

    private VehicleWorkbenchRecipe selected() {
        List<RecipeHolder<VehicleWorkbenchRecipe>> recipes = this.menu.recipes();

        return recipes.isEmpty() ? null : recipes.get(Mth.clamp(this.selected, 0, recipes.size() - 1)).value();
    }

    private int maxScroll() {
        return Math.max(0, this.menu.recipes().size() - ROWS);
    }

    // ------------------------------------------------------------------ 描画

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (!renderMaterialTooltip(graphics, mouseX, mouseY)) {
            renderTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;

        graphics.fill(left, top, left + WIDTH, top + HEIGHT, FRAME);
        outline(graphics, left, top, left + WIDTH, top + HEIGHT, EDGE);
        graphics.fill(left + LIST_X, top + 15, left + WIDTH - LIST_X, top + 16, ACCENT_DIM);

        renderList(graphics, left, top, mouseX, mouseY);
        renderDetail(graphics, left, top, mouseX, mouseY);
        renderInventory(graphics, left, top);
    }

    private void renderList(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + LIST_X;
        int y = top + LIST_Y;

        graphics.fill(x, y, x + LIST_W + BAR_W, y + LIST_H, WELL);

        List<RecipeHolder<VehicleWorkbenchRecipe>> recipes = this.menu.recipes();

        if (recipes.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("gui.ashvehicles.workbench.empty"),
                    x + 6, y + 6, TEXT_DIM, false);

            return;
        }

        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());

        for (int row = 0; row < ROWS; row++) {
            int index = this.scroll + row;

            if (index >= recipes.size()) {
                break;
            }

            int rowY = y + row * ROW_H;
            boolean chosen = index == this.selected;
            boolean can = this.affordable[index];

            if (chosen) {
                graphics.fill(x, rowY, x + LIST_W, rowY + ROW_H, ACCENT_FAINT);
                graphics.fill(x, rowY, x + 2, rowY + ROW_H, ACCENT);
            } else if (mouseX >= x && mouseX < x + LIST_W && mouseY >= rowY && mouseY < rowY + ROW_H) {
                graphics.fill(x, rowY, x + LIST_W, rowY + ROW_H, 0x20A6D93B);
            }

            ItemStack result = recipes.get(index).value().result();

            graphics.renderItem(result, x + 4, rowY + 1);

            String name = this.font.plainSubstrByWidth(result.getHoverName().getString(), LIST_W - 26);

            graphics.drawString(this.font, name, x + 24, rowY + 5, can ? TEXT : TEXT_DIM, false);

            if (!can) {
                graphics.fill(x + LIST_W - 5, rowY + 8, x + LIST_W - 2, rowY + 11, SHORT);
            }
        }

        renderScrollBar(graphics, left, top, recipes.size());
    }

    private void renderScrollBar(GuiGraphics graphics, int left, int top, int count) {
        int x = left + BAR_X;
        int y = top + LIST_Y;

        graphics.fill(x, y, x + BAR_W, y + LIST_H, SLOT);

        if (count <= ROWS) {
            return;
        }

        int knob = Math.max(12, LIST_H * ROWS / count);
        int travel = LIST_H - knob;
        int offset = maxScroll() == 0 ? 0 : travel * this.scroll / maxScroll();

        graphics.fill(x + 1, y + offset, x + BAR_W - 1, y + offset + knob, ACCENT_DIM);
        graphics.fill(x + 1, y + offset, x + BAR_W - 1, y + offset + 2, ACCENT);
    }

    private void renderDetail(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + DETAIL_X;
        int y = top + DETAIL_Y;

        graphics.fill(x, y, x + DETAIL_W, y + DETAIL_H, WELL);

        VehicleWorkbenchRecipe recipe = selected();

        if (recipe == null) {
            return;
        }

        ItemStack result = recipe.result();

        graphics.drawCenteredString(this.font, result.getHoverName(), x + DETAIL_W / 2, y + 5, ACCENT);

        // 一覧の16pxでは分からないところまで見せる、3倍の姿
        graphics.pose().pushPose();
        graphics.pose().translate(left + PREVIEW_X, top + PREVIEW_Y, 0.0F);
        graphics.pose().scale(3.0F, 3.0F, 3.0F);
        graphics.renderItem(result, 0, 0);
        graphics.pose().popPose();

        List<SizedIngredient> materials = recipe.materials();
        int strip = materialStripLeft(left, materials.size());

        for (int i = 0; i < materials.size() && i < MATERIAL_MAX; i++) {
            SizedIngredient material = materials.get(i);
            ItemStack[] shown = material.getItems();

            if (shown.length == 0) {
                continue;
            }

            int slotX = strip + i * MATERIAL_SLOT;
            int slotY = top + MATERIAL_Y;
            boolean enough = i < this.held.length && this.held[i] >= material.count();

            graphics.fill(slotX, slotY, slotX + 16, slotY + 16, SLOT);
            outline(graphics, slotX - 1, slotY - 1, slotX + 17, slotY + 17, enough ? ACCENT_DIM : SHORT);
            graphics.renderItem(shown[0], slotX, slotY);
            graphics.renderItemDecorations(this.font, shown[0], slotX, slotY,
                    String.valueOf(material.count()));
        }

        renderButton(graphics, left, top, mouseX, mouseY);
    }

    private int materialStripLeft(int left, int count) {
        int used = Math.min(count, MATERIAL_MAX);

        return left + DETAIL_X + (DETAIL_W - used * MATERIAL_SLOT) / 2 + 1;
    }

    private void renderButton(GuiGraphics graphics, int left, int top, int mouseX, int mouseY) {
        int x = left + BUTTON_X;
        int y = top + BUTTON_Y;
        boolean can = this.menu.canCraft(this.selected);
        boolean over = overButton(mouseX, mouseY);

        graphics.fill(x, y, x + BUTTON_W, y + BUTTON_H, can ? (over ? ACCENT : ACCENT_DIM) : SLOT);
        outline(graphics, x, y, x + BUTTON_W, y + BUTTON_H, can ? ACCENT : EDGE);

        Component label = Component.translatable(can
                ? "gui.ashvehicles.workbench.craft"
                : "gui.ashvehicles.workbench.missing");
        int color = can ? (over ? FRAME : TEXT) : TEXT_DIM;

        graphics.drawCenteredString(this.font, label, x + BUTTON_W / 2, y + (BUTTON_H - 8) / 2, color);
    }

    private void renderInventory(GuiGraphics graphics, int left, int top) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                slot(graphics, left + VehicleWorkbenchMenu.INVENTORY_X + column * 18 - 1,
                        top + VehicleWorkbenchMenu.INVENTORY_Y + row * 18 - 1);
            }
        }

        for (int column = 0; column < 9; column++) {
            slot(graphics, left + VehicleWorkbenchMenu.INVENTORY_X + column * 18 - 1,
                    top + VehicleWorkbenchMenu.HOTBAR_Y - 1);
        }
    }

    private void slot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, SLOT);
        outline(graphics, x, y, x + 18, y + 18, WELL);
    }

    private void outline(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        graphics.fill(x0, y0, x1, y0 + 1, color);
        graphics.fill(x0, y1 - 1, x1, y1, color);
        graphics.fill(x0, y0, x0 + 1, y1, color);
        graphics.fill(x1 - 1, y0, x1, y1, color);
    }

    /** 名前だけでなく「所持/必要」まで出す。足りない素材が何個足りないかが一番知りたいこと。 */
    private boolean renderMaterialTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        VehicleWorkbenchRecipe recipe = selected();

        if (recipe == null) {
            return false;
        }

        List<SizedIngredient> materials = recipe.materials();
        int strip = materialStripLeft(this.leftPos, materials.size());
        int slotY = this.topPos + MATERIAL_Y;

        if (mouseY < slotY || mouseY >= slotY + 16) {
            return false;
        }

        for (int i = 0; i < materials.size() && i < MATERIAL_MAX; i++) {
            int slotX = strip + i * MATERIAL_SLOT;

            if (mouseX < slotX || mouseX >= slotX + 16) {
                continue;
            }

            SizedIngredient material = materials.get(i);
            ItemStack[] shown = material.getItems();

            if (shown.length == 0) {
                return false;
            }

            int have = i < this.held.length ? this.held[i] : 0;

            graphics.renderTooltip(this.font, List.of(
                    shown[0].getHoverName().copy().getVisualOrderText(),
                    Component.translatable("gui.ashvehicles.workbench.have", have, material.count())
                            .withStyle(style -> style.withColor(have >= material.count() ? 0xA6D93B : 0xD9553B))
                            .getVisualOrderText()),
                    mouseX, mouseY);

            return true;
        }

        return false;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, ACCENT, false);
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY,
                TEXT_DIM, false);
    }

    // ------------------------------------------------------------------ 操作

    private boolean overButton(double mouseX, double mouseY) {
        int x = this.leftPos + BUTTON_X;
        int y = this.topPos + BUTTON_Y;

        return mouseX >= x && mouseX < x + BUTTON_W && mouseY >= y && mouseY < y + BUTTON_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int listX = this.leftPos + LIST_X;
        int listY = this.topPos + LIST_Y;

        if (mouseX >= listX && mouseX < listX + LIST_W && mouseY >= listY && mouseY < listY + LIST_H) {
            int index = this.scroll + (int) ((mouseY - listY) / ROW_H);

            if (index < this.menu.recipes().size()) {
                this.selected = index;

                refresh();
                click(1.0F);
            }

            return true;
        }

        int barX = this.leftPos + BAR_X;

        if (mouseX >= barX && mouseX < barX + BAR_W && mouseY >= listY && mouseY < listY + LIST_H) {
            this.draggingBar = true;

            dragTo(mouseY);

            return true;
        }

        if (overButton(mouseX, mouseY)) {
            craft();

            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingBar) {
            dragTo(mouseY);

            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingBar = false;

        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void dragTo(double mouseY) {
        double within = (mouseY - this.topPos - LIST_Y) / LIST_H;

        this.scroll = Mth.clamp((int) Math.round(within * maxScroll()), 0, maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY), 0, maxScroll());

        return true;
    }

    /** 組み上げの合図。押せる状態でなければ何も送らない。 */
    private void craft() {
        if (!this.menu.canCraft(this.selected) || this.minecraft == null || this.minecraft.gameMode == null) {
            click(0.6F);

            return;
        }

        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, this.selected);

        click(1.4F);
    }

    private void click(float pitch) {
        this.minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
    }
}
