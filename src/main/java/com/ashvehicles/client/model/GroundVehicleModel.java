package com.ashvehicles.client.model;

import org.joml.Vector3f;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.Ride;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * あらゆる地上車両を描く。ここに特定の車両専用の物は無い。ジオメトリ・テクスチャ・アニメーションのファイルは車両
 * 自身の名前で見つかるし、どのボーンが砲塔か転輪かは車両のファイルが決める。
 *
 * <p>可動部は全て {@link #setCustomAnimations} のコードで駆動する。GeckoLib はアニメーションコントローラの実行後に
 * 毎フレーム1回これを呼ぶ。ここには角度ではなく手順である物は1つも無い。砲塔はどこかを向き、砲はある仰角にあり、
 * 車輪はここまで回った、という具合だ。手順であるハッチは車両のアニメーションファイルに属することになる。
 *
 * <p>車両が名前を指定していないボーンや指定を誤ったボーンはクラッシュせず飛ばされ、単に固定されたままになる。
 */
public class GroundVehicleModel extends VehicleGeoModel<GroundVehicleEntity> {
    /**
     * <em>車両</em>座標系での各部品の回転方向。
     *
     * <p>それが個々のモデル自身の軸でどちら向きになるかはここの管轄ではない。{@link #turnAboutX}、
     * {@link #turnAboutY}、{@link #slideAlongZ} がジオメトリからボーン単位で判断するので、前向きに作られた車体でも、
     * 後ろ向きに作ってルートボーンで回した車体でも、同じ値が駆動する。仕組みと、車両単位のフラグでは足りない理由は
     * {@link VehicleGeoModel#turnAboutX} 参照。
     *
     * <p>ここで決めるべきは、その3つが働く座標系だけであり、それは GeckoLib の物だ。モデルは自身の −Z を向き、+X は
     * 車両の<em>左</em>になる。よって砲塔を右へ振るとは機首を −Z から −X へ回すことで、Y 軸周りの正の回転——だから
     * ここでは負。車両を前進させる転輪は上端を −Z へ運ぶので X 軸周りの負の回転——だからここでも負。砲身は +Z 方向へ
     * 後座するのでここでは正。砲を上げると銃口が −Z から +Y へ上がり、X 軸周りの正の回転になる。
     *
     * <p><em>全車両で一斉に</em>部品が逆向きに動くならここの符号を反転する。1台の1部品だけが逆なのはこれではない。
     * それはジオメトリがそのボーンを回転軸に対して直交させている場合で、Blockbench で直す。
     */
    private static final float TURRET_SIGN = -1.0F;
    private static final float GUN_SIGN = 1.0F;
    private static final float WHEEL_SIGN = -1.0F;
    /**
     * 操舵輪の回転方向。砲塔と同じ符号で、理由も砲塔と同じだ。どちらも車両の鉛直軸周りの回転であり、右を向いた車輪は
     * 右へ据えた砲と同じ回転になる。
     */
    private static final float STEER_SIGN = -1.0F;
    private static final float RECOIL_SIGN = 1.0F;

    @Override
    protected ResourceLocation idOf(GroundVehicleEntity animatable) {
        return animatable.getVehicleId();
    }

    @Override
    public void setCustomAnimations(GroundVehicleEntity animatable, long instanceId,
            AnimationState<GroundVehicleEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        applyPose(this, Setup.of(animatable.getStats()),
                Pose.of(animatable, animationState.getPartialTick()));
    }

    /**
     * モデルのポーズを決める、車両ファイル由来の数値群。ファイルから一度取り、まとめて持ち回る。
     *
     * <p>ファイルの4つの異なるブロックから来るのに1か所で必要になるし、問い合わせる車両を持たないゴーストは写真と
     * 一緒にこれらを全部運ぶ必要がある。1つずつ渡すのではなくここへ集約した。{@link #applyPose} が、それらが記述する
     * 対象の後ろに float 4つを裸で並べて受け取っていた経緯もそれだ。
     *
     * @param recoilTravel 発砲時に砲身が後座する距離（ブロック）
     * @param wheelTravel 転輪がバネ上で動く距離（ブロック）。0なら走行装置を車体に固定せず、描くサスペンションを
     *                    持たない車両になる
     */
    public record Setup(VehicleChassis.Model model, float recoilTravel, float wheelTravel) {
        public static Setup of(GroundVehicleDefinition stats) {
            return new Setup(stats.model(), stats.armament().recoil(), stats.suspension().travel());
        }
    }

    /**
     * 地上車両が自身に対して行うこと全て。車両から一度取り、その車両のどのモデルにも適用する。
     *
     * @param turretYaw 砲塔の指向。車体正面からの角度（度）
     * @param gunPitch 砲塔上面線からの砲の仰角（度）
     * @param wheelAngle 転輪の回転角（度）
     * @param steerAngle 操舵輪の切れ角（度）。右が正
     * @param recoil 砲身の後座量。0〜1
     * @param ride バネ上の車体の変位。走行装置はこの分だけ戻され、上で車体が動いても地面に留まる
     */
    public record Pose(float turretYaw, float gunPitch, float wheelAngle, float steerAngle, float recoil,
            Ride ride) {
        public static Pose of(GroundVehicleEntity vehicle, float partialTick) {
            return new Pose(
                    vehicle.getTurretYaw(partialTick),
                    vehicle.getGunPitch(partialTick),
                    vehicle.getWheelAngle(partialTick),
                    vehicle.getSteerAngle(partialTick),
                    vehicle.getRecoil(partialTick),
                    vehicle.getRide(partialTick));
        }

        /**
         * 2つのスナップショットの間のポーズ。問い合わせる車両を持たないゴースト用。
         *
         * <p>最新の1つではなく2つの間で求めるのは、ゴーストの隣に立つ戦車に対してゲームが描くのがそれだからだ。任意の
         * 瞬間に画面に出ているのは、前々tickを前tickへブレンドした物である。最新だけでポーズを付けたゴーストは1tick
         * 先行し、しかも毎tick跳ねる。
         *
         * <p>砲塔は近い側の経路で回す。折り返す角度なので、真後ろを横切る砲塔は放っておくと毎tick遠回りで戻ってしまう。
         * 転輪は折り返さない。あの値は履帯の総走行距離であり、上限なく増えるので、2つの単純なブレンドがその間に進んだ
         * 距離になる。
         *
         * @param previous 前々tick終端でのポーズ
         * @param now 前tick終端でのポーズ
         */
        public static Pose between(Pose previous, Pose now, float partialTick) {
            return new Pose(
                    Mth.rotLerp(partialTick, previous.turretYaw(), now.turretYaw()),
                    Mth.lerp(partialTick, previous.gunPitch(), now.gunPitch()),
                    Mth.lerp(partialTick, previous.wheelAngle(), now.wheelAngle()),
                    Mth.lerp(partialTick, previous.steerAngle(), now.steerAngle()),
                    Mth.lerp(partialTick, previous.recoil(), now.recoil()),
                    Ride.between(previous.ride(), now.ride(), partialTick));
        }
    }

    /** 地上車両のモデルにポーズを付ける。 */
    public static void applyPose(GeoModel<?> model, Setup figures, Pose pose) {
        VehicleChassis.Model setup = figures.model();
        String turretBone = setup.bone(GroundVehicleDefinition.Bone.TURRET);

        turnAboutY(model, turretBone, TURRET_SIGN * pose.turretYaw());

        // 砲と防盾は一緒に俯仰し、ジオメトリ上どちらも砲塔の子なので、砲塔がどちらを向いているかを知らないし知る
        // 必要も無い。
        turnAboutX(model, setup.bone(GroundVehicleDefinition.Bone.GUN), GUN_SIGN * pose.gunPitch());
        turnAboutX(model, setup.bone(GroundVehicleDefinition.Bone.MANTLET), GUN_SIGN * pose.gunPitch());
        // 機関銃も一緒に。それが「同軸」たる所以だ。砲に固定された銃身は砲が見る方を見る。ここのモデルは全て機関銃を
        // 砲ボーンではなく砲塔にぶら下げているので、ただ旋回するだけでなくここで俯仰させる——そうしないと、弾は水平の
        // ままの銃身から出て砲の指す方へ飛ぶことになる。出所である GroundVehicleDefinition.Coaxial 参照。
        turnAboutX(model, setup.bone(GroundVehicleDefinition.Bone.MG), GUN_SIGN * pose.gunPitch());

        // 砲身が後座する。回転ではなく平行移動で、しかも砲ではなく砲塔の軸で動かす。ボーンのオフセットは自身の俯仰
        // より前に適用されるからだ。後座量はこの種のファイルの他の値と同様ブロック単位で書かれているが、ボーンが要求
        // するのはモデル単位——車両自身のスケールを掛ける前で1ブロック16単位——である。
        float travel = RECOIL_SIGN * pose.recoil() * figures.recoilTravel() * BakedGeometry.UNITS
                / Math.max(setup.scale(), 0.01F);
        slideAlongZ(model, setup.bone(GroundVehicleDefinition.Bone.GUN), travel);

        // 主砲架と同じ目標に据えられる追加の砲架——同じ射撃指揮に従う軍艦の第2砲塔など。各々を自分の旋回輪の周りに
        // 回すので、甲板上に離れて置かれた2門は共有の支点周りではなく、それぞれ回って1つの照準へ揃う。
        for (String turret : setup.slavedTurrets()) {
            turnAboutY(model, turret, TURRET_SIGN * pose.turretYaw());
            turnAboutX(model, turret, GUN_SIGN * pose.gunPitch());
        }

        // 全輪を同じ角度で一斉に回す。どれがどの車輪かは問題にならない。同じ大きさで、同じ履帯に駆動されるからだ。
        // 各輪がどちら向きに作られたかも問題にならないし、少なくとも1台では同じ側の車輪ごとにそれが違っている。
        for (String wheel : setup.roadWheels()) {
            turnAboutX(model, wheel, WHEEL_SIGN * pose.wheelAngle());
            plant(model, wheel, pose.ride(), setup.scale(), figures.wheelTravel());
        }

        // そして操舵輪を、自身の鉛直軸周りに回す。車輪は大抵両方のリストに入っており、2つの回転が1つのボーンに載る
        // ——それはまったく正しいのだが、GeckoLib の適用順序のおかげでそうなっている。ボーン行列を Z→Y→X で組むので
        // X が最内になる。車輪はまず車軸周りに転がり、その全体がキングピン周りに振られる。逆順だと車輪は、もはや持って
        // いない鉛直軸の周りに転がることになり、高速の操舵輪は曲がる代わりにぶれる。
        for (String wheel : setup.steeredWheels()) {
            turnAboutY(model, wheel, STEER_SIGN * pose.steerAngle());
        }
    }


    /**
     * 上の車体が動いた後、転輪1つを地面へ戻す。
     *
     * <p>サスペンションは pose stack 上で<em>モデル全体</em>を揺らして描く——{@code GroundVehicleRenderer.applyRotations}
     * 参照——が、車体専用のボーンを持たないモデルではそれ以外に方法が無い。ここの車両では車体・砲塔・車載品・車輪が
     * 全て同じルートの子であり、「全部ではない何か」を動かす手立てが無いのだ。その結果、走行装置も車体と一緒に上下する
     * が、それはまさに逆である。だから各車輪を、車体の動きが持ち上げた分だけちょうど下げ、両者を打ち消す。車輪は地面の
     * 元の位置に留まり、車体がその上で動く。それがサスペンションの見た目だ。
     *
     * <p>車輪自身のストローク内に収める。それを超えると車輪はバンプストップに当たり、車両全体が本当に動く——それが正しい
     * し、着地の衝撃が戦車を跳ねさせ、バネに黙って吸収されない理由でもある。
     *
     * <p>車体が動いていないフレームも含め、毎フレーム書き込む。ボーンは画面上の同種の全車両と、各車両への全パスで共有
     * される1つのオブジェクトなので、「今フレームはやることが無かった」からとずらしたまま残した車輪は、次に描かれる
     * 車両でもずれたままになる。履帯の敷設がリンクを戻すのと同じ理由だ。
     */
    private static void plant(GeoModel<?> model, String bone, Ride ride, float scale, float travel) {
        if (bone.isEmpty()) {
            return;
        }

        model.getBone(bone).ifPresent(found -> {
            float lift = 0.0F;

            if (travel > 0.0F && !ride.isLevel()) {
                Vector3f centre = BakedGeometry.centreOf(found);
                float stop = travel / Math.max(scale, 0.01F);

                lift = Mth.clamp(ride.liftOf(centre.x(), centre.z(), scale), -stop, stop);
            }

            slideAlongY(found, -lift * BakedGeometry.UNITS);
        });
    }
}
