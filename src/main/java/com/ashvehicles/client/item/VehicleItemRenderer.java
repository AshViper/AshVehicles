package com.ashvehicles.client.item;

import javax.annotation.Nullable;

import com.ashvehicles.item.VehicleItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 機体アイテムを、{@link VehicleIcons 機体の絵}を貼った正方形1枚として描く。
 *
 * <p>平坦アイテムとはそもそもそれだけの物だ。意図的に機体そのものは描かない。全スロットで毎フレーム描かれる
 * モデルがアイテムのあるべき姿でない理由と、これが置き換え元のバニラアイテムより高価ではなく安価になる理由は
 * {@link VehicleIcons} 参照。
 *
 * <p>正方形は正方形であって、側面のある板ではない。バニラの平坦アイテムは手に持ったとき厚みが出るよう押し出されて
 * いる。剣には価値があるが、戦車の写真に100枚のクアッドを払う価値は無い。両面を描くので、裏から見てもアイテムは
 * そこにある。
 */
public final class VehicleItemRenderer extends BlockEntityWithoutLevelRenderer {
    /** アイテム変換が計算されるブロック内での平坦アイテムの位置。中央。 */
    private static final float DEPTH = 0.5F;

    @Nullable
    private static VehicleItemRenderer instance;

    private VehicleItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    /**
     * 唯一のインスタンス。アイテム拡張の登録時ではなく、最初にアイテムから要求されたときに構築する。登録時は
     * まだゲームの組み立て中で、渡せるブロックエンティティレンダラーもモデルセットも存在しないからだ。
     */
    public static VehicleItemRenderer instance() {
        VehicleItemRenderer built = instance;

        if (built == null) {
            built = new VehicleItemRenderer();
            instance = built;
        }

        return built;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!(stack.getItem() instanceof VehicleItem<?> item)) {
            return;
        }

        ResourceLocation icon = VehicleIcons.of(item.vehicle());

        // まだ撮影していないか、機体に撮影元のモデルが無い。いずれにせよ描く物は無く、次の1〜2フレームで
        // 決着する。
        if (icon == null) {
            return;
        }

        // カリング無し。正方形の裏面は表面の透過として見える。手持ちアイテムは周りを歩かれるし、裏から消える
        // アイテムは穴に見えてしまう。
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(icon));
        PoseStack.Pose pose = poseStack.last();

        corner(buffer, pose, 0.0F, 0.0F, 0.0F, 1.0F, packedLight, packedOverlay);
        corner(buffer, pose, 1.0F, 0.0F, 1.0F, 1.0F, packedLight, packedOverlay);
        corner(buffer, pose, 1.0F, 1.0F, 1.0F, 0.0F, packedLight, packedOverlay);
        corner(buffer, pose, 0.0F, 1.0F, 0.0F, 0.0F, packedLight, packedOverlay);
    }

    private static void corner(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v,
            int packedLight, int packedOverlay) {
        buffer.addVertex(pose, x, y, DEPTH)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(packedOverlay)
                .setLight(packedLight)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
