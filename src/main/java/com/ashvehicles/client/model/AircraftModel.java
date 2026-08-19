package com.ashvehicles.client.model;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * Draws any aircraft. Nothing here is specific to one: the geometry, texture and animation files are
 * found by the aircraft's own name, and which bone is an aileron or a gear leg comes from its file.
 *
 * <p>There is no animation JSON. Everything that moves is driven from code in
 * {@link #setCustomAnimations}, which GeckoLib calls once per frame after the (empty) animation
 * controllers have run, so anything set here is what gets drawn.
 *
 * <p>Control surfaces follow how fast the aircraft is actually rotating rather than the pilot's
 * keys: rotation is synced to every client, so other players see the surfaces move as well. A bone
 * an aircraft does not name, or names wrongly, is skipped rather than crashing; it just sits rigid.
 */
public class AircraftModel extends GeoModel<AircraftEntity> {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    // Travel of each moving part, in degrees. Flip a sign here if a part swings the wrong way.
    private static final float ELEVATOR_TRAVEL = 20.0F;
    private static final float AILERON_TRAVEL = 22.0F;
    private static final float RUDDER_TRAVEL = 18.0F;
    private static final float FLAP_TRAVEL = 15.0F;
    private static final float GEAR_RETRACT_TRAVEL = 90.0F;
    private static final float GEAR_DOOR_TRAVEL = 85.0F;

    @Override
    public ResourceLocation getModelResource(AircraftEntity animatable) {
        return file("geo/entity/", animatable, ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(AircraftEntity animatable) {
        return file("textures/entity/", animatable, ".png");
    }

    /** Only consulted if a controller ever plays a named animation, and no aircraft has one yet. */
    @Override
    public ResourceLocation getAnimationResource(AircraftEntity animatable) {
        return file("animations/entity/", animatable, ".animation.json");
    }

    private static ResourceLocation file(String directory, AircraftEntity animatable, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
                directory + animatable.getAircraftId().getPath() + suffix);
    }

    @Override
    public void setCustomAnimations(AircraftEntity animatable, long instanceId,
            AnimationState<AircraftEntity> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);

        AircraftDefinition.ModelSetup model = animatable.getStats().model();

        // Pulling the nose up drops the tailplane's trailing edge, hence the negated pitch delta.
        float elevator = deflection(-animatable.getPitchDelta(), animatable.getPitchRate(), ELEVATOR_TRAVEL);
        float aileron = deflection(animatable.getRollDelta(), animatable.getRollRate(), AILERON_TRAVEL);
        float rudder = deflection(animatable.getYawDelta(), animatable.getYawRate(), RUDDER_TRAVEL);

        rotateX(model, AircraftDefinition.Bone.ELEVATOR_LEFT, elevator);
        rotateX(model, AircraftDefinition.Bone.ELEVATOR_RIGHT, elevator);
        rotateX(model, AircraftDefinition.Bone.AILERON_LEFT, -aileron);
        rotateX(model, AircraftDefinition.Bone.AILERON_RIGHT, aileron);
        rotateY(model, AircraftDefinition.Bone.RUDDER, rudder);

        // The undercarriage swings up and back, and the bay doors are open whenever it is not stowed.
        float partialTick = animationState.getPartialTick();
        float gear = animatable.getGearProgress(partialTick);
        float retracted = 1.0F - gear;

        rotateX(model, AircraftDefinition.Bone.NOSE_GEAR, retracted * GEAR_RETRACT_TRAVEL);
        rotateX(model, AircraftDefinition.Bone.LEFT_GEAR, retracted * GEAR_RETRACT_TRAVEL);
        rotateX(model, AircraftDefinition.Bone.RIGHT_GEAR, retracted * GEAR_RETRACT_TRAVEL);
        rotateZ(model, AircraftDefinition.Bone.NOSE_GEAR_DOOR, gear * GEAR_DOOR_TRAVEL);
        rotateZ(model, AircraftDefinition.Bone.LEFT_GEAR_DOOR, gear * GEAR_DOOR_TRAVEL);
        rotateZ(model, AircraftDefinition.Bone.RIGHT_GEAR_DOOR, -gear * GEAR_DOOR_TRAVEL);

        float flaps = animatable.getFlapsProgress(partialTick);
        rotateX(model, AircraftDefinition.Bone.FLAP_LEFT, flaps * FLAP_TRAVEL);
        rotateX(model, AircraftDefinition.Bone.FLAP_RIGHT, flaps * FLAP_TRAVEL);
    }

    /** Maps a rotation rate onto a control surface: full travel once the aircraft turns at its limit. */
    private static float deflection(float ratePerTick, float maxRatePerTick, float travelDegrees) {
        if (maxRatePerTick <= 0.0F) {
            return 0.0F;
        }

        return Mth.clamp(ratePerTick / maxRatePerTick, -1.0F, 1.0F) * travelDegrees;
    }

    private void rotateX(AircraftDefinition.ModelSetup model, String role, float degrees) {
        getBone(model.bone(role)).ifPresent(bone -> bone.setRotX(degrees * DEG_TO_RAD));
    }

    private void rotateY(AircraftDefinition.ModelSetup model, String role, float degrees) {
        getBone(model.bone(role)).ifPresent(bone -> bone.setRotY(degrees * DEG_TO_RAD));
    }

    private void rotateZ(AircraftDefinition.ModelSetup model, String role, float degrees) {
        getBone(model.bone(role)).ifPresent(bone -> bone.setRotZ(degrees * DEG_TO_RAD));
    }
}
