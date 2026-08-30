package com.ashvehicles.client.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * あらゆる機体を描く。ここに特定の機体専用の物は無い。ジオメトリ・テクスチャ・アニメーションのファイルは機体自身
 * の名前で見つかるし、どのボーンが補助翼か脚かは機体のファイルが決める。
 *
 * <p>降着装置以外の可動部は全て {@link #setCustomAnimations} のコードで駆動する。GeckoLib はアニメーション
 * コントローラの実行後に毎フレーム1回これを呼ぶので、ここで設定した物がコントローラを上書きする。脚は逆で、機体の
 * アニメーションファイルから再生され、ここが触れるのはそのファイルを持たない機体の場合だけだ。
 * {@link AircraftAnimations} 参照。
 *
 * <p>舵面はパイロットのキーではなく機体の実際の角速度に追従する。角速度は全クライアントへ同期されるので、他の
 * プレイヤーにも舵面が動いて見える。機体が名前を指定していないボーンや指定を誤ったボーンはクラッシュせず飛ばされ、
 * 単に固定されたままになる。
 */
public class AircraftModel extends VehicleGeoModel<AircraftEntity> {
    /**
     * モデル上でノズルが振れる角度（度）。
     *
     * <p>機体の {@code vtol.max_angle} からは読まずここに持つ。あれはジオメトリではなく推力についての値だからだ。
     * 物理が90度をどう扱うかは決まっているが、90度に見せるためにモデルが何をすべきかはノズルの作り方次第であり、
     * 逆向きに振れるノズルはここの符号を反転して直す。
     */
    private static final float NOZZLE_TRAVEL = 90.0F;

    // 各可動部の作動量（度）。逆向きに動く部品があればここの符号を反転する。
    private static final float ELEVATOR_TRAVEL = 20.0F;
    private static final float AILERON_TRAVEL = 20.0F;
    private static final float RUDDER_TRAVEL = 18.0F;
    private static final float FLAP_TRAVEL = 15.0F;
    private static final float GEAR_RETRACT_TRAVEL = 90.0F;
    private static final float GEAR_DOOR_TRAVEL = 85.0F;

    @Override
    protected ResourceLocation idOf(AircraftEntity animatable) {
        return animatable.getAircraftId();
    }

    /** 同じファイル。再生前に中身を知る必要がある物のため。 */
    public static ResourceLocation animationFile(AircraftEntity animatable) {
        return animationFile(animatable.getAircraftId());
    }

    @Override
    public void setCustomAnimations(AircraftEntity animatable, long instanceId,
            AnimationState<AircraftEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        float partialTick = animationState.getPartialTick();
        applyPose(this, animatable.getStats().model(), Pose.of(animatable, partialTick));
    }

    /**
     * アニメーションファイルが再生する物を除く、機体の可動部すべてを構成するボーン回転。機体から一度取り、
     * その機体のどのモデルにも適用できる——実体でも、機体が範囲外へ出た後に描かれるゴーストの複製でも。
     *
     * @param elevator 昇降舵の偏向（度）
     * @param aileron 補助翼の偏向（度）。右側は符号が逆になる
     * @param rudder 方向舵の偏向（度）
     * @param gear 降着装置。0が上げ、1が下げ
     * @param flaps フラップ。0が上げ、1が下げ
     * @param rotor メインローターの現在角（度）。持たない機体では0
     * @param tailRotor テールローターの同じ値
     * @param rotorRate メインローターの1tickあたりの回転量（度）。tick終端で取ったポーズを次tick内の任意の瞬間まで
     *        進められるよう持ち回る。1tickでほぼ1回転するローターは2つの角度から補間できないし、どのみち角度は
     *        円周内へ折り返される
     * @param tailRotorRate テールローターの同じ値。こちらはさらに数倍速く回る
     * @param wingSweep 可変翼の後退角（度）。他の可動部と違って0〜1の作動量ではなく角度そのものを運ぶ。
     *        全開後退が何度かは機体ごとに違い、ここの定数ではなく機体ファイルの数値だからだ
     * @param sweepGear ここで脚を振るか。アニメーションファイルに脚サイクルを持たない機体用
     */
    public record Pose(float elevator, float aileron, float rudder, float gear, float flaps, float nozzle,
            float rotor, float tailRotor, float rotorRate, float tailRotorRate, float wingSweep,
            boolean sweepGear) {
        /** 機体の舵面が今どうなっているか。 */
        public static Pose of(AircraftEntity aircraft, float partialTick) {
            // 機首を上げると水平尾翼の後縁が下がるので、ピッチ差分を反転している。
            return new Pose(
                    deflection(-aircraft.getPitchDelta(), aircraft.getPitchRate(), ELEVATOR_TRAVEL),
                    deflection(aircraft.getRollDelta(), aircraft.getRollRate(), AILERON_TRAVEL),
                    deflection(aircraft.getYawDelta(), aircraft.getYawRate(), RUDDER_TRAVEL),
                    aircraft.getGearProgress(partialTick),
                    aircraft.getFlapsProgress(partialTick),
                    aircraft.getVtolProgress(partialTick),
                    aircraft.getRotorAngle(partialTick),
                    aircraft.getTailRotorAngle(partialTick),
                    aircraft.getRotorAngle(1.0F) - aircraft.getRotorAngle(0.0F),
                    aircraft.getTailRotorAngle(1.0F) - aircraft.getTailRotorAngle(0.0F),
                    aircraft.getWingSweep(partialTick),
                    !AircraftAnimations.hasGearCycle(aircraft));
        }

        /**
         * 2tickの間の任意の瞬間に描くポーズを、各tick終端で取ったポーズから求める。
         *
         * <p>これは機体自身の補間を、機体からではなくスナップショットから行った物だ。ゲームがエンティティに対して
         * 描くのは常に前々tickを前tickへブレンドした物であり、それ以外の描き方をしたゴーストは隣の機体より1tick
         * 先行してしまう。各部は機体が扱うのと同じように扱う。降着装置・フラップ・ノズル・可変翼はどこかへ向かう
         * 途中なので補間する。舵面は1tick中の機体の旋回速度に追従するが、それはそのtick全体で1つの値であり、機体自身も補間
         * しない。ローターは2角のブレンドではなく角速度から進める。1tickで1回転を超えるうえ、角度は折り返される
         * からだ。
         *
         * @param previous 前々tick終端でのポーズ
         * @param now 前tick終端でのポーズ
         */
        public static Pose between(Pose previous, Pose now, float partialTick) {
            float wind = partialTick - 1.0F;

            return new Pose(
                    now.elevator(), now.aileron(), now.rudder(),
                    Mth.lerp(partialTick, previous.gear(), now.gear()),
                    Mth.lerp(partialTick, previous.flaps(), now.flaps()),
                    Mth.lerp(partialTick, previous.nozzle(), now.nozzle()),
                    now.rotor() + now.rotorRate() * wind,
                    now.tailRotor() + now.tailRotorRate() * wind,
                    now.rotorRate(), now.tailRotorRate(),
                    Mth.lerp(partialTick, previous.wingSweep(), now.wingSweep()),
                    now.sweepGear());
        }
    }

    /**
     * 機体のモデルにポーズを付ける。舵面は機体の角速度に追従する。降着装置はアニメーションファイルの管轄で、ここ
     * から振るのは再生する脚サイクルを持たない機体の場合だけ——脚は真っ直ぐ後ろへ一続きに引き上げ、脚が格納されて
     * いない間はベイ扉を開く。降着装置の見た目としては正しくないが、閉じた扉を貫く脚よりははるかにましだ。
     */
    public static void applyPose(GeoModel<?> model, VehicleChassis.Model setup, Pose pose) {
        rotateX(model, setup, AircraftDefinition.Bone.ELEVATOR_LEFT, pose.elevator());
        rotateX(model, setup, AircraftDefinition.Bone.ELEVATOR_RIGHT, pose.elevator());
        rotateX(model, setup, AircraftDefinition.Bone.AILERON_LEFT, pose.aileron());
        rotateX(model, setup, AircraftDefinition.Bone.AILERON_RIGHT, -pose.aileron());
        rotateY(model, setup, AircraftDefinition.Bone.RUDDER, pose.rudder());
        rotateY(model, setup, AircraftDefinition.Bone.RUDDER_LEFT, pose.rudder());
        rotateY(model, setup, AircraftDefinition.Bone.RUDDER_RIGHT, pose.rudder());

        if (pose.sweepGear()) {
            float gear = pose.gear();
            float retracted = 1.0F - gear;

            rotateX(model, setup, AircraftDefinition.Bone.NOSE_GEAR, retracted * GEAR_RETRACT_TRAVEL);
            rotateX(model, setup, AircraftDefinition.Bone.LEFT_GEAR, retracted * GEAR_RETRACT_TRAVEL);
            rotateX(model, setup, AircraftDefinition.Bone.RIGHT_GEAR, retracted * GEAR_RETRACT_TRAVEL);
            rotateZ(model, setup, AircraftDefinition.Bone.NOSE_GEAR_DOOR, gear * GEAR_DOOR_TRAVEL);
            rotateZ(model, setup, AircraftDefinition.Bone.LEFT_GEAR_DOOR, gear * GEAR_DOOR_TRAVEL);
            rotateZ(model, setup, AircraftDefinition.Bone.RIGHT_GEAR_DOOR, -gear * GEAR_DOOR_TRAVEL);
        }

        rotateX(model, setup, AircraftDefinition.Bone.FLAP_LEFT, pose.flaps() * FLAP_TRAVEL);
        rotateX(model, setup, AircraftDefinition.Bone.FLAP_RIGHT, pose.flaps() * FLAP_TRAVEL);

        // 可変翼。翼根で機体の鉛直軸周りに回し、両翼端を尾部へ運ぶ。左右で符号が逆になるのは、同じ「後ろ」が
        // 鉛直軸周りでは互いに逆回りだからで、補助翼のような操縦上の左右差ではない。
        //
        // ここだけ turnAboutY を使う——上の舵面と違い、翼はモデルによってはルートボーンの半回転の下にあり、
        // 生の Y 回転では機体の半分で翼が前へ出てしまう。符号をボーンごとにジオメトリから判断させる。
        // VehicleGeoModel#turnAboutX 参照。角度は0〜1の作動量ではなく度で届いている。全開後退が何度かは
        // 機体ファイルの数値であって、ここの定数ではないからだ。
        turnAboutY(model, setup, AircraftDefinition.Bone.WING_LEFT, -pose.wingSweep());
        turnAboutY(model, setup, AircraftDefinition.Bone.WING_RIGHT, pose.wingSweep());

        // ノズルは舵面のような数度ではなく下まで一杯に振れる。アニメーションではなくここでポーズを付けるのは、
        // これが手順ではなく1つの角度だからだ。機体は転換がどこまで進んだか既に知っているし、それに伴って開く扉は
        // アニメーションファイルの管轄だ。AircraftAnimations 参照。
        rotateX(model, setup, AircraftDefinition.Bone.NOZZLE, pose.nozzle() * NOZZLE_TRAVEL);

        // ローター。同じ考え方を1回転まで押し進めた物だ。ここでポーズを付ける理由はノズルと同じで、加えてもう
        // 1つある。ローターの回転速度は機体ファイルの数値であり、その数値が動くたびアニメーションファイルを手作業
        // でタイミング調整し直す羽目になるからだ。メインローターはマスト周り、テールローターは自身のシャフト周りに
        // 回り、どちらの角度も機体が既に算出している。
        rotateY(model, setup, AircraftDefinition.Bone.ROTOR, pose.rotor());
        rotateX(model, setup, AircraftDefinition.Bone.TAIL_ROTOR, pose.tailRotor());

        // プロペラ。ローターと同じ角度を、同じ理由でここから使う——1機が両方を持つことはないので、機体が
        // 既に算出している角度がそのままプロペラの角度だ。回すのは各ボーン自身の中心周りで、それが
        // {@link #spinZ} の存在理由になる。
        for (String propeller : setup.propellers()) {
            spinZ(model, propeller, pose.rotor());
        }
    }

    private static void rotateX(GeoModel<?> model, VehicleChassis.Model setup, String role, float degrees) {
        rotateX(model, setup.bone(role), degrees);
    }

    private static void rotateY(GeoModel<?> model, VehicleChassis.Model setup, String role, float degrees) {
        rotateY(model, setup.bone(role), degrees);
    }

    private static void rotateZ(GeoModel<?> model, VehicleChassis.Model setup, String role, float degrees) {
        rotateZ(model, setup.bone(role), degrees);
    }

    private static void turnAboutY(GeoModel<?> model, VehicleChassis.Model setup, String role, float degrees) {
        turnAboutY(model, setup.bone(role), degrees);
    }

    /** 角速度を舵面の偏向へ写す。機体が限界の角速度で回っているとき舵一杯になる。 */
    private static float deflection(float ratePerTick, float maxRatePerTick, float travelDegrees) {
        if (maxRatePerTick <= 0.0F) {
            return 0.0F;
        }

        return Mth.clamp(ratePerTick / maxRatePerTick, -1.0F, 1.0F) * travelDegrees;
    }

}
