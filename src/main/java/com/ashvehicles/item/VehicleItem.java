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
 * Places the machine it was registered with onto the clicked block, facing the player.
 *
 * <p>The one thing an aeroplane and a tank do differently here is how they are pointed, and that is
 * a line: both are pointed by an attitude rather than by the pair of angles Minecraft keeps, but a
 * tank is also lying on something and an aeroplane is not. So {@link #point} is the hook and the
 * rest — find the block, face the player, check the room, consume the item — is shared.
 *
 * <p>A ship is the exception, and floats where the others rest. It is put down like a boat: right
 * clicking open water sets it on the surface, and clicking through shallow water sets it on the
 * water rather than on the bottom under it. See {@link #floatsOnWater} and {@link #use}.
 *
 * @param <T> the machine this item places
 */
public abstract class VehicleItem<T extends VehicleEntityBase> extends Item {
    /**
     * How far a box may overlap the world and still count as clear, in blocks. Enough for a wingtip
     * or a track to rest a little way into a slope, which is what they do.
     */
    private static final double PLACING_MARGIN = 0.25;

    private final Supplier<? extends EntityType<? extends T>> type;

    protected VehicleItem(Supplier<? extends EntityType<? extends T>> type, Properties properties) {
        super(properties);
        this.type = type;
    }

    /**
     * The id everything about this machine is found under: its data file, its geometry, its texture,
     * and the picture its own item is drawn as. Taken off the entity it places rather than off the
     * item's own name, which is the same thing today and is not the thing that settles it.
     */
    public ResourceLocation vehicle() {
        return BuiltInRegistries.ENTITY_TYPE.getKey(this.type.get());
    }

    /**
     * Points a freshly placed machine along the heading it was put down on.
     *
     * <p>Needed because {@code moveTo} only sets the pair of angles Minecraft keeps, and everything
     * in the mod is pointed by an attitude instead. Without it a machine sits facing due south
     * whichever way it was put down — and its boxes, which are placed from the attitude, sit facing
     * south with it.
     */
    protected abstract void point(T vehicle, float yaw);

    /**
     * Whether this machine is set down on water rather than on the ground: a ship, and nothing else.
     * A machine that floats is put down like a boat rather than on the block that was clicked.
     */
    protected boolean floatsOnWater() {
        return false;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        double y = pos.getY();

        // A ship clicked in the shallows — through the water onto the bottom — is set on the surface
        // over that bottom rather than on it, the same as one dropped straight onto open water. In
        // deep water the block ray finds no bottom at all and this never fires; {@link #use} does.
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
     * Puts a ship down on open water, where there is no block under the cursor for {@link #useOn} to
     * fasten onto. The ray is cast the way a boat's is — through the air and stopped by the water's
     * own surface — so the vessel lands on the sea rather than passing through it.
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

        // Only the water is a place to launch from. A block hit here is a dry one the player is
        // looking at over the top of a stretch of water, and putting a ship down on it is not what
        // was meant; that is a job for useOn and the ground.
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
     * Creates the machine, points it, checks the whole of its shape is clear, and — if it is — adds
     * it and takes the item. Shared by the two ways in: the block a tank is set on, and the water a
     * ship is launched into.
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

        // The whole shape has to be clear, not just the middle of it. The boxes stop against the
        // world now, so one set down with a wingtip or a track inside a hillside would be wedged
        // there for good. Water is no obstacle — a hull sits in it, not against it.
        if (!vehicle.hasRoomHere(PLACING_MARGIN)) {
            return InteractionResult.FAIL;
        }

        serverLevel.addFreshEntity(vehicle);
        serverLevel.gameEvent(player, GameEvent.ENTITY_PLACE, event);
        held.consume(1, player);

        return InteractionResult.sidedSuccess(false);
    }

    /**
     * The height of the surface of the water column standing on a block, or {@link Double#NaN} if
     * that block holds no water. Climbs to the top of the column so a ship lands on the water rather
     * than inside it.
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
