package com.ashvehicles.registry;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.particle.TintedParticleType;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * MOD が自前で描くもの一式。バニラ製は一つも無い。必要なのは、遠くからでも見える煙で、兵装ファイルが
 * 指定した色で描かれ、明るさを読む chunk が無い場所でも正しく光るもの——バニラのパーティクルはこの3つ
 * のどれもやらない。
 *
 * <p>中身は全部同じ型（{@link TintedParticleType}）。違いを作るのは
 * {@code assets/ashvehicles/particles/} の各ファイルが指すテクスチャと、{@code AshVehiclesClient}
 * でクライアントが結び付けるクラス。
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, AshVehicles.MODID);

    /** モーター燃焼中のノズル後方の噴煙。濃く、熱く、すぐ消える。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> MOTOR_SMOKE = register("motor_smoke");
    /** その後に空中へ残る分。パイロットが実際に目で追う航跡はこちら。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> CONTRAIL = register("contrail");
    /** 爆発の火球。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> BLAST = register("blast");
    /** そして巻き上がってできる煙。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> BLAST_SMOKE = register("blast_smoke");
    /** 大型爆発の衝撃波面。地表を走っていく。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> SHOCKWAVE = register("shockwave");
    /**
     * 燃え続ける炎。今爆発した物ではなく燃えている物のためのもので、墜落地点で燃える機体の残骸など。
     * 火球と同じテクスチャだが別物。{@link com.ashvehicles.client.particle.FlameParticle} 参照。
     */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> FIRE = register("fire");
    /** 被弾で飛び散る破片。何を背景にしても見えるだけの明るさを持つ。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> SPARK = register("spark");
    /** 着弾した物から削れた欠片。元のブロックの色で描かれる。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> DEBRIS = register("debris");
    /** 高荷重の翼から出るベイパー。ここで唯一、兵装のせいではないもの。 */
    public static final DeferredHolder<ParticleType<?>, TintedParticleType> VAPOUR = register("vapour");

    private static DeferredHolder<ParticleType<?>, TintedParticleType> register(String name) {
        return PARTICLE_TYPES.register(name, TintedParticleType::new);
    }

    private ModParticles() {
    }
}
