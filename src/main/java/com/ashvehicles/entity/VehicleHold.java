package com.ashvehicles.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 機体が内部に積んでいる物。9×3の枠であり、地上要員が再武装に使う弾庫でもある。
 *
 * <p>3行なのは、機体の弾庫に求められている物がまさにそれ——どのプレイヤーも既に知っている形のチェスト——
 * だからで、そうすれば {@code ChestMenu.threeRows} が自前の画面なしで描いてくれるから。9×3 がインター
 * フェースの全部。
 *
 * <p><b>飾りではない。</b> パイロンに吊る物はここから出るし、弾もここから引かれる
 * （{@link com.ashvehicles.weapon.WeaponMounts} 参照）。弾庫が空のまま駐機した機体は着陸時と同じく空の
 * まま。それがこの仕組みの要点で、機体が撃てるのは誰かが積んだ分だけ。
 *
 * <p>持ち主はサーバー。クライアントへは何も同期しない。誰かが開いている間はメニューが「見ている内容」を
 * 送るし、クライアントで描かれる他の何もこれに依存していない。
 */
public final class VehicleHold extends SimpleContainer {
    /** 9列3行。 */
    public static final int ROWS = 3;
    public static final int COLUMNS = 9;
    public static final int SIZE = ROWS * COLUMNS;

    /** 中身を機体のセーブデータのどこに置くか。 */
    private static final String KEY = "Hold";
    /** 中に手を入れたまま機体からどれだけ離れて立てるか（ブロック）。 */
    private static final double REACH = 4.0;

    private final VehicleEntityBase vehicle;

    VehicleHold(VehicleEntityBase vehicle) {
        super(SIZE);
        this.vehicle = vehicle;
    }

    /**
     * 開いた者は、乗っている間か機体のそばに立っている間は開いたままにできる。
     *
     * <p>判定は素の直方体ではなく機体が本当に持つ形状に対して行う。15m の機体では素の箱は胴体しか覆わない
     * ので、翼端で兵装を積んでいる者は常識的にはどう読んでも「機体のそばに立っている」のに、身を乗り出して
     * いる弾庫から手を払われることになる。
     */
    @Override
    public boolean stillValid(Player player) {
        if (this.vehicle.isRemoved()) {
            return false;
        }

        return player.getRootVehicle() == this.vehicle
                || player.canInteractWithEntity(this.vehicle.getBoundingBoxForCulling(), REACH);
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /**
     * {@code SimpleContainer.createTag} ではなくスロットごとに書く。あちらは読み戻しに {@code addItem}
     * を使い、見つけた最初の空きスロットへ詰め直すので、積んだ者が並べた弾庫が並び替わって戻ってくる——
     * 左にミサイル、右に燃料と置いたプレイヤーは、リロードしただけでそれが混ぜられているのを見ることになる。
     */
    void save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();

        for (int slot = 0; slot < this.getContainerSize(); slot++) {
            ItemStack stack = this.getItem(slot);

            if (stack.isEmpty()) {
                continue;
            }

            CompoundTag entry = new CompoundTag();
            entry.putByte("Slot", (byte) slot);
            list.add(stack.save(registries, entry));
        }

        tag.put(KEY, list);
    }

    void load(CompoundTag tag, HolderLookup.Provider registries) {
        this.clearContent();
        ListTag list = tag.getList(KEY, Tag.TAG_COMPOUND);

        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.getByte("Slot") & 0xFF;

            if (slot >= this.getContainerSize()) {
                // 前回ワールドを開いた時より弾庫が小さくなっている場合。入らなくなった分を落とす方が、
                // 残りの積荷まで一緒に捨てるよりまし。
                continue;
            }

            ItemStack.parse(registries, entry).ifPresent(stack -> this.setItem(slot, stack));
        }
    }
}
