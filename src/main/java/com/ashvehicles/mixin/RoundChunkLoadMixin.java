package com.ashvehicles.mixin;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.entity.DesignationEntity;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.entity.VehicleProjectile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 弾が着く先の chunk を、着く前に生成させない。
 *
 * <p><b>これは1行の話だ。</b> NeoForge は {@code Entity.setPosRaw} の末尾に自前の1行を足している——
 * {@code if (isAddedToLevel() && !level.isClientSide && !isRemoved()) level.getChunk(x >> 4, z >> 4);}
 * ——「移動先の chunk がロードされていることを保証する」ためのものだ。そして {@code Level.getChunk(int,int)}
 * は {@code ChunkStatus.FULL} を {@code requireChunk = true} で要求する。無ければその場で生成し、
 * 完成するまで tick スレッドを止める。
 *
 * <p>普通のエンティティにとっては正しい。歩く物・落ちる物は自分がいる地面を必要とする。だがこの MOD の弾
 * は、誰も開いていない空を通り抜けるために作られている。1tickに50ブロック進む機関砲弾は毎tick違う chunk
 * へ着地し、そのたびに<em>ワールド生成1回</em>を tick スレッドに乗せていた。毎秒100発撃つ砲では、それが
 * 毎tick数十件になる。
 *
 * <p><b>この MOD が chunk について払ってきた注意は、全部この1行に無効化されていた。</b>
 * {@code VehicleProjectile.groundUnder} も {@code boxIsOverTheWorld} も {@code Effects.groundIsThere} も、
 * 「待たずに読めるか」を {@code getChunkNow} で確かめてから世界に触る作りになっている。その全部より後、
 * 弾が実際に動く瞬間に、バニラ側が無条件で生成付きの要求を出していた。演算範囲の外を飛んでいる弾——
 * 当たり判定を一切していない弾——が1発あたり毎tick 10ミリ秒使っていた計測は、これで説明が付く。
 *
 * <p><b>弾についてだけ外す。</b> 他のエンティティは今まで通りこの行を通る。{@link WeaponTicker} が
 * 「tick は運ぶが chunk は1つも要求しない」と決めているのと同じ線で、弾は自分の下の地面を持たない。
 * ロード済みの地面の上では今まで通り当たり、その外ではすり抜けて飛ぶ——それは以前から意図された挙動で、
 * ここはその意図を実際に成立させるだけだ。
 */
@Mixin(Entity.class)
public abstract class RoundChunkLoadMixin {
    /**
     * この MOD の空を飛ぶ物なら chunk を要求せず null を返す。戻り値は捨てられるので null で構わない。
     *
     * <p>{@code @Redirect} を使うのは、消したいのがメソッド全体ではなく最後の1行だから。位置の更新も
     * セクションの移動も今まで通り走る。
     *
     * <p><b>名簿にしてあるのは、条件が2つに分かれるからだ。</b>
     *
     * <p>前の4つ——弾、対抗手段、光点、標的ドローン——は {@code isAlwaysTicking()} が真で、
     * {@code PersistentEntitySectionManager.getEffectiveStatus} がそれを見て常に {@code TICKING} を
     * 返す。つまりこれらは未ロードの chunk へ入っても追跡から外れず、{@code isAddedToLevel} が真のまま
     * なので、下の行の条件を<em>必ず</em>満たす。この4つにとっては、消さない限り毎移動が生成要求になる。
     *
     * <p><b>機体と、機体に乗っている物も入っている（2026-09-03）。</b> 前提が揃ったからだ。
     * {@code AircraftEntity.isAlwaysTicking()} はサーバーでも真になり、下に書いてある「消える機体」は
     * 構造的に起こらなくなった。乗員を含めるのは、乗員の位置更新も同じ1行を通り、パイロットの足元を同期
     * 生成するから。窓（{@code LateWorld}）の中ではどのみち空の chunk が返るが、機体の位置更新は1tick に
     * 何度も来るので、空の chunk を作るまでもなく null で済ませる。
     *
     * <p><b>以下は、機体を入れられなかった頃の記録。</b> 一度入れて、消える機体を作った。理屈はこうだった——飛行中の
     * 機体が入るのはチケットのある chunk なのだから、生成を待たなくてもチケットが追跡を保つ、と。回廊が
     * 追いつく限りではそれで正しい。追いつかない時が問題で、そここそが直したかった場面だった。
     *
     * <p>最高速で未生成の地形へ入る機体は回廊を追い越す。{@link AircraftChunkLoader} は、パイロットが
     * 飛ばしている間は前方1歩の chunk を<em>意図的に取らない</em>——取れば生成が tick スレッドに乗るから
     * だ。するとその chunk にはチケットが無く、ホルダーも無く、{@code Visibility} は {@code HIDDEN}。
     * 機体の {@code isAlwaysTicking()} はサーバーでは偽なので
     * {@code PersistentEntitySectionManager.getEffectiveStatus} は素通しし、{@code onMove}（3550行）が
     * {@code stopTracking} を呼び、{@code onTrackingEnd} が {@code onRemovedFromLevel} を呼ぶ。機体は
     * 世界から外れ、画面から消える。
     *
     * <p>つまりこの行は、副作用として「着地先の chunk は必ず存在する」を保証していた。引っかかりの正体は
     * その保証の代金で、代金だけ止めれば保証も止まる。機体を滑らかに飛ばすには、この行を消すのではなく、
     * 追跡を保つ別の手立て——回廊が本当に追いつくか、機体をサーバーでも常時 tick させるか——を先に用意
     * しなければならない。
     *
     * <p>地上車両も入れていない。回廊を持たず、運転手のすぐ隣を数ブロック/tick で進むだけなので、
     * 未生成の地形へ自分から突っ込むことがない——この行が本当に保険として働く唯一の場所だ。
     */
    @Redirect(method = "setPosRaw",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(II)"
                            + "Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk ashvehicles$leaveTheSkyAlone(Level level, int chunkX, int chunkZ) {
        Object self = this;

        if (self instanceof VehicleProjectile || self instanceof CountermeasureEntity
                || self instanceof DesignationEntity || self instanceof TargetDroneEntity
                || ((Entity) self).getRootVehicle() instanceof AircraftEntity) {
            return null;
        }

        return level.getChunk(chunkX, chunkZ);
    }
}
