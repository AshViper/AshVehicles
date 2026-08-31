package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.particle.Effects;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 至近弾でカメラが揺れる。
 *
 * <p>ここまで、この MOD の爆発は目と耳にしか届いていなかった。500kg の炸薬が30m先で炸裂しても画面は微動だに
 * せず、それが「見ている物」と「起きている事」を切り離していた。過圧の前面は物理的に体を叩く物であり、それを
 * 表現する手段はゲームには1つしかない。カメラである。
 *
 * <p>揺れが来るのは閃光と同時ではなく、<em>音と同時</em>だ。カメラを揺らすのは光ではなく空気の壁で、空気の壁は
 * 音速で来る。だから起点は {@link com.ashvehicles.client.sound.BlastSounds} が実際に轟音を鳴らす瞬間——同じ
 * 到達時刻計算を二度書かずに済むし、そもそも同じ現象だからだ。遠い爆発なら、閃光が見え、しばらく間があり、
 * 轟音と揺れが同時に来る。近い爆発なら三つが一度に来る。その差が距離の手掛かりになる。
 *
 * <p><b>揺れるのはカメラだけで、機体も照準も動かない。</b>角度はこのフレームの描画にだけ足され、入力にも
 * サーバーへ送る視線にも入らない。爆風で狙いが逸れるのが正しいかどうかは別の議論だが、少なくとも「画面が揺れた
 * せいで撃てなくなる」のは演出の仕事ではない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class BlastShake {
    /**
     * 揺れを感じる距離（ブロック）。爆発力1あたりと、最小規模でも感じる分。
     *
     * <p>音が届く距離（{@link com.ashvehicles.network.BlastSoundPayload#carry} は最大で1200ブロック）より
     * 近い。轟音は空を渡るが、体を叩く過圧はそこまでは届かない。十分に遠い爆発は「聞こえるが感じない」——
     * それが遠さの表現そのものだ。ただし線はかなり外にある。最大規模で270ブロック、つまり爆撃機が投弾して
     * 離脱しきる前に自分の爆弾を感じる程度には広い。
     */
    private static final double FELT_WITHIN = 30.0;
    private static final double FELT_PER_POWER = 20.0;
    /**
     * それでも、ここより遠くでは感じない（ブロック）。
     *
     * <p>試験棒が {@link Effects#LARGEST} まで開いている以上、比例のままでは5kmが揺れる。地平線の向こうで
     * 誰かが棒を振ったせいで手元が揺れるのは、演出ではなく故障に見える。
     */
    private static final double FELT_FURTHEST = 700.0;
    /**
     * 距離に対する減衰の指数。
     *
     * <p>2ではない。二乗で落とすと、届く距離を広げても増えるのは「ほとんど感じない範囲」ばかりで、実際に
     * 揺れる範囲は変わらない。1.4 なら半分の距離でまだ4割弱が残るので、広げた分がそのまま体感になる。
     */
    private static final double FADES_WITH_RANGE = 1.4;

    /** 真上で最大規模が炸裂したときの、片側への振れ幅（度）。 */
    private static final float MOST_SWING = 2.2F;
    /**
     * ロールの振れ幅が上下左右に対して持つ割合。
     *
     * <p>小さめにしてある。画面の回転は最も強く感じる軸で、同じだけ振ると「爆風を受けた」ではなく「船に乗って
     * いる」に見える。それでも0にはしない。ロールが少し混じるかどうかが、揺れが機械的な往復に見えるかどうかを
     * 分けている。
     */
    private static final float ROLL_SHARE = 0.55F;

    /**
     * 毎tick残る揺れの割合。
     *
     * <p>速い。爆風は殴って去る物で、余韻を持たない。0.8 なら半減まで3tick、ほぼ消えるまで15tick——0.75秒で、
     * これは「叩かれた」と読めて、なお操作を邪魔しない長さだ。
     */
    private static final float DIES_AWAY = 0.80F;
    /** これ以下になったら畳む。丸め残りのために毎フレーム三角関数を回す意味は無い。 */
    private static final float SPENT = 0.004F;

    /**
     * 揺れの速さ（ラジアン/tick）。3つとも互いに整数倍でないので、重ねても周期が戻ってこない。
     *
     * <p>速いことに意味がある。爆発の揺れは揺れではなく震えで、1秒に1往復する物はそう見えない。これらは
     * おおよそ 6〜11Hz にあたる。
     */
    private static final float RATTLE_YAW = 2.31F;
    private static final float RATTLE_PITCH = 3.47F;
    private static final float RATTLE_ROLL = 1.79F;
    /** 同じ軸に重ねる2本目の速さの比。1本だと正弦波に見えてしまう。 */
    private static final float SECOND_RATE = 2.7F;
    private static final float SECOND_SHARE = 0.4F;

    /** 今どれだけ揺れているか。0で静止、1で最大。 */
    private static float shaking;
    /**
     * 揺れ始めてからの tick 数。
     *
     * <p>ワールド時刻ではなくこれを使う。ワールド時刻は際限なく増えるので、float の位相にすると精度を失って
     * 揺れが階段状になるし、丸めて使えば丸めた所で位相が飛ぶ。こちらは1回の揺れが続く十数tickしか進まない。
     */
    private static int since;

    /**
     * 爆風の前面が着いた。{@link com.ashvehicles.client.sound.BlastSounds} が轟音を鳴らすのと同じ瞬間に
     * 呼ばれる。
     *
     * @param at 爆心
     * @param power 爆発規模
     */
    public static void felt(Vec3 at, float power) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null) {
            return;
        }

        double reach = Math.min(FELT_WITHIN + power * FELT_PER_POWER, FELT_FURTHEST);
        double away = minecraft.gameRenderer.getMainCamera().getPosition().distanceTo(at);

        if (away >= reach) {
            return;
        }

        double near = 1.0 - away / reach;
        float jolt = Math.min(power / Effects.BIGGEST, 1.0F) * (float) Math.pow(near, FADES_WITH_RANGE);

        // 続いている揺れに足す時は位相を触らない。触ると、2発目が1発目の波を途中で切って跳ねに見える。
        if (shaking <= 0.0F) {
            since = 0;
        }

        // 重ねる。斉射の2発目は1発目の揺れが収まるのを待たないので、足して天井で止める。
        shaking = Math.min(shaking + jolt, 1.0F);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (shaking <= 0.0F) {
            return;
        }

        // ワールドを離れたら残った揺れも捨てる。次のワールドを揺らす筋合いは無い。
        if (Minecraft.getInstance().level == null) {
            shaking = 0.0F;

            return;
        }

        shaking *= DIES_AWAY;
        since++;

        if (shaking < SPENT) {
            shaking = 0.0F;
        }
    }

    /**
     * このフレームのカメラ角度に揺れを足す。
     *
     * <p>優先度が最低なのは、足し算だからだ。機体と地上車両のカメラハンドラは角度を<em>代入</em>する——
     * コックピットの視界は機体姿勢そのものなので、そうするほかない。先に走ってしまうとその代入に消される。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (shaking <= 0.0F) {
            return;
        }

        // フレームをまたいで連続する位相。tick 境界で飛ばないよう partialTick まで入れる。
        float phase = since + (float) event.getPartialTick();
        float swing = shaking * MOST_SWING;

        event.setYaw(event.getYaw() + swing * rattle(phase, RATTLE_YAW, 0.0F));
        event.setPitch(event.getPitch() + swing * rattle(phase, RATTLE_PITCH, 1.7F));
        event.setRoll(event.getRoll() + swing * ROLL_SHARE * rattle(phase, RATTLE_ROLL, 0.9F));
    }

    /** 速さの違う正弦波2本。1本では往復運動に、無作為では砂嵐に見える。 */
    private static float rattle(float phase, float rate, float offset) {
        return Mth.sin(phase * rate + offset) * (1.0F - SECOND_SHARE)
                + Mth.sin(phase * rate * SECOND_RATE + offset) * SECOND_SHARE;
    }

    private BlastShake() {
    }
}
