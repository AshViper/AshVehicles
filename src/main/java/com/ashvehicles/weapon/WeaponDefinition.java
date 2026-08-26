package com.ashvehicles.weapon;

import java.util.Optional;

import com.mojang.datafixers.util.Either;
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
 * @param ammoItem which ammunition item feeds it, or empty to read it off how it fires. See
 *                 {@link #ammoKind()}
 * @param firing how it is fired
 * @param projectile what it fires
 * @param guidance how it steers, for a weapon that does. Absent means it does not
 * @param sound what it sounds like
 */
public record WeaponDefinition(Type type, boolean item, int ammo, Optional<AmmoKind> ammoItem,
        Firing firing, Projectile projectile, Optional<Guidance> guidance, SoundSetup sound) {

    /** {@code RRGGBB}, with or without a leading hash, as everything in these files writes colour. */
    static final Codec<Integer> COLOUR = Codec.STRING.comapFlatMap(
            text -> {
                try {
                    return DataResult.success(Integer.parseInt(text.replace("#", ""), 16));
                } catch (NumberFormatException exception) {
                    return DataResult.error(() -> "Not a colour: " + text);
                }
            },
            colour -> String.format("%06X", colour));

    public static final Codec<WeaponDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Type.CODEC.optionalFieldOf("type", Type.GUN).forGetter(WeaponDefinition::type),
            Codec.BOOL.optionalFieldOf("item", true).forGetter(WeaponDefinition::item),
            Codec.INT.fieldOf("ammo").forGetter(WeaponDefinition::ammo),
            AmmoKind.CODEC.optionalFieldOf("ammo_item").forGetter(WeaponDefinition::ammoItem),
            Firing.CODEC.fieldOf("firing").forGetter(WeaponDefinition::firing),
            Projectile.CODEC.fieldOf("projectile").forGetter(WeaponDefinition::projectile),
            Guidance.CODEC.optionalFieldOf("guidance").forGetter(WeaponDefinition::guidance),
            SoundSetup.CODEC.optionalFieldOf("sound", SoundSetup.DEFAULT).forGetter(WeaponDefinition::sound)
    ).apply(instance, WeaponDefinition::new));

    /**
     * Used when a weapon has no file the game can read at all: something that shoots, so the game
     * keeps running, but nothing anyone would mistake for a real weapon.
     */
    public static final WeaponDefinition FALLBACK = new WeaponDefinition(Type.GUN, true, 100, Optional.empty(),
            new Firing(5.0F, 1.0F, 1, 0.0F, Optional.empty()), Projectile.DEFAULT, Optional.empty(), SoundSetup.DEFAULT);

    /**
     * Whether holding the trigger keeps it firing, rather than sending one for each press.
     *
     * <p>The difference is not really about the weapon's kind, which is why it is a field rather
     * than a rule. What decides it is the rate: a cannon at fifty rounds a second is a thing you
     * hold down, and a hundred and twenty millimetres at one round every seven seconds is a thing
     * you press — and both of those are guns. Left out, a gun is automatic and everything else goes
     * one press at a time, which is what every weapon in the mod meant before the field existed.
     *
     * <p>Read by both the aircraft's pylons and a vehicle's built-in gun, so that a weapon behaves
     * the same way whichever machine it is bolted to.
     */
    public boolean isAutomatic() {
        return this.firing.automatic().orElse(this.type == Type.GUN);
    }

    /**
     * Which ammunition item this gun is loaded out of.
     *
     * <p>Named outright by a file that wants to, and otherwise read off what the weapon is and how
     * it fires. Anything that is not a gun goes in a tube one at a time; a gun you hold down is
     * belt-fed and one you press is loaded by hand, which is the same distinction
     * {@link #isAutomatic()} already draws. Between them they sort every weapon in the mod without a
     * line being added to any of their files. Say {@code ammo_item} to overrule it — a revolver
     * cannon loaded from a drum a shell at a time would want to, and so would anyone who wanted a
     * guided missile to cost something an unguided rocket does not.
     *
     * <p>This is what a machine's <em>built-in</em> armament is resupplied out of. A store hung on
     * an aircraft's pylon is resupplied out of the store itself, which is an item already; see
     * {@code WeaponMounts.draw}.
     */
    public AmmoKind ammoKind() {
        return this.ammoItem.orElseGet(() -> switch (this.type) {
            case GUN -> this.isAutomatic() ? AmmoKind.AUTOCANNON : AmmoKind.CANNON;
            default -> AmmoKind.ROCKET;
        });
    }

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
     * Whether what this fires holds open the ground it is flying over.
     *
     * <p>Something has to, or a weapon aimed past the edge of the loaded world lands on nothing.
     * Chunks exist around players and nowhere else, an aircraft holds open the corridor it is flying
     * down and no more, and a bomb released from three thousand feet is a long way from either by the
     * time it arrives. Blocks out there are not asked about at all — asking would generate the
     * terrain on the spot and on the main thread — so without a claim of its own a round passes
     * through the hillside it was aimed at and is given up on in the empty air behind it. See
     * {@link com.ashvehicles.entity.WeaponChunkLoader}.
     *
     * <p>Left out, everything claims ground, because everything is aimed at something. It used to be
     * only what was <em>dropped</em>, and that was a limit of the machinery rather than a decision
     * about weapons: a claim was one ticket per round, moved every tick, and a bomb spends ten
     * seconds coming down through the same two chunks while a gun crosses a chunk boundary twenty
     * times a second from thirty rounds at once. What made that affordable was making the claims
     * shared, unticked and rationed rather than making the guns shorter-ranged; the loader has the
     * detail.
     *
     * <p>Say {@code chunk_loading} outright to overrule it either way — {@code false} for something
     * that is only ever fired at aircraft, since an aeroplane is loaded wherever it is and the ground
     * under a missile chasing one is nobody's business.
     */
    public boolean loadsChunks() {
        return this.projectile.chunkLoading().orElse(true);
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
     * @param automatic whether the trigger fires for as long as it is held, or empty for the
     *                  default. See {@link WeaponDefinition#isAutomatic()}
     */
    public record Firing(float roundsPerSecond, float spread, int salvo, float salvoSpread,
            Optional<Boolean> automatic) {
        public static final Codec<Firing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("rounds_per_second").forGetter(Firing::roundsPerSecond),
                Codec.FLOAT.optionalFieldOf("spread", 0.5F).forGetter(Firing::spread),
                Codec.INT.optionalFieldOf("salvo", 1).forGetter(Firing::salvo),
                Codec.FLOAT.optionalFieldOf("salvo_spread", 0.0F).forGetter(Firing::salvoSpread),
                Codec.BOOL.optionalFieldOf("automatic").forGetter(Firing::automatic)
        ).apply(instance, Firing::new));

        /**
         * Ticks between rounds.
         *
         * <p>Need not be a whole number: a mount counts the fraction, so a rate that does not divide
         * into twenty still averages out to the figure in the file. A weapon faster than one round a
         * tick is written as a {@code salvo} instead, which is what a twin mounting physically is —
         * two barrels letting go together rather than one barrel going twice as fast.
         */
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
     * <p>A motor need not arrive at its whole thrust the moment it lights. {@code spool_ticks} works
     * it up from nothing rather than handing it over whole, so the missile gathers speed instead of
     * jumping to it. Leave it out and the motor is at full power off the rail, as it always was.
     *
     * @param damage dealt to whatever it hits directly, in the same points a player is worth twenty
     *               of. An airframe is worth a few hundred and takes it point for point
     * @param speed the speed it leaves at, in blocks per tick. The aircraft's own speed is added on
     * @param thrust acceleration from the motor, in blocks per tick squared, once it is at full power
     * @param burnTicks how long the motor burns. Zero for something with no motor at all
     * @param spoolTicks how long the motor takes to work up to {@code thrust} once it has lit. Zero
     *                   gives the whole of it from the first tick
     * @param topSpeed the fastest the motor will drive it, in blocks per tick
     * @param gravity how fast it drops, in blocks per tick squared
     * @param range how far it flies before it is given up on, in blocks
     * @param explosion blast made where it lands, in the same units as TNT's four. Zero for
     *                  something that simply hits
     * @param tracer colour it is drawn in, as {@code RRGGBB}
     * @param ricochet how obliquely this round has to strike armour before the armour throws it off
     *                 instead of biting, in degrees from the plate's own normal: nought is a square
     *                 hit and ninety is a graze along the surface. A long rod bites almost to the
     *                 grazing angle and wants a high figure; a small round rolls off a slope and
     *                 wants a low one. Zero, which is what anything without the field gets, means it
     *                 is never thrown off — right for a shaped charge and for anything that goes off
     *                 on contact rather than going through. See {@link com.ashvehicles.weapon.Ricochet}
     * @param trail the smoke it leaves behind it, if it leaves any
     * @param chunkLoading whether it holds the ground under it open, or empty for the default, which
     *                     is that it does. See {@link WeaponDefinition#loadsChunks()}
     */
    public record Projectile(float damage, float speed, float thrust, int burnTicks,
            int spoolTicks, float topSpeed,
            float gravity, float range, float explosion, int tracer, float ricochet,
            Optional<Trail> trail, Optional<Boolean> chunkLoading) {

        public static final Projectile DEFAULT = new Projectile(2.0F, 20.0F, 0.0F, 0, 0,
                0.0F, 0.02F, 200.0F, 0.0F, 0xFFC864, 0.0F, Optional.empty(), Optional.empty());

        /**
         * Reads the whole description of a trail, or the plain {@code true} that used to be all
         * there was to say. An older weapon file therefore still means what it meant, and gets the
         * ordinary trail; a newer one can say what colour its motor's smoke is.
         */
        private static final Codec<Optional<Trail>> TRAIL =
                Codec.either(Codec.BOOL, Trail.CODEC).xmap(
                        either -> either.map(
                                on -> on ? Optional.of(Trail.DEFAULT) : Optional.<Trail>empty(),
                                Optional::of),
                        trail -> trail.<Either<Boolean, Trail>>map(Either::right)
                                .orElseGet(() -> Either.left(false)));

        public static final Codec<Projectile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("damage").forGetter(Projectile::damage),
                Codec.FLOAT.fieldOf("speed").forGetter(Projectile::speed),
                Codec.FLOAT.optionalFieldOf("thrust", 0.0F).forGetter(Projectile::thrust),
                Codec.INT.optionalFieldOf("burn_ticks", 0).forGetter(Projectile::burnTicks),
                Codec.INT.optionalFieldOf("spool_ticks", 0).forGetter(Projectile::spoolTicks),
                Codec.FLOAT.optionalFieldOf("top_speed", 0.0F).forGetter(Projectile::topSpeed),
                Codec.FLOAT.optionalFieldOf("gravity", 0.02F).forGetter(Projectile::gravity),
                Codec.FLOAT.optionalFieldOf("range", 300.0F).forGetter(Projectile::range),
                Codec.FLOAT.optionalFieldOf("explosion", 0.0F).forGetter(Projectile::explosion),
                COLOUR.optionalFieldOf("tracer", 0xFFC864).forGetter(Projectile::tracer),
                Codec.FLOAT.optionalFieldOf("ricochet", 0.0F).forGetter(Projectile::ricochet),
                TRAIL.optionalFieldOf("trail", Optional.empty()).forGetter(Projectile::trail),
                Codec.BOOL.optionalFieldOf("chunk_loading").forGetter(Projectile::chunkLoading)
        ).apply(instance, Projectile::new));

        /** Whether armour can throw this round off at all, or whether it always bites. */
        public boolean canRicochet() {
            return this.ricochet > 0.0F;
        }

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
     * The smoke a motor leaves behind it.
     *
     * <p>There are two halves to it, because there are two things to see. The plume is what is
     * coming out of the nozzle now: thick, close, still moving with the missile, and only there
     * while the motor is burning. The trail is what that plume turned into a second ago, hanging
     * where the missile used to be and drifting apart. Nothing is drawn for a motor that has burnt
     * out, which is how a rocket's smoke stops at the point it went ballistic and how anyone
     * watching can tell.
     *
     * @param colour the trail proper, as {@code RRGGBB}: cold smoke, some way behind
     * @param exhaust the plume at the nozzle, which is hotter and usually darker
     * @param density puffs laid down per block flown. Below one leaves gaps on purpose
     * @param size how big each puff is, against the ordinary one
     */
    public record Trail(int colour, int exhaust, float density, float size) {
        /** What a weapon file that says no more than {@code "trail": true} gets. */
        public static final Trail DEFAULT = new Trail(0xD8D5CD, 0x9A958B, 1.3F, 1.0F);

        public static final Codec<Trail> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                COLOUR.optionalFieldOf("colour", DEFAULT.colour()).forGetter(Trail::colour),
                COLOUR.optionalFieldOf("exhaust", DEFAULT.exhaust()).forGetter(Trail::exhaust),
                Codec.FLOAT.optionalFieldOf("density", DEFAULT.density()).forGetter(Trail::density),
                Codec.FLOAT.optionalFieldOf("size", DEFAULT.size()).forGetter(Trail::size)
        ).apply(instance, Trail::new));
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
     * @param navGain the navigation constant proportional navigation is named for: how many times
     *                over the missile turns to null the line of sight's own rotation rather than
     *                merely matching it. Three to five is what a real seeker head is built around;
     *                much past that and a missile answers every flicker in the tracking as though
     *                the target had actually moved, which is its own kind of miss
     */
    public record Guidance(float turnRate, float lockAngle, float lockRange, int lockTicks,
            float trackAngle, float proximity, float navGain, Seeker seeker) {

        /**
         * What the seeker is looking at, and so what will fool it.
         *
         * <p>The whole of the point of having two sorts of countermeasure. A pilot who has been told
         * they are locked has a second or two to decide which handle to pull, and pulling the wrong
         * one leaves the missile exactly where it was.
         */
        public enum Seeker implements StringRepresentable {
            /** Homes on heat, and follows a flare instead. */
            HEAT("heat"),
            /** Homes on a radar return, and follows a cloud of chaff instead. */
            RADAR("radar");

            public static final Codec<Seeker> CODEC = StringRepresentable.fromEnum(Seeker::values);

            private final String name;

            Seeker(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }

            /** Whether a flare is what fools this one; chaff is what fools the other. */
            public boolean foolLetsGoOfFlares() {
                return this == HEAT;
            }
        }

        public static final Codec<Guidance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("turn_rate", 6.0F).forGetter(Guidance::turnRate),
                Codec.FLOAT.optionalFieldOf("lock_angle", 15.0F).forGetter(Guidance::lockAngle),
                Codec.FLOAT.optionalFieldOf("lock_range", 220.0F).forGetter(Guidance::lockRange),
                Codec.INT.optionalFieldOf("lock_ticks", 20).forGetter(Guidance::lockTicks),
                Codec.FLOAT.optionalFieldOf("track_angle", 75.0F).forGetter(Guidance::trackAngle),
                Codec.FLOAT.optionalFieldOf("proximity", 2.5F).forGetter(Guidance::proximity),
                Codec.FLOAT.optionalFieldOf("nav_gain", 3.5F).forGetter(Guidance::navGain),
                Seeker.CODEC.optionalFieldOf("seeker", Seeker.HEAT).forGetter(Guidance::seeker)
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

        /**
         * How far a weapon this loud is heard, in blocks, per point of volume.
         *
         * <p>Nothing to do with how loud it is next to the aeroplane, which is {@link #volume()}.
         * A cannon in life is heard across a valley and an aeroplane fights across several of them,
         * so a figure of a few hundred blocks is the one that matters and the loudness at the far end
         * is worked out from it. See {@link com.ashvehicles.client.sound.WeaponSounds}.
         */
        private static final float CARRY_PER_VOLUME = 160.0F;

        /** How far this weapon is heard, in blocks. */
        public float carry() {
            return Math.max(this.volume, 0.0F) * CARRY_PER_VOLUME;
        }

        /**
         * The loudness to hand the game when asking it to send the sound.
         *
         * <p>A fiction, and the only one available. The server sends a sound to everyone within
         * {@code max(volume, 1) * 16} blocks and to nobody else, so the volume is not really a
         * loudness at all — it is the only way to say how far a sound should travel. What arrives
         * would be deafening if it were played as sent, so the client throws the figure away and
         * works out the real loudness from the distance instead.
         */
        public float packetVolume() {
            return Math.max(this.carry() / 16.0F, 1.0F);
        }

        public static final Codec<SoundSetup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("fire").forGetter(SoundSetup::fire),
                Codec.FLOAT.optionalFieldOf("volume", DEFAULT.volume()).forGetter(SoundSetup::volume),
                Codec.FLOAT.optionalFieldOf("pitch", DEFAULT.pitch()).forGetter(SoundSetup::pitch)
        ).apply(instance, SoundSetup::new));
    }
}
