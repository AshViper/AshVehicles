package com.ashvehicles.particle;

import javax.annotation.Nullable;

import com.ashvehicles.network.BlastSoundPayload;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 炎・煙・音。MOD 内でそれらを出す物すべてのための共通処理。
 *
 * <p>元は兵装用に書いたが、兵装でない物からも欲しくなった。空中分解する機体は弾頭と同じ火球を同じ
 * パーティクルで作り、同じ距離まで同じように聞こえる。{@link com.ashvehicles.weapon.WeaponEffects}
 * に残ったのは本当に兵装固有の部分（着弾したブロックへの作用、発砲時の砲口）で、残骸の分は
 * {@code WreckEffects}。ここはその共有部分。
 *
 * <p>このファイルで知っておく価値があるのは、なぜ全パーティクルパケットに長距離フラグを立てるか。
 * 通常のパーティクルは32ブロック以遠の相手には送られず、何かの拍子に届いてもクライアント側で捨てられ
 * る。32ブロックは松明には妥当、兵器には無意味な距離だ。爆弾は300メートル上空から狙うもので、投下後の
 * パイロットの関心は「爆発したか、どこにか」の一点に尽きる。フラグは両方の上限をプロトコル上の天井で
 * ある512ブロックまで引き上げる。
 *
 * <p>これは、ロード範囲外の爆発が見えること自体の条件でもある。ここでは世界を一切必要としない。
 * パーティクルには位置と色を伝えるだけで、外側でも黒くならずに光ることは
 * {@link com.ashvehicles.client.particle.WeaponParticle} が保証する。
 *
 * <p>炸裂音だけはこの方法では送れない——音に長距離フラグは無く、到達距離は送受信の両側で
 * {@code volume * 16} ブロックに固定されている——ので、MOD 独自のパケットで送り、タイミングと音作りは
 * クライアントが行う。{@link BlastSoundPayload} と
 * {@link com.ashvehicles.client.sound.BlastSounds} 参照。
 */
public final class Effects {
    /** すす。兵装自体の色が何であれ、爆発が残す煙の色。 */
    public static final int SOOT = 0x3A3631;
    /** そして飛び散る物の色。光っているのではなく燃えている。 */
    public static final int EMBER = 0xFFB449;
    /**
     * 爆発が巻き上げる土煙。
     *
     * <p>白、しかもどこで爆発しても同じ白。以前は着弾した地面の色にしていた（砂漠なら砂色、石なら灰色）。
     * 現実の土煙はそうなのだが、実際の見え方は「目標ごとに爆発の色が変わる」だった。土煙の壁は土煙の壁
     * として読めれば十分で、材質はそこまでの価値が無い。
     */
    public static final int DUST = 0xFFFFFF;

    /** これ以上大きくしても描画も可聴距離も変わらなくなる、爆発規模の上限。 */
    public static final float BIGGEST = 12.0F;

    /**
     * この MOD の爆発は何も燃やし残さない。
     *
     * <p>Minecraft の爆発は破壊しきれなかった物に火を撒き、500kg の炸薬なら遠くまで撒く。それで得られる
     * のは戦場ではなく、誰も見ていない地面で夕方いっぱい広がり続ける山火事だ。爆弾のたびに、パイロット
     * が既に飛び去った風景へ静かに火を放つことになる。爆風・クレーター・衝撃波が兵器であり、その後の
     * 延焼は他人の MOD の仕事。
     */
    private static final boolean NO_FIRE = false;

    /**
     * 爆発力1あたり衝撃波が走る距離（ブロック）。
     *
     * <p>大きさを決めるのはこれだけなので、弾頭が2倍なら輪も2倍に広がり、爆発する物は必ず何らかの大きさ
     * の輪を出す。ロケットなら数ブロック、大型爆弾なら30m四方。この1つの数値を上げれば全兵装の衝撃波が
     * 比例して大きくなる。
     *
     * <p>意図的に、爆発の有効半径より遠くまで走らせている。ここで描いているのは外へ吹き出す土煙であり、
     * 現実でも実際に損害を与える範囲よりずっと遠くまで届く。
     */
    private static final float WAVE_REACH = 2.8F;
    /** 走っていく地面から少し浮かせる。輪が地面とピクセルを取り合わないように。 */
    private static final double WAVE_LIFT = 0.35;

    /**
     * エンジンが再生せず、再生できないと文句も言わない唯一の音。
     *
     * <p>バニラは爆発音・煙・ノックバックを1パケットに入れて64ブロック以内の全員へ送る。ノックバックは
     * 要るのでパケットも要る。すると音だけ消す方法は「中身の無い音」を指定することしかない——それが
     * {@code minecraft:intentionally_empty} の用途。
     */
    private static final Holder<SoundEvent> SILENCE = Holder.direct(SoundEvent.createVariableRangeEvent(
            ResourceLocation.withDefaultNamespace("intentionally_empty")));

    /** 炎、煙、破片。爆発を見た者が目にする3つを順に。 */
    public static void fireball(ServerLevel level, Vec3 at, float power, int colour) {
        send(level, at, ModParticles.BLAST.get().of(colour, power * 0.34F),
                4 + (int) (power * 1.6F), power * 0.16, power * 0.035);
        send(level, at, ModParticles.BLAST_SMOKE.get().of(SOOT, power * 0.42F),
                8 + (int) (power * 3.0F), power * 0.28, power * 0.022);
        sparks(level, at, EMBER, power);
    }

    /**
     * 衝撃波。パーティクル1個が輪の描画と土煙の巻き上げを自分でやる。
     * {@link com.ashvehicles.client.particle.ShockwaveParticle} 参照。
     *
     * <p>持たせる色は土煙の色で、輪の側はそれに対して自分を白くする（地面ではなく圧縮された空気なので）。
     * 持たせる大きさは波面が走る距離で、これは爆発規模だけで決まる（{@link #WAVE_REACH} 参照）。
     * つまり閾値を超えた弾頭だけでなく、どの弾頭も自分の規模に比例した波を出す。
     */
    public static void wave(ServerLevel level, Vec3 at, float power) {
        send(level, at.add(0.0, WAVE_LIFT, 0.0),
                ModParticles.SHOCKWAVE.get().of(DUST, power * WAVE_REACH), 1, 0.0, 0.0);
    }

    /**
     * 炸裂音。クライアント側でタイミング・位置・音色を決められるよう MOD 独自パケットで送る。聞こえ得る
     * 者にだけ送り、それ以外は煩わせない。{@link BlastSoundPayload} 参照。
     */
    public static void boom(ServerLevel level, Vec3 at, float power) {
        double carry = BlastSoundPayload.carry(power);
        BlastSoundPayload payload = new BlastSoundPayload(at.x, at.y, at.z, power);

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) < carry * carry) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    public static void sparks(ServerLevel level, Vec3 at, int colour, float power) {
        send(level, at, ModParticles.SPARK.get().of(colour, 1.0F),
                5 + (int) (power * 3.5F), 0.05, 0.09 + power * 0.05);
    }

    /**
     * 爆発本体。ダメージとノックバックを起こし、バニラの音と煙は外してある。
     *
     * <p>パーティクル枠には MOD の火球を渡す（サーバーの意向に関わらずクライアントはどちらかを描くので）。
     * 音の枠には {@link #SILENCE} を渡す。炸裂音は {@link #boom} の担当で、両方鳴らせば1回の爆発が
     * 定刻と遅れの2回聞こえてしまう。
     *
     * @param power ダメージ量と開く穴の大きさ。上限なし。描画だけは {@link #BIGGEST} で頭打ちにして
     *              最大級の弾頭が画面を埋めないようにするが、地面に対して何をするかは弾頭の自由
     */
    public static void blast(ServerLevel level, @Nullable Entity source, Vec3 at, float power, int colour) {
        float drawn = Mth.clamp(power, 1.0F, BIGGEST);
        ParticleOptions fireball = ModParticles.BLAST.get().of(colour, drawn * 0.3F);

        level.explode(source, Explosion.getDefaultDamageSource(level, source), null,
                at.x, at.y, at.z, power, NO_FIRE, Level.ExplosionInteraction.MOB,
                fireball, fireball, SILENCE);
    }

    /**
     * パーティクルの散布。{@code count} 個を {@code spread} ブロックの範囲に撒き、{@code speed} の
     * 速さでランダムな方向へ飛ばす。
     *
     * <p>プレイヤーごとに1パケット。長距離フラグはプレイヤー単位の呼び出しでしか立てられないため。範囲外
     * の相手はサーバー側で落とすので、これはブロードキャストではなくプレイヤーリストのループと数個の
     * パケットで済む。
     */
    public static void send(ServerLevel level, Vec3 at, TintedParticleOption particle, int count,
            double spread, double speed) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, true, at.x, at.y, at.z, count, spread, spread, spread, speed);
        }
    }

    /**
     * 同じもののうち、軸ごとに散らし方が違う場合用。
     *
     * <p>機体は細長く平たい。翼幅サイズの球に炎を撒くと、機体に沿う分と同じだけ上下にも撒かれ、
     * 「燃えている機体」ではなく「たまたま火球の中にいる機体」に見える。
     */
    public static void send(ServerLevel level, Vec3 at, TintedParticleOption particle, int count,
            Vec3 spread, double speed) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, true, at.x, at.y, at.z, count,
                    spread.x, spread.y, spread.z, speed);
        }
    }

    /**
     * ランダム方向の散布ではなく、自前の速度を持つパーティクル1個。
     *
     * <p>バニラのパーティクルパケットは同じ3つの数値で二通りの意味を持つ。個数が1以上なら散布範囲＋方向は
     * ランダム、0なら速度そのもの。どちらも要る（火球は散り、煙は流れる）ので、どちらか分かる名前を付けて
     * 両方置いてある。
     */
    public static void aimed(ServerLevel level, Vec3 at, TintedParticleOption particle, Vec3 velocity) {
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, particle, true, at.x, at.y, at.z, 0,
                    velocity.x, velocity.y, velocity.z, 1.0);
        }
    }

    private Effects() {
    }
}
