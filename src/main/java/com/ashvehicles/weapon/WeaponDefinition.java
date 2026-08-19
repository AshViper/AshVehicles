package com.ashvehicles.weapon;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

/**
 * One weapon, described entirely in JSON. Drop a file in {@code data/ashvehicles/weapon/} and the
 * mod registers an item for it at start-up, so it can be hung on any aircraft's pylons; an aircraft's
 * own file can also name one as built in.
 *
 * <p>Like the aircraft, the file is read once at start-up to learn what exists, and again from the
 * data packs on every {@code /reload}, so a weapon can be retuned without restarting.
 *
 * <p>Speeds are in blocks per tick, as everywhere else in the mod.
 *
 * @param type what kind of weapon this is, which decides how it flies and what it needs
 * @param item whether the mod should register an item for it. A gun built into an airframe has no
 *             business being carried about; a pod does
 * @param ammo rounds carried by one mount, when full
 * @param firing how it is fired
 * @param projectile what it fires
 * @param guidance how it steers, for a weapon that does. Absent means it does not
 * @param sound what it sounds like
 */
public record WeaponDefinition(Type type, boolean item, int ammo, Firing firing, Projectile projectile,
        Optional<Guidance> guidance, SoundSetup sound) {

    public static final Codec<WeaponDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Type.CODEC.optionalFieldOf("type", Type.GUN).forGetter(WeaponDefinition::type),
            Codec.BOOL.optionalFieldOf("item", true).forGetter(WeaponDefinition::item),
            Codec.INT.fieldOf("ammo").forGetter(WeaponDefinition::ammo),
            Firing.CODEC.fieldOf("firing").forGetter(WeaponDefinition::firing),
            Projectile.CODEC.fieldOf("projectile").forGetter(WeaponDefinition::projectile),
            Guidance.CODEC.optionalFieldOf("guidance").forGetter(WeaponDefinition::guidance),
            SoundSetup.CODEC.optionalFieldOf("sound", SoundSetup.DEFAULT).forGetter(WeaponDefinition::sound)
    ).apply(instance, WeaponDefinition::new));

    /**
     * Used when a weapon has no file the game can read at all: something that shoots, so the game
     * keeps running, but nothing anyone would mistake for a real weapon.
     */
    public static final WeaponDefinition FALLBACK = new WeaponDefinition(Type.GUN, true, 100,
            new Firing(5.0F, 1.0F, 1, 0.0F), Projectile.DEFAULT, Optional.empty(), SoundSetup.DEFAULT);

    /** True if this weapon steers towards something, and so needs something to steer towards. */
    public boolean isGuided() {
        return this.guidance.isPresent();
    }

    /**
     * Whether firing takes the store itself off the pylon.
     *
     * <p>A missile or a bomb is the thing hanging there: let it go and the rail is bare. A pod is a
     * container that stays bolted on however empty it is, and a gun is part of the airframe. So only
     * the first sort stops being drawn once it has been used, and only until the ground crew hang
     * another one.
     */
    public boolean leavesRail() {
        return this.type == Type.MISSILE || this.type == Type.BOMB;
    }

    /**
     * Whether this is released rather than fired, so that it leaves with the aircraft's own speed
     * and takes no push of its own along the nose.
     */
    public boolean isDropped() {
        return this.type == Type.BOMB;
    }

    /**
     * What kind of weapon this is. The difference is in how what it fires behaves: a gun's round
     * simply flies, a rocket's is pushed along by a motor first, and a missile's steers as well.
     */
    public enum Type implements StringRepresentable {
        /** Rounds, many and fast, in a stream for as long as the trigger is held. */
        GUN("gun"),
        /** Unguided, motor-driven, and gone where it was pointed when it left the rail. */
        ROCKET("rocket"),
        /** The same, but it steers towards whatever the pilot had locked when it launched. */
        MISSILE("missile"),
        /**
         * Dropped rather than fired. It leaves with the aircraft's own speed and nothing else, and
         * from there gravity has it: where it lands is decided at the moment of release, by how fast
         * and how high and how level the aeroplane was. Aiming one is flying the aeroplane.
         */
        BOMB("bomb");

        public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

        private final String name;

        Type(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        /** Whether one press of the trigger sends one, rather than firing for as long as it is held. */
        public boolean isSingleShot() {
            return this != GUN;
        }
    }

    /**
     * @param roundsPerSecond rate of fire while the trigger is held. Need not be a whole number of
     *                        rounds a tick: the mount keeps count of the fraction
     * @param spread half-angle of the cone the rounds leave in, in degrees. Zero is a laser
     * @param salvo how many leave together for each round spent. A rocket pod ripples several off
     *              at once; a missile rail lets go of one
     * @param salvoSpread extra scatter across a salvo, in degrees, on top of {@code spread}. What
     *                    makes a rocket salvo cover ground rather than land in one hole
     */
    public record Firing(float roundsPerSecond, float spread, int salvo, float salvoSpread) {
        public static final Codec<Firing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("rounds_per_second").forGetter(Firing::roundsPerSecond),
                Codec.FLOAT.optionalFieldOf("spread", 0.5F).forGetter(Firing::spread),
                Codec.INT.optionalFieldOf("salvo", 1).forGetter(Firing::salvo),
                Codec.FLOAT.optionalFieldOf("salvo_spread", 0.0F).forGetter(Firing::salvoSpread)
        ).apply(instance, Firing::new));

        /** Ticks between rounds. */
        public float ticksPerRound() {
            return 20.0F / Math.max(this.roundsPerSecond, 1.0E-3F);
        }
    }

    /**
     * What leaves the weapon.
     *
     * <p>A gun's round is given its whole speed at the muzzle and slows from there. A rocket or a
     * missile leaves slowly and is pushed by its motor for a while, which is why one fired from a
     * standing start still gets going and why the aircraft can outrun its own rockets for a moment
     * after launch.
     *
     * @param damage dealt to whatever it hits directly. Aircraft take it ten times over, like boats
     * @param speed the speed it leaves at, in blocks per tick. The aircraft's own speed is added on
     * @param thrust acceleration from the motor, in blocks per tick squared, while it is burning
     * @param burnTicks how long the motor burns. Zero for something with no motor at all
     * @param topSpeed the fastest the motor will drive it, in blocks per tick
     * @param gravity how fast it drops, in blocks per tick squared
     * @param range how far it flies before it is given up on, in blocks
     * @param explosion blast made where it lands, in the same units as TNT's four. Zero for
     *                  something that simply hits
     * @param fire whether the blast sets light to what it lands on
     * @param tracer colour it is drawn in, as {@code RRGGBB}
     * @param trail whether it leaves smoke behind it
     */
    public record Projectile(float damage, float speed, float thrust, int burnTicks, float topSpeed,
            float gravity, float range, float explosion, boolean fire, int tracer, boolean trail) {

        public static final Projectile DEFAULT =
                new Projectile(2.0F, 20.0F, 0.0F, 0, 0.0F, 0.02F, 200.0F, 0.0F, false, 0xFFC864, false);

        private static final Codec<Integer> COLOUR = Codec.STRING.comapFlatMap(
                text -> {
                    try {
                        return DataResult.success(Integer.parseInt(text.replace("#", ""), 16));
                    } catch (NumberFormatException exception) {
                        return DataResult.error(() -> "Not a colour: " + text);
                    }
                },
                colour -> String.format("%06X", colour));

        public static final Codec<Projectile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("damage").forGetter(Projectile::damage),
                Codec.FLOAT.fieldOf("speed").forGetter(Projectile::speed),
                Codec.FLOAT.optionalFieldOf("thrust", 0.0F).forGetter(Projectile::thrust),
                Codec.INT.optionalFieldOf("burn_ticks", 0).forGetter(Projectile::burnTicks),
                Codec.FLOAT.optionalFieldOf("top_speed", 0.0F).forGetter(Projectile::topSpeed),
                Codec.FLOAT.optionalFieldOf("gravity", 0.02F).forGetter(Projectile::gravity),
                Codec.FLOAT.optionalFieldOf("range", 300.0F).forGetter(Projectile::range),
                Codec.FLOAT.optionalFieldOf("explosion", 0.0F).forGetter(Projectile::explosion),
                Codec.BOOL.optionalFieldOf("fire", false).forGetter(Projectile::fire),
                COLOUR.optionalFieldOf("tracer", 0xFFC864).forGetter(Projectile::tracer),
                Codec.BOOL.optionalFieldOf("trail", false).forGetter(Projectile::trail)
        ).apply(instance, Projectile::new));

        /** Whether a motor pushes this along after it has left. */
        public boolean hasMotor() {
            return this.burnTicks > 0 && this.thrust > 0.0F;
        }

        /**
         * Ticks it lives before it is given up on. Worked out from how far it should get: something
         * under power covers its range at the speed its motor will reach, and something coasting at
         * the speed it left with.
         */
        public int lifetime() {
            float pace = this.hasMotor() ? Math.max(this.topSpeed, this.speed) : this.speed;

            return Math.max(1, Math.round(this.range / Math.max(pace, 1.0E-3F)));
        }
    }

    /**
     * How a missile finds what it was fired at.
     *
     * <p>Locking is done before launch and is the pilot's work: hold the nose on something inside
     * the seeker's cone and within its reach until it takes. Once away the missile is on its own,
     * and what it can do is bounded by how hard it can turn, so a target that turns harder than the
     * missile can follow will be missed. Nothing here homes unconditionally.
     *
     * @param turnRate how far the missile can bend its flight path, in degrees per tick
     * @param lockAngle half-angle of the seeker's cone, in degrees, measured off the nose
     * @param lockRange how far the seeker can see, in blocks
     * @param lockTicks how long the target must be held in the cone before the lock takes
     * @param trackAngle how far off its own nose the missile will still follow a target, in degrees.
     *                   Past this it has lost it and flies on ballistically
     * @param proximity how close it must get before it goes off, in blocks. A missile need not hit
     */
    public record Guidance(float turnRate, float lockAngle, float lockRange, int lockTicks,
            float trackAngle, float proximity) {

        public static final Codec<Guidance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("turn_rate", 6.0F).forGetter(Guidance::turnRate),
                Codec.FLOAT.optionalFieldOf("lock_angle", 15.0F).forGetter(Guidance::lockAngle),
                Codec.FLOAT.optionalFieldOf("lock_range", 220.0F).forGetter(Guidance::lockRange),
                Codec.INT.optionalFieldOf("lock_ticks", 20).forGetter(Guidance::lockTicks),
                Codec.FLOAT.optionalFieldOf("track_angle", 75.0F).forGetter(Guidance::trackAngle),
                Codec.FLOAT.optionalFieldOf("proximity", 2.5F).forGetter(Guidance::proximity)
        ).apply(instance, Guidance::new));
    }

    /**
     * The firing sound is found the same way as an engine's: the event named here, else one named
     * after the weapon ({@code <namespace>:weapon.<name>}), else a default chosen for the weapon's
     * kind. Played once a tick while firing, however many rounds that is.
     *
     * @param fire the sound event, or empty to look one up by the weapon's name
     * @param volume how loud, next to the weapon
     * @param pitch playback speed
     */
    public record SoundSetup(Optional<ResourceLocation> fire, float volume, float pitch) {
        public static final SoundSetup DEFAULT = new SoundSetup(Optional.empty(), 2.0F, 1.0F);

        public static final Codec<SoundSetup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("fire").forGetter(SoundSetup::fire),
                Codec.FLOAT.optionalFieldOf("volume", DEFAULT.volume()).forGetter(SoundSetup::volume),
                Codec.FLOAT.optionalFieldOf("pitch", DEFAULT.pitch()).forGetter(SoundSetup::pitch)
        ).apply(instance, SoundSetup::new));
    }
}
