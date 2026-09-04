package com.ashvehicles.aircraft;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.vehicle.VehicleType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.datafixers.util.Pair;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

/**
 * 機体1機を JSON だけで記述した物。{@code data/ashvehicles/ashvehicles/aircraft/} にファイルを置けば
 * 起動時に MOD がエンティティ型とアイテムを登録する。Java は要らない。
 *
 * <p>ファイルは2つの目的で2回読まれる。起動時には {@link DefinitionRegistry} が MOD 内の写しを読み、
 * レジストリが開いている間に {@link Hitbox} を確定させる（エンティティ型の大きさは登録の瞬間に固定される
 * ため）。ワールド読み込み時には同じファイルが {@code ashvehicles:aircraft} データパックレジストリを通り、
 * 当たり判定より下は全部そちらから読まれる。だからデータパックで機体を調整し、{@code /reload} で反映
 * できる。
 *
 * <p>速度は1tickあたりブロック、加速度は1tick二乗あたりブロック。毎秒20tickなので速度1.0は毎秒20ブロック。
 * 角速度は1tickあたり度。
 */
public record AircraftDefinition(VehicleChassis.Hitbox hitbox, VehicleChassis.Model model, Engine engine, Wing wing,
        Handling handling, Airframe airframe, Undercarriage landingGear, Surface flaps,
        VehicleChassis.CameraMount camera, VehicleChassis.Sound sound, VehicleChassis.Radar radar, Signature signature,
        Countermeasures countermeasures, VehicleType type, Optional<Vtol> vtol, Optional<Rotor> rotor,
        List<Hardpoint> hardpoints, List<Station> stations, Sync sync) {


    /**
     * 翼以外で機体を支える2つの方式を、1つのフィールドとして読む。
     *
     * <p>理由はコーデックのグループが16項目までだからで、継ぎ目を使うならこの2つが妥当だった。1分だけ
     * エンジンを借りる飛行機と、それ以外に飛ぶ手段の無いヘリコプターは二者択一で、両方持つ物はまともに
     * 存在しない。{@code vtol} と {@code rotor} のブロックは独立している場合とまったく同じに読み書きされ
     * る。ファイルから見れば実際に独立しているので。
     */
    private static final MapCodec<Pair<Optional<Vtol>, Optional<Rotor>>> LIFT_SYSTEM =
            Codec.mapPair(Vtol.CODEC.optionalFieldOf("vtol"), Rotor.CODEC.optionalFieldOf("rotor"));

    /**
     * 機体の種別。揚力方式と同じ理由で、それと1つのフィールドにまとめて読む——コーデックのグループが16項目
     * までで、種別は自分が関係する対に相乗りできるほど小さいから。飛行機とヘリコプターの違いはまさに
     * {@code vtol} と {@code rotor} が記述する部分なので、種別はそこに属する。読み書きは {@code "vtol"}
     * や {@code "rotor"} と並ぶ普通の {@code "type"} フィールドとして行われ、独立項目と区別が付かない。
     */
    private static final MapCodec<Pair<VehicleType, Pair<Optional<Vtol>, Optional<Rotor>>>> KIND_AND_LIFT =
            Codec.mapPair(VehicleType.CODEC.optionalFieldOf("type", VehicleType.AIRCRAFT), LIFT_SYSTEM);

    /**
     * 機体が持つ2種類の兵装取り付け先を、1つのフィールドとして読む。理由は上の2つと同じく16項目の上限で、
     * 継ぎ目としてはここが妥当だった——パイロンと砲座はどちらも「兵装がどこに付いているか」の記述であり、
     * 相手が空でも成立する。{@code "hardpoints"} と {@code "stations"} は独立項目とまったく同じに読み書き
     * される。ファイルから見れば実際に独立しているので。
     */
    private static final MapCodec<Pair<List<Hardpoint>, List<Station>>> ARMAMENT =
            Codec.mapPair(Hardpoint.CODEC.listOf().optionalFieldOf("hardpoints", List.of()),
                    Station.CODEC.listOf().optionalFieldOf("stations", List.of()));

    public static final Codec<AircraftDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VehicleChassis.Hitbox.CODEC.optionalFieldOf("hitbox", VehicleChassis.Hitbox.DEFAULT).forGetter(AircraftDefinition::hitbox),
            VehicleChassis.Model.CODEC.optionalFieldOf("model", VehicleChassis.Model.DEFAULT).forGetter(AircraftDefinition::model),
            Engine.CODEC.fieldOf("engine").forGetter(AircraftDefinition::engine),
            Wing.CODEC.fieldOf("wing").forGetter(AircraftDefinition::wing),
            Handling.CODEC.fieldOf("handling").forGetter(AircraftDefinition::handling),
            Airframe.CODEC.fieldOf("airframe").forGetter(AircraftDefinition::airframe),
            Undercarriage.CODEC.fieldOf("landing_gear").forGetter(AircraftDefinition::landingGear),
            Surface.CODEC.fieldOf("flaps").forGetter(AircraftDefinition::flaps),
            VehicleChassis.CameraMount.CODEC.optionalFieldOf("camera", VehicleChassis.CameraMount.DEFAULT).forGetter(AircraftDefinition::camera),
            VehicleChassis.Sound.CODEC.optionalFieldOf("sound", VehicleChassis.Sound.DEFAULT).forGetter(AircraftDefinition::sound),
            // 書いていない機体はレーダーを持たない。地上車両と同じ既定であり、同じ理由だ——「黙っている」
            // は「積んでいる」より「積んでいない」に近い。以前はここが Radar.DEFAULT で、レーダーの
            // 節を書き忘れた機体に3kmの索敵レーダーと4kmの警戒受信機が黙って付いていた。
            VehicleChassis.Radar.CODEC.optionalFieldOf("radar", VehicleChassis.Radar.NONE)
                    .forGetter(AircraftDefinition::radar),
            Signature.CODEC.optionalFieldOf("signature", Signature.DEFAULT).forGetter(AircraftDefinition::signature),
            Countermeasures.CODEC.optionalFieldOf("countermeasures", Countermeasures.DEFAULT)
                    .forGetter(AircraftDefinition::countermeasures),
            KIND_AND_LIFT.forGetter(definition ->
                    Pair.of(definition.type(), Pair.of(definition.vtol(), definition.rotor()))),
            ARMAMENT.forGetter(definition -> Pair.of(definition.hardpoints(), definition.stations())),
            Sync.CODEC.optionalFieldOf("sync", Sync.DEFAULT).forGetter(AircraftDefinition::sync)
    ).apply(instance, (hitbox, model, engine, wing, handling, airframe, landingGear, flaps, camera,
            sound, radar, signature, countermeasures, kindLift, armament, sync) ->
            new AircraftDefinition(hitbox, model, engine, wing, handling, airframe, landingGear, flaps,
                    camera, sound, radar, signature, countermeasures, kindLift.getFirst(),
                    kindLift.getSecond().getFirst(), kindLift.getSecond().getSecond(),
                    armament.getFirst(), armament.getSecond(), sync)));

    /**
     * ゲームが読めるファイルが1つも無い機体に使う値。意図的に大人しい。飛びはするのでゲームは動き続け
     * ログに原因も残るが、誰もこれを本物の数値とは思わない。
     */
    public static final AircraftDefinition FALLBACK = new AircraftDefinition(
            VehicleChassis.Hitbox.DEFAULT,
            VehicleChassis.Model.DEFAULT,
            new Engine(0.02F, 0.02F, 0.06F, 1.0F, Optional.empty(), VehicleChassis.Fuel.DEFAULT, 0.0F),
            new Wing(0.0F, 0.7F, 0.038F, 5.5F, 15.0F, 0.006F, 20.0F, 0.02F, 0.15F, 0.28F, 6.0F, 0.0F,
                    Optional.empty()),
            new Handling(1.5F, 3.0F, 1.0F, 0.25F, 3.0F, 0.85F, 0.06F),
            new Airframe(Airframe.DEFAULT_HEALTH, 1.8F, 3.0F, 0.0F, 0, 0.0F, 0.0F,
                    List.of(VehicleChassis.Seat.at(new Vec3(0.0, 0.5, 0.0))), Optional.empty()),
            new Undercarriage(40, 0.6F, 0.995F, 0.85F, 0.55F, 1.1F, 1.2F, 1.05F, true, Optional.empty()),
            new Surface(20, 0.5F, 0.4F),
            VehicleChassis.CameraMount.DEFAULT,
            VehicleChassis.Sound.DEFAULT,
            VehicleChassis.Radar.NONE,
            Signature.DEFAULT,
            Countermeasures.DEFAULT,
            VehicleType.AIRCRAFT,
            Optional.empty(),
            Optional.empty(),
            List.of(),
            List.of(),
            Sync.DEFAULT);

    /**
     * ヘリコプターか。自分でそう名乗る物と、ローターを持っていてローターに語らせる物の両方。種別がフィー
     * ルドになる前に作られたファイルは種別を書かず飛行機として読まれるが、ヘリコプターらしく飛ばせてきたの
     * はずっと rotor ブロックの方だ。だからどちらでも答えとして受け取り、古いファイルは何も変えずに動き
     * 続ける。
     */
    public boolean isHelicopter() {
        return this.type == VehicleType.HELICOPTER || this.rotor.isPresent();
    }

    /**
     * 車輪で着地して無事に降りられる速度（1tickあたりブロック）。ファイルが自前の値を書いてもよく、無ければ
     * 種別ごとの値になる。両者はかけ離れている——飛行機が着陸する速度は、ヘリコプターなら何かに触れただけで
     * 壊れる速度だ。{@link Undercarriage#landingSpeed} 参照。
     */
    public float landingSpeed() {
        return this.landingGear.landingSpeed().orElse(this.isHelicopter()
                ? Undercarriage.HELICOPTER_LANDING_SPEED
                : Undercarriage.DEFAULT_LANDING_SPEED);
    }



    /** {@link ModelSetup#bones} でボーンに与えられる役割名。 */
    public static final class Bone {
        public static final String ELEVATOR_LEFT = "elevator_left";
        public static final String ELEVATOR_RIGHT = "elevator_right";
        public static final String AILERON_LEFT = "aileron_left";
        public static final String AILERON_RIGHT = "aileron_right";
        public static final String FLAP_LEFT = "flap_left";
        public static final String FLAP_RIGHT = "flap_right";
        /**
         * 可変翼の主翼そのもの。翼根で機体の鉛直軸周りに回り、翼端が尾部へ向かう。名指しするのは付け根の
         * ボーンだけでよい——補助翼もフラップもパイロンも翼の子として一緒に動く。{@code wing.sweep} を
         * 持たない機体では読まれない。
         */
        public static final String WING_LEFT = "wing_left";
        public static final String WING_RIGHT = "wing_right";
        public static final String RUDDER = "rudder";
        public static final String RUDDER_LEFT = "rudder_left";
        public static final String RUDDER_RIGHT = "rudder_right";
        public static final String NOSE_GEAR = "nose_gear";
        public static final String LEFT_GEAR = "left_gear";
        public static final String RIGHT_GEAR = "right_gear";
        public static final String NOSE_GEAR_DOOR = "nose_gear_door";
        public static final String LEFT_GEAR_DOOR = "left_gear_door";
        public static final String RIGHT_GEAR_DOOR = "right_gear_door";
        /**
         * 垂直離着陸が可能な機体のエンジンノズル。ホバリングへ移行する際に下へ振れる。揚力方式のために開く
         * それ以外の物——ファン扉、ロールポスト、補助インテーク——は1つの角度ではなく一連の動きなので、機体の
         * アニメーションファイルに {@code vtol_open} / {@code vtol_closed} として置く。
         * {@link com.ashvehicles.client.model.AircraftAnimations} 参照。
         */
        public static final String NOZZLE = "nozzle";
        /**
         * 左右で別々のボーンを振る機体のための同じ物。
         *
         * <p>ノズルが1つのボーンで済むのは、それが機体の下に1組だけ付いている場合だ。ティルトローター機の
         * ナセルは主翼の両端にあり、モデル上も2つのボーンになる——{@code nozzle} 1つでは片方しか傾かず、
         * 機体は片肺で立ち上がることになる。舵面や可変翼が左右別々に名指しされているのと同じ理由であり、
         * 同じように、どちらを名指ししても振れ方は同じだ。角度は共通で、実機でも2つは常に揃って動く。
         */
        public static final String NOZZLE_LEFT = "nozzle_left";
        public static final String NOZZLE_RIGHT = "nozzle_right";
        /**
         * ヘリコプターのメインローター。自分のマスト回り——つまり機体が何をしていようとモデルの垂直軸回り
         * ——に回る。アニメーションではなくここで名指しするのは、これが一連の動きではなく1つの角度だから。
         * 回転速度は機体が既に知っており、アニメーションファイルではそれが変わるたびにタイミングを取り直す
         * 必要が出る。
         */
        public static final String ROTOR = "rotor";
        /** テールローター。モデルの左右軸回りに回り、ディスクは横を向く。 */
        public static final String TAIL_ROTOR = "tail_rotor";

        private Bone() {
        }
    }

    /**
     * 操縦していないクライアントでこの機体をどう描くか。3つとも {@code AircraftInterpolation} で使われ、
     * その理屈もそこに書いてある。
     *
     * <p>
     *
     * @param correctionTicks 補正を飛ばしきる tick 数。描かれる機体が正直でいられる程度に短く、補正が
     *                        「見かけの速度の段差」にならない程度に長く。サーバー数tick分がどの機体にも合う
     * @param snapDistance これを超える誤差なら滑らせずにその場へ置く距離（ブロック）。通常の飛行で生じる
     *                     どの誤差より大きく、テレポートより小さくすること
     * @param maxPredictionTicks 最後の補正の後、推測航法を信じる tick 数。これを超えたら古い速度で流し続け
     *                           るのではなく機体を返す
     */
    public record Sync(int correctionTicks, double snapDistance, int maxPredictionTicks) {
        public static final Sync DEFAULT = new Sync(5, 8.0, 10);

        public static final Codec<Sync> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("correction_ticks", 5).forGetter(Sync::correctionTicks),
                Codec.DOUBLE.optionalFieldOf("snap_distance", 8.0).forGetter(Sync::snapDistance),
                Codec.INT.optionalFieldOf("max_prediction_ticks", 10).forGetter(Sync::maxPredictionTicks)
        ).apply(instance, Sync::new));
    }

    /** @param maxThrust スロットル全開時の機首方向への加速度
     *  @param throttleRate スロットルキーを押している間の1tickあたりのレバー移動量
     *  @param spoolRate 実推力と指令推力の差を毎tick詰める割合。レバーはエンジンではない。全開を要求された
     *                   ターボファンがそれを出すまで数秒かかり、その待ち時間が離陸滑走の感触の大半を作る。
     *                   1 なら旧挙動（推力がレバーに完全追従）に戻る
     *  @param seaLevelDensity 海面高度の空気密度。推力と揚力への倍率。推力はこれとともに落ち、それが機体の
     *                         上昇限度を作る */
    public record Engine(float maxThrust, float throttleRate, float spoolRate, float seaLevelDensity,
            Optional<Afterburner> afterburner, VehicleChassis.Fuel fuel, float propellerRpm) {
        public static final Codec<Engine> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("max_thrust").forGetter(Engine::maxThrust),
                Codec.FLOAT.fieldOf("throttle_rate").forGetter(Engine::throttleRate),
                Codec.FLOAT.optionalFieldOf("spool_rate", 0.06F).forGetter(Engine::spoolRate),
                Codec.FLOAT.optionalFieldOf("sea_level_density", 1.0F).forGetter(Engine::seaLevelDensity),
                Afterburner.CODEC.optionalFieldOf("afterburner").forGetter(Engine::afterburner),
                // エンジンの一部として書く。燃料はエンジンが消す物であり、機体が持つ独立した設備ではない
                // ——そして機体の最上位コーデックは既に16項目の上限に達している。
                VehicleChassis.Fuel.CODEC.optionalFieldOf("fuel", VehicleChassis.Fuel.DEFAULT)
                        .forGetter(Engine::fuel),
                // プロペラの回転数（毎分）。描画専用で、0 なら回す物を持たないエンジン——ジェットはこれだ。
                // ローターと違い飛び方には何も関与しない。プロペラ機の推力は他の機体と同じく max_thrust が
                // 決めており、ここが決めるのは「その推力を出している間、羽根がどれだけ速く見えるか」だけ。
                Codec.FLOAT.optionalFieldOf("propeller_rpm", 0.0F).forGetter(Engine::propellerRpm)
        ).apply(instance, Engine::new));

        /** プロペラが1tickに回る角度（度）。毎秒20tick、毎分60秒。 */
        public float propellerDegreesPerTick() {
            return this.propellerRpm * 360.0F / (60.0F * 20.0F);
        }
    }

    /**
     * アフターバーナー。タービン後方のジェットパイプへ燃料を吹き込んで点火する装置。
     *
     * <p>単なる「もっとスロットル」ではなく、そう思って調整したファイルは要点を外している。ミリタリー推力の
     * 上限でエンジンは既に持てる物を全部出しており、アフターバーナーがするのは「一度エンジンを通った燃料を、
     * まだ酸素の残っている唯一の場所で燃やす」こと。得られるのは大きな推力と、それ以上に大きな「戦闘機が
     * なりたくない物」全部だ。うるさく、目立ち、そして熱い——最後の1つが重要で、熱源追尾はそれをずっと遠く
     * から見る。だから面白い判断は「積むかどうか」ではなく「いつ点けるか」になる。
     *
     * <p><b>作動条件。</b> 専用キーは無い。スロットルレバーにはミリタリー全開の位置にストッパーがあり、その
     * 先にデテントがある。パイロットは意図的にストッパーを押し越えて再燃焼域へ入れる。ここでは、レバーが既に
     * ストッパーに当たっている状態でスロットルを開き続けることがその「押し越え」になる。ラッチを持っている
     * のは {@code AircraftEntity}。
     *
     * @param thrust 全再燃焼時の {@code max_thrust} への倍率。戦闘機なら1.5倍前後が妥当。2倍を超えると
     *               飛行機ではなくロケット
     * @param lightRate 指令と実際の再燃焼量の差を毎tick詰める割合。エンジンのスプールより速い。バーナーの
     *                  点火はマッチであって、タービンが回転を上げるのとは違うから。1 なら1tickで点く
     * @param heat 全開時の赤外線放射への倍率。これが対価であり、狙われていると分かっているパイロットが再燃焼
     *             を避ける理由。{@link Signature#heat}（機体単体の冷たい状態）に掛かり、2つの積がシーカーの
     *             探している物になる。1 は「最も熱い」
     * @param nozzles 噴流が機体から出る位置。機体自身の軸で（{@code +Z} が機首方向、{@code +Y} がキャノピー
     *                を抜ける上方向）、ジェットパイプ1本につき1項目。空リストなら当たり判定の形状から求めた
     *                尾部の1箇所になる。胴体に埋め込まれた単発機には正しく、左右に離れた双発機には間違い
     */
    public record Afterburner(float thrust, float lightRate, float heat, List<Vec3> nozzles) {
        public static final Codec<Afterburner> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("thrust", 1.5F).forGetter(Afterburner::thrust),
                Codec.FLOAT.optionalFieldOf("light_rate", 0.2F).forGetter(Afterburner::lightRate),
                Codec.FLOAT.optionalFieldOf("heat", 3.0F).forGetter(Afterburner::heat),
                Vec3.CODEC.listOf().optionalFieldOf("nozzles", List.of()).forGetter(Afterburner::nozzles)
        ).apply(instance, Afterburner::new));

        /** その再燃焼量における推力倍率。0 なら1、全開なら {@code thrust}。 */
        public double thrustFactor(float reheat) {
            return 1.0 + Math.max(this.thrust - 1.0F, 0.0F) * Mth.clamp(reheat, 0.0F, 1.0F);
        }

        /** 赤外線放射についての同じ物。上の推力はこれで支払われている。 */
        public float heatFactor(float reheat) {
            return 1.0F + Math.max(this.heat - 1.0F, 0.0F) * Mth.clamp(reheat, 0.0F, 1.0F);
        }
    }

    /**
     * 翼。実際に機体を支えている物。揚力は速度だけの関数ではなく、翼が気流と出会う角度から生まれる。機体が
     * 地面を離れるのに機首上げを要する理由であり、機首を上げすぎると空から落ちる理由でもある。
     *
     * @param maxSpeed 暴走に対する保険であって機体が到達すべき値ではない。最高速度は抗力が既に決めている。
     *                 0以下で無効
     * @param stallSpeed 操縦性の基準速度。これを下回ると舵が効かなくなる
     * @param lift 揚力全体の倍率。揚力係数と対気速度の二乗に掛かるので、その積が重力と釣り合う所が水平飛行
     *             になる
     * @param liftSlope 迎え角1ラジアンあたりに得られる揚力係数。薄翼なら 2π 近く、厚翼ならそれより低い
     * @param stallAngle これを超えると気流が剥離し、揚力が増えずに落ちていく迎え角（度）
     * @param drag 形状抗力。形が支払うコストで、対気速度の二乗に比例
     * @param airBrakeDrag エアブレーキ展開時に {@code drag} へ掛ける倍率。翼が何かしているのではなく気流に
     *                     板を立てているだけなので、独立した値ではなく形状抗力への倍率。そして
     *                     {@code drag} が既に言っている「この機体がどれだけ滑らか(抵抗が少ない)か」に対して
     *                     大きさを決める必要がある。抗力の大きい古い形状の4倍の板は、綺麗な形状の後ろでは
     *                     誤差に埋もれる
     * @param inducedDrag 揚力に伴う抗力。揚力係数の二乗に比例。急旋回で速度が落ちる原因
     * @param lateralDrag 横滑りをどれだけ速く殺すか。胴体は横向きには飛ばない
     * @param groundEffect 地面近くでの揚力増加。自由大気での値に対する割合で、翼幅ほどの高さで消える。機体が
     *                     滑走路から浮き上がる時に乗るクッションであり、着陸最後の数mを漂う原因
     * @param span 翼幅（ブロック）。地面効果が届く高さでもある
     * @param rotateSpeed 昇降舵が初めて機首を滑走路から持ち上げられる速度。これ未満では
     *                    操縦桿をどれだけ引いても機体は転がるだけ。水平尾翼の上に何かを持ち上げるだけの
     *                    空気が流れていないから。離陸がまず滑走で次に機首上げであって、機首を上げて翼が
     *                    追い付くのを待つ物ではない理由がこれ。0 なら失速速度から導く
     * @param sweep 主翼が動く機体ならその可変翼。持たない機体では空で、翼は作られた位置に留まる。
     *              {@link Sweep} 参照
     */
    public record Wing(float maxSpeed, float stallSpeed, float lift, float liftSlope, float stallAngle,
            float drag, float airBrakeDrag, float inducedDrag, float lateralDrag, float groundEffect,
            float span, float rotateSpeed, Optional<Sweep> sweep) {

        /**
         * ファイルが値を書かない場合に機首を上げる、失速速度に対する割合。
         *
         * <p>下ではなく上、それがこの数値の要点。失速速度以下で機首を上げた機体は、下の空気クッションだけ
         * で飛んでいる。滑走路を離れ、クッションから抜け出し、翼が残っていないことに気付いてまた降りる——
         * それを繰り返し、離陸せずに滑走路をイルカ跳びしていく。実際の運用では失速より少し上で機首を上げ、
         * さらに少し上で上昇していく。この値がそれ。
         */
        private static final float DEFAULT_ROTATE_FRACTION = 1.05F;

        /** 機首を上げられる最低速度。未設定なら失速速度から導く。 */
        public float effectiveRotateSpeed() {
            return this.rotateSpeed > 0.0F ? this.rotateSpeed : this.stallSpeed * DEFAULT_ROTATE_FRACTION;
        }

        public static final Codec<Wing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("max_speed", 0.0F).forGetter(Wing::maxSpeed),
                Codec.FLOAT.fieldOf("stall_speed").forGetter(Wing::stallSpeed),
                Codec.FLOAT.fieldOf("lift").forGetter(Wing::lift),
                Codec.FLOAT.optionalFieldOf("lift_slope", 5.5F).forGetter(Wing::liftSlope),
                Codec.FLOAT.optionalFieldOf("stall_angle", 15.0F).forGetter(Wing::stallAngle),
                Codec.FLOAT.fieldOf("drag").forGetter(Wing::drag),
                Codec.FLOAT.optionalFieldOf("air_brake_drag", 20.0F).forGetter(Wing::airBrakeDrag),
                Codec.FLOAT.optionalFieldOf("induced_drag", 0.02F).forGetter(Wing::inducedDrag),
                Codec.FLOAT.optionalFieldOf("lateral_drag", 0.15F).forGetter(Wing::lateralDrag),
                Codec.FLOAT.optionalFieldOf("ground_effect", 0.28F).forGetter(Wing::groundEffect),
                Codec.FLOAT.optionalFieldOf("span", 10.0F).forGetter(Wing::span),
                Codec.FLOAT.optionalFieldOf("rotate_speed", 0.0F).forGetter(Wing::rotateSpeed),
                Sweep.CODEC.optionalFieldOf("sweep").forGetter(Wing::sweep)
        ).apply(instance, Wing::new));

        /**
         * その迎え角でこの翼が作る揚力。気流が翼から剥がれるまで角度とともに順調に増え、それを超えると増え
         * ずに落ちていく。失速とはそれが全部。臨界角の2倍で完全に消える。
         */
        public double liftCoefficient(float angleOfAttackDegrees) {
            double stall = Math.max(this.stallAngle, 1.0F);
            double magnitude = Math.abs(angleOfAttackDegrees);

            if (magnitude <= stall) {
                return this.liftSlope * Math.toRadians(angleOfAttackDegrees);
            }

            double peak = this.liftSlope * Math.toRadians(stall);
            double remaining = Math.max(0.0, 1.0 - (magnitude - stall) / stall);

            return Math.signum(angleOfAttackDegrees) * peak * remaining;
        }
    }

    /**
     * 可変翼。飛ぶ速度域が1つの翼で賄えないほど広い機体が、翼の方を速度に合わせる仕組み。
     *
     * <p><b>パイロットのスイッチではない理由。</b>実機でも後退角は操縦する物ではなく、対気速度（実際には
     * マッハ数）から自動で決まる。前進位置にすべき時と後退位置にすべき時を決めるのは空気の側であって、
     * パイロットが選べる余地は元から無い——選べば必ず間違える。低速で後退させれば翼は揚力を作らず機体は
     * 落ち、高速で前進させれば翼は抗力の塊になり、いずれ構造が保たない。よってここでもキーは無く、機体は
     * {@code spread_speed} 以下で翼を全開前進させ、{@code swept_speed} 以上で全開後退させ、その間を連続
     * に動く。着陸進入に入った機体の翼は、パイロットが何もしなくても既に前へ出ている。
     *
     * <p>段差ではなく傾斜にしてあるのは、境界での往復を防ぐためでもある。閾値1つで切り替える翼は、ちょうど
     * その速度で飛ぶ機体の上で延々と動き続ける。傾斜なら、その速度に対応する後退角で静止する。
     *
     * @param travel 全開前進から全開後退までに翼が回る角度（度）。ジオメトリが翼を置いた位置が0度であり、
     *               ここにあるのは絶対角ではなくそこからの移動量。モデルは前進位置で作る
     * @param spreadSpeed これ以下では翼が全開前進する対気速度（ブロック/tick）
     * @param sweptSpeed これ以上では翼が全開後退する対気速度。{@code spreadSpeed} より大きいこと
     * @param cycleTicks 端から端まで動くのに要するtick数。翼を駆動する装置の速さであり、これがあるので
     *                   加速中の機体の翼は速度に瞬時には追い付かない
     * @param lift 全開後退時の揚力倍率。後退翼は気流を斜めに受けるので、同じ迎え角で作る揚力が減る。1で無効
     * @param drag 全開後退時の形状抗力倍率。減らすためにこそ翼を後退させるので、こちらは1未満になる。1で無効
     */
    public record Sweep(float travel, float spreadSpeed, float sweptSpeed, int cycleTicks,
            float lift, float drag) {

        public static final Codec<Sweep> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("travel").forGetter(Sweep::travel),
                Codec.FLOAT.fieldOf("spread_speed").forGetter(Sweep::spreadSpeed),
                Codec.FLOAT.fieldOf("swept_speed").forGetter(Sweep::sweptSpeed),
                Codec.INT.optionalFieldOf("cycle_ticks", 60).forGetter(Sweep::cycleTicks),
                Codec.FLOAT.optionalFieldOf("lift", 1.0F).forGetter(Sweep::lift),
                Codec.FLOAT.optionalFieldOf("drag", 1.0F).forGetter(Sweep::drag)
        ).apply(instance, Sweep::new));

        /**
         * その対気速度で翼が居るべき位置。0が全開前進、1が全開後退。
         *
         * <p>2つの速度が逆順に書かれたファイル——あるいは同じ値が2つ書かれたファイル——でも0除算にはせず、
         * 前進端に貼り付ける。翼を持たない機体と同じ振る舞いになるので、間違いは静かで、直せば直る。
         */
        public float progressAt(double speed) {
            float band = this.sweptSpeed - this.spreadSpeed;

            if (band <= 0.0F) {
                return 0.0F;
            }

            return Mth.clamp((float) (speed - this.spreadSpeed) / band, 0.0F, 1.0F);
        }

        /** その位置での後退角（度）。モデルにも計器にも同じ値を渡す。 */
        public float angle(float progress) {
            return progress * this.travel;
        }

        /** その位置での揚力倍率。 */
        public float liftFactor(float progress) {
            return Mth.lerp(progress, 1.0F, this.lift);
        }

        /** その位置での形状抗力倍率。 */
        public float dragFactor(float progress) {
            return Mth.lerp(progress, 1.0F, this.drag);
        }
    }

    /**
     * 操縦系が何をするか。各レートは舵を一杯に切った時に「最終的に」達する値であって、即座に達する値では
     * ない。舵面は機体の質量を相手に仕事をするので。
     *
     * @param controlLag 現在レートと指令レートの差を毎tick詰める割合。1 は旧来の即応。小さいほど機体に
     *                   重さが出る
     * @param weathervane 機首が飛行経路の方へ引き戻される角速度（1tickあたり度）。垂直尾翼が仕事をしている
     *                    分であり、バンクを旋回に変える物。翼が機体を引き回し、尾翼が進行方向へ機首を向ける
     * @param alphaLimit パイロットが到達を許される失速角の割合。昇降舵は翼が追従できる何倍もの速さで機首を
     *                   振れるので、操縦桿を引ききったパイロットは0.3秒で失速に達し旋回が完全に止まる。この
     *                   値は機体を「翼が最も強く引く角度」に保つ。それがその機体に可能な最小旋回半径になる。
     *                   1以上にすれば失速の管理をパイロットへ返す
     * @param aeroDamping 気流が機体の回転にどれだけ抵抗するか。対気速度の二乗に比例。パイロットに舵効きを
     *                    与えるのと同じ面が、その舵が起こした回転を減衰させる。速い機体が神経質ではなく
     *                    硬く感じる理由。これが無いと舵効きだけが速度とともに増え、それを収める物が何も
     *                    増えないので機体がふらつく。0で無効
     */
    public record Handling(float pitchRate, float rollRate, float yawRate, float controlLag,
            float weathervane, float alphaLimit, float aeroDamping) {

        public static final Codec<Handling> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("pitch_rate").forGetter(Handling::pitchRate),
                Codec.FLOAT.fieldOf("roll_rate").forGetter(Handling::rollRate),
                Codec.FLOAT.fieldOf("yaw_rate").forGetter(Handling::yawRate),
                Codec.FLOAT.optionalFieldOf("control_lag", 0.25F).forGetter(Handling::controlLag),
                Codec.FLOAT.optionalFieldOf("weathervane", 3.0F).forGetter(Handling::weathervane),
                Codec.FLOAT.optionalFieldOf("alpha_limit", 0.85F).forGetter(Handling::alphaLimit),
                Codec.FLOAT.optionalFieldOf("aero_damping", 0.06F).forGetter(Handling::aeroDamping)
        ).apply(instance, Handling::new));
    }

    /**
     * @param health 機体構造が壊れるまでに受けられる量（ヒットポイント）。ダメージは点数通りに引かれる。
     *               プレイヤーのハート2個分の機関砲弾は、機体からはこの単位で4を奪う。省略時は
     *               {@link #DEFAULT_HEALTH}
     * @param crashSpeed これを超える速度で何かに当たると機体が全損する衝突速度
     * @param maxG 機体構造が自重の何倍まで耐えるか。これより強く引くと壊れ始める。0以下なら決して壊れない
     * @param salvage 撃破後、レンチを持った者が回収できる金属量（鉄インゴット換算）。省略すれば耐久から
     *                計算する
     * @param mass 何も吊っていないこの機体が量る重さ（kg）。実機の「装備重量」——燃料と乗員を積み、
     *             翼下は空——をそのまま書く。F-16 なら 12000。<b>ファイルに書かれた飛行性能はすべてこの
     *             重さでの値だ。</b>翼下に吊った物の重さはこれに足され、推力・揚力・抗力はその比で割られる
     *             ので、満載の機体は加速も上昇も旋回も鈍り、失速速度が上がる。0にすれば重さは何も
     *             しない——これを書く前の全機体がそうだった
     * @param payload パイロンに吊れる合計重量（kg）。実機の「最大兵装搭載量」をそのまま書く。ラック・
     *                兵装・ポッドの重さの合計がこれを超える搭載はそもそも受け付けられず、地上要員は次の
     *                1発を吊らない。0なら無制限。{@link com.ashvehicles.weapon.WeaponMounts#storeMass()}
     *                参照
     * @param seats 座席1つにつき1項目。機体自身の軸で x が右、y が上、z が機首方向。項目数が搭乗可能人数。
     *              各要素は裸の点か、その乗員がどこから外を見るかも書いたブロック。複座機が欲しいのは後者。
     *              {@link VehicleChassis.Seat} 参照
     */
    public record Airframe(float health, float crashSpeed, float explosionPower, float maxG,
            int salvage, float mass, float payload, List<VehicleChassis.Seat> seats,
            Optional<Ejection> ejection) {

        /** ファイルに書かれていない場合の機体の耐久値。 */
        public static final float DEFAULT_HEALTH = 300.0F;

        public static final Codec<Airframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("health", DEFAULT_HEALTH).forGetter(Airframe::health),
                Codec.FLOAT.fieldOf("crash_speed").forGetter(Airframe::crashSpeed),
                Codec.FLOAT.fieldOf("explosion_power").forGetter(Airframe::explosionPower),
                Codec.FLOAT.optionalFieldOf("max_g", 0.0F).forGetter(Airframe::maxG),
                Codec.INT.optionalFieldOf("salvage", 0).forGetter(Airframe::salvage),
                Codec.FLOAT.optionalFieldOf("mass", 0.0F).forGetter(Airframe::mass),
                Codec.FLOAT.optionalFieldOf("payload", 0.0F).forGetter(Airframe::payload),
                VehicleChassis.Seat.CODEC.listOf().fieldOf("seats").forGetter(Airframe::seats),
                Ejection.CODEC.optionalFieldOf("ejection").forGetter(Airframe::ejection)
        ).apply(instance, Airframe::new));

        /**
         * 吊り物の重さを与えたときの、機体が持ち上げている物の総重量に対する空虚時の比。1で「ファイル
         * に書かれた通りの機体」、2で「その2倍の重さの機体」。
         *
         * <p>推力も揚力も抗力も、この値で割られて加速度になる。力は重さを知らないが、加速度は知っている
         * ——同じ主翼が2倍の質量を引き回せば、旋回半径は2倍になる。重力だけは割らない。重力は力ではなく
         * 加速度としてこの MOD に入っているので、重い機体も軽い機体も同じ速さで落ちる。実際そうだ。
         *
         * <p>重さを書いていない機体は常に1を返す。書く前の全機体が、書いた後もまったく同じに飛ぶ。
         */
        public double burden(float carried) {
            return this.mass > 0.0F ? (this.mass + Math.max(carried, 0.0F)) / this.mass : 1.0;
        }
    }

    /**
     * 射出座席。この項目が無い機体からは、自分で扉を開けて出るしかない。
     *
     * <p>座席の下から見た機体の上方向へ押し出すのであって、真上ではない。背面飛行中に引いたハンドルは
     * 乗員を地面へ撃ち出す——実機がそうであり、低空で背面に入った機体が取り返しの付かないものである理由
     * でもある。
     *
     * <p>落下ダメージ自体は {@link com.ashvehicles.entity.CrewSafety} が既に免除している。傘はその
     * ためにあるのではなく、降りてくる時間を作るためにある。300ブロックから落ちるのに4秒しかかからない
     * なら、パイロットには自分がどこへ降りるかを見る間も無い。
     *
     * @param speed 座席が乗員を押し出す初速（ブロック/tick）。機体の速度に上乗せされる
     * @param canopy 座席を離れてから傘が開くまでの時間（tick）。0なら開かない——傘の無い座席で、
     *               乗員は自由落下のまま降りる
     */
    public record Ejection(float speed, int canopy) {
        public static final Ejection DEFAULT = new Ejection(1.4F, 25);

        public static final Codec<Ejection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("speed", DEFAULT.speed()).forGetter(Ejection::speed),
                Codec.INT.optionalFieldOf("canopy", DEFAULT.canopy()).forGetter(Ejection::canopy)
        ).apply(instance, Ejection::new));
    }



    /**
     * 他人のレーダーから見てこの機体がどれだけ大きく見えるか。
     *
     * <p>実際の<em>大きさ</em>ではない。レーダー反射は大きさよりも形状と表面の材質でずっと大きく変わる。
     * 照射された波を散らすよう作られた機体は戦闘機サイズで鳥並みの反射しか返さないし、パイロンとミサイルを
     * ぶら下げた機体は機体単体よりはるかに多く返す。ここにあるのはそれだけ——清浄形態の機体の数値と、外部に
     * 付けた物1つあたりの上乗せ分。
     *
     * <p><b>距離との関係は線形ではない。</b> ある目標に対するレーダーの探知距離は反射断面積の4乗根に比例
     * する。反射が距離の4乗で減衰するからだ——つまり反射が1/16の目標は1/16の距離ではなく半分の距離で見え
     * る。ステルスの価値は大きいが全能ではない。航空機設計者が付き合っているのと同じ算術。
     *
     * @param radar 清浄形態の反射断面積。通常の戦闘機を1.0とする。1/10なら見つけにくく、1/100なら極めて
     *              見つけにくい。0なら不可視だが、そんな物は存在しない
     * @param store <em>外部</em>搭載物1つあたりの上乗せ分。ウェポンベイ内の物は0で、ベイとはそのための物。
     *              {@link Hardpoint#internal()} 参照
     */
    public record Signature(float radar, float store, float heat) {
        public static final Signature DEFAULT = new Signature(1.0F, 0.2F, 1.0F);

        public static final Codec<Signature> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("radar", DEFAULT.radar()).forGetter(Signature::radar),
                Codec.FLOAT.optionalFieldOf("store", DEFAULT.store()).forGetter(Signature::store),
                Codec.FLOAT.optionalFieldOf("heat", DEFAULT.heat()).forGetter(Signature::heat)
        ).apply(instance, Signature::new));

        /**
         * 熱源追尾ヘッドがこれをどれだけ遠くから見るか。そのファイルが与える距離に対する割合。
         *
         * <p>上のレーダーが4乗根なのに対しこちらは平方根で、その差は数値の趣味ではなく物理だ。レーダーは
         * 目標を照らしてから返ってきた物を捉えるので、探知距離は反射の4乗根に比例する。熱源追尾は聞いて
         * いるだけで、聞こえる量は距離の二乗だけで減衰する。だから熱を3倍にするバーナーは距離をほぼ2倍に
         * するが、レーダー反射を3倍にしてもほとんど動かない。
         *
         * <p>上限は1。レーダー側と同じ理由だが、こちらはもっと強い理由がある。シーカーが検討するのは掃引が
         * 既に見つけた物だけで、その掃引はシーカー自身の探知距離と同じ大きさの箱——サーバーが投げる最も高価
         * な問いであり、熱い目標のために広げれば体積分そのままコストを払うことになる。だから
         * {@code lock_range} は「シーカーが見る中で最も熱い物」——全再燃焼中の通常の戦闘機——に対する距離
         * とする。それより冷たい物は近づかないと見つからない。この値がやっているのはそれだけ。
         *
         * <p>だからファイルはこう書くのがよい。バーナーを持つ機体は {@code heat} を<em>ミリタリー推力</em>
         * での値（1をかなり下回る）にし、パイロットが点火した時に {@code afterburner.heat} が上限へ押し
         * 戻す。1のままなら常に最大限見えることになり、それは隠すバーナーを持たない機体には正しい答え。
         */
        public static float heatReach(float heat) {
            return (float) Math.min(Math.sqrt(Math.max(heat, 0.0F)), 1.0);
        }

        /**
         * レーダーがこれをどれだけ遠くから見るか。通常の戦闘機に対する距離に対する割合。
         *
         * @param cross 探している反射断面積。機体と搭載物の合計
         */
        public static float reach(float cross) {
            // レーダー自身の探知距離を超えさせない。通常の戦闘機より大きい反射は、そうしないとレーダー
            // ファイルの距離を超えた所で見つかってしまい、あの数値が何も意味しなくなる。小さい反射は近づか
            // ないと見つからない。この関数の役目はそれだけ。
            return (float) Math.min(Math.pow(Math.max(cross, 0.0F), 0.25), 1.0);
        }
    }

    /**
     * 推力偏向による揚力方式。下へ振れるノズルと、そこから派生する物すべて。他と同じように滑走路を使う
     * しかない機体では省略する。
     *
     * <p>ノズルを90度にすることで、エンジンは「機体を前へ押す物」から「機体を支える物」に変わる。飛行モデル
     * に追加で教えることは何も無い——機体が止まれば翼は勝手に揚力を作らなくなるし、重力は最初からそこにある。
     * 以下の3つの数値は、それが成立するためにエンジンが持つべき値、翼が降りた後に機体を操る物、そしてホバー
     * が滑走にならないようにする物。
     *
     * @param maxAngle ノズルが振れる角度（度）。90 が真下
     * @param rate 振れる速さ（1tickあたり度）。よって90度への完全な転換には {@code maxAngle / rate} tick
     *             かかる
     * @param liftThrust ノズル全下げ時にエンジンが出せる加速度（1tick二乗あたりブロック）。
     *                   <b>重力に勝つ必要がある</b>——{@value #GRAVITY_NOTE}——さもないと機体はホバリング
     *                   できず、ゆっくり落ちるだけになる。両端の間では通常の {@code engine.max_thrust} と
     *                   ブレンドされるので巡航推力には影響しない
     * @param authority ノズル全下げ時に姿勢制御ノズルが与える操縦性。飛行速度の翼が与える分に対する割合。
     *                 これが無いとホバリング中の機体は操縦性ゼロになる。何の上にも空気が流れていないので
     * @param hoverDrag ホバー時に横方向の流れをどれだけ速く殺すか（1tickあたり）。上の空力抗力とは無関係。
     *                 あちらは歩く速度では何もしない
     * @param conversionSpeed ノズルを<em>下げ</em>られる最大速度（1tickあたりブロック）。高速でエアブレー
     *                        キ代わりに使われるのを防ぐ。戻す方向は決して拒否しない。このレバーは緊急時の
     *                        答えであるべきで、「既に速い機体でしか格納できないノズル」は「速くない機体が
     *                        格納できないノズル」だから
     */
    public record Vtol(float maxAngle, float rate, float liftThrust, float authority, float hoverDrag,
            float conversionSpeed) {

        /** 上のドキュメントから参照される。超えるべき値を、超える物のすぐ隣に書いておくため。 */
        static final String GRAVITY_NOTE = "0.02453 blocks per tick squared";

        public static final Vtol DEFAULT = new Vtol(90.0F, 1.0F, 0.030F, 0.9F, 0.06F, 2.2F);

        public static final Codec<Vtol> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("max_angle", DEFAULT.maxAngle()).forGetter(Vtol::maxAngle),
                Codec.FLOAT.optionalFieldOf("rate", DEFAULT.rate()).forGetter(Vtol::rate),
                Codec.FLOAT.optionalFieldOf("lift_thrust", DEFAULT.liftThrust()).forGetter(Vtol::liftThrust),
                Codec.FLOAT.optionalFieldOf("authority", DEFAULT.authority()).forGetter(Vtol::authority),
                Codec.FLOAT.optionalFieldOf("hover_drag", DEFAULT.hoverDrag()).forGetter(Vtol::hoverDrag),
                Codec.FLOAT.optionalFieldOf("conversion_speed", DEFAULT.conversionSpeed())
                        .forGetter(Vtol::conversionSpeed)
        ).apply(instance, Vtol::new));

        /** ノズルが格納位置から全下げまで動くのにかかる tick 数。 */
        public int cycleTicks() {
            return (int) Math.max(this.maxAngle / Math.max(this.rate, 1.0E-3F), 1.0F);
        }
    }

    /**
     * 揚力を生むローター。これがあると機体はまったく別物になる。存在すればヘリコプターとなり、翼の飛行
     * モデルではなく {@code AircraftEntity} のローター飛行モデルが使われる。無ければ飛行機のまま。
     *
     * <p><b>ヘリコプターとは何か。</b> 飛行機は前へ投げ出され、通り抜ける空気で自分を支えるので、動き続け
     * なければならず、機首の向く方へ進む。ヘリコプターは自分の気流を持ち歩く。ローターはディスクに直交する
     * 単一の力を作り、その力が機体の全部だ。ディスクを前へ傾ければ前へ進み、横へ傾ければ横へ進み、水平なら
     * その場に留まる。他に機体を押す物は無い。だからどのヘリコプターも巡航中は機首下げだし、落ちずに空中で
     * 停止できる——飛行機には決してできない2つのこと。
     *
     * <p><b>パイロットが持つ物。</b> コレクティブ（スロットルレバーで、ローターの引く強さを決める）、
     * サイクリック（ピッチとロールの操縦桿で、ディスクを向ける）、そしてペダル（テールローターで、機体を
     * 動かさずに機首を振る）。3つとも静止状態で効く。機体がどこかへ行っているかに関わらずローターは回って
     * いるから。それが {@link Vtol}——1分だけエンジンを借りる飛行機——との違いの全部。
     *
     * <p><b>他所から読む値。</b> wing ブロックは今も適用され、今も文字通りの意味を持つ。{@code drag} は
     * 胴体が支払うコストで最高速度を決め、{@code lateral_drag} は垂直尾翼、{@code max_speed} は超過禁止
     * 速度。ただし3つは設定前に読み直す価値がある。{@code span} は地面クッションが届く高さで、ヘリコプター
     * では翼幅ではなくローターの<em>直径</em>。{@code stall_speed} は垂直尾翼が効き始める基準でしかない
     * （ここでは何も失速しないので）。そして {@code lift} は翼が本当に飛ぶ機体——複合ヘリコプターやオート
     * ジャイロ——のための物で、普通の攻撃ヘリでは0が最善。迎え角は胴体基準で測られ、普通のヘリコプターは
     * かなり機首下げで巡航するので、スタブ翼にそれを負の迎え角として読ませれば、支えるどころか機体を地面へ
     * 押し付ける。実際の寄与は小さく、これはそれを「モデル化しない」より悪くモデル化する。
     *
     * @param lift コレクティブ全開・回転数全開でローターが作る加速度（1tick二乗あたりブロック）。
     *             <b>重力に勝つ必要があり</b>（{@value Vtol#GRAVITY_NOTE}）、しかも余裕を持って。その差が
     *             上昇・旋回・搭載に使える全部だから
     * @param spoolTicks 静止状態からローターが回転数に達するまでの時間。始動スイッチは無い。座席に座ること
     *                   がスイッチで、これはその後の待ち時間。実際の乗員も同じ待ち時間を持つし、ヘリコプター
     *                   に飛び乗ってそのまま飛び去れない理由でもある
     * @param translationalLift 機体が動き始めた後の追加揚力（割合）。ホバー中のローターは自分が既に使った
     *                          空気を叩いている。動かせば各ブレードが乱されていない空気に届く。垂直には自分
     *                          を持ち上げられないヘリコプターが、地面を滑りながらなら飛び去れることが多い
     *                          理由
     * @param translationalSpeed その効果が完全に効く速度（1tickあたりブロック）。ホバー減衰を分布させる
     *                           基準でもある
     * @param authority 回転数全開時にローターが与える操縦性。飛行機の舵面が失速速度で持つ分を基準にする。
     *                 ヘリコプターが静止中も高速時と同じだけ操縦できる理由
     * @param maxTilt ディスクを水平からどれだけ傾けられるか（度）。つまりどれだけ強く加速させられるか。
     *                <b>サイクリックはディスクを動かしてそこへ置いてくる</b>。キーを離した瞬間に戻ったり
     *                しない。それは現代のヘリコプターが全部積んでいる姿勢保持装置の仕事であり、キーボードで
     *                巡航を要求する唯一の方法でもある。キーは全押しか非押しのどちらかなので、水平へ戻る
     *                操縦桿では機体に「ホバー」と「全力突進」の2設定しか無く、その間が無くなる。この角度を
     *                超えたら戻されるので、パイロットにも他の何にもヘリコプターをひっくり返せない
     * @param trim サイクリックがディスクを動かす速さ。{@link Handling} のレートに対する割合。1 ならレート
     *             全部を操縦桿へ渡すことになり、キーボードではキーに触れた瞬間に最大傾斜へ飛ぶ機体になる。
     *             0.5 なら1秒程度の移動時間があり、途中のどこでも止められる
     * @param stability {@code max_tilt} を超えたディスクをどれだけ強く戻すか。超過1度につき1tickあたりの
     *                  回転角（度）。限界内にいる間はまったく効かず、機体は生涯そこで過ごす
     * @param hoverDrag ホバー時に流れをどれだけ速く殺すか（1tickあたり）。機体が速度を得るにつれ消えていく。
     *                 これが無いと横へ小突かれたヘリコプターは横へ進み続ける。全速度域で効かせればホバー
     *                 ではなく駐車ブレーキになる
     * @param discDrag 機体を真上・真下へ動かす難しさ。昇降率の二乗に比例。ローターとその下に吊られた物は、
     *                 下から来る空気には巨大な平面を、前から来る空気にはほぼ何も差し出さない。胴体自身の抗力
     *                 ではどちらの数値もまるで説明できない。昇降率がフィート毎分で語られる隣で速度がノットで
     *                 語られる理由がこれ。コレクティブを下げたヘリコプターが落ちる時に抗う相手でもあり、
     *                 ローターに比例するので、ローターが止まった機体は今やただの金属塊として落ちる
     * @param bluffDrag 機体を真後ろへ飛ばす時に胴体自身の抗力の何倍かかるか。真正面へ飛ぶ場合の1へ滑らかに
     *                 補間される。胴体は前へ進むための形で、向きを変えれば側面全部を空気へ差し出す。その差
     *                 だけが、ヘリコプターが後進で前進最高速度に達するのを防いでいる。実機の後進・横進の限界
     *                 が前進の何分の一かであるのはこのため。1 にすると機体は後ろ向きでも前向きと同じくらい
     *                 快適に飛ぶ
     * @param torque コレクティブ全開時に、吊られているローターから胴体が押し回されるヨー角速度（1tickあたり
     *               度）。正で機首が右へ振れる。上から見て反時計回りのローターがそうする。逆向きに作られた
     *               機体は負の値を取り、0 ならペダルはパイロットだけの物になる。コレクティブに追従するので、
     *               上昇を要求するたびに機首が回っていく感触になる——手動で飛ばすことの大半がそれ
     * @param rpm メインローターの回転数（毎分）。描画専用
     * @param tailRpm テールローターの同じ物。数倍速く回る。描画専用
     */
    public record Rotor(float lift, int spoolTicks, float translationalLift, float translationalSpeed,
            float authority, float maxTilt, float trim, float stability, float hoverDrag,
            float discDrag, float bluffDrag, float torque, float rpm, float tailRpm) {

        public static final Rotor DEFAULT = new Rotor(0.034F, 90, 0.15F, 0.8F, 1.0F, 22.0F, 0.5F,
                0.15F, 0.02F, 0.025F, 10.0F, 0.0F, 300.0F, 1500.0F);

        public static final Codec<Rotor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("lift", DEFAULT.lift()).forGetter(Rotor::lift),
                Codec.INT.optionalFieldOf("spool_ticks", DEFAULT.spoolTicks()).forGetter(Rotor::spoolTicks),
                Codec.FLOAT.optionalFieldOf("translational_lift", DEFAULT.translationalLift())
                        .forGetter(Rotor::translationalLift),
                Codec.FLOAT.optionalFieldOf("translational_speed", DEFAULT.translationalSpeed())
                        .forGetter(Rotor::translationalSpeed),
                Codec.FLOAT.optionalFieldOf("authority", DEFAULT.authority()).forGetter(Rotor::authority),
                Codec.FLOAT.optionalFieldOf("max_tilt", DEFAULT.maxTilt()).forGetter(Rotor::maxTilt),
                Codec.FLOAT.optionalFieldOf("trim", DEFAULT.trim()).forGetter(Rotor::trim),
                Codec.FLOAT.optionalFieldOf("stability", DEFAULT.stability()).forGetter(Rotor::stability),
                Codec.FLOAT.optionalFieldOf("hover_drag", DEFAULT.hoverDrag()).forGetter(Rotor::hoverDrag),
                Codec.FLOAT.optionalFieldOf("disc_drag", DEFAULT.discDrag()).forGetter(Rotor::discDrag),
                Codec.FLOAT.optionalFieldOf("bluff_drag", DEFAULT.bluffDrag()).forGetter(Rotor::bluffDrag),
                Codec.FLOAT.optionalFieldOf("torque", DEFAULT.torque()).forGetter(Rotor::torque),
                Codec.FLOAT.optionalFieldOf("rpm", DEFAULT.rpm()).forGetter(Rotor::rpm),
                Codec.FLOAT.optionalFieldOf("tail_rpm", DEFAULT.tailRpm()).forGetter(Rotor::tailRpm)
        ).apply(instance, Rotor::new));

        /** メインローターが1tickに回る角度（度）。毎秒20tick、毎分60秒。 */
        public float degreesPerTick() {
            return this.rpm * 360.0F / (60.0F * 20.0F);
        }

        /** テールローターの同じ物。 */
        public float tailDegreesPerTick() {
            return this.tailRpm * 360.0F / (60.0F * 20.0F);
        }
    }

    /**
     * 誰かの照準を狂わせるために機体が後方へ放出できる物。
     *
     * <p>2種類あり、どちらを取るかは警戒受信機が今答えたばかりの問いだ。フレアはエンジンより熱い火で熱源
     * 追尾を騙し、チャフは金属箔の雲でレーダー追尾を騙す。間違えた方を撃つのは何も撃たないのと同じで、それ
     * が受信機を「無視する物」ではなく「読む価値のある物」にしている。
     *
     * @param flares 搭載数。積まない機体は0
     * @param chaff もう一方の同じ物
     * @param intervalTicks 投射機が次を放出できるまでの速さ。押しっぱなしならこれが消費速度になる
     * @param reloadTicks 駐機状態で地上要員が満載1回分を補充する時間。どれだけ減っていても満載分として数える
     * @param speed 機体からどれだけ強く放り出されるか（1tickあたりブロック）
     */
    public record Countermeasures(int flares, int chaff, int intervalTicks, int reloadTicks, float speed) {
        public static final Countermeasures DEFAULT = new Countermeasures(30, 30, 6, 300, 0.35F);

        public static final Codec<Countermeasures> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.optionalFieldOf("flares", DEFAULT.flares()).forGetter(Countermeasures::flares),
                Codec.INT.optionalFieldOf("chaff", DEFAULT.chaff()).forGetter(Countermeasures::chaff),
                Codec.INT.optionalFieldOf("interval_ticks", DEFAULT.intervalTicks()).forGetter(Countermeasures::intervalTicks),
                Codec.INT.optionalFieldOf("reload_ticks", DEFAULT.reloadTicks()).forGetter(Countermeasures::reloadTicks),
                Codec.FLOAT.optionalFieldOf("speed", DEFAULT.speed()).forGetter(Countermeasures::speed)
        ).apply(instance, Countermeasures::new));

        /** その種類を満載時に何個積むか。 */
        public int capacity(boolean flare) {
            return flare ? this.flares : this.chaff;
        }
    }

    /**
     * 何かが吊られる場所。兵装はすべて機首方向へ真っ直ぐ撃つので、この点は「弾が出る位置」であり「搭載物が
     * 描かれる位置」でもある。
     *
     * <p>{@code fixed} 兵装を持つハードポイントは機体構造の一部。常にその兵装を積み、他は吊れず、そこには
     * 何も描かれない（機体自身のモデルが既に描いているので）。それ以外はプレイヤーが積むステーションで、
     * 2種類ある。{@link Kind} 参照。
     *
     * @param name ログ用およびステーションを見分けるためのラベル。プレイヤーには表示しない
     * @param pos 機体自身の軸での位置。x が右、y が上、z が機首方向。砲なら砲口に置く
     * @param fixed ここに内蔵された兵装。プレイヤーが積むステーションでは空
     * @param wingtip 翼の先端のステーションか。{@link #wingtip()} 参照
     * @param kind このステーションが兵装を積むかポッドを積むか。fixed ステーションではどちらでもないので
     *             無視される
     */
    public record Hardpoint(String name, Vec3 pos, Optional<ResourceLocation> fixed, boolean internal,
            boolean wingtip, Kind kind, List<ResourceLocation> ammunition) {

        public static final Codec<Hardpoint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(Hardpoint::name),
                Vec3.CODEC.fieldOf("pos").forGetter(Hardpoint::pos),
                ResourceLocation.CODEC.optionalFieldOf("fixed").forGetter(Hardpoint::fixed),
                Codec.BOOL.optionalFieldOf("internal", false).forGetter(Hardpoint::internal),
                Codec.BOOL.optionalFieldOf("wingtip", false).forGetter(Hardpoint::wingtip),
                Kind.CODEC.optionalFieldOf("kind", Kind.WEAPON).forGetter(Hardpoint::kind),
                // 内蔵砲に積める弾種。地上車両の架台が並べる物とまったく同じで、同じファイルを指す。
                //
                // 機体の砲は弾倉を1本しか持たない。地上の砲塔は砲弾を種類ごとに棚へ積み分け、装填手が
                // 車長の呼んだ1発を薬室へ送る——だから切り替えられる。機体の機関砲にあるのはベルト1本で、
                // 積むのは地上作業だ。飛びながら弾種を替えられないのはそのためで、この一覧が言うのは
                // 「地上で何を積めるか」になる。{@code WeaponMounts.loadRound} 参照。
                //
                // 吊り物のパイロンでは意味を持たない。あちらに載るのは兵装そのもので、兵装は自分の弾を
                // 持っている。
                ResourceLocation.CODEC.listOf().optionalFieldOf("ammunition", List.of())
                        .forGetter(Hardpoint::ammunition)
        ).apply(instance, Hardpoint::new));

        public boolean isFixed() {
            return this.fixed.isPresent();
        }

        /** そもそもプレイヤーが積むステーションか。機体に内蔵されていない物すべて。 */
        public boolean isPylon() {
            return !this.isFixed();
        }

        /** このステーションが兵装（ラックと、その上の搭載物）を取るか。 */
        public boolean isWeaponPylon() {
            return this.isPylon() && this.kind == Kind.WEAPON;
        }

        /** このステーションがポッドを取るか。ここから何かが撃たれることは無い。 */
        public boolean isSpecialPylon() {
            return this.isPylon() && this.kind == Kind.SPECIAL;
        }

        /**
         * ここに吊る物が機内に収まるか。
         *
         * <p>気にするのはレーダーだけ。ウェポンベイ内の搭載物は誰にも見えない場所で運ばれ、機体の反射を
         * 何も増やさない。同じ物を翼下のレールに吊れば、それはステルス機にボルト留めしたコーナーリフレクタ
         * であり、形状が買った物のかなりを台無しにする。{@link Signature} 参照。
         */
        public boolean internal() {
            return this.internal;
        }

        /**
         * ここが翼の先端か。F-16 の翼端レールのように、翼の下ではなく翼が終わる場所そのものに付く
         * ステーション。
         *
         * <p>気にするのは何が付くかだけ。翼端には{@link com.ashvehicles.weapon.RackDefinition#wingtip()
         * 翼端レール}しか付かず、その翼端レールは翼端にしか付かない
         * （{@link com.ashvehicles.weapon.WeaponMounts#canFitRackAt} 参照）。翼端は下面を持たないので
         * 投下ラックを吊る場所が無く、逆に翼端レールは翼桁の先端へボルト留めする金具であって、パイロンの
         * 下に吊れる物ではない。どちらの向きの禁止も同じ1つの事実から来ている。
         */
        public boolean wingtip() {
            return this.wingtip;
        }

        /**
         * ステーションの用途。2種類は互換ではなく、2種類ある意味がまさにそこにある。機体の兵装と装備は
         * 別々に積まれ何も奪い合わないので、照準ポッドを吊ってもミサイルは減らないし、フル武装でもセンサー
         * ステーションは空いたまま。
         */
        public enum Kind implements StringRepresentable {
            /**
             * 兵装用。直接は何も吊らない。まず {@link com.ashvehicles.weapon.RackDefinition ラック}——
             * ランチレールや投下ラック——を付け、搭載物はその上に載る。どのラックを付けるかが、そのステー
             * ションの搭載数・各搭載位置・受ける種類を決める。
             */
            WEAPON("weapon"),
            /**
             * 装備用。照準ポッド、ジャマー、デコイ発射機など。
             * {@link com.ashvehicles.weapon.EquipmentDefinition ポッド} を1つ、ラックを挟まずステーション
             * へ直付けし、積んでいる限り自分の仕事をする。ここの物が選択されたり撃たれたりすることは無い。
             */
            SPECIAL("special");

            public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

            private final String name;

            Kind(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }
        }
    }

    /**
     * 乗員が振れる砲。パイロンの上の砲を、操縦していない者が自分の視線で照準し自分の引き金で撃つ。
     *
     * <p><b>砲そのものはここに無い。</b>ここが名指しするのはハードポイントで、何が載っているか・残弾は
     * いくつか・地上要員がどう補給するかは、翼下のレールとまったく同じく {@link Hardpoint} と
     * {@link com.ashvehicles.weapon.WeaponMounts} の管轄のままだ。砲座が足すのは3つだけ——誰が撃つのか、
     * どこまで振れるのか、そして今どこを向いているのか。だから機体に組み込まれた機関砲（{@code fixed} の
     * ハードポイント）も、プレイヤーが吊ったガンポッドも、同じ1つの仕組みで振れる。
     *
     * <p><b>振れるのは砲だけ。</b>名指しされたパイロンに吊ってあるのが砲でなければ、砲座は手を出さない
     * ——ミサイルは発射時のロックへケージングされ、爆弾は落ちるだけであり、どちらも「どこを向いているか」
     * を持たない。旋回するパイロンにミサイルを吊ったパイロットが、それを撃てなくなったりはしない。
     *
     * <p><b>誰が撃つのか。</b>{@code seat} の席に座っている者。空席ならパイロットへ戻る。だから1人で乗れば
     * 全部の砲座がその1人の物になり、誰かが砲手席へ着いた瞬間その砲座はその人の物になる。パイロットが
     * 「持っていた物を渡す」形にしてあるのは、逆にすると2人目が乗り込んでも何も変わらないからだ。
     *
     * <p><b>どこを向いているか。</b>{@code bearing} を中心に {@code traverse} だけ振れ、{@code elevation}
     * から {@code depression} までの間で上下する。すべて機体座標系の角度なので、機体が傾けば砲も一緒に傾く
     * ——ヘリコプターが機首を目標へ向けなくても撃てるのはここであり、逆に横向きの砲を持つ機体が目標の周りを
     * 傾いたまま回り続けるのもここだ。射手は範囲内へ視線を向けるだけでよく、砲は自分の旋回速度でそこへ向かう。
     *
     * @param name 計器に出す砲座の名前。空ならパイロン名から作る
     * @param pylons この砲座が振るハードポイントの名前。複数書けば1組として一緒に振れ一緒に撃つ——
     *               左右のガンポッドは2つで1つの武器だからだ
     * @param seat この砲座を撃つ乗員の席番号。0（操縦席）を指す砲座は常にパイロットの物
     * @param bearing 砲座の正面（度）。0 が機首、正が右、負が左。左舷へ向いた砲は −90
     * @param traverse そこから左右へ振れる角度（度）
     * @param elevation 水平から上へ向けられる角度（度）
     * @param depression 同じく下へ（度）。正の値が下向き
     * @param traverseRate 旋回速度（1tickあたり度）
     * @param elevationRate 俯仰速度（1tickあたり度）
     */
    /**
     * @param bone 振れる部品のボーン名。空なら模型は動かない
     * @param barrel 俯仰だけを受け持つボーンの名前。空なら {@code bone} が方位と俯仰の両方を受ける
     */
    public record Station(String name, List<String> pylons, int seat, float bearing, float traverse,
            float elevation, float depression, float traverseRate, float elevationRate,
            List<String> bone, List<String> barrel) {

        /**
         * ボーン名を1つでも一覧でも読む。
         *
         * <p>ほとんどの砲座は動く部品を1つしか持たない——砲塔の輪と、その上の砲身だ。だが翼下の1組の
         * パイロンを一緒に振る砲座もある（AH-64 のロケットポッドがそれで、左右2本が同じ照準に従う）。
         * 1つで済むファイルに角括弧を書かせないために、両方受ける。
         */
        private static final Codec<List<String>> BONES = Codec.either(Codec.STRING, Codec.STRING.listOf())
                .xmap(either -> either.map(one -> one.isEmpty() ? List.<String>of() : List.of(one), many -> many),
                        many -> many.size() == 1 ? Either.left(many.get(0)) : Either.right(many));

        public static final Codec<Station> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(Station::name),
                Codec.STRING.listOf().fieldOf("pylons").forGetter(Station::pylons),
                Codec.INT.optionalFieldOf("seat", 0).forGetter(Station::seat),
                Codec.FLOAT.optionalFieldOf("bearing", 0.0F).forGetter(Station::bearing),
                Codec.FLOAT.optionalFieldOf("traverse", 15.0F).forGetter(Station::traverse),
                Codec.FLOAT.optionalFieldOf("elevation", 5.0F).forGetter(Station::elevation),
                Codec.FLOAT.optionalFieldOf("depression", 60.0F).forGetter(Station::depression),
                Codec.FLOAT.optionalFieldOf("traverse_rate", 3.0F).forGetter(Station::traverseRate),
                Codec.FLOAT.optionalFieldOf("elevation_rate", 3.0F).forGetter(Station::elevationRate),
                BONES.optionalFieldOf("bone", List.of()).forGetter(Station::bone),
                BONES.optionalFieldOf("barrel", List.of()).forGetter(Station::barrel)
        ).apply(instance, Station::new));

        /** 俯仰を受け持つボーン。専用の物が無ければ振れる部品そのもの。 */
        public List<String> elevates() {
            return this.barrel.isEmpty() ? this.bone : this.barrel;
        }

        /** 模型に動く部品があるか。無い機体では砲は据え付けのまま描かれる。 */
        public boolean animated() {
            return !this.bone.isEmpty() || !this.barrel.isEmpty();
        }

        /** 計器に出す名前。ファイルが黙っていれば最初のパイロン名。 */
        public String label() {
            return !this.name.isEmpty() ? this.name : this.pylons.isEmpty() ? "gun" : this.pylons.get(0);
        }

        /** 旋回範囲へ収めた方位。 */
        public float clampYaw(float degrees) {
            return Mth.clamp(degrees, this.bearing - this.traverse, this.bearing + this.traverse);
        }

        /** 俯仰範囲へ収めた仰角。 */
        public float clampPitch(float degrees) {
            return Mth.clamp(degrees, -this.depression, this.elevation);
        }
    }

    /**
     * 降着装置。気流の中へ張り出す面であり、飛ぶ前の機体が転がって進む足でもある。
     *
     * @param cycleTicks 格納位置から全下げまでの所要時間
     * @param dragPenalty 下げた時の追加抗力。清浄形態の値に対する割合
     * @param rollingFriction 車輪で1tick転がった後に残る地上速度の割合。車輪は転がる物なので、1 をかなり
     *                        下回ると機体は滑走路上で飛行速度に到達できなくなる
     * @param brakeFriction ブレーキを掛けている間の同じ物
     * @param lateralFriction 1tick後に残る<em>横方向</em>速度の割合。車輪は一方向に転がり他方向には擦れる。
     *                        その差だけが、機体が滑走路上を滑り回らずに直進する理由。転がり側よりずっと0に
     *                        近い値
     * @param steerRate 地上滑走速度で前輪が機体を回せる角速度（1tickあたり度）。方向舵とは無関係。地上の
     *                  車輪は垂直尾翼を過ぎる空気の速さを気にしない。だから機体は歩く速度で駐機場から操向
     *                  できるし、空力舵ではそれができない
     * @param steerFade 前輪操向が完全に消える速度（1tickあたりブロック）。それより上では方向舵が仕事をする。
     *                  高速でも前輪が食い付いたままなら、機体を滑走路から放り出すだけになる
     * @param climbHeight 降着装置がぶつからずに乗り越える段差の高さ（ブロック）。これが無い機体はブロック
     *                    1つの縁も越えられない。当たり判定に引っ掛かるうえ、そもそも飛ぶには自分の墜落速度
     *                    より速く走る必要があるので、完全に平らでない滑走路からの離陸は毎回爆発で終わった。
     *                    これは降着装置が降着装置の仕事をしているだけで、脚が下りていて機体が正立している
     *                    間だけ効く。1未満だとブロック1個分を越えられず、それは滑走路上の機体が最もよく
     *                    出会う段差
     * @param retractable そもそも上がるか。上がらない機体は多い——ヘリコプターの車輪も軽飛行機の脚も——し、
     *                    脚が固定された機体が脚レバーに応じるべきではない。パイロットがそれで達成できるのは
     *                    「モデル上は何も動かないまま、地上での段差乗り越えを失う」ことだけだから
     * @param landingSpeed 他が何であれ車輪での接地が生存可能な速度（1tickあたりブロック）。パイロットの計器
     *                     に出る値なので、2.78 は表示上の 200 km/h。省略時は機体の種別により
     *                     {@link #DEFAULT_LANDING_SPEED} か {@link #HELICOPTER_LANDING_SPEED}
     */
    public record Undercarriage(int cycleTicks, float dragPenalty, float rollingFriction, float brakeFriction,
            float lateralFriction, float steerRate, float steerFade, float climbHeight, boolean retractable,
            Optional<Float> landingSpeed) {

        /**
         * ファイルが書かない場合に飛行機の降着装置が受け入れる接地速度。200 km/h＝1tickあたり2.78ブロック。
         * 着陸で出す速度をかなり上回っており、それは意図的だ。要点は「まともな速度で飛んだ進入は、最後の
         * 降下率が何であれ機体を全損させるほど失敗できない」ということ。
         */
        public static final float DEFAULT_LANDING_SPEED = 2.78F;
        /**
         * ヘリコプターの同じ物。50 km/h＝1tickあたり0.7ブロック。低いのは、ヘリコプターに進入速度と呼べる
         * 物が無いから——止まってから降りる——で、この値が扱うべきなのはその降下だ。着陸の仕方のどこにも
         * 滑走に似た部分が無い。
         */
        public static final float HELICOPTER_LANDING_SPEED = 0.7F;

        public static final Codec<Undercarriage> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("cycle_ticks").forGetter(Undercarriage::cycleTicks),
                Codec.FLOAT.fieldOf("drag_penalty").forGetter(Undercarriage::dragPenalty),
                Codec.FLOAT.optionalFieldOf("rolling_friction", 0.995F).forGetter(Undercarriage::rollingFriction),
                Codec.FLOAT.optionalFieldOf("brake_friction", 0.85F).forGetter(Undercarriage::brakeFriction),
                Codec.FLOAT.optionalFieldOf("lateral_friction", 0.55F).forGetter(Undercarriage::lateralFriction),
                Codec.FLOAT.optionalFieldOf("steer_rate", 1.1F).forGetter(Undercarriage::steerRate),
                Codec.FLOAT.optionalFieldOf("steer_fade", 1.2F).forGetter(Undercarriage::steerFade),
                Codec.FLOAT.optionalFieldOf("climb_height", 1.05F).forGetter(Undercarriage::climbHeight),
                Codec.BOOL.optionalFieldOf("retractable", true).forGetter(Undercarriage::retractable),
                Codec.FLOAT.optionalFieldOf("landing_speed").forGetter(Undercarriage::landingSpeed)
        ).apply(instance, Undercarriage::new));
    }

    /**
     * 気流の中へ張り出す物。
     *
     * @param cycleTicks 格納位置から全展開までの所要時間
     * @param dragPenalty 展開時の追加抗力。清浄形態の値に対する割合
     * @param liftBonus 展開時の追加揚力。清浄形態の翼の値に対する割合
     */
    public record Surface(int cycleTicks, float dragPenalty, float liftBonus) {
        public static final Codec<Surface> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("cycle_ticks").forGetter(Surface::cycleTicks),
                Codec.FLOAT.fieldOf("drag_penalty").forGetter(Surface::dragPenalty),
                Codec.FLOAT.optionalFieldOf("lift_bonus", 0.0F).forGetter(Surface::liftBonus)
        ).apply(instance, Surface::new));
    }
}
