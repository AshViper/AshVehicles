package com.ashvehicles.entity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.ashvehicles.AshVehicles;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Keeps the machines from killing the people riding them.
 *
 * <p>None of what this prevents is anybody shooting at anybody. It is all the damage a machine does
 * to its own crew simply by being a large object that moves, which Minecraft has no notion of and
 * therefore accounts for in the only way it knows: as a man standing in mid-air, as a man inside a
 * wall, as a man who has just fallen a very long way.
 *
 * <p><b>Three of them.</b>
 *
 * <p><em>Sitting in one.</em> A seat is a place inside a solid object, and half of what the game
 * does with a position that is inside something is to hurt whoever is there. A cockpit that passes
 * through a hillside on the way past suffocates the pilot; a machine set down anywhere awkward
 * crushes them. Nothing that happens to somebody strapped into a machine is their own doing, so
 * nothing that happens to them is charged to them: while they are aboard, the machine is what takes
 * the damage, which is what its several hundred hit points are for.
 *
 * <p><em>Standing on one.</em> A deck is a floor the game cannot see. Whoever is on it is carried by
 * {@link Hitboxes#carry}, and being carried downwards is bookkept as falling however carefully the
 * carrying is done — see the note there, which is where the distance is dropped rather than paid.
 * This is the second line: a fall that arrives anyway while somebody is up against a machine is the
 * machine's doing and is written off, and so are the other three ways a moving wall hurts whoever is
 * leaning on it. Anything else still lands, so standing on a wing is not a suit of armour.
 *
 * <p><em>Having just left one.</em> The most reliable way to die in this mod was to touch the sneak
 * key at altitude, or to be sitting in something when it was shot down: the seat lets go, and the
 * fall that follows is a fall nobody chose to take. For a few seconds after a machine puts somebody
 * out, the first landing is free.
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class CrewSafety {
    /**
     * How long after a machine lets go of somebody the fall that follows is still the machine's
     * doing, in ticks.
     *
     * <p>Ten seconds, which is about four hundred blocks of falling and therefore covers a bailout
     * from any altitude these aircraft fly at. It is a ceiling rather than a duration: the grace is
     * spent by the first landing, so it is not ten seconds of walking off cliffs.
     */
    private static final long BAILOUT_GRACE = 200L;

    /**
     * When each of them was last put out of a machine.
     *
     * <p>Weakly, so that somebody who logs out or dies takes their entry with them, and guarded
     * because entities leave machines on both sides and each side ticks on its own thread.
     */
    private static final Map<Entity, Long> BAILED = Collections.synchronizedMap(new WeakHashMap<>());

    private CrewSafety() {
    }

    /**
     * Everything that is about to be done to somebody aboard a machine, or to somebody a machine is
     * up against.
     *
     * <p>Aboard is everything: a crew is out of the game's reach until they get out, and what
     * becomes of the machine is the machine's business. Merely touching one is only the handful of
     * ways a moving obstacle hurts whoever it is touching, because a wing is a place to stand and
     * not a licence.
     *
     * <p>Nothing here stands in the way of the two sources that are not damage at all — the void and
     * {@code /kill}. A pilot who flies out of the bottom of the world dies of it like anybody else,
     * and an aircraft nobody can get rid of is not an improvement on one that kills its crew.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity crew = event.getEntity();
        DamageSource source = event.getSource();

        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }

        if (aboard(crew) || (isContact(source) && Hitboxes.touching(crew))) {
            event.setCanceled(true);
        }
    }

    /**
     * A landing that has been reached with a machine's help.
     *
     * <p>Cancelled rather than reduced, because the distance behind it is not a distance anybody
     * fell: it is a deck that descended, a seat that was let go of, or a hull that pushed somebody
     * off a ledge, and there is no honest fraction of it to charge.
     *
     * <p>This is also where a bailout's grace is spent. Landing is the end of the fall the machine
     * started, and whatever happens after it is the player's own again.
     */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        LivingEntity faller = event.getEntity();

        if (aboard(faller) || Hitboxes.touching(faller) || bailedOut(faller)) {
            BAILED.remove(faller);
            event.setCanceled(true);
        }
    }

    /**
     * Notes the moment a machine lets go of somebody, and forgets it the moment one takes them
     * aboard again.
     *
     * <p>Both mounting and dismounting come through here, and the machine is the one being got into
     * or out of. Anything else — a horse, a boat, a minecart — is nothing to do with this.
     */
    @SubscribeEvent
    public static void onMountChange(EntityMountEvent event) {
        if (!(event.getEntityBeingMounted() instanceof VehicleEntityBase) || event.getLevel().isClientSide) {
            return;
        }

        Entity crew = event.getEntityMounting();

        if (event.isDismounting()) {
            BAILED.put(crew, event.getLevel().getGameTime());
        } else {
            BAILED.remove(crew);
        }
    }

    /** Whether they are riding one of the mod's machines, in any seat of anything it is towing. */
    private static boolean aboard(Entity crew) {
        return crew.isPassenger() && crew.getRootVehicle() instanceof VehicleEntityBase;
    }

    /** Whether a machine has recently put them out and they have not landed since. */
    private static boolean bailedOut(Entity crew) {
        Long left = BAILED.get(crew);

        if (left == null) {
            return false;
        }

        long since = crew.level().getGameTime() - left;

        if (since >= 0L && since <= BAILOUT_GRACE) {
            return true;
        }

        BAILED.remove(crew);

        return false;
    }

    /**
     * The ways being up against something that moves hurts you: landing on it or being carried down
     * by it, being pushed into a wall by it, running into it, and being crowded by it.
     *
     * <p>All four are the same event as far as anybody standing on a wing is concerned — the machine
     * moved and they were in the way of it — and none of them is a thing a player can do anything
     * about from up there.
     */
    private static boolean isContact(DamageSource source) {
        return source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypes.IN_WALL)
                || source.is(DamageTypes.FLY_INTO_WALL)
                || source.is(DamageTypes.CRAMMING);
    }
}
