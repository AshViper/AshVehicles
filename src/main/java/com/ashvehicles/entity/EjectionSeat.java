package com.ashvehicles.entity;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 射出座席。降機キーの長押しで火を噴く。
 *
 * <p>飛んでいる機体から降りること自体は前からできた。座席が加えるのは3つ——押し出す速度、機体から離れる
 * 距離、そして傘だ。前の2つは、撃たれた機体・地面へ向かっている機体・既に燃えている機体から離れるための
 * もので、最後の1つは降りてくる間に自分がどこへ降りるのかを見るためのもの。
 *
 * <p>押し出す方向は機体の上方向であって真上ではない。背面で引けば地面へ撃ち出される。低空で裏返った機体
 * が取り返しの付かないものであるのは実機でもそうで、ここでもそうしておく。
 *
 * <p>落下ダメージには触れない。{@link CrewSafety} が既に、機体が手放した者の最初の着地を無料にしている。
 * 傘があるのは死なないためではなく、4秒で落ちてくるものを30秒にするためだ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class EjectionSeat {
    /** 座席が乗員を機体から離す距離（ブロック）。次のtickに機体が拾い直さない程度。 */
    private static final double CLEARANCE = 2.5;

    /** 傘の効果時間（tick）。着地すれば早く切れる。上限であって、これだけ浮くわけではない。 */
    private static final int CANOPY_TICKS = 2400;

    /**
     * 傘が開くのを待っている者と、開くまでの残り時間。開いた後は {@link #OPENED}。
     *
     * <p>弱参照。降りきる前にログアウトした者・死んだ者は自分のエントリを連れて消える。
     */
    private static final Map<Player, int[]> DESCENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** 傘が開いた後の状態。以後は着地を待つだけ。 */
    private static final int OPENED = -1;

    private EjectionSeat() {
    }

    /** この機体が射出座席を積んでいるか。画面がハンドルを出すかどうかもこれで決める。 */
    public static boolean has(AircraftEntity aircraft) {
        return aircraft.getStats().airframe().ejection().isPresent();
    }

    /**
     * ハンドルを引く。乗っている本人だけが飛び出す——他の席のハンドルは他の席の乗員の物だ。
     *
     * @return 実際に射出したか。座席の無い機体では false
     */
    public static boolean pull(AircraftEntity aircraft, Player crew) {
        Optional<AircraftDefinition.Ejection> fitted = aircraft.getStats().airframe().ejection();

        if (fitted.isEmpty() || crew.getVehicle() != aircraft || !(crew.level() instanceof ServerLevel level)) {
            return false;
        }

        AircraftDefinition.Ejection seat = fitted.get();
        Vec3 up = Attitude.toWorld(aircraft.getAttitude(), new Vec3(0.0, 1.0, 0.0));
        Vec3 from = crew.position();
        // 機体が今出している速度に座席の分を足す。飛び出した者は機体の運動を持って出る
        Vec3 thrown = aircraft.getVelocity().add(up.scale(seat.speed()));

        crew.stopRiding();

        Vec3 to = from.add(up.scale(CLEARANCE));

        crew.setPos(to.x, to.y, to.z);

        if (crew instanceof ServerPlayer sent) {
            sent.connection.teleport(to.x, to.y, to.z, crew.getYRot(), crew.getXRot());
        }

        crew.setDeltaMovement(thrown);
        // これが立っていると、サーバーはこのtickの終わりに速度そのものをクライアントへ送る。上の
        // テレポートは絶対座標なのでクライアント側の速度を消すが、その後に届くこれが上書きする
        crew.hurtMarked = true;
        crew.resetFallDistance();

        // 傘の無い座席（canopy 0）は追わない。開く物が無いのだから待つ物も無い
        if (seat.canopy() > 0) {
            DESCENDING.put(crew, new int[] {seat.canopy()});
        }

        level.playSound(null, from.x, from.y, from.z, SoundEvents.FIREWORK_ROCKET_LAUNCH,
                SoundSource.PLAYERS, 3.0F, 0.5F);
        Effects.send(level, from, ModParticles.MOTOR_SMOKE.get().of(Effects.SOOT, 1.2F), 24, 0.6, 0.4);
        Effects.send(level, from, ModParticles.SPARK.get().of(Effects.EMBER, 1.0F), 30, 0.5, 0.9);

        return true;
    }

    /**
     * 降下中の乗員。傘を開き、着いたら畳む。
     *
     * <p>効果を着地で切るのは、傘を持ったまま歩き回れてしまわないようにするためだ。40分の低重力は
     * 脱出の続きではなく、別のアイテムの効果になってしまう。
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player crew = event.getEntity();

        if (crew.level().isClientSide) {
            return;
        }

        int[] state = DESCENDING.get(crew);

        if (state == null) {
            return;
        }

        if (state[0] > 0) {
            state[0]--;

            if (state[0] == 0) {
                open(crew);
            }

            return;
        }

        if (state[0] == OPENED && (crew.onGround() || crew.isInWater() || crew.isPassenger())) {
            crew.removeEffect(MobEffects.SLOW_FALLING);
            DESCENDING.remove(crew);
        }
    }

    /** 傘が開く。 */
    private static void open(Player crew) {
        DESCENDING.put(crew, new int[] {OPENED});

        crew.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, CANOPY_TICKS, 0, false, false, true));
        crew.level().playSound(null, crew.getX(), crew.getY(), crew.getZ(), SoundEvents.PHANTOM_FLAP,
                SoundSource.PLAYERS, 1.6F, 0.6F);

        if (crew.level() instanceof ServerLevel level) {
            Effects.send(level, crew.position().add(0.0, 1.5, 0.0),
                    ModParticles.BLAST_SMOKE.get().of(Effects.DUST, 1.6F), 20, 0.8, 0.05);
        }
    }
}
