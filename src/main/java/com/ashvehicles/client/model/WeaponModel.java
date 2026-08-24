package com.ashvehicles.client.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ashvehicles.AshVehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;

/**
 * Draws any weapon, in flight or hanging under a wing, from its own geometry file.
 *
 * <p>Nothing here is specific to one weapon: the geometry and texture are found by the weapon's own
 * name, so {@code r60} is drawn from {@code geo/weapon/r60.geo.json} and
 * {@code textures/weapon/r60.png} without being told where they are. That is the same arrangement
 * the aircraft use, and it means a new weapon needs no Java at all — a JSON file in
 * {@code data/}, a model, and a texture.
 *
 * <p>A weapon with no model of its own falls back to a plain one rather than to a missing-texture
 * cube or a crash, in the same way a weapon with no firing sound falls back to a default. Somebody
 * adding a weapon gets something that flies and is visible from the first minute, and can draw it
 * properly later.
 *
 * @param <T> whatever is being drawn: a missile in the air, or a store on a pylon
 */
public abstract class WeaponModel<T extends GeoAnimatable> extends GeoModel<T> {
    /** Used by any weapon that has no geometry of its own. */
    private static final ResourceLocation DEFAULT_GEOMETRY =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "geo/weapon/default.geo.json");
    private static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "textures/weapon/default.png");
    private static final ResourceLocation DEFAULT_ANIMATION =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "animations/weapon/default.animation.json");

    /**
     * Which files each weapon is drawn from, worked out once and then remembered.
     *
     * <p>Both halves of that were being paid for far too often. Building the name meant a string
     * joined and then validated character by character; deciding whether a pack provides it meant
     * asking the resource manager, which walks the whole pack stack and ends in a file being looked
     * for on disk. GeckoLib asks for the geometry and the texture of everything it draws on
     * <em>every frame</em>, and a laden aircraft is a dozen of those — so a wing full of missiles
     * was several dozen file-system lookups a frame, for an answer that only a resource reload can
     * change.
     *
     * <p>Cleared by {@link #clearCache()} when one does. Concurrent because the render thread and
     * the client tick both reach it, and a stale half-built map would be worse than the lookup.
     */
    private static final Map<ResourceLocation, Files> FILES = new ConcurrentHashMap<>();

    /** The three files one weapon is drawn from, each already resolved against the packs. */
    private record Files(ResourceLocation geometry, ResourceLocation texture, ResourceLocation animation) {
    }

    /** Which weapon the thing being drawn is. */
    protected abstract ResourceLocation weaponId(T animatable);

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return geometryFile(this.weaponId(animatable));
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return textureFile(this.weaponId(animatable));
    }

    /** Only consulted if a controller ever plays a named animation, and no weapon has one. */
    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return files(this.weaponId(animatable)).animation();
    }

    /** The geometry a weapon is drawn from, by its id; what {@link #getModelResource} answers. */
    public static ResourceLocation geometryFile(ResourceLocation weapon) {
        return files(weapon).geometry();
    }

    /** The texture a weapon is drawn with, by its id; what {@link #getTextureResource} answers. */
    public static ResourceLocation textureFile(ResourceLocation weapon) {
        return files(weapon).texture();
    }

    /**
     * Forgets which files each weapon is drawn from. Called when the resource packs are reloaded,
     * which is the only thing that can change the answer — so a pack added at runtime is picked up
     * exactly as it was when every draw asked afresh.
     */
    public static void clearCache() {
        FILES.clear();
    }

    private static Files files(ResourceLocation weapon) {
        return FILES.computeIfAbsent(weapon, id -> new Files(
                found(file("geo/weapon/", id, ".geo.json"), DEFAULT_GEOMETRY),
                found(file("textures/weapon/", id, ".png"), DEFAULT_TEXTURE),
                DEFAULT_ANIMATION));
    }

    private static ResourceLocation file(String directory, ResourceLocation weapon, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(weapon.getNamespace(),
                directory + weapon.getPath() + suffix);
    }

    /**
     * The weapon's own file if any resource pack provides it, otherwise the fallback. Asked once per
     * weapon per resource reload; see {@link #FILES}.
     */
    private static ResourceLocation found(ResourceLocation wanted, ResourceLocation fallback) {
        return Minecraft.getInstance().getResourceManager().getResource(wanted).isPresent()
                ? wanted
                : fallback;
    }
}
