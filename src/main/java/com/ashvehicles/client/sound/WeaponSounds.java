package com.ashvehicles.client.sound;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.weapon.Dispenser;
import com.ashvehicles.weapon.Impact;
import com.ashvehicles.weapon.Ricochet;
import com.ashvehicles.weapon.WeaponDefinition;
import com.ashvehicles.weapon.WeaponMounts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * リソースパックが提供していない、MOD の単発兵装音の代わりに鳴らす物を見つける。
 *
 * <p>サーバーは何を鳴らすかを決めてサウンドイベント名を指す。それしかできないからだ。音はリソースパックにあり、
 * サーバーはそれを見たことがない。だから録音の無い兵装は単に無音になるし、それに気付ける立場にいるのはクライアント
 * だけだ。ここでは、リソースパックが解決できない MOD の {@code weapon.*} イベントを捕まえ、実在する最も近い物を
 * 同じ位置で代わりに鳴らす。
 *
 * <p>「最も近い物」はイベントによる。
 *
 * <ul>
 * <li>兵装名のイベント {@code weapon.<name>} は、その種別の MOD 既定へ落ちる。銃なら {@code weapon.gun}、モーター
 *     付きなら {@code weapon.launch}、爆弾なら {@code weapon.release}。爆弾は発射ではなく投下で、何かが炸裂する音
 *     ではなくラックが跳ね上がる音になる。
 * <li>{@code weapon.release} 自体は、MOD が同梱している {@code weapon.launch} へ落ちる。だから爆弾は、あるべき
 *     打撃音を誰かが録音するまでの間、無音ではなく「何かが機体を離れる音」になる。
 * <li>装甲を滑って跳ねる {@code weapon.<name>.ricochet} は、MOD 共通の {@code weapon.ricochet} へ、次にゲーム自身の
 *     金床音へ落ちる。硬い物が板に当たって逸れる音にゲームで最も近い物だ。
 * <li>跳ねずに MOD の箱へ食い込む {@code weapon.<name>.impact} は、MOD 共通の {@code weapon.impact} へ、次にゲームの
 *     金床設置音へ落ちる——2つある金床音のうち鈍い方だ。命中音の用途は跳弾と耳で区別することだからである。
 *     {@link Impact} 参照。
 * <li>地上員の作業音 {@code weapon.load} は、ゲーム自身の金属衝突音へ落ちる。
 * <li>{@code weapon.gun} と {@code weapon.launch} は何へも落ちない。MOD が両方同梱しており、それらを取り除いた
 *     パックは自らの意思を表明している。
 * </ul>
 *
 * <p>したがって兵装に専用音を与えるにはファイルだけで足りる。{@code sounds.json} に {@code weapon.<name>} と
 * {@code .ogg} を追加すればそちらが使われる。エンジン音も同じ仕組みだ。{@link EngineSounds} 参照。発射の瞬間ではなく
 * 飛翔中の音はここには一切無い。ループはまともに代替できないからだ。{@link ProjectileSounds} 参照。
 *
 * <p>音量とピッチは、置き換えられる音ではなく兵装自身のファイルから取る。そうするほかない。このイベントはサウンド
 * エンジンが録音を引く前に発火するので、インスタンスはまだ音量を答えられず、問えば例外になる。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class WeaponSounds {
    /**
     * 地上員作業のフォールバック。ゲーム自身の鉄のトラップドア音で、重い物が別の物へ固定される音に最も近い。
     */
    private static final ResourceLocation LOAD_FALLBACK =
            ResourceLocation.withDefaultNamespace("block.iron_trapdoor.close");

    /**
     * 対抗手段ディスペンサーのフォールバック。ゲーム自身の花火音で、機体から放り出された物に火が付く音に最も近い。
     */
    private static final ResourceLocation DECOY_FALLBACK =
            ResourceLocation.withDefaultNamespace("entity.firework_rocket.launch");

    /**
     * 跳弾のフォールバック。ゲーム自身の金床落下音で、硬く重い物が板に当たる音に聞こえる唯一の物だ。
     * {@link Ricochet#PITCH} で上げてある。それが鍛冶屋の打撃音を、砲塔を滑る砲弾に変える。
     */
    private static final ResourceLocation RICOCHET_FALLBACK =
            ResourceLocation.withDefaultNamespace("block.anvil.land");

    /**
     * 食い込んだ命中のフォールバック。ゲーム自身の金床設置音で、重い物が金属へ到達しそこに留まる音に最も近い。2つある
     * 金床音のうち意図的に鈍い方を選んでいる。命中音の用途の全てが跳弾と耳で区別できることだからだ。{@link Impact#PITCH}
     * がさらに下げる。
     */
    private static final ResourceLocation IMPACT_FALLBACK =
            ResourceLocation.withDefaultNamespace("block.anvil.place");

    /**
     * 地上員作業の音量。サーバーが要求したのと同じ値を、それを所有する唯一の場所から取る。この時点では音から読み戻せ
     * ないからだ。ピッチは兵装を吊るときの物を使う。パックが持たない音の代役を、取り外しの音と区別する価値は無い。
     */
    private static final WeaponDefinition.SoundSetup LOAD_SETUP = new WeaponDefinition.SoundSetup(
            Optional.empty(), WeaponMounts.LOAD_VOLUME, WeaponMounts.LOAD_PITCH);

    /** ディスペンサー向けの同じ値。値はディスペンサー側にある。 */
    private static final WeaponDefinition.SoundSetup DECOY_SETUP = new WeaponDefinition.SoundSetup(
            Optional.empty(), Dispenser.RELEASE_VOLUME, Dispenser.RELEASE_PITCH);

    /**
     * 発砲音が距離とともに小さくなる指数。1未満なので最初は急に落ちてから遠方まで粘る。耳に対する音量の振る舞いで
     * あり、遠方まで届かせることに意味を持たせている要素でもある。
     */
    private static final float FALLOFF = 0.85F;
    /** 全到達距離で失う鋭さの量。近くでの破裂音は1マイル先では鈍い音になる。 */
    private static final float DULLING = 0.45F;

    private static final Set<ResourceLocation> WARNED = new HashSet<>();
    /** この不具合の報告が既にログに1件あるか。 */
    private static final AtomicBoolean FAILED = new AtomicBoolean();

    /**
     * ここで起きることに、ワールドを失う価値のある物は無い。
     *
     * <p>このイベントは音を要求したパケットの処理内部から発火するので、ここで投げた例外は音を失うだけでは済まない。
     * パケットが失敗しプレイヤーがゲームから切断される。銃がどの録音を使うかにその価値は無いので、想定外の事態では音を
     * サーバーの要求通りに残し、1度だけその旨を記録する。
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        try {
            substituteDefault(event);
        } catch (Exception exception) {
            if (FAILED.compareAndSet(false, true)) {
                AshVehicles.LOGGER.error("Cannot choose a weapon sound; leaving it to the server's choice", exception);
            }
        }
    }

    private static void substituteDefault(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();

        if (sound == null) {
            return;
        }

        ResourceLocation id = sound.getLocation();

        if (!AshVehicles.MODID.equals(id.getNamespace()) || !id.getPath().startsWith(ModSounds.WEAPON_PREFIX)) {
            return;
        }

        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        WeaponDefinition firing = weaponFor(id);
        // サーバーが volume 欄へ音量ではなく到達距離を入れたか。これが再生方法を決める唯一の要素だ。instance 参照。
        boolean carried = firing != null || isRicochet(id) || isImpact(id);

        if (ModSounds.exists(sounds, id)) {
            // 録音は存在する。誤っているのはこの距離での音量だけであり、しかもゲームが本来送るより遠くまで送られた
            // 音に限られる。
            if (carried) {
                event.setSound(instance(SoundEvent.createVariableRangeEvent(id), sound,
                        setupFor(id, firing), true));
            }

            return;
        }

        WeaponDefinition weapon = firing;
        ResourceLocation fallback = fallbackFor(sounds, id, weapon);

        if (fallback == null) {
            return;
        }

        if (WARNED.add(id)) {
            AshVehicles.LOGGER.info("No resource pack provides {}; falling back on {}", id, fallback);
        }

        // 要求元が何を望んでいようと位置も数値も同じ。変わるのは録音だけだ。
        event.setSound(instance(SoundEvent.createVariableRangeEvent(fallback), sound,
                setupFor(id, weapon), carried));
    }

    /**
     * 兵装の発砲音を、聞いている距離にふさわしい形へ置き直す。
     *
     * <p>届いた音は volume 欄に音量ではない値を載せている。サーバーはその欄へ到達距離を入れるほかなかった。その欄が
     * 決めるのは到達距離だけだからだ——{@link WeaponDefinition.SoundSetup#packetVolume()} 参照。送られたまま鳴らすと、
     * 300ブロック先の機関砲がコックピット内と同じ音量になる。
     *
     * <p>だからその値を捨て、この側しか知らない唯一の情報——聞き手が発生地点からどれだけ離れて立っているか——から本当の
     * 値をここで求める。曲線の形は爆発音と同じで、理由も同じだ。最初は急に小さくなってから遠方まで届き、進むにつれ鋭さ
     * を失う。空気は高周波から先に吸うので、谷を越えた破裂音は鈍い音になる。{@link BlastSounds} 参照。
     *
     * <p>距離は既に音量へ織り込んであるので減衰は切るが、位置は発生地点に置いたままにして方向を正しく保つ。
     */
    private static SimpleSoundInstance instance(SoundEvent recording, SoundInstance sound,
            WeaponDefinition.SoundSetup setup, boolean carried) {
        if (!carried) {
            // ゲームが本来送る距離を超えていない音。地上員作業などで、発生地点で聞かれ、volume が本当に音量である物だ。
            return new SimpleSoundInstance(recording, sound.getSource(), setup.volume(), setup.pitch(),
                    SoundInstance.createUnseededRandom(), sound.getX(), sound.getY(), sound.getZ());
        }

        Vec3 at = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        double away = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(at);
        float fade = (float) Mth.clamp(away / Math.max(setup.carry(), 1.0F), 0.0, 1.0);

        return new SimpleSoundInstance(recording.getLocation(), sound.getSource(),
                setup.volume() * (float) Math.pow(1.0F - fade, FALLOFF),
                setup.pitch() * (1.0F - fade * DULLING),
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                at.x, at.y, at.z, false);
    }

    /**
     * このイベントに最も近く、リソースパックが実際に提供している物。代わりに置く価値のある物が無ければ null。
     */
    @Nullable
    private static ResourceLocation fallbackFor(SoundManager sounds, ResourceLocation id,
            @Nullable WeaponDefinition weapon) {
        if (id.equals(ModSounds.LOAD)) {
            return LOAD_FALLBACK;
        }

        if (id.equals(ModSounds.DECOY)) {
            return DECOY_FALLBACK;
        }

        if (id.equals(ModSounds.RELEASE)) {
            return ModSounds.firstPresent(sounds, ModSounds.LAUNCH);
        }

        // MOD が同梱している2つ。どちらも無いなら、パックは MOD の音を「無し」に置き換えたということであり、そこへ
        // ゲームの音を入れるのは差し出口になる。
        if (id.equals(ModSounds.GUN) || id.equals(ModSounds.LAUNCH)) {
            return null;
        }

        if (weapon == null && isImpact(id)) {
            // 跳弾と同じ仕組みで、下の switch から外しておく理由も同じだ。weapon.gun へ落ちた命中音は、射線の遠端で
            // 機関砲がもう一度撃つ音になってしまう。
            return ModSounds.firstPresent(sounds, ModSounds.IMPACT, IMPACT_FALLBACK);
        }

        if (weapon == null && isRicochet(id)) {
            // まず個別兵装の金属音、次に MOD 共通、次にゲームの物。下の switch へは決して落とさない。weapon.gun へ
            // 落ちた跳弾は機関砲がもう一度撃つ音になる。ここの他所と同様、この名前でイベントを主張した兵装が勝つ。
            return ModSounds.firstPresent(sounds, ModSounds.RICOCHET, RICOCHET_FALLBACK);
        }

        // weapon.* の下のそれ以外は兵装自身の名前であり、応える物は無い。
        return switch (weapon == null ? WeaponDefinition.Type.GUN : weapon.type()) {
            case GUN -> ModSounds.firstPresent(sounds, ModSounds.GUN);
            case ROCKET, MISSILE -> ModSounds.firstPresent(sounds, ModSounds.LAUNCH);
            // 増槽は撃たれないので、ここへ来るのは投棄の音を要求された場合だけ。爆弾と同じ「切り離し」の
            // 音で正しい——どちらもレールから物が離れる音であって、点火の音ではない。
            case BOMB, TANK -> ModSounds.firstPresent(sounds, ModSounds.RELEASE, ModSounds.LAUNCH);
        };
    }

    /**
     * これが個別兵装の跳弾音か、全てが落ちてくる共通の跳弾音か。
     *
     * <p>どちらも兵装ではなく役割で名付けられている——{@code weapon.rh120.ricochet} と {@code weapon.ricochet}——ので、
     * 名前の末尾だけで判定できる。
     */
    private static boolean isRicochet(ResourceLocation id) {
        return id.getPath().endsWith("." + ModSounds.RICOCHET_ROLE);
    }

    /**
     * これが個別兵装の命中音か、全てが落ちてくる共通の命中音か。
     *
     * <p>跳弾とまったく同様、兵装ではなく役割で名付けられている——{@code weapon.120mm_cannon.impact} と
     * {@code weapon.impact}——ので、末尾だけで判定できる。
     */
    private static boolean isImpact(ResourceLocation id) {
        return id.getPath().endsWith("." + ModSounds.IMPACT_ROLE);
    }

    /** 音量とピッチ。兵装自身の値か、要求元が使った値。 */
    private static WeaponDefinition.SoundSetup setupFor(ResourceLocation id, @Nullable WeaponDefinition weapon) {
        if (weapon != null) {
            return weapon.sound();
        }

        // 跳弾は発砲音ではないし、そのように送られてもいない。両端が基準にしたのは跳弾自身の値だ。
        if (isRicochet(id)) {
            return Ricochet.SOUND_SETUP;
        }

        // 命中音も発砲音ではないし、同じ理由で自前の距離を持つ。それが重要になる砲手は射線の反対端にいる。
        if (isImpact(id)) {
            return Impact.SOUND_SETUP;
        }

        if (id.equals(ModSounds.LOAD)) {
            return LOAD_SETUP;
        }

        return id.equals(ModSounds.DECOY) ? DECOY_SETUP : WeaponDefinition.SoundSetup.DEFAULT;
    }

    /**
     * このイベントを要求している兵装。無ければ null。
     *
     * <p>必要なのは、{@link PlaySoundEvent} がサウンドエンジンの録音解決より前に届くからだ。インスタンスはまだ音量を
     * 答えられず、問えば例外になるので、値は兵装から取るしかない。イベント名が指す兵装だけでなく全兵装を調べるので、
     * 手作業で別のイベントを指定した兵装も照合できる。
     */
    @Nullable
    private static WeaponDefinition weaponFor(ResourceLocation event) {
        for (Map.Entry<ResourceLocation, WeaponDefinition> entry : Definitions.WEAPONS.all().entrySet()) {
            ResourceLocation fire = entry.getValue().sound().fire()
                    .orElseGet(() -> ModSounds.named(entry.getKey(), ModSounds.WEAPON_PREFIX));

            if (fire.equals(event)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private WeaponSounds() {
    }
}
