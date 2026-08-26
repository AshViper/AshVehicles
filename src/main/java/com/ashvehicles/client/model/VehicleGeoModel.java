package com.ashvehicles.client.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3f;

import com.ashvehicles.AshVehicles;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * Finds the three files any of the mod's machines is drawn from, and remembers where they are.
 *
 * <p>Nothing here names a machine. An aircraft or a vehicle called {@code su_25} is drawn from
 * {@code geo/entity/su_25.geo.json} and {@code textures/entity/su_25.png} without being told where
 * they are, because a machine's id is the name everything about it is found under.
 *
 * <p><b>Why the answers are kept.</b> They are derived names — a directory, the machine's own path, a
 * suffix — and building one means a string joined and then validated a character at a time. That is
 * nothing to do once and a good deal to do on every frame of every machine on the screen, which is
 * what GeckoLib asks for, and again on every tick of every ghost. Nothing about the answer can ever
 * change: an id is settled when the entity is built.
 */
public abstract class VehicleGeoModel<T extends Entity & GeoEntity> extends GeoModel<T> {
    protected static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private record Files(ResourceLocation geometry, ResourceLocation texture, ResourceLocation animation) {
    }

    private static final Map<ResourceLocation, Files> FILES = new ConcurrentHashMap<>();

    /** The id everything about this machine is found under. */
    protected abstract ResourceLocation idOf(T animatable);

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return geometryFile(this.idOf(animatable));
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return textureFile(this.idOf(animatable));
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return animationFile(this.idOf(animatable));
    }

    /**
     * A bone an animation names and the geometry does not is skipped, as one the pose code cannot
     * find is. Losing a bay door or a hatch because somebody renamed it is not worth taking the
     * client down for, and the alternative — every file having to be exactly in step with its
     * geometry or nobody can play — is not a trade anyone would make.
     */
    @Override
    public boolean crashIfBoneMissing() {
        return false;
    }

    public static ResourceLocation geometryFile(ResourceLocation id) {
        return files(id).geometry();
    }

    public static ResourceLocation textureFile(ResourceLocation id) {
        return files(id).texture();
    }

    public static ResourceLocation animationFile(ResourceLocation id) {
        return files(id).animation();
    }

    private static Files files(ResourceLocation id) {
        return FILES.computeIfAbsent(id, name -> new Files(
                file("geo/entity/", name, ".geo.json"),
                file("textures/entity/", name, ".png"),
                file("animations/entity/", name, ".animation.json")));
    }

    private static ResourceLocation file(String directory, ResourceLocation id, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, directory + id.getPath() + suffix);
    }

    /**
     * Turns a bone <em>from</em> where the geometry file put it, rather than to a flat angle.
     *
     * <p>This is the whole difference between a wheel that spins and a wheel that jumps out of its
     * hub the moment anything touches it. A road wheel is usually built lying on its side and stood
     * up by a rotation in the file; setting the rotation outright throws that away and lays the wheel
     * flat again. GeckoLib's own animations add to the same figure — see {@code AnimationProcessor},
     * which lerps a keyframe and adds the initial snapshot to it — so this is what a bone posed from
     * code has to do as well.
     */
    protected static void rotateX(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotX(found.getInitialSnapshot().getRotX() + degrees * DEG_TO_RAD));
    }

    protected static void rotateY(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotY(found.getInitialSnapshot().getRotY() + degrees * DEG_TO_RAD));
    }

    protected static void rotateZ(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotZ(found.getInitialSnapshot().getRotZ() + degrees * DEG_TO_RAD));
    }

    /** Slides a bone along its own Z, from wherever the geometry file put it. */
    protected static void slideZ(GeoModel<?> model, String bone, float units) {
        pose(model, bone, found -> found.setPosZ(found.getInitialSnapshot().getOffsetZ() + units));
    }

    // ------------------------------------------------------------------
    // Posing a bone in the machine's axes rather than its own
    // ------------------------------------------------------------------

    /**
     * The same three turns and the slide, but about the <em>machine's</em> axes rather than the
     * bone's own — so that a part goes the way it is asked to go whatever frame its geometry was
     * built in.
     *
     * <p><b>What the problem is.</b> A bone is turned inside its parent, and its parent inside its
     * parent, up to the root; a rotation anywhere up that chain carries the bone's axes round with
     * it. Half the models here are built facing backwards and turned round by a half turn on the
     * root bone, and a wheel or a barrel under a root like that has its own +X pointing at the
     * machine's left. Ask both kinds of model for the same turn about X and one of them elevates its
     * gun and rolls its wheels forwards while the other depresses and rolls back. That is the whole
     * of what a track running the wrong way, or a gun that dips when it should rise, ever is.
     *
     * <p>It is not only the root: a wheel bone frequently carries a half turn of its own so that it
     * could be built once and reused down the side, and one of these models turns eight of its
     * twenty-one that way and not the other thirteen. There is no per-vehicle flag that can express
     * that, which is why the answer is worked out per bone.
     *
     * <p><b>What is done about it.</b> Before the turn is applied, the axis it will actually happen
     * about is carried up the chain of parents into the machine's own frame, and if it comes out
     * pointing backwards the angle is negated. So the caller asks for a lay to the right, or a wheel
     * winding on, and gets it; nobody has to know which way round the artist left the file.
     *
     * <p>The rest pose is what is carried up, not this frame's — a turret that has been slewed is
     * still a turret the right way up, and the gun under it elevates the same way at every bearing.
     *
     * <p>An axis that comes out square to the one asked for — a bone quarter-turned, so that a turn
     * about X tips the part sideways instead of rolling it — cannot be fixed by a sign and is left
     * alone. That is a model to fix in Blockbench, not here.
     */
    protected static void turnAboutX(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> turnAboutX(found, degrees));
    }

    /** The same, given the bone rather than its name. @see #slideAlongY */
    protected static void turnAboutX(GeoBone bone, float degrees) {
        bone.setRotX(rest(bone).getRotX() + machineSignX(bone) * degrees * DEG_TO_RAD);
    }

    /** @see #turnAboutX */
    protected static void turnAboutY(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotY(
                rest(found).getRotY() + machineSignY(found) * degrees * DEG_TO_RAD));
    }

    /** @see #turnAboutX */
    protected static void slideAlongZ(GeoModel<?> model, String bone, float units) {
        pose(model, bone, found -> slideAlongZ(found, units));
    }

    /** The same, given the bone rather than its name. @see #slideAlongY */
    protected static void slideAlongZ(GeoBone bone, float units) {
        bone.setPosZ(rest(bone).getOffsetZ() + machineSignZ(bone) * units);
    }

    /**
     * As {@link #slideAlongZ}, along the machine's vertical, and given the bone rather than its name.
     *
     * <p>The bone rather than the name because the callers for this one — a road wheel being put
     * back down on the ground as the body moves above it — have to know where the bone is before
     * they can know how far to move it, and looking the same bone up twice a frame for every wheel
     * on the vehicle is not free.
     */
    protected static void slideAlongY(GeoBone bone, float units) {
        bone.setPosY(rest(bone).getOffsetY() + machineSignYSlide(bone) * units);
    }


    /**
     * As {@link #machineSignZ}, for a slide along Y. None of the bone's own turns come into it, for
     * the same reason: an offset happens in the parent's axes and not in the bone's.
     */
    private static float machineSignYSlide(GeoBone bone) {
        return sign(intoMachine(bone, new Vector3f(0.0F, 1.0F, 0.0F)).y());
    }


    /**
     * Whether a turn about this bone's X comes out as a turn the same way about the machine's.
     *
     * <p>The bone's own Z and Y turns come into it and its own X does not. GeckoLib builds a bone's
     * matrix as Z, then Y, then X — see {@code RenderUtil.rotateMatrixAroundBone} — so the X is the
     * innermost of the three and the axis it happens about is the bone's own X carried through the
     * other two.
     */
    private static float machineSignX(GeoBone bone) {
        BoneSnapshot rest = rest(bone);
        Vector3f axis = new Vector3f(1.0F, 0.0F, 0.0F).rotateY(rest.getRotY()).rotateZ(rest.getRotZ());

        return sign(intoMachine(bone, axis).x());
    }

    /** As {@link #machineSignX}, for a turn about Y, which only the bone's own Z turn is outside of. */
    private static float machineSignY(GeoBone bone) {
        Vector3f axis = new Vector3f(0.0F, 1.0F, 0.0F).rotateZ(rest(bone).getRotZ());

        return sign(intoMachine(bone, axis).y());
    }

    /**
     * As {@link #machineSignX}, for a slide along Z.
     *
     * <p>None of the bone's own turns come into this one. A bone's position offset is applied before
     * it is taken out to its pivot and turned — {@code RenderUtil.prepMatrixForBone} again — so a
     * slide happens in the parent's axes and not in the bone's.
     */
    private static float machineSignZ(GeoBone bone) {
        return sign(intoMachine(bone, new Vector3f(0.0F, 0.0F, 1.0F)).z());
    }

    /** Carries a direction in a bone's parent's axes up the chain of parents into the machine's. */
    private static Vector3f intoMachine(GeoBone bone, Vector3f axis) {
        for (GeoBone up = bone.getParent(); up != null; up = up.getParent()) {
            BoneSnapshot rest = rest(up);

            axis.rotateX(rest.getRotX()).rotateY(rest.getRotY()).rotateZ(rest.getRotZ());
        }

        return axis;
    }

    private static float sign(float of) {
        return of < 0.0F ? -1.0F : 1.0F;
    }

    /**
     * Where the geometry file left a bone. A bone GeckoLib has not taken its snapshot of yet is
     * asked to take it, which for a bone nothing has moved is the same answer.
     */
    protected static BoneSnapshot rest(GeoBone bone) {
        BoneSnapshot rest = bone.getInitialSnapshot();

        if (rest == null) {
            bone.saveInitialSnapshot();
            rest = bone.getInitialSnapshot();
        }

        return rest;
    }

    private static void pose(GeoModel<?> model, String bone, java.util.function.Consumer<GeoBone> what) {
        if (bone.isEmpty()) {
            return;
        }

        model.getBone(bone).ifPresent(what);
    }
}
