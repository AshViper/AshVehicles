package com.ashvehicles.client.sound;

import java.util.Objects;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.weapon.TargetLock;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * シーカーが出す音。乗員が計器を見ずに「撃てる」と知る手段だ。
 *
 * <p>{@link WarningSounds} は同じ午後のもう半分であり、どちらがどちらかを述べておく価値がある。あちらは座席後ろの
 * 受信機で、他人のシーカーが<em>こちら</em>を捉えたという悪い知らせ。こちらは自分のレール上のミサイルで、射撃機会
 * の到来を告げる——シーカーが取り組んでいる間はうなり、捉えれば定常音になる。
 *
 * <p><b>聞くべき物が3つあるのは、ロックが1つではなく3つの瞬間だからだ。</b>シーカーが何かを捉えて取り組み始める。
 * ロックが閉じる——乗員がその間ボアサイトを保ち続けねばならない数秒だ。そして捉えるか、目標が円錐から出て最初から
 * やり直しになるか。だから、ロックが閉じるにつれ上がる捜索音、ロックが続く間鳴る定常音、そして得ていたロックが外れた
 * ときの短い下降音がある。
 *
 * <p>真ん中の1つは HUD にはできない仕事だ。計器面の枠はロックが閉じるにつれ既に締まっている——だが目標を追っている
 * パイロットが見ているのは枠ではなく目標であり、上がっていく音は目を逸らさせずに同じことを伝える。上昇を担う
 * {@link SeekerSoundInstance} 参照。
 *
 * <p><b>どれだけ上がるかは、誰の録音が鳴っているかによる。</b>それが下の仕組みの全てだ。MOD 自身の捜索音はこの用途
 * のために、聞かせたい音程で切ってある。だから切られたまま鳴らし、上昇は上下に手のひら幅程度——ロックが閉じるのが
 * 聞き取れて、録音を別物に変えてしまうほどではない量だ。借り物——別段階の音やゲーム自身の音——はまったく別の用途で
 * 切られた物なので、大きく下げてから全域を上がる。台無しにする物が何も無く、区別すべき事だけがあるからだ。
 *
 * <p><b>2種類の機体に、1つの計器。</b>機体はシーカーをパイロンと同居させ、発射機は発射筒と同居させるが、両者は
 * クライアントへの報告方法がかなり違う——前者は {@link TargetLock} を丸ごと送り、後者は目標と進行度を送る。どちらの
 * 違いも耳には届かないので、ここでは両方を1つの {@link Readout} へ読み込み、以下は同じように動く。
 *
 * <p>乗員だけでなく搭乗者全員に聞こえる。計器が既にそうしているからだ。計器面でシーカーの枠が締まるのを見ながら
 * 何も聞こえない搭乗者の方が、逆より奇妙だろう。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class SeekerSounds {
    /** 聞いている者に分かる範囲でのシーカーの状態。 */
    public enum Stage {
        /** ロック可能な物を積んでいるが何も見ていない。無音。 */
        IDLE,
        /** 何かを捉えて取り組み中。ロックが閉じるにつれ上がるうなり。 */
        SEEK,
        /** 捉えた。続く間だけ鳴る定常音。 */
        LOCK
    }

    /**
     * プレイヤーが搭乗している機体で、シーカーがこのtickに伝えること。
     *
     * @param vehicle どの機体か。エンティティ番号。別の機体へ乗り換えたら最初からやり直すため
     * @param weapon このシーカーを持つ兵装。発射機の単一弾なら null
     * @param stage 現在の状態
     * @param progress ロックの進行度。0〜1
     */
    public record Readout(int vehicle, @Nullable ResourceLocation weapon, Stage stage, float progress) {
    }

    /** トーンとその扱い方。開始音程、上昇先、音量。 */
    private record Voice(SoundEvent sound, float base, float climb, float volume) {
    }

    /**
     * 切られたままの捜索音と、その僅かな変化幅。ロック全域で0.16は3半音未満——締まっていくのがはっきり聞き取れ、かつ
     * 録音したうなりが誤った速度で再生されているように聞こえるにはまるで足りない量だ。
     */
    private static final float SEEK_PITCH = 0.92F;
    private static final float SEEK_CLIMB = 0.16F;
    /** そして他所から借りたトーン。台無しにする物が無いので、低く始めて全域を上がる。 */
    private static final float BORROWED_SEEK_PITCH = 0.7F;
    private static final float BORROWED_SEEK_CLIMB = 0.5F;

    /** 切られたままのロック音と、良い知らせだと示すため上げた借り物。 */
    private static final float LOCK_PITCH = 1.0F;
    private static final float BORROWED_LOCK_PITCH = 1.3F;
    /** ロックが外れた音。誰も録音していないためゲーム自身の音が代役を務める場合。 */
    private static final float LOST_PITCH = 0.7F;
    /** ロック音もロック喪失音も、どこへも上がらない。 */
    private static final float NO_CLIMB = 0.0F;

    /** エンジンより、警戒受信機より小さく。これは悪い知らせではなく良い知らせだ。 */
    private static final float SEEK_VOLUME = 0.4F;
    private static final float LOCK_VOLUME = 0.55F;
    private static final float LOST_VOLUME = 0.55F;

    /**
     * パックがより良い物を持つまでの間、シーカーの作動音に最も近いゲーム内の音。うなりには低い持続音、ロックには澄んだ
     * 音。受信機のフォールバック音とは意図的に別にしてある——この2つの計器が決してやってはならないのは、互いに似た音に
     * なることだ。
     */
    private static final ResourceLocation SEARCH_FALLBACK =
            SoundEvents.NOTE_BLOCK_DIDGERIDOO.value().getLocation();
    private static final ResourceLocation LOCK_FALLBACK = SoundEvents.NOTE_BLOCK_PLING.value().getLocation();

    /** シーカーが最後に伝えていた内容。ロックの喪失は鳴らし、保持中は鳴らさないため。 */
    @Nullable
    private static Readout sounding;
    /** シーカーに伝えることがあれば、現在鳴っているトーン。 */
    @Nullable
    private static SeekerSoundInstance tone;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.isPaused()) {
            return;
        }

        Readout now = readout();

        // 機体から降りたか、シーカーを持たない兵装へ切り替えた。伝えることも、伝える対象も無い。鳴っているトーンも
        // 自分で同じことに気付いて止まる。
        if (now == null) {
            sounding = null;

            return;
        }

        Readout was = sounding;
        sounding = now;

        // 得ていたロックが外れた。音1つ分の価値がある。撃てるか撃てないかの違いであり、乗員が枠を見るのをやめた
        // まさにその瞬間に起きるからだ。同じ機体の同じシーカーに対してのみ数えるので、別の兵装を選ぶことや降車は
        // ロック喪失ではなく、あるべき沈黙になる。
        if (was != null && was.stage() == Stage.LOCK && now.stage() != Stage.LOCK
                && was.vehicle() == now.vehicle() && Objects.equals(was.weapon(), now.weapon())) {
            chirp(minecraft, now.weapon());
        }

        if (now.stage() == Stage.IDLE) {
            return;
        }

        // 変化時に開始し、サウンドエンジンが落とした場合は再開する——チャンネル満杯、リソースリロード、あるいは
        // その後に戻された音量スライダーのため。
        SoundManager sounds = minecraft.getSoundManager();

        if (tone == null || !tone.matches(now) || tone.isStopped() || !sounds.isActive(tone)) {
            Voice voice = voice(sounds, now);

            tone = new SeekerSoundInstance(voice.sound(), now, voice.base(), voice.climb(), voice.volume());
            sounds.play(tone);
        }
    }

    /**
     * プレイヤーが搭乗している機体のシーカーがこのtickに伝えること。目の前にシーカーが無ければ null。
     *
     * <p>null と {@link Stage#IDLE} は別の答えであり、その違いが重要だ。IDLE は「武装済みで何も見ていない」状態で、
     * ロックが外れた後に残る物。null は「シーカーが無い」状態で、機関砲を選んだ後に残る物。ロック喪失にあたるのは
     * 前者だけだ。
     */
    @Nullable
    public static Readout readout() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }

        Entity riding = minecraft.player.getVehicle();

        if (riding instanceof AircraftEntity aircraft) {
            return aboard(aircraft);
        }

        if (riding instanceof GroundVehicleEntity vehicle) {
            return aboard(vehicle);
        }

        return null;
    }

    /**
     * 機体のシーカー。選択中の兵装が持つ物で、丸ごと送られる。クライアントは目標・捕捉済みか・どれだけ保持している
     * かを持つので、ロックの進行度は計器が求めるのとまったく同じようにここで求められる。
     */
    @Nullable
    private static Readout aboard(AircraftEntity aircraft) {
        WeaponMounts weapons = aircraft.getWeapons();
        WeaponDefinition weapon = weapons.selectedWeapon();

        if (weapon == null || weapon.guidance().isEmpty()) {
            return null;
        }

        TargetLock lock = weapons.lock();
        Entity target = lock.target();

        if (target == null || target.isRemoved()) {
            return new Readout(aircraft.getId(), weapons.selected(), Stage.IDLE, 0.0F);
        }

        return new Readout(aircraft.getId(), weapons.selected(),
                lock.isLocked() ? Stage.LOCK : Stage.SEEK, lock.progress(weapon.guidance().get()));
    }

    /**
     * 発射機のシーカー。発射筒が保持する1発分だ。ロック自体はサーバー上にあり、他の側は同期データから目標と進行度を
     * 読む。照準が描かれる元と同じ2つの値だ。
     *
     * <p>機関砲を選択中は無音。照準の挙動と同じで、主兵装を据えている乗員には、シーカーが何を捉えていようとミサイル
     * 射撃は提示されない。
     */
    @Nullable
    private static Readout aboard(GroundVehicleEntity vehicle) {
        if (!vehicle.isMissileMode()) {
            return null;
        }

        ResourceLocation missileId = vehicle.getStats().launcher().missile().orElse(null);
        WeaponDefinition missile = missileId == null ? null : Definitions.weapon(missileId);

        if (missile == null || missile.guidance().isEmpty()) {
            return null;
        }

        Entity target = vehicle.getSeekerTarget();

        if (target == null || target.isRemoved()) {
            return new Readout(vehicle.getId(), missileId, Stage.IDLE, 0.0F);
        }

        return new Readout(vehicle.getId(), missileId,
                vehicle.isSeekerLocked() ? Stage.LOCK : Stage.SEEK, vehicle.getSeekerProgress());
    }

    /**
     * 短い音を1つ鳴らして忘れる。ロックが外れたことを示す音だ。
     *
     * <p><b>これは何からも借りない。</b>他の2つのトーンはループ——MOD 自身の物で4秒、リソースパックの物なら20秒に
     * なりうる——であり、ループを1回鳴らしても短い音にはならない。下でまた始まったうなりの上に同じ持続音が重なるだけ
     * だ。だからロック喪失用に切られた録音があるか、無ければゲーム自身の音を使い、ロック音は本来の場所に残す。ループ
     * 用に切っていない録音をループさせない {@link ModSounds} と同じ理屈を逆から見た物だ。
     */
    private static void chirp(Minecraft minecraft, @Nullable ResourceLocation weapon) {
        SoundManager sounds = minecraft.getSoundManager();
        ResourceLocation playing = ModSounds.firstPresent(sounds, cutFor(Stage.IDLE, weapon));
        SoundEvent recording = SoundEvent.createVariableRangeEvent(
                playing == null ? LOCK_FALLBACK : playing);

        // ロック喪失用に切られた録音は既に正しい音程だ。ゲームの音はそうではないので下げる。伝えるべきは「何かが
        // 失われた」ことであり、下がる音がそれを言う。
        sounds.play(SimpleSoundInstance.forUI(recording, playing == null ? LOST_PITCH : 1.0F, LOST_VOLUME));
    }

    /**
     * この段階で鳴らすトーン。専用録音があればそれ、無ければ最も近い物、それも無ければゲーム自身の物。そしていずれの
     * 場合も、答えた物をどう扱うか。
     */
    private static Voice voice(SoundManager sounds, Readout readout) {
        Stage stage = readout.stage();
        ResourceLocation own = ModSounds.firstPresent(sounds, cutFor(stage, readout.weapon()));

        // この段階用に切られた物。切られた音程のまま鳴らす。
        if (own != null) {
            return new Voice(SoundEvent.createVariableRangeEvent(own),
                    stage == Stage.SEEK ? SEEK_PITCH : LOCK_PITCH,
                    stage == Stage.SEEK ? SEEK_CLIMB : NO_CLIMB,
                    stage == Stage.SEEK ? SEEK_VOLUME : LOCK_VOLUME);
        }

        // 別段階用に切られた物か、そもそもシーカー用でない物。音程をずらすので、1つの録音が両方の仕事をしていても
        // 2つの段階を耳で区別できる。
        ResourceLocation borrowed = ModSounds.firstPresent(sounds, cutFor(other(stage), readout.weapon()));
        SoundEvent recording = SoundEvent.createVariableRangeEvent(borrowed != null
                ? borrowed
                : stage == Stage.SEEK ? SEARCH_FALLBACK : LOCK_FALLBACK);

        return new Voice(recording,
                stage == Stage.SEEK ? BORROWED_SEEK_PITCH : BORROWED_LOCK_PITCH,
                stage == Stage.SEEK ? BORROWED_SEEK_CLIMB : NO_CLIMB,
                stage == Stage.SEEK ? SEEK_VOLUME : LOCK_VOLUME);
    }

    /** 2つのトーンのうちもう一方。専用録音を持たない段階が借りる相手。 */
    private static Stage other(Stage stage) {
        return stage == Stage.SEEK ? Stage.LOCK : Stage.SEEK;
    }

    /**
     * この段階用に切られたと見なせる物すべて。まずこの兵装専用の {@code weapon.<weapon>.<role>}、次に MOD の
     * {@code seeker.<role>}。
     *
     * <p>これのおかげで、一式を録音せずとも1つの兵装だけ他と違ううなりにできる——赤外線シーカーとレーダーシーカーは
     * 同じ音ではないし、片方に {@code weapon.<weapon>.seek} だけを与えれば、他の伝達内容は MOD 自身の物を借りて済む。
     */
    private static ResourceLocation[] cutFor(Stage stage, @Nullable ResourceLocation weapon) {
        String role = switch (stage) {
            case SEEK -> ModSounds.SEEK_ROLE;
            case LOCK -> ModSounds.LOCK_ROLE;
            case IDLE -> ModSounds.LOST_ROLE;
        };
        ResourceLocation mod = switch (stage) {
            case SEEK -> ModSounds.SEEKER_SEARCH;
            case LOCK -> ModSounds.SEEKER_LOCK;
            case IDLE -> ModSounds.SEEKER_LOST;
        };

        if (weapon == null) {
            return new ResourceLocation[] {mod};
        }

        return new ResourceLocation[] {ModSounds.named(weapon, ModSounds.WEAPON_PREFIX, role), mod};
    }

    private SeekerSounds() {
    }
}
