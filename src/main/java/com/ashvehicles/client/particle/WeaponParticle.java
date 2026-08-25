package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;

/**
 * What every particle the mod draws has in common: its colour comes from the option that made it,
 * it can be seen out beyond the loaded world, and it leaves no depth behind it.
 *
 * <p>The lighting is the reason these exist. Vanilla asks the world what a particle is lit
 * by, and when the chunk under it is not loaded the world's answer is zero — so a particle out there
 * is not merely dim, it is drawn in flat black. An explosion beyond the edge of the loaded world
 * looks, to anyone watching, like a smear of soot appearing in mid-air. Anything with no chunk under
 * it is therefore lit as what it actually is instead: open sky, or its own fire.
 *
 * <p>The depth is the reason they still needed changing. See {@link #NO_DEPTH_WRITE}.
 */
public abstract class WeaponParticle extends TextureSheetParticle {
    /** Full sky light and no block light, which is what smoke at altitude is lit by. */
    private static final int OPEN_AIR = LightTexture.pack(0, 15);

    /**
     * Vanilla's translucent particle sheet with the one thing about it that this mod cannot live
     * with taken off: it does not write depth.
     *
     * <p>{@link ParticleRenderType#PARTICLE_SHEET_TRANSLUCENT} begins with
     * {@code RenderSystem.depthMask(true)}. A puff of smoke is therefore blended over what is behind
     * it <em>and</em> stamps its own quad into the depth buffer, so anything drawn afterwards at a
     * greater depth is thrown away — not dimmed by the smoke, not blended with it, simply not drawn.
     * Vanilla never notices, because vanilla draws nothing worth seeing after its particles.
     *
     * <p>This mod does. The ghost pass runs at
     * {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES}, and it has to: that is the first stage
     * past Distant Horizons' vanilla fade, which would otherwise paint over anything drawn earlier
     * (see {@link com.ashvehicles.client.ghost.GhostRenderDispatcher}). So every machine the ghost
     * pass draws was being depth-rejected by the mod's own particles — and the mod's particles are
     * exactly the ones that follow a machine about. A missile past
     * {@code ghostStartDistance} lays contrail and exhaust at its own tail every tick and was then
     * hidden behind them; an aeroplane with the burner lit was hidden behind its own plume,
     * completely so from any angle looking up the pipe. What anybody watching saw was a trail of
     * smoke with nothing making it.
     *
     * <p>Dropping the depth write costs nothing that was worth having. These particles still depth
     * <em>test</em>, so the ground and everything the game drew still hides them; they simply leave
     * no depth of their own for the ghost pass to fail against. Among themselves they now blend
     * rather than clip, which is what smoke should have been doing in the first place.
     * {@code ParticleEngine.render} restores {@code depthMask(true)} when it is done, so nothing
     * drawn later inherits this.
     */
    private static final ParticleRenderType NO_DEPTH_WRITE = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "ashvehicles:particle_sheet_translucent_no_depth";
        }
    };

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
        return NO_DEPTH_WRITE;
    }
}
