package com.ashvehicles.mixin;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.LateWorld;
import com.ashvehicles.entity.VehicleEntityBase;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 縁石に乗り上げるたびにサーバーが運転中の車両を引き戻すのを止め、機体の報告を頭から拒否するのも止める。
 *
 * <p>クライアントが運転する車両は現在位置を報告し、サーバーはそれを受け入れる前に検査する。検査の半分は
 * 「クライアント側の移動が可能だったか」で、そこは {@code VehicleEntityBase.move} が報告をそのまま受け
 * 入れることで既に答えている。運転側は実際に見えている地面に対して判定を通しており、1tick 遅れて古い位置
 * の地形を探るサーバーにできるのは移動を削ることだけだから。
 *
 * <p>もう半分がこれで、見落とされていた。バニラは車両の素の直方体が空中に空いているかを2回——報告された
 * 移動の前と後で——問い、前は空いていて後は空いていなければ車両を元の位置へ戻す。戦車にとってその素の
 * 直方体は履帯の上に立つ幅4×高さ3のプレハブ小屋で、車両が実際に衝突する形ではない。衝突するのは自分の
 * ファイルに書かれた箱の方で、{@code GroundVehicleEntity} は運転手が問うであろう質問をその箱の四隅で
 * 問う。結果、小屋は車両が余裕で登れる段差の1ブロック目に引っ掛かり、しかも2ブロック手前から引っ掛かる。
 * 縁石の一歩手前で何も無い空気に対して急停止し、エンジンを吹かしたまま止まる、という挙動になる。
 *
 * <p>差し替えているのは問いであって答えではない。バニラの補正は「空いていた場所に立っていた車両が何かへ
 * 突っ込んだ」場合のためのもの。素の直方体が移動可能範囲と無関係な車両は、この検査の言う意味で最初から
 * 「空いた場所に立って」いない。そう答えることが補正を降ろす方法になる。自前の箱を持たない車両には触れ
 * ない。あちらは素の直方体が本当に形なので、検査は正しい。
 *
 * <p>これで新たに緩む物は何も無い。手前の距離検査は生きている——クライアントは飛行速度で運べる以上の距離
 * を報告できない。そのための {@link #ashvehicles$speedTheServerCanSee} である——し、その範囲内でどこまで
 * 動けるかは {@code limitToShape} が決める。あちらはこれより良い判定で、しかも地面が見えている側で走る。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class VehicleMoveCheckMixin {
    @Shadow
    public ServerPlayer player;

    /** 報告の処理に入る前に窓が開いていたか。閉じる時にその状態へ戻す。サーバースレッド専用。 */
    @Unique
    private boolean ashvehicles$skyWasOpen;

    /**
     * 操縦報告を適用している間、{@link LateWorld} の窓を開ける。
     *
     * <p>報告の適用は {@code absMoveTo} で機体を動かし、その位置更新が移動先の chunk をロードしようとする。
     * 機体の tick の外なので {@link LateWorldTickMixin} の窓は閉じており、ここで開けなければ報告1本ごとに
     * 同期生成が走る。詰まった直後には数十本が同じ tick に着くので、ここが一番効く。
     *
     * <p>このメソッドは netty スレッドからも呼ばれ、その時は先頭の {@code ensureRunningOnSameThread} が
     * 投げて終わる——出口を通らない。だから旗に触るのはサーバースレッドの時だけ。入れ子も起こり得る。
     * 別のエンティティの tick が chunk を待っている間にサーバーは溜まった仕事を処理し、その中にこの報告
     * が入っている。そこで出口は「閉じる」ではなく「入る前の状態へ戻す」。
     */
    @Inject(method = "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
            at = @At("HEAD"))
    private void ashvehicles$skyOpens(ServerboundMoveVehiclePacket packet, CallbackInfo callback) {
        if (this.player.serverLevel().getServer().isSameThread()
                && this.player.getRootVehicle() instanceof AircraftEntity) {
            this.ashvehicles$skyWasOpen = LateWorld.enter();
        }
    }

    @Inject(method = "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
            at = @At("RETURN"))
    private void ashvehicles$skyCloses(ServerboundMoveVehiclePacket packet, CallbackInfo callback) {
        if (this.player.serverLevel().getServer().isSameThread()
                && this.player.getRootVehicle() instanceof AircraftEntity) {
            LateWorld.restore(this.ashvehicles$skyWasOpen);
        }
    }

    /**
     * 1回の報告が覆ってよい、機体自身の移動 tick 数。
     *
     * <p>クライアントの1tick がサーバーの1tick なら1が正直な値だが、そうではない。パケットは固まって届く
     * ——どちらかで一瞬詰まれば、同じ起点から測った報告が2つ3つとサーバーの1tick の間に着く——ので、余裕
     * ゼロの検査は不正ではなく「しゃっくり」で発火する。4 はおよそ0.2秒分で、それでも報告を「機体が実際に
     * 飛べた範囲」に縛る。
     */
    private static final double REPORTS_MAY_COVER_TICKS = 4.0;

    /**
     * サーバーが報告された移動を照らし合わせる速度。
     *
     * <p>バニラは「クライアントの言う位置へ本当に到達できたか」を問い、その距離を車両自身の
     * deltaMovement と比べて答える。進んでいた分より10ブロック以上多ければ拒否。拒否すると車両を元の
     * 位置へ戻し、クライアントへ補正を送り、クライアントはそれを無条件に適用する。
     *
     * <p>この比較は、この MOD の機体に対しては両辺とも間違っている。クライアントが飛ばしている機体の
     * deltaMovement をサーバーが意図的かつ恒久的にゼロにしているのは、飛行モデルがパイロット側で走り位置
     * はパケットで届くから。実の値を入れればそれがパイロットへ送り返され、当人の飛行モデルが今出した答え
     * と喧嘩する（{@code AircraftEntity.tick} 参照）。そして距離の方は機体の1tick 分で、このパックの速度
     * なら上限10に対して17ブロックになる。
     *
     * <p>結果、高速機からの報告は毎回拒否され、拒否のたびに最後に受理された位置へ引き戻され、機体は飛ぶ
     * 代わりに空中で震えた。シングルプレイでは一切現れない。バニラは内蔵サーバーのホストに対してこの検査
     * を飛ばすからだ——マルチ専用の不具合であり、サーバーで機体が途中から固まる原因はこれが全て。
     *
     * <p>ここで検査を止めているのではなく、正しい値を教えている。サーバーは機体の速度を知っている。飛ばし
     * ている側が毎tick 報告し、到着時にクランプされ、{@code VehicleEntityBase.getVelocity} から出てくる。
     * その数tick 分が1回の報告が正当に覆える範囲で、それを超える分は今も拒否される。
     */
    @Redirect(method = "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 ashvehicles$speedTheServerCanSee(Entity entity) {
        if (entity instanceof VehicleEntityBase machine) {
            return machine.getVelocity().scale(REPORTS_MAY_COVER_TICKS);
        }

        return entity.getDeltaMovement();
    }

    @Redirect(method = "handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;noCollision"
                            + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
    private boolean ashvehicles$ignorePlainBox(ServerLevel level, Entity entity, AABB box) {
        if (entity instanceof VehicleEntityBase machine && machine.getParts().length > 0) {
            return false;
        }

        return level.noCollision(entity, box);
    }
}
