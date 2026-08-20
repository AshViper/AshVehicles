package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;

/**
 * What every particle the mod draws has in common: its colour comes from the option that made it,
 * and it can be seen out beyond the loaded world.
 *
 * <p>That second part is the whole reason these exist. Vanilla asks the world what a particle is lit
 * by, and when the chunk under it is not loaded the world's answer is zero — so a particle out there
 * is not merely dim, it is drawn in flat black. An explosion beyond the edge of the loaded world
 * looks, to anyone watching, like a smear of soot appearing in mid-air. Anything with no chunk under
 * it is therefore lit as what it actually is instead: open sky, or its own fire.
 */
public abstract class WeaponParticle extends TextureSheetParticle {
    /** Full sky light and no block light, which is what smoke at altitude is lit by. */
    private static final int OPEN_AIR = LightTexture.pack(0, 15);

    protected WeaponParticle(ClientLevel level, double x, double y, double z, TintedParticleOption options) {
        super(level, x, y, z);
        this.rCol = options.red();
        this.gCol = options.green();
        this.bCol = options.blue();
    }

    @Override
    protected int getLightColor(float partialTick) {
        return this.level.hasChunkAt(BlockPos.containing(this.x, this.y, this.z))
                ? super.getLightColor(partialTick)
                : this.lightBeyondTheWorld();
    }

    /** How this is lit when there is no world under it to ask. */
    protected int lightBeyondTheWorld() {
        return OPEN_AIR;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }
}
