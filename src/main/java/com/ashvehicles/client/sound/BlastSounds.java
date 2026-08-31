package com.ashvehicles.client.sound;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.BlastShake;
import com.ashvehicles.network.BlastSoundPayload;
import com.ashvehicles.particle.Effects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 遠方から聞こえる爆発音。
 *
 * <p>ゲームが本来出す音には3つの誤りがあり、その3つこそが「遠い爆発が遠く聞こえる」理由だ。
 *
 * <ul>
 * <li><b>届くのが早すぎる。</b>音速は約17ブロック/tick なので、300ブロック先の爆弾は閃光の1秒近く後に聞こえる
 *     はずだ。閃光と轟音が同時に来ることは「近い」ことを示す最も強い手掛かりであり、ゲームはその手掛かりを全てに
 *     与えてしまう。
 * <li><b>小さすぎ、そして突然無音になる。</b>サウンドエンジンは {@code max(volume, 1) * 16} ブロックにわたって
 *     線形に音量を0まで落とす。爆発なら64ブロックだ。それを超えると、どんな規模の爆発でも音は一切しない。
 * <li><b>鋭すぎる。</b>空気は高周波から先に吸うので、近くでは破裂音でも遠くでは低い轟きになる。
 * </ul>
 *
 * <p>よって到達時刻を計り、音量はエンジンの減衰に任せずここで算出し、ピッチを距離とともに下げる。減衰を切って
 * 再生する——距離は既に織り込み済み——が、位置は爆発の実位置にするので、方向は正しいままだ。
 *
 * <p>バニラ自身の爆発音は反対側で退けている。{@link com.ashvehicles.weapon.WeaponEffects} 参照。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class BlastSounds {
    /** ブロック/tick。秒速343m を、毎秒20tick で換算した値。 */
    private static final double SPEED_OF_SOUND = 17.15;
    /**
     * 距離による減音の指数。1未満なので最初は急に落ちてから遠方まで粘る。耳に対する音量の振る舞いでもあり、
     * 遠方まで届かせることに意味を持たせている要素でもある。
     */
    private static final double FALLOFF = 0.85;
    /** 真上での爆発のピッチと、全到達距離でそのうちどれだけ失われるか。 */
    private static final float NEAR_PITCH = 1.05F;
    private static final float DULLING = 0.5F;
    /** これ以上は保持しない。その頃にはプレイヤーがまったく別の場所にいるかもしれないからだ。 */
    private static final int LONGEST_WAIT = 200;

    /**
     * 規模が上がるほど再生速度を落とす、その下限。
     *
     * <p>Minecraft の音響エンジンはピッチを 0.5〜2.0 に丸めるので、これが出せる限界の遅さ——録音そのものの
     * 2倍の長さになる。核の轟音を核の轟音にしているのは音量ではなく<b>低さと長さ</b>だ。同じ録音でも、
     * 再生を落とせば破裂音は轟きになり、轟きは地鳴りになる。
     *
     * <p>{@link Effects#BIGGEST} から {@link Effects#LARGEST} までかけて連続的に落ちるので、境目は無い。
     * 規模を上げれば上げただけ低く、長くなり続ける。
     */
    private static final float SLOWEST = 0.5F;

    private static final List<Pending> WAITING = new ArrayList<>();

    /** 爆発が起きた。聞こえるか・いつ聞こえるかは発生位置から算出する。 */
    public static void hear(Vec3 at, float power) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            return;
        }

        int wait = Math.min((int) (earTo(minecraft, at) / SPEED_OF_SOUND), LONGEST_WAIT);

        if (wait <= 0) {
            play(minecraft, at, power);
        } else {
            WAITING.add(new Pending(at, power, wait));
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (WAITING.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // ワールドを離れるとき保留中の音も捨てる。前のワールドの轟音が次のワールドで届くのは妙な話だ。
        if (minecraft.level == null) {
            WAITING.clear();

            return;
        }

        Iterator<Pending> each = WAITING.iterator();

        while (each.hasNext()) {
            Pending boom = each.next();

            if (--boom.wait <= 0) {
                each.remove();
                play(minecraft, boom.at, boom.power);
            }
        }
    }

    /**
     * プレイヤーが今いる場所に爆発がどれだけ残っているか。
     *
     * <p>送信時の値を保持せず到達時に測り直す。機体では1秒＝100ブロックであり、音の旅路の間ずっとプレイヤーは
     * 飛んでいたからだ。
     */
    private static void play(Minecraft minecraft, Vec3 at, float power) {
        // 到達したのは音ではなく空気の壁で、音はその一部でしかない。近ければ同じ壁が体も叩くので、揺れも
        // ここから始まる。到達時刻の計算をもう一度書かずに済むのは副産物で、本質は同じ現象だということ。
        BlastShake.felt(at, power);

        double fade = Mth.clamp(earTo(minecraft, at) / BlastSoundPayload.carry(power), 0.0, 1.0);
        float volume = (float) Math.pow(1.0 - fade, FALLOFF);

        if (volume <= 0.0F) {
            return;
        }

        minecraft.getSoundManager().play(new BlastSoundInstance(
                recording(minecraft), volume, pitch(power, (float) fade), at));
    }

    /**
     * この規模・この距離の爆発を再生する速さ。
     *
     * <p>2つが重なっている。<b>距離</b>——空気は高周波から先に吸うので、遠いほど籠もる。そして<b>規模</b>
     * ——大きい爆発は低く、そして長い。後者はピッチを下げることで長さも一緒に稼いでいる。同じ録音を遅く回せば
     * 破裂音は轟きになり、轟きは地鳴りになるからで、別の音を重ねるより1つの音が伸び続ける方が「大きい」に
     * 聞こえる。
     */
    private static float pitch(float power, float fade) {
        float dulled = NEAR_PITCH - fade * DULLING;
        float heavy = Mth.clamp((power - Effects.BIGGEST) / (Effects.LARGEST - Effects.BIGGEST), 0.0F, 1.0F);

        return Mth.lerp(heavy, dulled, SLOWEST);
    }

    private static double earTo(Minecraft minecraft, Vec3 at) {
        return minecraft.gameRenderer.getMainCamera().getPosition().distanceTo(at);
    }

    /** リソースパックが提供していれば MOD 自身の轟音、無ければゲームの物。 */
    private static ResourceLocation recording(Minecraft minecraft) {
        return ModSounds.exists(minecraft.getSoundManager(), ModSounds.BLAST)
                ? ModSounds.BLAST
                : SoundEvents.GENERIC_EXPLODE.value().getLocation();
    }

    /** 到達途中の爆発音と、残りの飛行tick数。 */
    private static final class Pending {
        private final Vec3 at;
        private final float power;
        private int wait;

        private Pending(Vec3 at, float power, int wait) {
            this.at = at;
            this.power = power;
            this.wait = wait;
        }
    }

    /**
     * 位置は指定するので方向は正しいが、減衰はさせない。エンジンの減衰は64ブロックで0になるのに、ここで面白い
     * ことが起きるのは全てそれより遠くだからだ。距離は既に音量とピッチに織り込んである。
     */
    private static final class BlastSoundInstance extends AbstractSoundInstance {
        private BlastSoundInstance(ResourceLocation recording, float volume, float pitch, Vec3 at) {
            super(recording, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.volume = volume;
            this.pitch = pitch;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }
    }

    private BlastSounds() {
    }
}
