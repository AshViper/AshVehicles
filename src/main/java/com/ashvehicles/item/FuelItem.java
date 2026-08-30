package com.ashvehicles.item;

import java.util.List;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.vehicle.VehicleChassis;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 燃料缶。持って機械を右クリックすれば、中身がそのタンクへ移る。
 *
 * <p>1個で {@code refuel_rate}——機械のファイルが決める量——を入れる。入る量は機械側が決めるので、大型の
 * 艦は戦闘機より多くの缶を要求する。それが正しい。タンクの大きさは機械の性質であって、缶の性質ではない。
 *
 * <p><b>溢れる分は受け取らない。</b> 満タンの機体に缶を使っても缶は減らない。半分だけ入る余地があれば、
 * 缶は消費されて半分だけ入る——燃料は缶に残らない。缶は「燃料そのもの」ではなく「1回の給油」だからだ。
 * 満タンへの給油を無効にしているのは、それを許すと「うっかりクリックで缶を1個失う」が起き続けるからで、
 * それは仕組みではなく事故になる。
 *
 * <p>クリエイティブでは減らない。バニラのバケツと同じ扱いで、建築中の者に兵站を課す理由は無い。
 */
public class FuelItem extends Item {
    public FuelItem(Properties properties) {
        super(properties);
    }

    /**
     * 機械へ1缶注ぐ。実際に入ったかどうかを返す。呼ぶのはサーバー側の {@code interact} から。
     *
     * @return 給油できたか。満タンだったり燃料を持たない機械だったりすれば false で、缶は減らない
     */
    public static boolean refuel(VehicleEntityBase vehicle, Player player, ItemStack stack) {
        VehicleChassis.Fuel setup = vehicle.fuelSetup();

        if (!setup.fitted() || setup.refuelRate() <= 0.0F) {
            return false;
        }

        float went = vehicle.addFuel(setup.refuelRate());

        // 本体タンクが満タンなら増槽へ。缶を持って機体を撫でた者が求めているのは「この機体を満たすこと」で
        // あって「本体タンクだけを満たすこと」ではない。増槽も空なら、これが唯一の満たし方でもある。
        if (went <= 0.0F && vehicle instanceof AircraftEntity aircraft) {
            went = aircraft.fillTanks(Math.round(setup.refuelRate()));
        }

        if (went <= 0.0F) {
            return false;
        }

        // バニラのバケツと同じ扱い。建築中の者に兵站を課す理由は無い。
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 0.8F, 1.1F);

        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.ashvehicles.fuel_can").withStyle(ChatFormatting.GRAY));
    }
}
