package com.ashvehicles.ai.perception;

import javax.annotation.Nullable;

import com.ashvehicles.ai.decision.TacticalAction;
import com.ashvehicles.ai.role.VehicleRole;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 味方1つの観測。人でも AI でも同じ形。
 *
 * <p>AI の味方なら、その役割と今の行動も読める。「撃たれている味方を助けに行く」「迷ったら進めている味方に
 * 付いて行く」「戦車が正面を受け持っている間に側面へ回る」——どれも味方が今何をしているかを知っていて
 * 初めて選べる。人の味方については何をしているかは分からないので、null のまま。
 */
public final class AllyObservation {
    private final Entity entity;
    private double distance;
    private Vec3 direction = Vec3.ZERO;
    private float healthFraction = 1.0F;
    private boolean bot;
    private boolean underFire;

    @Nullable
    private VehicleRole role;

    @Nullable
    private TacticalAction action;

    /** 最近その味方を撃った相手の ID。いなければ -1。 */
    private int attackerId = -1;

    /** その味方が今撃ち合っている相手の ID。いなければ -1。 */
    private int engagingId = -1;

    private long updatedTick;

    AllyObservation(Entity entity) {
        this.entity = entity;
    }

    /**
     * 陣営の頭から支援を割り当てられた味方（{@code team/SupportCalls}）。自分の観測の外にいてもよい。分かっているのは、撃たれて
     * いることと、撃ってきている相手だけ。
     */
    public static AllyObservation called(Entity entity, double distance, float healthFraction, int attackerId,
            long now) {
        AllyObservation observation = new AllyObservation(entity);

        observation.update(distance, Vec3.ZERO, healthFraction, true, true, null, null, attackerId, -1, now);

        return observation;
    }

    public Entity entity() {
        return this.entity;
    }

    public double distance() {
        return this.distance;
    }

    public Vec3 direction() {
        return this.direction;
    }

    public float healthFraction() {
        return this.healthFraction;
    }

    /** AI が動かしている味方か。 */
    public boolean bot() {
        return this.bot;
    }

    /** 最近撃たれた。 */
    public boolean underFire() {
        return this.underFire;
    }

    @Nullable
    public VehicleRole role() {
        return this.role;
    }

    @Nullable
    public TacticalAction action() {
        return this.action;
    }

    public int attackerId() {
        return this.attackerId;
    }

    public int engagingId() {
        return this.engagingId;
    }

    public long updatedTick() {
        return this.updatedTick;
    }

    void update(double distance, Vec3 direction, float healthFraction, boolean bot, boolean underFire,
            @Nullable VehicleRole role, @Nullable TacticalAction action, int attackerId, int engagingId, long now) {
        this.distance = distance;
        this.direction = direction;
        this.healthFraction = healthFraction;
        this.bot = bot;
        this.underFire = underFire;
        this.role = role;
        this.action = action;
        this.attackerId = attackerId;
        this.engagingId = engagingId;
        this.updatedTick = now;
    }
}
