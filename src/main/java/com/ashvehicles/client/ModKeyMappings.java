package com.ashvehicles.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Aircraft controls. The three rotation axes are flown directly: W and S pitch, A and D roll, Q and
 * E yaw, with shift and control on the throttle.
 *
 * <p>Several of these sit on keys vanilla already uses. That is deliberate - shift and control fall
 * under the fingers for a throttle - and {@link AircraftInputHandler} swallows what vanilla would
 * otherwise do with them while the player is flying. Shift in particular no longer means "get out";
 * alt does, in every seat of every machine here — see {@link VehicleDismountHandler}.
 */
public final class ModKeyMappings {
    private static final String CATEGORY = "key.categories.ashvehicles";

    /**
     * The stick.
     *
     * <p>These four used to be read straight off the vanilla movement keys instead of having
     * bindings of their own. That flew perfectly well and could not be changed: pitch and roll never
     * appeared in the controls screen at all, so a pilot who wanted the nose on a different pair of
     * keys had nowhere to say so, and anyone who had moved <em>walking</em> off WASD found the
     * aeroplane still answering to keys that no longer did anything else.
     *
     * <p>They keep the movement keys as their defaults, so nothing changes for anybody who was happy
     * with them, and the controls screen marks them as clashing with walking — which is true, is
     * deliberate, and is the same thing the throttle and the flaps already do here. Nobody walks
     * while strapped into an aeroplane.
     *
     * <p>Stick forward drops the nose, as in any flight simulator, so the walk-forwards key is
     * {@code pitch_down} rather than {@code pitch_up}.
     */
    public static final KeyMapping PITCH_DOWN = create("pitch_down", GLFW.GLFW_KEY_W);
    public static final KeyMapping PITCH_UP = create("pitch_up", GLFW.GLFW_KEY_S);
    public static final KeyMapping ROLL_LEFT = create("roll_left", GLFW.GLFW_KEY_A);
    public static final KeyMapping ROLL_RIGHT = create("roll_right", GLFW.GLFW_KEY_D);

    public static final KeyMapping THROTTLE_UP = create("throttle_up", GLFW.GLFW_KEY_LEFT_SHIFT);
    public static final KeyMapping THROTTLE_DOWN = create("throttle_down", GLFW.GLFW_KEY_LEFT_CONTROL);
    public static final KeyMapping YAW_LEFT = create("yaw_left", GLFW.GLFW_KEY_Q);
    public static final KeyMapping YAW_RIGHT = create("yaw_right", GLFW.GLFW_KEY_E);
    public static final KeyMapping AIR_BRAKE = create("air_brake", GLFW.GLFW_KEY_B);
    public static final KeyMapping TOGGLE_GEAR = create("toggle_gear", GLFW.GLFW_KEY_G);
    public static final KeyMapping TOGGLE_FLAPS = create("toggle_flaps", GLFW.GLFW_KEY_F);
    /** Swings the nozzle of a lift-capable aircraft down, and back up again. Nothing on the rest. */
    public static final KeyMapping TOGGLE_VTOL = create("toggle_vtol", GLFW.GLFW_KEY_R);
    /** Steps through whatever is on the pylons. The trigger itself is the vanilla attack button. */
    public static final KeyMapping CYCLE_WEAPON = create("cycle_weapon", GLFW.GLFW_KEY_X);
    /**
     * The two countermeasure handles. Separate keys rather than one, because which of them is the
     * right one is the question the warning receiver has just answered: a flare for a seeker homing
     * on heat, chaff for one homing on a radar return. Held down, they keep dispensing.
     */
    public static final KeyMapping RELEASE_FLARE = create("release_flare", GLFW.GLFW_KEY_C);
    public static final KeyMapping RELEASE_CHAFF = create("release_chaff", GLFW.GLFW_KEY_V);

    /**
     * Held to stop the mouse flying the aeroplane and let it look around instead.
     *
     * <p>On the middle mouse button, where the hand already is. Flying by pointing costs the pilot
     * the one thing the mouse used to be for — a look over the shoulder — and a fight is mostly
     * spent wanting to know what is behind you. Let go and the view comes back to the mark.
     *
     * <p>It sits on vanilla's pick-block, which is of no use from inside a cockpit and is swallowed
     * while flying by {@link AircraftInputHandler}.
     */
    public static final KeyMapping FREE_LOOK = create("free_look", InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
    /** Hands the aircraft back to the keys entirely, and the mouse back to looking around. */
    public static final KeyMapping TOGGLE_MOUSE_AIM = create("toggle_mouse_aim", GLFW.GLFW_KEY_M);
    /**
     * Held to bring the sight up: the view narrows on whatever the gun is laid on, and the mouse
     * slows to match. One key for every seat at the controls of anything; see {@link AimZoom}.
     *
     * <p>On the right mouse button, where every other game puts it. That is vanilla's use key,
     * which from inside a cockpit or a turret has nothing to use and is swallowed at the controls
     * by {@link AircraftInputHandler} and {@link GroundVehicleInputHandler}, the same as the attack
     * button under it.
     */
    public static final KeyMapping AIM = create("aim", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT);

    /**
     * The driver's controls, which are a different set from the pilot's and are bound separately.
     *
     * <p>They fall on the same four keys by default, because those are the four keys anybody reaches
     * for to make something move, and a player is never flying and driving at once. Sharing the
     * bindings instead would be worse than the duplication: a stick and a pair of tillers do
     * different things — forward on a stick drops the nose, forward on a tiller drives ahead — and
     * anybody who wanted the two on different keys would have had nowhere to say so.
     */
    public static final KeyMapping DRIVE_FORWARD = create("drive_forward", GLFW.GLFW_KEY_W);
    public static final KeyMapping DRIVE_BACK = create("drive_back", GLFW.GLFW_KEY_S);
    public static final KeyMapping STEER_LEFT = create("steer_left", GLFW.GLFW_KEY_A);
    public static final KeyMapping STEER_RIGHT = create("steer_right", GLFW.GLFW_KEY_D);
    /** Holds the vehicle still, and holds it on a slope. */
    public static final KeyMapping VEHICLE_BRAKE = create("vehicle_brake", GLFW.GLFW_KEY_SPACE);

    /**
     * The way out of anything in the mod, cockpit or driver's seat, front seat or back.
     *
     * <p>Not shift, which is what vanilla gets out on and is the throttle in here: a pilot climbing
     * away from the ground with the power on would step out of the aeroplane doing it. One key for
     * every seat rather than one per machine, because a crew member who has to remember which seat
     * they are in before they can get out of it has been given the wrong key.
     * {@link VehicleDismountHandler} is what reads it.
     */
    public static final KeyMapping DISMOUNT = create("dismount", GLFW.GLFW_KEY_LEFT_ALT);

    /**
     * Opens the hold of the machine the crew are aboard. From outside one, the hold is opened by
     * crouching and right-clicking the machine itself.
     *
     * <p>Not on the inventory key, which is where a hand would go for it. That one opens the
     * player's own inventory, is bound to whatever each player has bound it to, and is read by the
     * game before anything here would see it; taking it away from a pilot would be taking away the
     * one screen every player expects to be able to open. {@code I} is the next key along and is
     * free.
     */
    public static final KeyMapping OPEN_HOLD = create("open_hold", GLFW.GLFW_KEY_I);

    /**
     * Moves the crew member to the next seat of the machine they are aboard — the driver's included,
     * so a lone rider can walk the whole thing seat by seat. One key for every seat of everything in
     * the mod, and only while aboard: which seat is next is the server's to settle, see
     * {@link com.ashvehicles.network.SwitchSeatPayload}. {@code K} is clear of vanilla's in-game keys
     * and sits under the hand that is already on the movement keys.
     */
    public static final KeyMapping SWITCH_SEAT = create("switch_seat", GLFW.GLFW_KEY_K);

    public static final KeyMapping[] ALL = {PITCH_UP, PITCH_DOWN, ROLL_LEFT, ROLL_RIGHT,
            THROTTLE_UP, THROTTLE_DOWN, YAW_LEFT, YAW_RIGHT,
            AIR_BRAKE, TOGGLE_GEAR, TOGGLE_FLAPS, TOGGLE_VTOL, CYCLE_WEAPON, RELEASE_FLARE, RELEASE_CHAFF,
            FREE_LOOK, TOGGLE_MOUSE_AIM, AIM,
            DRIVE_FORWARD, DRIVE_BACK, STEER_LEFT, STEER_RIGHT, VEHICLE_BRAKE,
            DISMOUNT, OPEN_HOLD, SWITCH_SEAT};

    private static KeyMapping create(String name, int key) {
        return create(name, InputConstants.Type.KEYSYM, key);
    }

    private static KeyMapping create(String name, InputConstants.Type type, int key) {
        return new KeyMapping("key.ashvehicles." + name, KeyConflictContext.IN_GAME, type, key, CATEGORY);
    }

    private ModKeyMappings() {
    }
}
