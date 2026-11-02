package com.ashvehicles.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.network.EjectLaunchPayload;
import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModEntities;
import com.ashvehicles.registry.ModParticles;
import com.ashvehicles.vehicle.Attitude;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 射出座席。降機キーの長押しで火を噴く。
 *
 * <p>実機と同じ順に起きる。
 * <ol>
 * <li><b>連動射出。</b> 誰か1人が引けば、乗っている全員が {@link #CREW_INTERVAL} ずつずれて飛び出す。
 *     引いた本人が最初。同時に出すと座席同士がぶつかる、というのが実機でずらす理由。
 * <li><b>カタパルトとロケット。</b> 座席の {@code speed} の半分を一撃で与え、残りの1.5倍を
 *     {@link #ROCKET_TICKS} かけて足す。一撃だけだった頃は頂点が12ブロックで、機体の速度をそのまま持って
 *     出るため、乗員は機体から15ブロックも離れなかった。
 * <li><b>自由落下。</b> 人は体を広げた分の抗力（速度の2乗）を受けるので、機体の速度はすぐに抜け、機体は
 *     先へ飛び去る。座席は燃え尽きると離れて落ちる（{@link EjectedSeatEntity}）。
 * <li><b>傘。</b> 地面から {@link #OPEN_HEIGHT} まで降りてから開き、1秒ほどかけて膨らみ、毎秒6ブロック
 *     ほどで降りる。移動キーで少し流れる向きを選べる。
 * </ol>
 *
 * <p>押し出す方向は機体の上方向であって真上ではない。背面で引けば地面へ撃ち出される。
 *
 * <p>プレイヤーの移動はクライアントが回すので、推力と抗力はクライアントで掛ける。推力の向きは
 * {@link EjectLaunchPayload} で本人へ送り、開いた傘の印は重力属性の値0の修飾子で、属性は追跡している全員へ
 * 同期されるので他のプレイヤーにも傘が描ける。サーバーは開く時と畳む時を決め、落下距離を捨てる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class EjectionSeat {
    /** 座席が乗員を機体から離す距離（ブロック）。次のtickに機体が拾い直さない程度。 */
    private static final double CLEARANCE = 2.5;

    /** ロケットが燃える長さ（tick）。座席も同じ長さだけ推す。 */
    public static final int ROCKET_TICKS = 8;

    /** 座席の {@code speed} のうち、カタパルトが一撃で与える割合と、ロケットが足す割合。 */
    private static final double CATAPULT_SHARE = 0.5;
    private static final double ROCKET_SHARE = 1.5;

    /** 乗員どうしの射出の間隔（tick）。 */
    private static final int CREW_INTERVAL = 8;

    /** 体を広げた人の抗力係数。v を 1/(1+c|v|) 倍する。終端速度は毎tick 約2.7ブロック（毎秒55m）。 */
    private static final double BODY_DRAG = 0.012;

    /** 傘が開く地上高（ブロック）。これより高い所では自由落下を続ける。 */
    private static final int OPEN_HEIGHT = 80;

    /** 傘の抗力係数。重力と釣り合うのが毎tick 約0.3ブロック（毎秒6ブロック）。 */
    private static final double CANOPY_DRAG = 0.65;

    /** 傘が膨らみきるまで（tick）。開いた瞬間に満額の抗力を掛けると、高速の落下が1tickで止まってしまう。 */
    public static final int INFLATE_TICKS = 20;

    /** 開いた傘の印。重力に値0を足すだけで、物理には効かない。 */
    private static final ResourceLocation CANOPY = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "canopy");

    /**
     * サーバー: 傘が開くのを待っている者と、開けるようになるまでの残り時間。開いた後は {@link #OPENED}。
     *
     * <p>弱参照。降りきる前にログアウトした者・死んだ者は自分のエントリを連れて消える。
     */
    private static final Map<Player, int[]> DESCENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** サーバー: 連動射出で順番を待っている乗員。 */
    private static final Map<Player, Queued> QUEUED = Collections.synchronizedMap(new WeakHashMap<>());

    /** クライアント: 自分が今どの段階を落ちているか。 */
    private static final Map<Player, Fall> FALLING = Collections.synchronizedMap(new WeakHashMap<>());

    /** クライアントが座席から外れるのを待つ上限（tick）。 */
    private static final int SETTLE_TICKS = 10;

    /** 傘が開いた後の状態。以後は着地を待つだけ。 */
    private static final int OPENED = -1;

    private record Queued(AircraftEntity aircraft, int[] ticks) {
    }

    /** クライアント側の降下。推力の向きと強さ、燃焼の残り、傘が開いてからの tick。 */
    private static final class Fall {
        final Vec3 thrust;
        int burn = ROCKET_TICKS;
        int canopy;
        int age;

        Fall(Vec3 thrust) {
            this.thrust = thrust;
        }
    }

    private EjectionSeat() {
    }

    /** この機体が射出座席を積んでいるか。画面がハンドルを出すかどうかもこれで決める。 */
    public static boolean has(AircraftEntity aircraft) {
        return aircraft.getStats().airframe().ejection().isPresent();
    }

    /**
     * ハンドルを引く。引いた本人がすぐ飛び出し、乗っている他の全員が順に続く。
     *
     * @return 実際に射出したか。座席の無い機体では false
     */
    public static boolean pull(AircraftEntity aircraft, Player crew) {
        if (!eject(aircraft, crew)) {
            return false;
        }

        List<Player> others = new ArrayList<>();

        for (Entity passenger : aircraft.getPassengers()) {
            if (passenger instanceof Player other && !QUEUED.containsKey(other)) {
                others.add(other);
            }
        }

        for (int i = 0; i < others.size(); i++) {
            QUEUED.put(others.get(i), new Queued(aircraft, new int[] {CREW_INTERVAL * (i + 1)}));
        }

        return true;
    }

    /** 1人を撃ち出す。 */
    private static boolean eject(AircraftEntity aircraft, Player crew) {
        Optional<AircraftDefinition.Ejection> fitted = aircraft.getStats().airframe().ejection();

        if (fitted.isEmpty() || crew.getVehicle() != aircraft || !(crew.level() instanceof ServerLevel level)) {
            return false;
        }

        AircraftDefinition.Ejection seat = fitted.get();
        Vec3 up = Attitude.toWorld(aircraft.getAttitude(), new Vec3(0.0, 1.0, 0.0));
        Vec3 from = crew.position();
        // 機体が今出している速度にカタパルトの分を足す。飛び出した者は機体の運動を持って出る
        Vec3 thrown = aircraft.getVelocity().add(up.scale(seat.speed() * CATAPULT_SHARE));
        Vec3 thrust = up.scale(seat.speed() * ROCKET_SHARE / ROCKET_TICKS);

        QUEUED.remove(crew);
        crew.stopRiding();

        Vec3 to = from.add(up.scale(CLEARANCE));

        crew.setPos(to.x, to.y, to.z);

        if (crew instanceof ServerPlayer sent) {
            sent.connection.teleport(to.x, to.y, to.z, crew.getYRot(), crew.getXRot());
            PacketDistributor.sendToPlayer(sent, new EjectLaunchPayload(thrust, 1.0F));
        }

        crew.setDeltaMovement(thrown);
        // これが立っていると、サーバーはこのtickの終わりに速度そのものをクライアントへ送る。上の
        // テレポートは絶対座標なのでクライアント側の速度を消すが、その後に届くこれが上書きする
        crew.hurtMarked = true;
        crew.resetFallDistance();
        fold(crew);

        // 傘の無い座席（canopy 0）は追わない。開く物が無いのだから待つ物も無い
        if (seat.canopy() > 0) {
            DESCENDING.put(crew, new int[] {seat.canopy()});
        }

        EjectedSeatEntity chair = ModEntities.EJECTED_SEAT.get().create(level);

        if (chair != null) {
            chair.moveTo(to.x, to.y - 0.5, to.z, crew.getYRot(), 0.0F);
            chair.setDeltaMovement(thrown);
            chair.setThrust(thrust);
            level.addFreshEntity(chair);
        }

        level.playSound(null, from.x, from.y, from.z, SoundEvents.FIREWORK_ROCKET_LAUNCH,
                SoundSource.PLAYERS, 3.0F, 0.5F);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 0.8F, 1.6F);
        Effects.send(level, from, ModParticles.MOTOR_SMOKE.get().of(Effects.SOOT, 1.2F), 24, 0.6, 0.4);
        Effects.send(level, from, ModParticles.SPARK.get().of(Effects.EMBER, 1.0F), 30, 0.5, 0.9);

        return true;
    }

    /** クライアント: サーバーが自分を撃ち出した。 */
    public static void launched(Player crew, Vec3 thrust, float scale) {
        FALLING.put(crew, new Fall(thrust.scale(scale)));
    }

    /**
     * 降下中の乗員。クライアントでは推力と抗力を掛け、サーバーでは順番待ちを撃ち出し、傘を開き、着いたら
     * 畳む。
     *
     * <p>着地で畳むのは、傘を持ったまま歩き回れてしまわないようにするためだ。
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player crew = event.getEntity();

        if (crew.level().isClientSide) {
            if (crew.isLocalPlayer()) {
                fly(crew);
            }

            return;
        }

        Queued queued = QUEUED.get(crew);

        if (queued != null) {
            if (crew.getVehicle() != queued.aircraft()) {
                QUEUED.remove(crew);
            } else if (--queued.ticks()[0] <= 0) {
                eject(queued.aircraft(), crew);
            }
        }

        int[] state = DESCENDING.get(crew);

        if (state == null) {
            return;
        }

        if (crew.onGround() || crew.isInWater() || crew.isPassenger() || !crew.isAlive()) {
            fold(crew);
            DESCENDING.remove(crew);

            return;
        }

        if (state[0] == OPENED) {
            // 落下距離はクライアントの報告からサーバーが数える。傘の下の数百ブロックは落下ではない
            crew.resetFallDistance();

            return;
        }

        if (state[0] > 0) {
            state[0]--;
        }

        if (state[0] == 0 && heightAboveGround(crew) <= OPEN_HEIGHT) {
            open(crew);
        }
    }

    /** クライアント: ロケットの推力、体の抗力、開いた傘の抗力。 */
    private static void fly(Player crew) {
        Fall fall = FALLING.get(crew);

        if (fall == null) {
            return;
        }

        // この報せは降車の報せより先に着くことがある。座席から外れるまでは数え始めない
        if (++fall.age <= SETTLE_TICKS && crew.isPassenger()) {
            return;
        }

        if (crew.onGround() || crew.isInWater() || crew.isPassenger()) {
            FALLING.remove(crew);

            return;
        }

        Vec3 velocity = crew.getDeltaMovement();

        if (fall.burn > 0) {
            fall.burn--;
            crew.setDeltaMovement(velocity.add(fall.thrust));

            return;
        }

        double drag = BODY_DRAG;

        if (deployed(crew)) {
            double inflation = Math.min(1.0, ++fall.canopy / (double) INFLATE_TICKS);

            drag += CANOPY_DRAG * inflation * inflation;
        }

        crew.setDeltaMovement(velocity.scale(1.0 / (1.0 + drag * velocity.length())));
    }

    /** 地面（葉と水を含む）からの高さ。読めないチャンクの上では開かせる——地面がどこか分からないのだから。 */
    private static double heightAboveGround(Player crew) {
        BlockPos at = crew.blockPosition();

        if (!crew.level().hasChunkAt(at)) {
            return 0.0;
        }

        return crew.getY() - crew.level().getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ());
    }

    /** 傘が開いているか。両側で読める。描画もこれを見る。 */
    public static boolean deployed(Player crew) {
        AttributeInstance gravity = crew.getAttribute(Attributes.GRAVITY);

        return gravity != null && gravity.hasModifier(CANOPY);
    }

    /** 傘が開く。 */
    private static void open(Player crew) {
        DESCENDING.put(crew, new int[] {OPENED});

        AttributeInstance gravity = crew.getAttribute(Attributes.GRAVITY);

        if (gravity != null) {
            gravity.addOrUpdateTransientModifier(
                    new AttributeModifier(CANOPY, 0.0, AttributeModifier.Operation.ADD_VALUE));
        }

        crew.resetFallDistance();
        crew.level().playSound(null, crew.getX(), crew.getY(), crew.getZ(), SoundEvents.PHANTOM_FLAP,
                SoundSource.PLAYERS, 1.6F, 0.6F);

        if (crew.level() instanceof ServerLevel level) {
            Effects.send(level, crew.position().add(0.0, 1.5, 0.0),
                    ModParticles.BLAST_SMOKE.get().of(Effects.DUST, 1.6F), 20, 0.8, 0.05);
        }
    }

    /** 傘を畳む。 */
    private static void fold(Player crew) {
        AttributeInstance gravity = crew.getAttribute(Attributes.GRAVITY);

        if (gravity != null) {
            gravity.removeModifier(CANOPY);
        }
    }
}
