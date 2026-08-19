package com.ashvehicles.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Aircraft controls. The three rotation axes are flown directly: W and S pitch, A and D roll, Q and
 * E yaw. Pitch and roll ride on the vanilla movement keys, so only the rest need bindings.
 *
 * <p>Several of these sit on keys vanilla already uses. That is deliberate - shift and control fall
 * under the fingers for a throttle - and {@link AircraftInputHandler} swallows what vanilla would
 * otherwise do with them while the player is flying. Shift in particular no longer means "get out";
 * alt does.
 */
public final class ModKeyMappings {
    private static final String CATEGORY = "key.categories.ashvehicles";

    public static final KeyMapping THROTTLE_UP = create("throttle_up", GLFW.GLFW_KEY_LEFT_SHIFT);
    public static final KeyMapping THROTTLE_DOWN = create("throttle_down", GLFW.GLFW_KEY_LEFT_CONTROL);
    public static final KeyMapping YAW_LEFT = create("yaw_left", GLFW.GLFW_KEY_Q);
    public static final KeyMapping YAW_RIGHT = create("yaw_right", GLFW.GLFW_KEY_E);
    public static final KeyMapping DISMOUNT = create("dismount", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping AIR_BRAKE = create("air_brake", GLFW.GLFW_KEY_B);
    public static final KeyMapping TOGGLE_GEAR = create("toggle_gear", GLFW.GLFW_KEY_G);
    public static final KeyMapping TOGGLE_FLAPS = create("toggle_flaps", GLFW.GLFW_KEY_F);
    /** Steps through whatever is on the pylons. The trigger itself is the vanilla attack button. */
    public static final KeyMapping CYCLE_WEAPON = create("cycle_weapon", GLFW.GLFW_KEY_X);

    public static final KeyMapping[] ALL = {THROTTLE_UP, THROTTLE_DOWN, YAW_LEFT, YAW_RIGHT, DISMOUNT,
            AIR_BRAKE, TOGGLE_GEAR, TOGGLE_FLAPS, CYCLE_WEAPON};

    private static KeyMapping create(String name, int key) {
        return new KeyMapping("key.ashvehicles." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    private ModKeyMappings() {
    }
}
