package com.ashvehicles.client.model;

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

    /** Which weapon the thing being drawn is. */
    protected abstract ResourceLocation weaponId(T animatable);

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return found(file("geo/weapon/", animatable, ".geo.json"), DEFAULT_GEOMETRY);
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return found(file("textures/weapon/", animatable, ".png"), DEFAULT_TEXTURE);
    }

    /** Only consulted if a controller ever plays a named animation, and no weapon has one. */
    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "animations/weapon/default.animation.json");
    }

    /** The geometry a weapon is drawn from, by its id; what {@link #getModelResource} answers. */
    public static ResourceLocation geometryFile(ResourceLocation weapon) {
        return found(file("geo/weapon/", weapon, ".geo.json"), DEFAULT_GEOMETRY);
    }

    /** The texture a weapon is drawn with, by its id; what {@link #getTextureResource} answers. */
    public static ResourceLocation textureFile(ResourceLocation weapon) {
        return found(file("textures/weapon/", weapon, ".png"), DEFAULT_TEXTURE);
    }

    private ResourceLocation file(String directory, T animatable, String suffix) {
        return file(directory, this.weaponId(animatable), suffix);
    }

    private static ResourceLocation file(String directory, ResourceLocation weapon, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(weapon.getNamespace(),
                directory + weapon.getPath() + suffix);
    }

    /**
     * The weapon's own file if any resource pack provides it, otherwise the fallback. Asked afresh
     * each time rather than remembered, so a pack added at runtime is picked up by a resource reload
     * like everything else.
     */
    private static ResourceLocation found(ResourceLocation wanted, ResourceLocation fallback) {
        return Minecraft.getInstance().getResourceManager().getResource(wanted).isPresent()
                ? wanted
                : fallback;
    }
}
