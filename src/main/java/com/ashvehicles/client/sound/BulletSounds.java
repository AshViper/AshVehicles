package com.ashvehicles.client.sound;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.ashvehicles.entity.BulletEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 弾が通り過ぎる破裂音。
 *
 * <p>ここで空中にある他の物には追従するループが与えられる——{@link ProjectileSounds} 参照——が、銃弾には意図的に
 * 与えない。理由は2つあり、どちらも今も有効だ。数が非常に多く、1発ごとのチャンネルをサウンドエンジンは用意でき
 * ない。そして弾は一瞬しか空中にいない。戦車砲弾は7tickで300ブロックを横切るので、0.25秒かけてフェードインする
 * ループは寿命の大半をフェードインに費やし、残りは可聴範囲の外で過ごす。
 *
 * <p>だがそれはループに反対する論拠であって、音に反対する論拠ではない。通り過ぎる弾の実際の音は、通過する瞬間に
 * 通過する場所で鳴る短い破裂音1つだ——そしてそれは安い。クライアントに見える弾1発につき1tickあたり距離1つと、
 * 聞く価値があるほど近付いた僅かな弾へのワンショットだけで済む。
 *
 * <p><b>何を鳴らすかより、どこで鳴らすかが重要だ。</b>弾は1tickで40ブロック進むので、tick開始時の位置も終了時の
 * 位置も、実際に通り過ぎた場所からは遠く離れている。どちらで鳴らしても破裂音は聞き手の後ろか遥か前から来てしま
 * う。代わりに、そのtickにおける弾の<em>経路</em>が聞き手に最も近付いた点を求め、そこで鳴らす。おかげで音は弾が
 * 本当に来た方向から届く。
 *
 * <p>弾の生涯の最初のtick——銃口から始まるtick——では鳴らさない。さもないと、聞き手が乗っている機体から撃った銃は
 * 自身の発射音に重ねて耳元で破裂音を鳴らす。機関銃なら毎秒20回だ。1tick後には弾は十分遠くにあり、問いは銃について
 * ではなく弾についてのものになる。
 *
 * <p><b>どの録音を使うか。</b>専用の物を持つ兵器は {@code <namespace>:weapon.<name>.crack}、無ければ MOD の
 * {@code ashvehicles:weapon.crack}、無ければゲーム自身の空振り音——小さな物が空気を高速で切る音にゲームで最も近い
 * 物だ。だから録音が一切無くても聞こえるし、あればより良くなる。{@link ModSounds} 参照。
 */
public final class BulletSounds {
    /**
     * 破裂音に値するには弾がどれだけ近くを通る必要があるか（ブロック）。
     *
     * <p>サウンドエンジンが運ぶ距離よりかなり短く、それは意図的だ。これは「自分の脇を通る弾」の音であって「他人の
     * 脇を通る弾」の音ではない。それより外では聞くべき物は無いし、それが200ブロック先の銃撃戦を破裂音の壁にしない
     * 仕組みでもある。
     */
    private static final double CRACK_DISTANCE = 16.0;

    /**
     * 何発通り過ぎようと1tickで鳴らす破裂音の上限。
     *
     * <p>機関砲は毎tick 1発を空中へ送り、連射は連射としてまとめて届くので、上限が要る。さもないと聞き手に向けた
     * 1門の銃だけで毎秒20回のワンショットになり、ゲーム内の他の全ての音がチャンネルを失う。連射が連射として読める
     * には3で足りる。
     */
    private static final int MOST_PER_TICK = 3;

    /** 兵器もパックも何も録音していない場合に使う、ゲーム自身の空振り音。 */
    private static final ResourceLocation CRACK_FALLBACK =
            ResourceLocation.withDefaultNamespace("entity.player.attack.sweep");

    private static final float LOW_PITCH = 0.9F;
    private static final float HIGH_PITCH = 1.15F;

    /**
     * このクライアントから見えていて、まだ破裂音を鳴らしていない空中の全弾。
     *
     * <p>弱参照にしてあるので、レベルが忘れた弾は、刈り取るはずだったtickに何が起きようとエントリごと消える。
     * 破裂音は1発につき1回。弾は真っ直ぐ飛ぶので、通り過ぎた後は遠ざかる一方であり、それ以上近付くことはない。
     */
    private static final Set<BulletEntity> LIVE = Collections.newSetFromMap(new WeakHashMap<>());

    private BulletSounds() {
    }

    /** レベルへ入ってきた物が弾なら記録する。 */
    public static void offer(Entity entity) {
        if (entity instanceof BulletEntity round) {
            LIVE.add(round);
        }
    }

    /** ワールドを離れるときリストごと捨てる。次のワールドは別の空だ。 */
    public static void forget() {
        LIVE.clear();
    }

    public static void tick(Minecraft minecraft) {
        if (LIVE.isEmpty() || minecraft.level == null) {
            return;
        }

        // 乗員自身の目ではなくカメラを使う。サウンドエンジンが聞く位置はカメラなので、何が聞こえるほど近付いたか
        // を決めるのもカメラであるべきだ。戦車では両者が十数ブロック離れており、それはこの判定距離の大半にあたる。
        Vec3 ear = minecraft.gameRenderer.getMainCamera().getPosition();
        SoundManager sounds = minecraft.getSoundManager();
        Iterator<BulletEntity> rounds = LIVE.iterator();
        int left = MOST_PER_TICK;

        while (rounds.hasNext()) {
            BulletEntity round = rounds.next();

            if (round.isRemoved() || round.level() != minecraft.level) {
                rounds.remove();

                continue;
            }

            // 銃口から始まるtickは銃のものであって、弾のものではない。
            if (round.tickCount <= 1) {
                continue;
            }

            Vec3 passed = nearestApproach(round, ear);

            if (passed == null || passed.distanceToSqr(ear) > CRACK_DISTANCE * CRACK_DISTANCE) {
                continue;
            }

            // チャンネルが取れたかに関わらず取り除く。既に通り過ぎており、鳴らせなかった破裂音を次tickにさらに
            // 遠くから鳴らしてやる義理は無い。
            rounds.remove();

            if (left <= 0) {
                continue;
            }

            SoundEvent crack = crackOf(sounds, round);

            if (crack == null) {
                continue;
            }

            float pitch = Mth.lerp(minecraft.level.getRandom().nextFloat(), LOW_PITCH, HIGH_PITCH);

            minecraft.level.playLocalSound(passed.x, passed.y, passed.z, crack, SoundSource.NEUTRAL,
                    1.0F, pitch, false);
            left--;
        }
    }

    /**
     * このtickにおける弾の経路が聞き手に最も近付いた点。まだ動いていなければ null。
     *
     * <p>弾ではなく経路。40ブロック/tick では経路の両端とも実際の通過点から遠く離れており、どちらで鳴らしても
     * 破裂音は誤った方向から来る。
     */
    @Nullable
    private static Vec3 nearestApproach(BulletEntity round, Vec3 ear) {
        Vec3 from = new Vec3(round.xOld, round.yOld, round.zOld);
        Vec3 step = round.position().subtract(from);
        double flown = step.lengthSqr();

        if (flown < 1.0E-6) {
            return null;
        }

        // ステップ内へクランプする。まだ聞き手に届いていない弾は、通っていない点ではなく経路の手前端で測る。
        double along = Mth.clamp(ear.subtract(from).dot(step) / flown, 0.0, 1.0);

        return from.add(step.scale(along));
    }

    @Nullable
    private static SoundEvent crackOf(SoundManager sounds, BulletEntity round) {
        ResourceLocation recording = ModSounds.firstPresent(sounds,
                ModSounds.named(round.getWeaponId(), ModSounds.WEAPON_PREFIX, ModSounds.CRACK_ROLE),
                ModSounds.CRACK, CRACK_FALLBACK);

        return recording == null ? null : SoundEvent.createVariableRangeEvent(recording);
    }
}
