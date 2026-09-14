package com.ashvehicles.ai.learning;

import com.ashvehicles.ai.battlefield.TacticalMap;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * AI 1両が走った跡から、地図の経験（{@link MapMemory}）を拾う係。{@code navigation/Navigator} が持つ。
 *
 * <p>足すのはマスを跨いだ瞬間と、詰まった・戻り始めた・沈んだ瞬間だけで、毎 tick するのはマスの番号の比較1回。
 *
 * <p><b>「すんなり抜けた」は厳しめに数える。</b> 入ってから {@value #PASS_TICKS} tick 以内に出て、その間に1度も
 * 詰まらず、後退も戻りもしていないこと。拠点の円の中で撃ち合いながら居座った車両は、そのマスを抜けたことに
 * ならない——入った回数にだけ数える。
 */
public final class MapTrace {
    /** これより早くマスを出たら、すんなり抜けたと数える（tick）。4ブロックのマスを重い戦車が這っても抜けられる長さ。 */
    private static final int PASS_TICKS = 60;

    /** 抜け出すために戻り始めたときの詰まりの重さ。1回の詰まりより、ずっと確かな「ここは駄目だ」。 */
    private static final float TRAPPED = 3.0F;

    private final Entity vehicle;

    private boolean placed;
    private int cellX;
    private int cellZ;
    private int enteredAt;

    /** 今のマスで詰まったか、後退か戻りをしたか。 */
    private boolean troubled;

    /** 今のマスで沈んだことを、もう数えたか。 */
    private boolean wetHere;

    public MapTrace(Entity vehicle) {
        this.vehicle = vehicle;
    }

    /**
     * 毎 tick。
     *
     * @param age        その車両の AI が動いた tick
     * @param submerged  車体が沈む水の中にいる
     * @param retreating 後退しているか、抜け出すために戻っている
     */
    public void tick(Vec3 here, int age, boolean submerged, boolean retreating) {
        int x = TacticalMap.cellOf(here.x);
        int z = TacticalMap.cellOf(here.z);

        if (retreating) {
            this.troubled = true;
        }

        if (!this.placed || x != this.cellX || z != this.cellZ) {
            MapMemory memory = MapMemory.of(this.vehicle.level());

            if (memory != null) {
                if (this.placed && !this.troubled && age - this.enteredAt <= PASS_TICKS) {
                    memory.add(this.cellX, this.cellZ, MapKnowledge.PASSES, 1.0F);
                }

                memory.add(x, z, MapKnowledge.VISITS, 1.0F);
            }

            this.placed = true;
            this.cellX = x;
            this.cellZ = z;
            this.enteredAt = age;
            this.troubled = retreating;
            this.wetHere = false;
        }

        if (submerged && !this.wetHere) {
            MapMemory memory = MapMemory.of(this.vehicle.level());

            this.wetHere = true;
            this.troubled = true;

            if (memory != null) {
                memory.add(x, z, MapKnowledge.WET, 1.0F);
            }
        }
    }

    /** 詰まった。 */
    public void stalled() {
        this.mark(1.0F);
    }

    /** 何度も詰まって、抜け出すために戻り始めた。 */
    public void trapped() {
        this.mark(TRAPPED);
    }

    private void mark(float weight) {
        if (!this.placed) {
            return;
        }

        MapMemory memory = MapMemory.of(this.vehicle.level());

        this.troubled = true;

        if (memory != null) {
            memory.add(this.cellX, this.cellZ, MapKnowledge.STALLS, weight);
        }
    }
}
