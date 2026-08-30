package com.ashvehicles.client.sound;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.network.BlastSoundPayload;

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
        double fade = Mth.clamp(earTo(minecraft, at) / BlastSoundPayload.carry(power), 0.0, 1.0);
        float volume = (float) Math.pow(1.0 - fade, FALLOFF);

        if (volume <= 0.0F) {
            return;
        }

        minecraft.getSoundManager().play(new BlastSoundInstance(
                recording(minecraft), volume, NEAR_PITCH - (float) fade * DULLING, at));
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
