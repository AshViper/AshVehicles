package com.ashvehicles.weapon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.match.Deathmatch;
import com.ashvehicles.network.BlastSoundPayload;
import com.ashvehicles.particle.Effects;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 核の炸裂。閃光とキノコ雲、音速で広がる打撃の波、火、そして試合の決着。
 *
 * <p><b>規模を上げて核にしない。</b> {@code projectile.explosion} を数百にすれば、バニラの爆発が数百ブロックの光線を
 * 引き、ロード済みの外では地形を生成させ（[[explosions-generate-chunks]]）、中では山を1つ削り、爆心から1ブロック
 * 外れただけで打撃が目減りする。だから核は打撃・火・押し・穴・見た目を別々の数字で持つ
 * （{@link WeaponDefinition.Nuclear}）。見た目は {@link Effects#NUCLEAR} を超えた規模の起爆ひとそろいが既に
 * 持っている——火球の浮上、凝結の殻、ベースサージ、20秒のキノコ雲、灼熱した空。
 *
 * <p><b>打撃は波で渡す。</b> 起爆の瞬間に範囲の全員へ一度に配ると、600ブロック先の機体が閃光と同じ tick に砕ける。
 * 見える環（{@code client/particle/ShockwaveParticle} の核の版）も轟音（{@code client/sound/Air.SPEED}）も音速で
 * 来るので、打撃も音速で来させる——環が自分を通り過ぎた瞬間に壊れる。巻き込む相手は起爆の瞬間に1回だけ探し
 * （[[entity-queries-cost-box-size]]、毎 tick 半径600の箱を歩かない）、近い順に並べて、前面が届いた者から打つ。
 *
 * <p><b>地形の遮蔽は測らない。</b> 光線を引けばロード済みの外で地形を生成させる。{@code Effects.shockwave} と同じ
 * 割り切りで、核の前では山の陰も陰ではない。穴だけは {@code crater} の規模でバニラの爆発に任せ、それは
 * {@link Effects#blast} がロード済みかと試合中かを確かめてから開ける。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class NuclearBlast {
    /** 環を地面からどれだけ浮かせるか。{@code BlastStageParticle.WAVE_LIFT} と同じ理由で同じ値。 */
    private static final double RING_LIFT = 0.35;

    /** 巻き込む相手を探す箱の余白（ブロック）。大きな機体は原点より箱の方が外へ出ている。 */
    private static final double MARGIN = 64.0;

    /** 火の付いている秒数。 */
    private static final float BURNS_FOR = 10.0F;

    /** 突き飛ばしに足す上向きの割合。地面に沿って押すだけでは、押された者は地面に擦り付けられるだけになる。 */
    private static final double HEAVE = 0.4;

    /**
     * 投下の警報の音量と音程。
     *
     * <p>バニラで遠くから長く鳴って「何かが来る」と言う音は略奪隊の角笛しか無い。音程を下げて空襲警報の低い唸りへ
     * 寄せる。本人の位置で鳴らす（{@code playNotifySound}）ので、到達距離は音量ではなく送る相手で決まる。
     */
    private static final float SIREN_VOLUME = 1.0F;
    private static final float SIREN_PITCH = 0.6F;

    private static final List<NuclearBlast> LIVE = new ArrayList<>();

    private final ServerLevel level;
    private final Vec3 at;
    private final DamageSource source;
    private final WeaponDefinition.Nuclear nuke;
    /** 巻き込む相手と、起爆の瞬間の距離。近い順。 */
    private final List<Caught> caught;
    /** 波の前面が爆心からどこまで出たか（ブロック）。 */
    private double front;
    /** {@link #caught} の、まだ打っていない最初の位置。 */
    private int next;

    private record Caught(Entity entity, double distance) {
    }

    private NuclearBlast(ServerLevel level, Vec3 at, DamageSource source, WeaponDefinition.Nuclear nuke) {
        this.level = level;
        this.at = at;
        this.source = source;
        this.nuke = nuke;
        this.caught = this.gather();
    }

    /**
     * 核が放された。届く者全員に警報を出し、試合中なら陣営を弾に控える（{@link Deathmatch#armNuclear}）。
     *
     * <p>試合の外でも出す。落ちてくるまでの10秒ほどは、地上の誰にとっても逃げるための時間だ。
     */
    public static void released(ServerLevel level, AircraftEntity aircraft, VehicleProjectile bomb) {
        double within = BlastSoundPayload.carry(Effects.LARGEST);
        Component warning = Component.translatable("message.ashvehicles.nuclear.released")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(aircraft) > within * within) {
                continue;
            }

            player.displayClientMessage(warning, true);
            player.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, SIREN_VOLUME, SIREN_PITCH);
        }

        Deathmatch.armNuclear(aircraft, bomb);
    }

    /**
     * 着弾点で核が起爆した。弾の {@code burst} の代わりに、ここが炸裂の全部を出す。
     *
     * <p>順序に意味がある。<b>試合の決着が先</b>——波が戦場の機体を壊すたびにチケットが引かれ、別の決着が先に付くのを
     * 防ぐ（{@link Deathmatch#nuclearDetonated}）。<b>穴を開けてよいかはその前に訊く</b>——決着の後に訊くと、試合が
     * 終わったことになっているので、核で決着した会場だけが削れる。
     *
     * @param source 弾自身の打撃の出所。波の打撃は全員でこれを使い回す（機体は同じ tick の同じ打撃を箱の数だけ数えない）
     * @param colour 火球の色。弾の曳光色
     */
    public static void detonate(ServerLevel level, VehicleProjectile bomb, DamageSource source, Vec3 at,
            WeaponDefinition.Nuclear nuke, int colour) {
        boolean keepGround = Deathmatch.protectsTerrain(level);

        Deathmatch.nuclearDetonated(level, bomb);

        Effects.detonate(level, at, Mth.clamp(nuke.show(), Effects.NUCLEAR, Effects.LARGEST), colour);
        Effects.send(level, at.add(0.0, RING_LIFT, 0.0),
                ModParticles.NUCLEAR_WAVE.get().of(Effects.DUST, (float) nuke.reach()), 1, 0.0, 0.0);

        if (nuke.crater() > 0.0F && !keepGround) {
            Effects.blast(level, bomb, at, nuke.crater(), colour);
        }

        LIVE.add(new NuclearBlast(level, at, source, nuke));
    }

    /**
     * 波が届き得る者を1回だけ集める。機体の箱は親へまとめ、近い順に並べる。
     *
     * <p>生き物と機体だけ。落ちているアイテムや弾は、打撃を受ける物ではない。
     */
    private List<Caught> gather() {
        double reach = this.nuke.reach();
        AABB box = new AABB(this.at, this.at).inflate(reach + MARGIN);
        Map<Entity, Double> nearest = new IdentityHashMap<>();

        for (Entity entity : this.level.getEntities((Entity) null, box,
                candidate -> candidate instanceof LivingEntity || candidate instanceof VehicleEntityBase
                        || candidate instanceof VehiclePart)) {
            Entity subject = entity instanceof VehiclePart part && part.getParent() != null
                    ? part.getParent() : entity;

            if (!(subject instanceof LivingEntity) && !(subject instanceof VehicleEntityBase)) {
                continue;
            }

            double distance = this.distanceOf(subject);

            if (distance < reach) {
                nearest.putIfAbsent(subject, distance);
            }
        }

        List<Caught> found = new ArrayList<>();

        nearest.forEach((entity, distance) -> found.add(new Caught(entity, distance)));
        found.sort(Comparator.comparingDouble(Caught::distance));

        return found;
    }

    /** 機体は一番近い箱の表面まで（{@link VehicleEntityBase#distanceToShape}）、それ以外は足元まで。 */
    private double distanceOf(Entity entity) {
        return entity instanceof VehicleEntityBase machine
                ? machine.distanceToShape(this.at)
                : entity.position().distanceTo(this.at);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (LIVE.isEmpty()) {
            return;
        }

        Iterator<NuclearBlast> each = LIVE.iterator();

        while (each.hasNext()) {
            if (each.next().advance(event.getServer())) {
                each.remove();
            }
        }
    }

    /**
     * 前面を音速ぶん進め、届いた者を打つ。打ち終わったら true。
     *
     * <p>届いたかは起爆の瞬間の距離で決め、打撃はその瞬間の距離で測る。前面より遅く逃げている者は、逃げた分だけ軽く
     * 済む——音速より速く逃げられる物は無いので、それで波に追い付かれないことは無い。
     */
    private boolean advance(MinecraftServer server) {
        // ワールドを閉じた後の、前のサーバーの波。
        if (this.level.getServer() != server) {
            return true;
        }

        this.front += WeaponDefinition.Nuclear.WAVE_SPEED;

        while (this.next < this.caught.size() && this.caught.get(this.next).distance() <= this.front) {
            this.strike(this.caught.get(this.next).entity());
            this.next++;
        }

        return this.next >= this.caught.size();
    }

    /** 1人（1機）を打つ。火を付け、打撃を渡し、生き残った生き物を外へ突き飛ばす。 */
    private void strike(Entity entity) {
        if (entity.isRemoved() || entity.level() != this.level) {
            return;
        }

        double distance = this.distanceOf(entity);
        double amount = this.nuke.damageAt(distance);

        if (amount <= 0.0) {
            return;
        }

        if (entity instanceof LivingEntity living && distance <= this.nuke.fire()) {
            living.igniteForSeconds(BURNS_FOR);
        }

        entity.hurt(this.source, (float) amount);

        // 機体は押さない。操縦中の機体の速度はパイロットのクライアントが持っていて、サーバーが足した分は巻き戻される
        // （[[server-cannot-see-piloted-aircraft-velocity]]）。
        if (entity instanceof LivingEntity living && living.isAlive()) {
            this.push(living, 1.0 - Math.min(distance / this.nuke.reach(), 1.0));
        }
    }

    private void push(LivingEntity living, double strength) {
        Vec3 away = living.position().subtract(this.at);
        Vec3 along = away.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 1.0, 0.0) : away.normalize();
        double shove = this.nuke.push() * strength
                * (1.0 - living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE));

        if (shove <= 0.0) {
            return;
        }

        living.setDeltaMovement(living.getDeltaMovement()
                .add(along.x * shove, along.y * shove + shove * HEAVE, along.z * shove));
        // サーバーで足した速度を、次の tick の終わりに本人のクライアントへ送らせる。
        living.hurtMarked = true;
    }
}
