package com.ashvehicles.vehicle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One ground vehicle, described entirely in JSON. Drop a file in
 * {@code data/ashvehicles/vehicle/} and the mod registers an entity type and an item for it at
 * start-up; no Java needed.
 *
 * <p>The arrangement is the aircraft's, and deliberately so — see
 * {@link com.ashvehicles.aircraft.AircraftDefinition}. The file is read twice: at start-up by
 * the mod itself, so that {@link Hitbox} is known while the
 * registries are still open; and again on every {@code /reload} from the data packs, so that
 * everything below the hitbox can be retuned without restarting. Both reads are
 * {@code DefinitionRegistry}'s doing and neither is this file's business.
 *
 * <p>What it describes is a different machine. Nothing here holds itself up: a ground vehicle is
 * pressed against the ground by gravity and turns because one track is driven harder than the
 * other, so there is no wing, no throttle to spool and no attitude the driver chooses. The hull's
 * pitch and roll belong to the ground it is standing on, and the only thing aimed independently of
 * the hull is the turret.
 *
 * <p>Speeds are in blocks per tick and accelerations in blocks per tick squared; at twenty ticks a
 * second, a speed of 0.35 is seven blocks a second, or about 25 km/h. Rates are degrees per tick.
 */
public record GroundVehicleDefinition(VehicleChassis.Hitbox hitbox, VehicleChassis.Model model, Powertrain powertrain,
        Suspension suspension, Turret turret, Armament armament, Coaxial coaxial, Launcher launcher, Hull hull,
        VehicleChassis.CameraMount camera, VehicleChassis.Sound sound, VehicleChassis.Radar radar,
        VehicleType type, Buoyancy buoyancy, Crush crush, List<Station> turrets) {

    public static final Codec<GroundVehicleDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VehicleChassis.Hitbox.CODEC.optionalFieldOf("hitbox", VehicleChassis.Hitbox.DEFAULT).forGetter(GroundVehicleDefinition::hitbox),
            VehicleChassis.Model.CODEC.optionalFieldOf("model", VehicleChassis.Model.DEFAULT).forGetter(GroundVehicleDefinition::model),
            Powertrain.CODEC.fieldOf("powertrain").forGetter(GroundVehicleDefinition::powertrain),
            Suspension.CODEC.optionalFieldOf("suspension", Suspension.DEFAULT)
                    .forGetter(GroundVehicleDefinition::suspension),
            Turret.CODEC.optionalFieldOf("turret", Turret.NONE).forGetter(GroundVehicleDefinition::turret),
            Armament.CODEC.optionalFieldOf("armament", Armament.NONE).forGetter(GroundVehicleDefinition::armament),
            Coaxial.CODEC.optionalFieldOf("coaxial", Coaxial.NONE).forGetter(GroundVehicleDefinition::coaxial),
            Launcher.CODEC.optionalFieldOf("launcher", Launcher.NONE).forGetter(GroundVehicleDefinition::launcher),
            Hull.CODEC.fieldOf("hull").forGetter(GroundVehicleDefinition::hull),
            VehicleChassis.CameraMount.CODEC.optionalFieldOf("camera", VehicleChassis.CameraMount.DEFAULT).forGetter(GroundVehicleDefinition::camera),
            VehicleChassis.Sound.CODEC.optionalFieldOf("sound", VehicleChassis.Sound.DEFAULT).forGetter(GroundVehicleDefinition::sound),
            VehicleChassis.Radar.CODEC.optionalFieldOf("radar", VehicleChassis.Radar.NONE)
                    .forGetter(GroundVehicleDefinition::radar),
            // Which kind of ground machine this is, and — for the one kind that floats — how it sits
            // in the water. A file that names neither is a plain tank, which is what almost all of
            // them are; a warship sets the type to "ship" and, if it wants anything other than the
            // default trim, a "buoyancy" block to go with it.
            VehicleType.CODEC.optionalFieldOf("type", VehicleType.GROUND_VEHICLE)
                    .forGetter(GroundVehicleDefinition::type),
            Buoyancy.CODEC.optionalFieldOf("buoyancy", Buoyancy.DEFAULT).forGetter(GroundVehicleDefinition::buoyancy),
            Crush.CODEC.optionalFieldOf("crush", Crush.DEFAULT).forGetter(GroundVehicleDefinition::crush),
            // 主砲塔以外の砲塔。1つも書かない車両——同梱のほぼ全部——では、このリストに関わる処理は
            // どれも1tickに1度も走らない。{@link Station} 参照。
            //
            // <b>これがトップレベル16個目のフィールドであり、DFU が一度に組める上限そのものだ。</b>
            // これ以上足すものは、どれかのブロックの中へ入れ子にすること。
            Station.CODEC.listOf().optionalFieldOf("turrets", List.of())
                    .forGetter(GroundVehicleDefinition::turrets)
    ).apply(instance, GroundVehicleDefinition::new));

    /** Whether this vehicle is a ship, floated on the water rather than resting on the ground. */
    public boolean isShip() {
        return this.type == VehicleType.SHIP;
    }

    /**
     * Used when a vehicle has no file the game can read at all. Deliberately slow and docile: it
     * drives, so the game keeps running and the log says what went wrong, but nobody will mistake it
     * for the real numbers.
     */
    public static final GroundVehicleDefinition FALLBACK = new GroundVehicleDefinition(
            VehicleChassis.Hitbox.DEFAULT,
            VehicleChassis.Model.DEFAULT,
            new Powertrain(0.2F, 0.1F, 0.006F, 0.04F, 0.004F, 1.5F, 1.0F, 0.6F, VehicleChassis.Fuel.GROUND),
            Suspension.DEFAULT,
            Turret.NONE,
            Armament.NONE,
            Coaxial.NONE,
            Launcher.NONE,
            new Hull(Hull.DEFAULT_HEALTH, 3.0F, 0, 0.0F,
                    List.of(VehicleChassis.Seat.at(new Vec3(0.0, 1.0, 0.0))), false, Optional.empty(),
                    1.0F, 0),
            VehicleChassis.CameraMount.DEFAULT,
            VehicleChassis.Sound.DEFAULT,
            VehicleChassis.Radar.NONE,
            VehicleType.GROUND_VEHICLE,
            Buoyancy.DEFAULT,
            Crush.DEFAULT,
            List.of());



    /** The roles a bone can be given in {@link ModelSetup#bones}. */
    public static final class Bone {
        /**
         * The turret, which traverses about its own ring — so about the model's vertical, whatever
         * the hull is sitting on. Everything mounted in the turret is a child of this bone in the
         * geometry and comes round with it for nothing.
         */
        public static final String TURRET = "turret";
        /**
         * The gun, which elevates about the trunnions. A child of the turret, so it is aimed in the
         * turret's frame and needs to know nothing about which way the turret is pointing.
         */
        public static final String GUN = "gun";
        /** The mantlet, if it is a separate bone that elevates with the gun rather than part of it. */
        public static final String MANTLET = "mantlet";
        /** A machine gun on the turret roof, which follows the gun's elevation but not its aim. */
        public static final String MG = "mg";
        public static final String COMMANDER_HATCH = "commander_hatch";
        public static final String DRIVER_HATCH = "driver_hatch";

        private Bone() {
        }
    }

    /**
     * What drives the vehicle and what turns it.
     *
     * <p>A tracked vehicle turns by driving one track harder than the other, which means it can turn
     * on the spot and turns <em>less</em> sharply the faster it is going — the opposite of a steered
     * wheel, and the reason the rate is a pair of figures rather than one. A wheeled vehicle is the
     * same record with {@code pivot_rate} set to nothing, which is exactly what a wheeled vehicle
     * can do standing still.
     *
     * @param maxSpeed flat-out forwards, blocks per tick
     * @param reverseSpeed flat out backwards, which for a tank is a good deal less
     * @param acceleration how hard it pulls away, blocks per tick squared
     * @param braking how hard the brakes stop it, in the same units
     * @param rollingResistance how quickly it slows with nothing pressed, in the same units. This is
     *                          the track and the transmission rather than the brakes, so it is small
     * @param steerRate degrees a tick it comes round at full speed
     * @param pivotRate degrees a tick it comes round standing still, which for a tracked vehicle is
     *                  faster. Zero for anything that cannot turn without rolling
     * @param gradeResistance how much of gravity along a slope the vehicle has to fight, as a
     *                        fraction. One is honest physics — a steep enough hill stops it dead and
     *                        rolls it back down — and less than one is a vehicle with more torque
     *                        than sense. Nothing at all makes hills free
     */
    public record Powertrain(float maxSpeed, float reverseSpeed, float acceleration, float braking,
            float rollingResistance, float steerRate, float pivotRate, float gradeResistance,
            VehicleChassis.Fuel fuel) {

        public static final Codec<Powertrain> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("max_speed").forGetter(Powertrain::maxSpeed),
                Codec.FLOAT.optionalFieldOf("reverse_speed", 0.15F).forGetter(Powertrain::reverseSpeed),
                Codec.FLOAT.fieldOf("acceleration").forGetter(Powertrain::acceleration),
                Codec.FLOAT.optionalFieldOf("braking", 0.05F).forGetter(Powertrain::braking),
                Codec.FLOAT.optionalFieldOf("rolling_resistance", 0.004F).forGetter(Powertrain::rollingResistance),
                Codec.FLOAT.optionalFieldOf("steer_rate", 1.6F).forGetter(Powertrain::steerRate),
                Codec.FLOAT.optionalFieldOf("pivot_rate", 1.1F).forGetter(Powertrain::pivotRate),
                Codec.FLOAT.optionalFieldOf("grade_resistance", 0.8F).forGetter(Powertrain::gradeResistance),
                // Written as part of the drivetrain, for the same reason an aircraft writes it into its
                // engine: fuel is what the engine consumes, not a separate fitting. The default lives on
                // VehicleChassis.Fuel rather than here -- a constant declared in this class below CODEC
                // would still be null when Powertrain's own CODEC reads it, and an optionalFieldOf with a
                // null default throws while decoding rather than where the mistake is.
                VehicleChassis.Fuel.CODEC.optionalFieldOf("fuel", VehicleChassis.Fuel.GROUND)
                        .forGetter(Powertrain::fuel)
        ).apply(instance, Powertrain::new));
    }

    /**
     * How the hull sits on the ground and what it can drive over.
     *
     * @param climbHeight how tall a step it drives straight over, in blocks. A tank walks over a
     *                    metre of wall; a car does not
     * @param slopeLimit the steepest slope it can hold, in degrees. Steeper than this and the
     *                   drivetrain cannot hold it and it slides back. Forty-five degrees is a
     *                   staircase of whole blocks, which is as steep as this world's ground gets
     *                   short of a wall, so a tracked vehicle is given the lot. What it is compared
     *                   against is a reading taken off block terrain, which is coarse by a block
     *                   over the contact patch — see {@code GroundVehicleEntity.holdableSlope}
     * @param settleRate how much of the way the hull turns towards the ground's angle in one tick,
     *                   in [0, 1]. One snaps it flat against every block it crosses, which reads as
     *                   a twitch; a fifth of the way a tick is a hull that rolls onto a slope
     * @param grip how much of a sideways slide is killed each tick, in [0, 1]. Tracks bite, so this
     *             is high; ice and a hard turn are what it is for
     * @param wheelRadius the road wheels' radius in blocks, which is what turns distance travelled
     *                    into how far the wheels have gone round. Drawing only
     * @param contactLength the length of the track on the ground, in blocks. This is what the hull's
     *                      pitch is read across, so it is the ground contact patch rather than the
     *                      whole vehicle: a gun barrel hanging six blocks over the bow has no say in
     *                      which way the hull is lying
     * @param contactWidth the distance between the two tracks, in blocks, across which the roll is
     *                     read for the same reason
     * @param travel how far a road wheel moves up and down from where the model was built, in
     *               blocks, and so how far the body above it can move on its springs. This is the
     *               one figure that decides whether a vehicle has a suspension at all: nothing bolts
     *               the body to the running gear and the hull slides over the landscape as it always
     *               did. Drawing only — see {@link Ride}, which is where the whole of it lives — so
     *               a generous figure costs nothing but a livelier-looking vehicle. Torsion bars are
     *               deep: a third of a block is a tank, less is a hull sitting on its stops
     * @param stiffness how hard the springs pull the body back to where it sits at rest, per tick.
     *                  What this sets is how quickly the body answers: a fifth is a heavy hull that
     *                  takes most of a second to come back, and half is a light one that snaps
     * @param damping the fraction of the body's own speed the dampers take out each tick, in [0, 1].
     *                Low and the vehicle wallows for several seconds after every bump; high and it
     *                is over before it is seen. A third is a hull that rocks once and settles
     * @param dive how far the nose lifts, in degrees, at the hardest this vehicle can pull away —
     *             and drops by, at the hardest it can stop. Read against the vehicle's own
     *             acceleration and braking, so the figure means the same thing on a scout car and on
     *             sixty tonnes
     * @param lean how far the body leans away from a corner, in degrees, at the hardest corner this
     *             vehicle can turn at its own top speed. Away from it: a body thrown outwards leans
     *             onto its outer springs, and a hull that leaned into its turns would read as an
     *             aeroplane
     */
    public record Suspension(float climbHeight, float slopeLimit, float settleRate, float grip,
            float wheelRadius, float contactLength, float contactWidth, float travel, float stiffness,
            float damping, float dive, float lean) {
        public static final Suspension DEFAULT =
                new Suspension(1.0F, 45.0F, 0.2F, 0.85F, 0.4F, 4.0F, 2.5F,
                        0.22F, 0.22F, 0.32F, 2.2F, 3.0F);

        public static final Codec<Suspension> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("climb_height", DEFAULT.climbHeight()).forGetter(Suspension::climbHeight),
                Codec.FLOAT.optionalFieldOf("slope_limit", DEFAULT.slopeLimit()).forGetter(Suspension::slopeLimit),
                Codec.FLOAT.optionalFieldOf("settle_rate", DEFAULT.settleRate()).forGetter(Suspension::settleRate),
                Codec.FLOAT.optionalFieldOf("grip", DEFAULT.grip()).forGetter(Suspension::grip),
                Codec.FLOAT.optionalFieldOf("wheel_radius", DEFAULT.wheelRadius()).forGetter(Suspension::wheelRadius),
                Codec.FLOAT.optionalFieldOf("contact_length", DEFAULT.contactLength())
                        .forGetter(Suspension::contactLength),
                Codec.FLOAT.optionalFieldOf("contact_width", DEFAULT.contactWidth())
                        .forGetter(Suspension::contactWidth),
                Codec.FLOAT.optionalFieldOf("travel", DEFAULT.travel()).forGetter(Suspension::travel),
                Codec.FLOAT.optionalFieldOf("stiffness", DEFAULT.stiffness()).forGetter(Suspension::stiffness),
                Codec.FLOAT.optionalFieldOf("damping", DEFAULT.damping()).forGetter(Suspension::damping),
                Codec.FLOAT.optionalFieldOf("dive", DEFAULT.dive()).forGetter(Suspension::dive),
                Codec.FLOAT.optionalFieldOf("lean", DEFAULT.lean()).forGetter(Suspension::lean)
        ).apply(instance, Suspension::new));
    }

    /**
     * The turret and the gun in it, aimed in the hull's frame rather than the world's.
     *
     * <p>That is the whole of why this is separate from the hull. Where the hull is pointing is
     * decided by the ground and by the driver; where the gun is pointing is decided by the gunner,
     * and the two have nothing to do with one another. A turret laid on a target holds that target
     * while the hull crosses a ditch underneath it.
     *
     * @param traverseRate degrees a tick the turret comes round at
     * @param elevationRate degrees a tick the gun rises and falls at
     * @param depression how far the gun goes below the turret roof line, in degrees, written as a
     *                   positive number. This is the figure that decides whether a vehicle can fight
     *                   from behind a crest
     * @param elevation how far above it, in degrees
     * @param ring where the turret ring is, in the vehicle's own axes. Only the collision boxes need
     *             this — the model turns the turret about whatever pivot the geometry gives its
     *             bone — but they need it exactly: a box marked {@code "turret"} in the collision
     *             file is swung about this point, and a ring half a block out puts the gun's box
     *             half a block off the gun at every angle but dead ahead
     */
    public record Turret(float traverseRate, float elevationRate, float depression, float elevation,
            Vec3 ring) {
        /** A vehicle with no turret at all: nothing traverses and nothing elevates. */
        public static final Turret NONE = new Turret(0.0F, 0.0F, 0.0F, 0.0F, Vec3.ZERO);

        public static final Codec<Turret> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("traverse_rate", 1.8F).forGetter(Turret::traverseRate),
                Codec.FLOAT.optionalFieldOf("elevation_rate", 1.2F).forGetter(Turret::elevationRate),
                Codec.FLOAT.optionalFieldOf("depression", 9.0F).forGetter(Turret::depression),
                Codec.FLOAT.optionalFieldOf("elevation", 20.0F).forGetter(Turret::elevation),
                Vec3.CODEC.optionalFieldOf("ring", Vec3.ZERO).forGetter(Turret::ring)
        ).apply(instance, Turret::new));

        /** Whether this vehicle has a turret worth turning. */
        public boolean exists() {
            return this.traverseRate > 0.0F || this.elevationRate > 0.0F;
        }
    }

    /**
     * 主砲塔とは別に、自分の砲手・自分の砲・自分の旋回輪を持つ砲塔。
     *
     * <p><b>{@code slaved_turrets} とは正反対の物だ。</b>あちらは同じ射撃指揮に従う第2砲塔——主砲塔が
     * 向いた先へ一緒に向く模型上のボーンでしかない——で、撃つ者も弾倉も1つだ。こちらは独立した砲塔で、
     * 席番号を持ち、その席に座った者の視線で据わり、その者の引き金で撃ち、自分の弾倉を数える。
     *
     * <p><b>砲を持つのは席であって人ではない。</b>空席の砲塔は運転手へ戻る（{@code TurretStations} 参照）
     * ので、1人で走らせれば全砲塔が1人の物になり、砲手が乗った砲塔から順に手が離れていく。乗り降りに伴う
     * 特別な処理は無く、毎tick誰がどこに座っているかを見るだけだ。機体の砲座
     * （{@link com.ashvehicles.aircraft.AircraftDefinition.Station}）とまったく同じ取り決めで、あちらに
     * 倣って名前も揃えてある。
     *
     * <p><b>主砲塔はここに書かない。</b>運転手が据える砲塔は今までどおり {@code turret} と
     * {@code armament} であり、ここに並ぶのはそれ以外の砲塔だけ。運転席＝主砲塔の砲手という戦車の取り決めを
     * 変えないためで、同梱の単砲塔車両はこのリストを持たない。
     *
     * @param name 計器に出す名前。書かなければ砲塔ボーンの名前
     * @param seat この砲塔を受け持つ座席の番号。0 は運転席なので、砲手を乗せるなら1以上。空席ならこの
     *             砲塔は運転手のものになる
     * @param weapon この砲塔が撃つ兵装ファイル。空なら模型が回るだけの砲塔になる
     * @param ring この砲塔が旋回する輪の位置。車両座標系で、主砲塔の {@code turret.ring} と同じ意味
     * @param trunnion この砲塔の砲が俯仰する耳軸。同じく車両座標系
     * @param barrelLength 耳軸から砲口までの距離（ブロック）
     * @param barrels 砲口が2つ以上ある砲架のための一覧。{@link Barrel} と同じ物で、ここでは
     *                {@code ring} を書く意味が無い——この砲塔の旋回輪は上の {@code ring} だからだ
     * @param bearing 砲手がいないときに向いている方位（度）。可動範囲の中心でもある
     * @param traverse その方位から左右へ振れる角（度）。180 以上で全周旋回
     * @param elevation 仰角の上限（度）
     * @param depression 俯角の下限（度）
     * @param traverseRate 旋回速度（度/tick）
     * @param elevationRate 俯仰速度（度/tick）
     * @param model この砲塔を描くボーンと、模型がそれをどの向きで残しているか。{@link Model} 参照
     * @param ammunition この砲塔が受け取る弾種。書かなければ兵装ファイル自身の弾を撃ち、弾を積む手段が
     *                   無い砲塔になる。<b>並べた砲塔は一度に1種類だけを積む</b>——車両の3つの架台
     *                   （{@link com.ashvehicles.weapon.Magazine}）と違い、砲塔は種類ごとの内訳を持たない。
     *                   空になれば別の種類を積める
     */
    public record Station(String name, int seat, Optional<ResourceLocation> weapon, Vec3 ring, Vec3 trunnion,
            float barrelLength, List<Barrel> barrels, float bearing, float traverse, float elevation,
            float depression, float traverseRate, float elevationRate, Model model,
            List<ResourceLocation> ammunition) {

        public static final Codec<Station> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("name", "").forGetter(Station::name),
                Codec.INT.optionalFieldOf("seat", 0).forGetter(Station::seat),
                ResourceLocation.CODEC.optionalFieldOf("weapon").forGetter(Station::weapon),
                Vec3.CODEC.optionalFieldOf("ring", Vec3.ZERO).forGetter(Station::ring),
                Vec3.CODEC.optionalFieldOf("trunnion", Vec3.ZERO).forGetter(Station::trunnion),
                Codec.FLOAT.optionalFieldOf("barrel_length", 0.0F).forGetter(Station::barrelLength),
                Barrel.CODEC.listOf().optionalFieldOf("barrels", List.of()).forGetter(Station::barrels),
                Codec.FLOAT.optionalFieldOf("bearing", 0.0F).forGetter(Station::bearing),
                Codec.FLOAT.optionalFieldOf("traverse", 180.0F).forGetter(Station::traverse),
                Codec.FLOAT.optionalFieldOf("elevation", 20.0F).forGetter(Station::elevation),
                Codec.FLOAT.optionalFieldOf("depression", 8.0F).forGetter(Station::depression),
                Codec.FLOAT.optionalFieldOf("traverse_rate", 1.8F).forGetter(Station::traverseRate),
                Codec.FLOAT.optionalFieldOf("elevation_rate", 1.2F).forGetter(Station::elevationRate),
                Model.CODEC.optionalFieldOf("model", Model.NONE).forGetter(Station::model),
                ResourceLocation.CODEC.listOf().optionalFieldOf("ammunition", List.of())
                        .forGetter(Station::ammunition)
        ).apply(instance, Station::new));

        /**
         * 砲塔を描く物。どのボーンが回り、どのボーンが俯仰し、模型がそれをどの向きで残しているか。
         *
         * @param bone 旋回するボーン。書かなければ模型は動かない——照準と弾は正しいまま、砲塔だけが
         *             据え付けで描かれる
         * @param gun そのボーンの中で俯仰するボーン。省略すると {@code bone} が両方受け持つ
         * @param rest 模型が既に振られている砲塔の角度（度）。0 で車首方向——大半の砲塔はそう作られて
         *             いる。横や後ろを向いた姿勢で作られている砲塔（{@code .geo.json} のボーン自身が
         *             回転を持っている物）ではその角度をここに書く。{@code model.nozzle_rest} と
         *             まったく同じ仕組みで、同じ理由でここにある——模型の作られ方の話は、模型ごとに
         *             言うしかない。<b>{@code ring} と {@code trunnion}、そして砲塔上の当たり判定箱は
         *             この角を差し引いた「車首を向いた姿勢」で書く</b>——模型が寝ている向きで書くと、
         *             砲塔が回った瞬間に弾と箱が模型から離れる
         */
        public record Model(String bone, String gun, float rest) {
            /** 模型に動く部品が無い砲塔。照準も弾も正しいまま、砲塔だけが据え付けで描かれる。 */
            public static final Model NONE = new Model("", "", 0.0F);

            public static final Codec<Model> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.optionalFieldOf("bone", "").forGetter(Model::bone),
                    Codec.STRING.optionalFieldOf("gun", "").forGetter(Model::gun),
                    Codec.FLOAT.optionalFieldOf("rest", 0.0F).forGetter(Model::rest)
            ).apply(instance, Model::new));
        }

        /** 計器に出す名前。ファイルが黙っていれば旋回するボーンの名前。 */
        public String label() {
            return !this.name.isEmpty() ? this.name
                    : this.model.bone().isEmpty() ? "turret" : this.model.bone();
        }

        /** 旋回を受け持つボーン。 */
        public String bone() {
            return this.model.bone();
        }

        /** 俯仰を受け持つボーン。専用の物が無ければ旋回するボーンそのもの。 */
        public String elevates() {
            return this.model.gun().isEmpty() ? this.model.bone() : this.model.gun();
        }

        /** 模型が既に振られている角（度）。描くときだけ差し引く。 */
        public float rest() {
            return this.model.rest();
        }

        /** 撃つ物があるか。無い砲塔も回りはする——照準器は据えられるし、模型も付いてくる。 */
        public boolean armed() {
            return this.weapon.isPresent();
        }

        /** 全周旋回する砲塔か。可動範囲を持つ砲塔と違い、方位は折り返して最短経路で回る。 */
        public boolean allRound() {
            return this.traverse >= 180.0F;
        }

        /** 旋回範囲へ収めた方位（度）。全周旋回の砲塔はそのまま。 */
        public float clampYaw(float degrees) {
            if (this.allRound()) {
                return Mth.wrapDegrees(degrees);
            }

            return Mth.clamp(Mth.wrapDegrees(degrees - this.bearing), -this.traverse, this.traverse)
                    + this.bearing;
        }

        /** 俯仰範囲へ収めた仰角（度）。 */
        public float clampPitch(float degrees) {
            return Mth.clamp(degrees, -this.depression, this.elevation);
        }

        /** 砲口の数。1本を下回らない——砲には砲身がある。 */
        public int barrelCount() {
            return Math.max(this.barrels.size(), 1);
        }

        /**
         * 砲身1本。並べていない砲架では、上の耳軸と長さが記述しているその1本。範囲外の添字は最後の1本で、
         * 砲身数を間違えたファイルは撃てなくなるのではなく違う穴から撃つ。
         */
        public Barrel barrel(int index) {
            if (this.barrels.isEmpty()) {
                return new Barrel(this.trunnion, Optional.of(this.barrelLength), Optional.empty());
            }

            return this.barrels.get(Math.min(Math.max(index, 0), this.barrels.size() - 1));
        }
    }

    /**
     * The gun in the turret: which weapon it is, where its muzzle is, and what firing it does to the
     * vehicle.
     *
     * <p>How hard it hits, how far it reaches and how often it can fire are not here. Those belong
     * to the weapon's own file in {@code data/ashvehicles/weapon/}, exactly as they do for anything
     * an aircraft carries, and a tank gun is no different for being bolted in rather than hung on.
     * What is here is the part that is about <em>this vehicle</em>: where the barrel ends, and what
     * happens to the tank when the gun goes off.
     *
     * <p>The muzzle is described as a point and a length rather than as a point, because the barrel
     * swings. The trunnion is where the gun pivots in elevation, which is a fixed place on the
     * turret; the muzzle is that far along whichever way the gun is currently laid. Written as a
     * single point it would be right at one elevation and wrong at every other.
     *
     * <p>A mount with more than one barrel says so in {@code barrels}, and fires out of them in
     * turn: see {@link Barrel}.
     *
     * @param main the weapon fired by the trigger, or empty for a vehicle with nothing to fire
     * @param trunnion where the gun pivots in elevation, in the vehicle's own axes. Swung about the
     *                 turret ring with everything else on the turret
     * @param barrelLength from the trunnion to the muzzle, in blocks, along the bore
     * @param recoil how far the barrel slides back when it fires, in blocks. Drawing only
     * @param recoilTicks how long it takes to run back out again
     * @param rock how far the hull rocks on its springs when the gun fires, in degrees. Drawing only:
     *             the vehicle does not go anywhere. Sixty tonnes on tracks does not measurably move when
     *             the gun goes off, and a hull that slid backwards every shot read as a bug rather than
     *             as weight — but it does sit back on its torsion bars, and that is what this is. The
     *             rock goes into the same suspension displacement the ground gives, so the hull tips
     *             while the running gear stays where it was. Left out, it is worked out from how far the
     *             barrel slides, which already says how big the gun is
     * @param barrels every barrel this mount fires out of, for anything with more than the one.
     *                Left out, the mount is the single barrel the {@code trunnion} and
     *                {@code barrel_length} above describe, which is what every file said before
     *                there was a second one
     * @param ammunition every kind of round this mount can be loaded with, in the order the gunner
     *                   cycles through them. Left out, the mount fires the weapon file's own round
     *                   and is loaded from the generic ammunition boxes, which is what every file
     *                   said before there were named rounds. Listed, the mount takes <em>only</em>
     *                   these, it carries a separate count of each within the weapon's total
     *                   stowage, and the weapon-select key steps through them before it moves on to
     *                   the next mount. Each name is a file under
     *                   {@code data/<namespace>/ammunition/}: see
     *                   {@link com.ashvehicles.weapon.AmmunitionDefinition}
     */
    public record Armament(Optional<ResourceLocation> main, Vec3 trunnion, float barrelLength,
            float recoil, int recoilTicks, Optional<Float> rock, List<Barrel> barrels,
            List<ResourceLocation> ammunition) {
        /** A vehicle with nothing to fire. */
        public static final Armament NONE =
                new Armament(Optional.empty(), Vec3.ZERO, 0.0F, 0.0F, 1, Optional.empty(), List.of(),
                        List.of());

        public static final Codec<Armament> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("main").forGetter(Armament::main),
                Vec3.CODEC.optionalFieldOf("trunnion", Vec3.ZERO).forGetter(Armament::trunnion),
                Codec.FLOAT.optionalFieldOf("barrel_length", 0.0F).forGetter(Armament::barrelLength),
                Codec.FLOAT.optionalFieldOf("recoil", 0.35F).forGetter(Armament::recoil),
                Codec.INT.optionalFieldOf("recoil_ticks", 14).forGetter(Armament::recoilTicks),
                Codec.FLOAT.optionalFieldOf("rock").forGetter(Armament::rock),
                Barrel.CODEC.listOf().optionalFieldOf("barrels", List.of()).forGetter(Armament::barrels),
                ResourceLocation.CODEC.listOf().optionalFieldOf("ammunition", List.of())
                        .forGetter(Armament::ammunition)
        ).apply(instance, Armament::new));

        /**
         * 発砲が車体を揺らす角度（度）。書いていなければ砲身の後座量から求める。
         *
         * <p>後座量は既に「どれだけ大きな砲か」を言っている。125mm の 0.62 ブロックと 25mm 機関砲の 0.12
         * ブロックは、そのまま揺れの大小でもある。だから既定値のためにもう1つ数値を書かせる理由が無い。
         */
        public float rockDegrees() {
            return this.rock.orElse(this.recoil * ROCK_PER_BLOCK);
        }

        /** Whether there is anything to fire at all. */
        public boolean exists() {
            return this.main.isPresent();
        }

        /** How many barrels the mount fires out of. Never fewer than one: a gun has a barrel. */
        public int barrelCount() {
            return Math.max(this.barrels.size(), 1);
        }

        /**
         * One barrel of the mount, with whatever it leaves out taken from the mount as a whole.
         *
         * <p>A file that lists none is a single-barrelled mount, and its one barrel is what the
         * trunnion and the length above describe. An index past the end is the last barrel rather
         * than a thrown exception: a file that is wrong about how many barrels it has should fire
         * out of the wrong one, not stop the vehicle firing at all.
         */
        public Barrel barrel(int index) {
            if (this.barrels.isEmpty()) {
                return new Barrel(this.trunnion, Optional.of(this.barrelLength), Optional.empty());
            }

            return this.barrels.get(Math.min(Math.max(index, 0), this.barrels.size() - 1));
        }
    }

    /**
     * One barrel of a mount that has more than one: a twin thirty-millimetre, a warship's after
     * turret laid by the same fire control, anything that fires out of more than one hole.
     *
     * <p><b>Barrels rather than rounds.</b> A weapon file can already put several rounds into the
     * air at once — that is {@code salvo}, and it is what a shell full of buckshot does. This is the
     * other thing entirely: one round at a time, out of a different hole each time. The rate of
     * fire, the magazine and the loading are exactly what they were, and all that changes is where
     * the round comes out. A twin mount that fired twice as fast would be a different weapon file,
     * not a second barrel.
     *
     * <p><b>Its own ring, if it has one.</b> A pair of barrels in the same mounting come round with
     * the mounting and say nothing here. A warship's after turret is a different mounting on the
     * same fire control: it is laid at the same target but traverses about its own barbette, and it
     * says where that is. Left out, the barrel rides the turret ring the whole vehicle uses.
     *
     * @param trunnion where this barrel pivots in elevation, in the vehicle's own axes
     * @param length from that trunnion to the muzzle, in blocks. Left out, the mount's own
     * @param ring the ring this barrel traverses about. Left out, the vehicle's turret ring
     */
    /** 砲身の後座1ブロックあたり、車体が揺れる角度（度）。{@link Armament#rockDegrees} 参照。 */
    private static final float ROCK_PER_BLOCK = 4.0F;

    public record Barrel(Vec3 trunnion, Optional<Float> length, Optional<Vec3> ring) {
        public static final Codec<Barrel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("trunnion").forGetter(Barrel::trunnion),
                Codec.FLOAT.optionalFieldOf("barrel_length").forGetter(Barrel::length),
                Vec3.CODEC.optionalFieldOf("ring").forGetter(Barrel::ring)
        ).apply(instance, Barrel::new));

        /** How long this barrel is, or the mount's own length for one that does not say. */
        public float lengthOr(float fallback) {
            return this.length.orElse(fallback);
        }

        /** The ring this barrel comes round about, or the vehicle's own for one that does not say. */
        public Vec3 ringOr(Vec3 fallback) {
            return this.ring.orElse(fallback);
        }
    }

    /**
     * The machine gun bolted alongside the main armament, and fired on its own trigger.
     *
     * <p><b>Why it is not simply a second {@link Armament}.</b> The main gun is the thing the whole
     * turret is built round: it recoils, it shoves the hull about when it goes off, it is what the
     * sight is harmonised for, and it is what the crew are cycling between when a vehicle also
     * carries missiles. A coaxial is none of that. It is a barrel clamped to the side of the mantlet
     * with a belt running into it, it does not move the tank, and it is never <em>selected</em> —
     * it is simply there, and a gunner engaging infantry with it has not put the main armament away
     * to do so. So it has its own trigger, its own belt and its own count, and nothing here about
     * recoil or kick, because there is nothing worth drawing.
     *
     * <p><b>Coaxial means what it says</b>: the barrel is clamped to the gun and looks exactly where
     * the gun looks. So there is no aim of its own to describe and none is offered — it fires along
     * the bore, which is what makes laying the main gun on something also lay this on it. All the
     * file says is where the rounds leave from.
     *
     * <p>How hard the rounds hit, how fast they leave and how quickly they come is not here either.
     * That is the weapon's own file under {@code data/ashvehicles/weapon/}, exactly as it is for the
     * main gun and for anything hung on a wing.
     *
     * @param gun the weapon the belt feeds, or empty for a vehicle that carries no machine gun
     * @param muzzle where the rounds leave, in the vehicle's own axes, with the turret at dead ahead
     *               and the gun level. Carried round the ring with the turret and rocked about the
     *               trunnion with the gun, since that is what it is bolted to
     * @param ammunition every kind of round this mount can be loaded with, in the order the gunner
     *                   cycles through them. Left out, the mount fires the weapon file's own round
     *                   and is loaded from the generic ammunition boxes, which is what every file
     *                   said before there were named rounds. Listed, the mount takes <em>only</em>
     *                   these, it carries a separate count of each within the weapon's total
     *                   stowage, and the weapon-select key steps through them before it moves on to
     *                   the next mount. Each name is a file under
     *                   {@code data/<namespace>/ammunition/}: see
     *                   {@link com.ashvehicles.weapon.AmmunitionDefinition}
     */
    public record Coaxial(Optional<ResourceLocation> gun, Vec3 muzzle,
            List<ResourceLocation> ammunition) {
        /** A vehicle with no machine gun, which is what a file saying nothing gets. */
        public static final Coaxial NONE = new Coaxial(Optional.empty(), Vec3.ZERO, List.of());

        public static final Codec<Coaxial> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("gun").forGetter(Coaxial::gun),
                Vec3.CODEC.optionalFieldOf("muzzle", Vec3.ZERO).forGetter(Coaxial::muzzle),
                ResourceLocation.CODEC.listOf().optionalFieldOf("ammunition", List.of())
                        .forGetter(Coaxial::ammunition)
        ).apply(instance, Coaxial::new));

        /** Whether there is a machine gun aboard at all. */
        public boolean exists() {
            return this.gun.isPresent();
        }
    }

    /**
     * The missiles, for a vehicle that carries them in tubes rather than hanging them on pylons.
     *
     * <p>Deliberately not {@link com.ashvehicles.weapon.WeaponMounts}, for the reason the gun is
     * not: a pylon is a place a store is <em>hung</em>, and most of that class is about which
     * station is selected and what somebody loaded onto it. A launcher's tubes are built in, they
     * all hold the same round, and the only questions are how many are left and whether the seeker
     * has anything. How hard the missile hits, how far it reaches, how hard it can turn and what its
     * seeker is looking for are none of this record's business either — they are in the weapon's own
     * file under {@code data/ashvehicles/weapon/}, exactly as they are for a missile on a wing.
     *
     * <p>What is here is the part that is about <em>this vehicle</em>: which round it carries and
     * where the round leaves from.
     *
     * @param missile the weapon the tubes hold, or empty for a vehicle with no missiles
     * @param rail where a round leaves, in the vehicle's own axes, with the turret at dead ahead.
     *             Carried round the ring with the turret and elevated with the gun, since on
     *             anything that carries both the tubes are bolted to the same mounting the barrels
     *             are — which is what makes laying the gun on a target also lay the tubes on it
     * @param ammunition every kind of round this mount can be loaded with, in the order the gunner
     *                   cycles through them. Left out, the mount fires the weapon file's own round
     *                   and is loaded from the generic ammunition boxes, which is what every file
     *                   said before there were named rounds. Listed, the mount takes <em>only</em>
     *                   these, it carries a separate count of each within the weapon's total
     *                   stowage, and the weapon-select key steps through them before it moves on to
     *                   the next mount. Each name is a file under
     *                   {@code data/<namespace>/ammunition/}: see
     *                   {@link com.ashvehicles.weapon.AmmunitionDefinition}
     * @param backblast what comes out of the back of the tubes, for a launcher whose tubes are open
     *                  at that end. Left out, nothing does. See {@link Backblast}
     */
    public record Launcher(Optional<ResourceLocation> missile, Vec3 rail,
            List<ResourceLocation> ammunition, Optional<Backblast> backblast) {
        /** A vehicle with no missiles. */
        public static final Launcher NONE =
                new Launcher(Optional.empty(), Vec3.ZERO, List.of(), Optional.empty());

        public static final Codec<Launcher> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("missile").forGetter(Launcher::missile),
                Vec3.CODEC.optionalFieldOf("rail", Vec3.ZERO).forGetter(Launcher::rail),
                ResourceLocation.CODEC.listOf().optionalFieldOf("ammunition", List.of())
                        .forGetter(Launcher::ammunition),
                Backblast.CODEC.optionalFieldOf("backblast").forGetter(Launcher::backblast)
        ).apply(instance, Launcher::new));

        /** Whether there are any tubes at all. */
        public boolean exists() {
            return this.missile.isPresent();
        }

        /**
         * The exhaust out of the open end of the tubes.
         *
         * <p>A rocket in a tube lights its motor while it is still in the tube, so the same gas that
         * throws the rocket forward leaves the other end going the other way — faster, since nothing
         * is in its way. On an MLRS that plume is aimed down and back by the elevation of the tubes
         * themselves, so it reaches the ground a few blocks behind the vehicle and takes the ground
         * with it. Most of what is seen when a BM-21 or a TOS-1 fires is that, not the rocket.
         *
         * <p>Only tubes open at the back have it. A missile in a sealed canister that is thrown clear
         * before its motor lights has none, and neither does a gun — so this is left out of every
         * file that does not mean it, rather than defaulted on.
         *
         * @param length how far behind the rail the open end of the tube sits, in blocks. The plume
         *               starts there, which is what puts it behind and below the vehicle when the
         *               tubes are elevated
         * @param power how big the plume is, on the same scale explosions use. A 122mm tube is worth
         *              a couple; a 220mm one is worth rather more. It also sets how far the plume
         *              reaches and how wide the dust stands, so this is the only knob most files need
         */
        public record Backblast(float length, float power) {
            public static final Codec<Backblast> CODEC = RecordCodecBuilder.create(instance ->
                    instance.group(
                            Codec.FLOAT.optionalFieldOf("length", 3.0F).forGetter(Backblast::length),
                            Codec.FLOAT.optionalFieldOf("power", 2.4F).forGetter(Backblast::power)
                    ).apply(instance, Backblast::new));
        }
    }

    /**
     * The vehicle itself: what it is worth, what it does when it is finished, and where the crew sit.
     *
     * @param health what a whole vehicle of this sort is worth, in hit points
     * @param explosionPower how big a hole it leaves
     * @param salvage how much metal is left in a wreck of one, in iron ingots, once it has been
     *                destroyed and somebody comes along with a wrench. Left out, it is worked out
     *                from the health instead
     * @param armour how good the plate is at throwing a round off rather than letting it in, in
     *               degrees taken off the angle the round would otherwise need. Nought is plain
     *               steel and the round's own figure stands; a few degrees is composite armour, and
     *               is worth more than it sounds — every degree is armour the crew do not have to
     *               find by turning the hull. The slope itself is not here and never will be: the
     *               boxes are lying at whatever angle the vehicle is lying at, so a hull turned to
     *               meet the shot is already a shallower hit
     * @param seats the crew places, in the vehicle's own axes — x to the right, y up, z towards the
     *              front — in blocks. The first is the driver's, and the driver is who drives. Each
     *              is a bare point or a block that also says where that crew member looks out from;
     *              see {@link VehicleChassis.Seat}
     * @param crewed whether this is a gun the crew stand beside rather than a vehicle they get into.
     *               A towed piece has no cab and nowhere to sit: the layer stands at the breech and
     *               winds the handwheels, the loader hands a round in, and somebody pulls the
     *               lanyard. So a vehicle that says this cannot be ridden at all, is laid from
     *               outside — see {@code GroundVehicleEntity.tickCrewedTurret} — and holds one
     *               loading rather than a magazine, because nothing on it stows rounds. Everything
     *               else about it is a ground vehicle like any other. A file that leaves this out
     *               is driven, which is what all but a couple of them are
     * @param damageTaken 届いた打撃のうち実際に受け取る割合。1 が素通し
     * @param handles where the two handwheels are, for a gun that is {@code crewed}. Left out, they
     *                are taken from gearing the vehicle has already described — see {@link Handles}
     */
    public record Hull(float health, float explosionPower, int salvage, float armour,
            List<VehicleChassis.Seat> seats, boolean crewed, Optional<Handles> handles,
            float damageTaken, int cost) {
        public static final float DEFAULT_HEALTH = 300.0F;

        public static final Codec<Hull> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("health", DEFAULT_HEALTH).forGetter(Hull::health),
                Codec.FLOAT.optionalFieldOf("explosion_power", 4.0F).forGetter(Hull::explosionPower),
                Codec.INT.optionalFieldOf("salvage", 0).forGetter(Hull::salvage),
                Codec.FLOAT.optionalFieldOf("armour", 0.0F).forGetter(Hull::armour),
                VehicleChassis.Seat.CODEC.listOf().fieldOf("seats").forGetter(Hull::seats),
                Codec.BOOL.optionalFieldOf("crewed", false).forGetter(Hull::crewed),
                Handles.CODEC.optionalFieldOf("handles").forGetter(Hull::handles),
                // 届いた打撃のうち、この車体が実際に受け取る割合。1 が素通し——同梱のほぼ全部がそれで、
                // 耐久の点数がそのまま引かれる。
                //
                // <b>耐久を増やすのとは別の物だ。</b>耐久は「何発で壊れるか」であり、こちらは「1発が
                // どれだけ効くか」。厚い装甲を持つ車体は、計器に出る点数を膨らませずにこちらで固くする
                // ——読み手にとって 4000 のままの方が、16000 になるより「何発耐えるか」が読みやすい。
                //
                // 跳弾（{@code armour}）とも装甲厚（箱の {@code plate}）とも別。前者は弾を<em>弾く</em>
                // 角度、後者は入った弾が板を<em>抜けたか</em>で、どちらも当たった場所で決まる。これは
                // 車体のどこに届いた打撃にも一律に掛かる。
                Codec.FLOAT.optionalFieldOf("damage_taken", 1.0F).forGetter(Hull::damageTaken),
                // 試合で1両出すのに要る出撃ポイント。0 なら種別の既定（{@code match/Costs}）。
                //
                // <p><b>強い車両ほど高い。</b> チケットが陣営の残機なら、こちらは個人の財布だ。書くのは
                // ここ——車両ごとの数値であり、車両ファイルは既にその類の数値を全部持っている。
                Codec.INT.optionalFieldOf("cost", 0).forGetter(Hull::cost)
        ).apply(instance, Hull::new));
    }

    /**
     * 車外の砲手が掴む2つのハンドル。旋回用と俯仰用で、片手が回せるのは一方だけ。
     *
     * <p><b>これは「点」であって当たり判定ではない。</b> 砲を指している十字線に近い方が掴まれるので、
     * 2つが十分離れてさえいれば、小さな輪を正確に狙う必要はない。実際に効くのは「砲のどちら側を見ているか」
     * であり、位置はそれを分ける基準と、計器がワールド上に置く印の場所を兼ねる。
     *
     * <p>書かなければ、車両が既に述べている歯車の位置から取る——旋回は砲塔リングの少し上、俯仰は砲耳。
     * どちらもその軸を実際に回している場所であり、2つは上下に分かれる。模型に本物のハンドルが付いている
     * 砲は、それを書いた方が読みやすい印になる。
     */
    public record Handles(Handle traverse, Handle elevate) {
        public static final Codec<Handles> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Handle.CODEC.fieldOf("traverse").forGetter(Handles::traverse),
                Handle.CODEC.fieldOf("elevate").forGetter(Handles::elevate)
        ).apply(instance, Handles::new));
    }

    /**
     * ハンドル1つ。車両自身の軸での位置と、何に取り付いているか。
     *
     * <p>取り付け先は当たり判定の箱とまったく同じ語彙で、同じ意味だ。砲塔に付いたハンドルは砲塔と一緒に
     * 回り、砲に付いたハンドルは砲と一緒に上下する——模型のハンドルが実際にそう動くので、印もそう動く
     * 必要がある。
     *
     * @param at 車両自身の軸での位置（x 右、y 上、z 前）、ブロック
     * @param mount 取り付け先。既定は砲塔
     */
    public record Handle(Vec3 at, VehicleShape.Mount mount) {
        public static final Codec<Handle> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("at").forGetter(Handle::at),
                VehicleShape.Mount.CODEC.optionalFieldOf("mount", VehicleShape.Mount.TURRET)
                        .forGetter(Handle::mount)
        ).apply(instance, Handle::new));
    }

    /**
     * How a ship sits in the water. Read only by a vehicle whose {@code type} is {@code ship}; a
     * tank carries the default and never touches it.
     *
     * <p>What holds a ship up is not the ground under it but the water it displaces, and the whole
     * of that is one balance: the deeper the hull is pushed, the harder the water pushes back, and
     * the vessel rides where the two come level. So there is no wing to fly and no slope to lie
     * along — a ship on flat water sits flat — and the file's job is to say where that resting line
     * is and how briskly the hull returns to it after a wave, a shell, or being dropped in.
     *
     * <p>The model here is a spring rather than a full account of displaced volume: the hull is
     * pulled towards its resting draught at a rate that grows with how far it is from it, and the
     * bobbing that would leave is taken out by the damping. It is cheap, it is stable, and on
     * Minecraft's flat seas it is indistinguishable from the real thing.
     *
     * @param draught how deep the vehicle's origin floats below the water's surface at rest, in
     *                blocks. The origin of a ship model is usually its keel, so this is how much of
     *                the hull is under water — a metre or two for a boat, more for a warship. It is
     *                what sets the waterline on the drawn hull, so it is worth getting to look right
     * @param buoyancy how hard the water pushes the hull back towards that resting draught, as an
     *                 acceleration per block it is out of place — the stiffness of the spring. Higher
     *                 is a cork that pops straight back up; lower is a laden hull that wallows. Too
     *                 high and the hull springs out of the water it was dropped into
     * @param damping the fraction of the hull's up-and-down speed taken out each tick, in [0, 1].
     *                This is what stops a ship dropped in the sea bobbing for ever; most of one, so
     *                that it settles in a second or two rather than ringing like a bell
     * @param trimRate how quickly the hull returns to level after it has been tipped, in [0, 1] a
     *                 tick. A ship on flat water has no slope to hold, so unlike a tank it always
     *                 eases back to level; this is only how fast
     */
    public record Buoyancy(float draught, float buoyancy, float damping, float trimRate) {
        public static final Buoyancy DEFAULT = new Buoyancy(1.0F, 0.08F, 0.35F, 0.1F);

        public static final Codec<Buoyancy> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("draught", DEFAULT.draught()).forGetter(Buoyancy::draught),
                Codec.FLOAT.optionalFieldOf("buoyancy", DEFAULT.buoyancy()).forGetter(Buoyancy::buoyancy),
                Codec.FLOAT.optionalFieldOf("damping", DEFAULT.damping()).forGetter(Buoyancy::damping),
                Codec.FLOAT.optionalFieldOf("trim_rate", DEFAULT.trimRate()).forGetter(Buoyancy::trimRate)
        ).apply(instance, Buoyancy::new));
    }

    /**
     * What the vehicle drives through rather than stops against.
     *
     * <p>Sixty tonnes does not wait for a hedge, and it does not wait for the wall of a shed either.
     * What it does wait for is masonry, and the game already knows which is which: a block's
     * explosion resistance is the one number in Minecraft that says how stoutly the thing is built,
     * and it separates the two exactly where a driver would. Leaves are a fifth of a point, glass a
     * third, soil and sand a half, wool most of one, timber two or three; stone, brick and iron are
     * six, and everything meant to stand up to anything is in the thousands. So the vehicle is given
     * a figure and drives through everything at or under it.
     *
     * <p>It is not a hole through the world. Only what stands in the <em>hull</em> is broken — the
     * body of the vehicle, from a step above whatever it is lying on up to the top of the turret —
     * so ground under the tracks is still ground and a hillside is still climbed rather than
     * tunnelled through. What is left is the thing this is for: a bank of earth taller than the
     * vehicle can climb loses the top of itself and the vehicle drives over the rest.
     *
     * <p>Growing things are a separate question and are not asked this one. See
     * {@link com.ashvehicles.entity.BlockCrusher#CRUSHABLE}: anything in that tag goes down under
     * any vehicle, however little this figure is, because a tank stopped by a sapling is not a tank.
     *
     * @param resistance the greatest explosion resistance the vehicle breaks through. Three is the
     *                   default and is roughly "timber and terrain yes, masonry no", which is what
     *                   these machines look like they ought to do. Nought leaves it with nothing but
     *                   the undergrowth; a figure over six lets it through stonework as well
     * @param drops whether what is broken leaves anything to pick up. Off by default, and
     *              deliberately: a tank crossing a forest is several hundred blocks, and every one
     *              of them as an item on the ground is a lag spike with saplings in it
     */
    public record Crush(float resistance, boolean drops) {
        public static final Crush DEFAULT = new Crush(3.0F, false);

        public static final Codec<Crush> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("resistance", DEFAULT.resistance()).forGetter(Crush::resistance),
                Codec.BOOL.optionalFieldOf("drops", DEFAULT.drops()).forGetter(Crush::drops)
        ).apply(instance, Crush::new));
    }
}
