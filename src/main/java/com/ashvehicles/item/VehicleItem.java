package com.ashvehicles.item;

import java.util.function.Supplier;

import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 登録時に紐づけられた機体を、クリックしたブロックの上へプレイヤー向きで設置する。
 *
 * <p>ここで機体と戦車が違うのは向きの付け方だけで、それも1行。どちらも Minecraft が持つ2つの角度では
 * なく姿勢で向きを決めるが、戦車は何かの上に寝ており機体はそうではない。よって {@link #point} だけを
 * フックにし、残り（ブロックを探す、プレイヤーの方を向く、空間を確認する、アイテムを消費する）は共通。
 *
 * <p>例外は艦で、他が「乗る」ところを「浮く」。設置もボートと同じで、開けた水面を右クリックすれば水面
 * に乗り、浅瀬越しにクリックしても底ではなく水面に乗る。{@link #floatsOnWater} と {@link #use} 参照。
 *
 * @param <T> このアイテムが設置する機体
 */
public abstract class VehicleItem<T extends VehicleEntityBase> extends Item {
    /**
     * 箱が世界とどれだけ重なっても「空いている」と見なすか（ブロック）。翼端や履帯が斜面に少し食い込む
     * 程度は許す。実際そうなるので。
     */
    private static final double PLACING_MARGIN = 0.25;

    private final Supplier<? extends EntityType<? extends T>> type;

    protected VehicleItem(Supplier<? extends EntityType<? extends T>> type, Properties properties) {
        super(properties);
        this.type = type;
    }

    /**
     * この機体に関する全て（データファイル、ジオメトリ、テクスチャ、アイテムの絵）が見つかる ID。
     * アイテム自身の名前ではなく設置するエンティティから取る。今日は同じ値だが、決めているのは
     * そちらではない。
     */
    public ResourceLocation vehicle() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(this.type.get());
    }

    /**
     * 設置直後の機体を、置いた向きへ向ける。
     *
     * <p>{@code moveTo} は Minecraft が持つ2つの角度しか設定せず、この MOD の向きは全部姿勢で決まるため
     * 必要。これが無いと、どちら向きに置いても機体は真南を向いたままになり、姿勢から配置される当たり判定
     * の箱も一緒に南を向く。
     */
    protected abstract void point(T vehicle, float yaw);

    /**
     * 地面ではなく水面に置く機体か。該当するのは艦だけ。浮く機体はクリックしたブロックの上ではなく
     * ボートと同じ流儀で置かれる。
     */
    protected boolean floatsOnWater() {
        return false;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        double y = pos.getY();

        // 浅瀬で（水越しに底を）クリックした艦は、その底の上ではなく上の水面に置く。開けた水面へ直接
        // 落とした場合と同じ扱い。深い水ではブロック判定が底を見つけずここは動かない。その場合は
        // {@link #use} が担当する。
        if (this.floatsOnWater()) {
            double surface = waterSurfaceAt(level, pos);

            if (Double.isNaN(surface)) {
                surface = waterSurfaceAt(level, context.getClickedPos());
            }

            if (!Double.isNaN(surface)) {
                y = surface;
            }
        }

        Player player = context.getPlayer();
        float yaw = player == null ? 0.0F : player.getYRot();

        return this.place(level, pos.getX() + 0.5, y, pos.getZ() + 0.5, yaw, player,
                context.getItemInHand(), pos);
    }

    /**
     * カーソル下に {@link #useOn} が掴めるブロックが無い、開けた水面へ艦を置く。判定はボートと同じ撃ち方
     * （空気を貫き水面で止まる）なので、艦は海面に乗り、突き抜けない。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);

        if (!this.floatsOnWater()) {
            return InteractionResultHolder.pass(held);
        }

        HitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);

        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(held);
        }

        BlockPos hitPos = ((BlockHitResult) hit).getBlockPos();

        // 進水させてよいのは水の上だけ。ここでブロックに当たったなら、それは水面越しに見えている乾いた
        // ブロックであり、そこへ艦を置くのは意図と違う。それは useOn と地面の仕事。
        if (!level.getFluidState(hitPos).is(FluidTags.WATER)) {
            return InteractionResultHolder.pass(held);
        }

        Vec3 where = hit.getLocation();
        InteractionResult result = this.place(level, where.x, where.y, where.z, player.getYRot(),
                player, held, hitPos);

        if (result == InteractionResult.FAIL) {
            return InteractionResultHolder.fail(held);
        }

        return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
    }

    /**
     * 機体を生成し、向きを付け、形状全体が空いているか確認し、空いていれば追加してアイテムを消費する。
     * 入口2つ（戦車を乗せるブロックと、艦を進水させる水面）で共通。
     */
    private InteractionResult place(Level level, double x, double y, double z, float yaw,
            Player player, ItemStack held, BlockPos event) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.sidedSuccess(true);
        }

        T vehicle = this.type.get().create(serverLevel);

        if (vehicle == null) {
            return InteractionResult.FAIL;
        }

        vehicle.moveTo(x, y, z, yaw, 0.0F);
        this.point(vehicle, yaw);

        // 空いている必要があるのは中央ではなく形状全体。今や箱は世界に対して止まるので、翼端や履帯が
        // 斜面に埋まった状態で置けば永久に嵌まる。水は障害ではない。船体は水に「乗る」のではなく
        // 「浸かる」。
        if (!vehicle.hasRoomHere(PLACING_MARGIN)) {
            return InteractionResult.FAIL;
        }

        serverLevel.addFreshEntity(vehicle);
        serverLevel.gameEvent(player, GameEvent.ENTITY_PLACE, event);
        held.consume(1, player);

        return InteractionResult.sidedSuccess(false);
    }

    /**
     * そのブロック上の水柱の水面高さ。水が無ければ {@link Double#NaN}。水柱の頂上まで登るので、艦は水中
     * ではなく水面に着く。
     */
    private static double waterSurfaceAt(Level level, BlockPos at) {
        if (!level.getFluidState(at).is(FluidTags.WATER)) {
            return Double.NaN;
        }

        BlockPos.MutableBlockPos cursor = at.mutable();

        while (level.getFluidState(cursor.relative(Direction.UP)).is(FluidTags.WATER)) {
            cursor.move(Direction.UP);
        }

        FluidState top = level.getFluidState(cursor);

        return cursor.getY() + top.getHeight(level, cursor);
    }
}
