package com.ashvehicles.mixin;

import com.ashvehicles.entity.CorridorClaim;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.world.chunk.ForcedChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 回廊がチケットを立てる時に、地形の完成を待たせない。
 *
 * <p>消すのは {@code ForcedChunkManager.forceChunk} の中の1行だけ——{@code tickets.add} が成功した直後の
 * {@code level.getChunk(chunkX, chunkZ)} で、戻り値はその場で {@code pop} されている。買っているのは同期性
 * だけであり、その同期性が「未生成の土地へ入った機体がサーバーごと固まる」の正体だった。理屈は
 * {@link CorridorClaim} に書いてある。
 *
 * <p>この MOD の回廊が立てているチケットの間だけ黙らせる。他の MOD のチャンクローダーは今まで通り
 * 地面が在ることを保証されたまま返ってくる。
 */
@Mixin(ForcedChunkManager.class)
public abstract class CorridorTicketMixin {
    /**
     * 待たない。チケットは既に立っているので、地形はこの後で生成器のスレッドが作って届ける。
     *
     * <p>戻す null は誰にも読まれない。元の呼び出しの戻り値も読まれていない（バイトコードで
     * {@code invokevirtual} の次が {@code pop}）。
     */
    @Redirect(method = "forceChunk(Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Comparable;IIZZ"
                    + "Lnet/minecraft/server/level/TicketType;Ljava/util/function/Function;)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getChunk(II)"
                            + "Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private static LevelChunk ashvehicles$dontWaitForTheGround(ServerLevel level, int chunkX, int chunkZ) {
        return CorridorClaim.inside() ? null : level.getChunk(chunkX, chunkZ);
    }
}
