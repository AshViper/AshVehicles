package com.ashvehicles.client;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.GroundVehicleEntity.Armament;
import com.ashvehicles.entity.GroundVehicleEntity.Crank;
import com.ashvehicles.entity.VehiclePart;
import com.ashvehicles.item.AmmunitionItem;
import com.ashvehicles.item.WrenchItem;
import com.ashvehicles.network.GunCrewPayload;
import com.ashvehicles.weapon.Magazine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 乗らずに、脇に立って撃つ砲の操作。マウスの3ボタンだけで足りる。
 *
 * <p><b>牽引砲には操縦席が無い。</b> D-30 も ZU-23 も、乗り込む物ではなく人が取り付く物だ。だから
 * {@code hull.crewed} を立てた車両は乗車を断り、代わりに車外の1人がここから操作する。運転も、乗員の計器も、
 * 座席の切り替えも無い——あるのは砲を向けること、弾を込めること、撃つことの3つだけ。
 *
 * <p><b>ハンドルは2つあり、どちらを掴むかは十字線が決める。</b> 旋回ハンドルと俯仰ハンドルは砲の別々の
 * 場所にあり（{@code hull.handles}）、砲を指している十字線に近い方が掴まれる。輪を正確に狙う必要は無い
 * ——効いているのは「砲のどちら側を見ているか」であり、2つの輪は {@link GunCrewHud} がワールド上に描く。
 *
 * <ul>
 *   <li><b>右クリック長押し</b>——掴んだハンドルを正方向へ。旋回なら右、俯仰なら上げ</li>
 *   <li><b>シフト+右クリック長押し</b>——同じハンドルを逆方向へ。旋回なら左、俯仰なら下げ</li>
 *   <li><b>左クリック</b>——発射。砲を殴りはしない</li>
 * </ul>
 *
 * <p><b>向きはシフトが決め、軸は十字線が決める。</b> だから掴んだまま親指1本で往復でき、据え終わりの
 * 微調整——行き過ぎたぶんを少し戻す——が持ち替え無しにできる。
 *
 * <p>装填はこれらの手前にある。弾を手に持って右クリックすれば、それは装填であってハンドルではない
 * ——{@code GroundVehicleEntity.loadRound} が受ける方の道だ。{@link #loading} がその判断を先に済ませる。
 *
 * <p><b>掴んだ砲は、目を離しても離れない。</b> 掴むのは押した瞬間の1回だけで、以後は押している限り同じ
 * ハンドルが回り続ける。手を離すか、砲から離れるか、砲が壊れれば終わる。
 *
 * <p><b>バニラの2ボタンを止める。</b> ハンドルを回している間の右クリックはブロック設置でも食事でもなく、
 * 砲へ向けた左クリックは殴打でも採掘でもない。乗員の操作で同じことをしている
 * {@link GroundVehicleInputHandler} と同じ理由で、同じ場所を塞ぐ。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class GunCrewHandler {
    /** 今ハンドルを掴んでいる砲。押している間だけ。 */
    @Nullable
    private static GroundVehicleEntity cranking;

    /** 掴んだ時に選ばれた方のハンドル。掴んでいる間は変わらない。 */
    private static Crank held = Crank.NONE;

    /** 十字線が今指している人力砲、あるいは掴んでいる砲。計器はこれを描く。 */
    @Nullable
    private static GroundVehicleEntity manned;

    /** 掴んでいなければ、今の十字線で掴まれる方のハンドル。計器はこれを明るくする。 */
    private static Crank offered = Crank.NONE;

    /** 前tickの右クリックの状態。掴むのは押し下げた瞬間だけなので、その境目が要る。 */
    private static boolean useWasDown;

    /** 前tickに何かを要求していたか。やめた最初の1tickだけ「手を離した」を送るために要る。 */
    private static boolean wasAsking;

    /**
     * このクライアントの操作者が今就いている砲。就いていなければ null。
     *
     * <p>{@link GunCrewHud} が読む。就いているとは「十字線が砲に乗っている」か「ハンドルを掴んでいる」の
     * どちらかで、どちらであっても操作者が読みたい物——砲の向きと薬室の中身——は同じだ。
     */
    @Nullable
    public static GroundVehicleEntity manned() {
        return manned;
    }

    /** 今の十字線が指している（あるいは既に掴んでいる）ハンドル。無ければ {@link Crank#NONE}。 */
    public static Crank handle() {
        return cranking != null ? held : offered;
    }

    /** そのハンドルを今実際に回しているか。計器が「掴んでいる」と「指しているだけ」を描き分ける。 */
    public static boolean isCranking() {
        return cranking != null;
    }

    /** 回している向き。右／上げが +1、左／下げが -1。回していなければ 0。 */
    public static int direction() {
        if (cranking == null) {
            return 0;
        }

        return Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.isShiftKeyDown() ? -1 : 1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        // 画面が開いている間は砲から手が離れている。キーは画面が開いた時点で全部離された扱いになるので
        // ハンドルは勝手に止まるが、十字線は開く直前の物が残るため、計器だけがその場に居座ってしまう。
        if (player == null || minecraft.isPaused() || minecraft.screen != null
                || player.getVehicle() != null) {
            forget();

            return;
        }

        GroundVehicleEntity aimed = onCrosshair(minecraft, player);
        boolean useDown = minecraft.options.keyUse.isDown();
        boolean pressed = useDown && !useWasDown;

        useWasDown = useDown;
        offered = aimed == null ? Crank.NONE : nearerHandle(player, aimed);

        if (!useDown) {
            cranking = null;
            held = Crank.NONE;
        } else if (cranking == null && pressed && aimed != null && !loading(player, aimed)) {
            // 掴むのは押し下げた瞬間だけ。押しっ放しのまま装填が終わった手が、そのままハンドルへ滑り込む
            // ことは無い——込めた者は指を離してから改めて掴む。
            cranking = aimed;
            held = offered;
        }

        if (cranking != null && !operable(player, cranking)) {
            cranking = null;
            held = Crank.NONE;
        }

        manned = cranking != null ? cranking : aimed;

        if (manned == null) {
            return;
        }

        // 引き金は掴んでいる間も効く。片手でハンドルを回しながらもう片方で紐を引くのは、実際にその砲を
        // 撃つ手順そのものだ。掴んでいなければ、砲を見ている間だけ。
        boolean trigger = minecraft.options.keyAttack.isDown();
        int direction = direction();
        boolean asking = direction != 0 || trigger;

        // 何も要求していない間は黙る。砲を眺めているだけで毎tick送るのは、何も言っていないことを毎tick
        // 言うのと同じだ。ただし要求をやめた最初の1tickだけは送る——それが「手を離した」の合図であり、
        // サーバーはそれで掴んだ状態を解く。届かなくなった場合の後始末はサーバー側の時間切れが行う。
        if (asking || wasAsking) {
            PacketDistributor.sendToServer(new GunCrewPayload(manned.getId(),
                    handle().ordinal(), direction, trigger));
        }

        wasAsking = asking;
    }

    /**
     * ハンドルを回している間の右クリックと、砲へ向けた左クリックからバニラの意味を取り上げる。
     *
     * <p>右を塞ぐのは掴んでいる間だけ。掴む前の右クリックは装填であり、それはバニラの経路を通って
     * サーバーの {@code interact} へ届く必要がある。
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isUseItem() && cranking != null) {
            event.setSwingHand(false);
            event.setCanceled(true);

            return;
        }

        if (event.isAttack() && manned != null) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * ハンドルを回している間は採掘しない。
     *
     * <p>押し続ける採掘はキーの割り当てを通らず、{@code Minecraft.continueAttack} が毎tick直接行う。
     * だから {@link #onInteractionKey} では止まらず、砲を据えながら遠くの地面を掘ることになっていた。
     */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (manned != null && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }

    /**
     * 十字線に近い方のハンドル。
     *
     * <p>距離は視線からの外れ具合で測り、上限を置かない。十字線が既に砲に乗っている以上、問いは
     * 「どちらのハンドルか」であって「ハンドルを狙えているか」ではないからだ。小さな輪に十字線を
     * 合わせさせると、操作は精密になるのではなく単に難しくなる。
     */
    private static Crank nearerHandle(LocalPlayer player, GroundVehicleEntity gun) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);

        return offAxis(eye, look, gun.getHandle(Crank.ELEVATE, 1.0F))
                < offAxis(eye, look, gun.getHandle(Crank.TRAVERSE, 1.0F))
                ? Crank.ELEVATE
                : Crank.TRAVERSE;
    }

    /** その点が視線からどれだけ外れているか（距離の2乗）。真後ろの点は目の位置から測られる。 */
    private static double offAxis(Vec3 eye, Vec3 look, Vec3 point) {
        Vec3 offset = point.subtract(eye);

        return offset.subtract(look.scale(Math.max(offset.dot(look), 0.0))).lengthSqr();
    }

    /** 十字線の先にある人力操作の砲。無ければ null。 */
    @Nullable
    private static GroundVehicleEntity onCrosshair(Minecraft minecraft, LocalPlayer player) {
        if (!(minecraft.hitResult instanceof EntityHitResult hit)) {
            return null;
        }

        Entity struck = hit.getEntity();

        // 砲の当たり判定は箱が持っており、素の直方体は的ではない。どの箱を指していても指しているのは砲だ。
        if (struck instanceof VehiclePart part) {
            struck = part.getParent();
        }

        return struck instanceof GroundVehicleEntity gun && operable(player, gun) ? gun : null;
    }

    /** その砲を今この者が操作できるか。人力操作の砲であり、壊れておらず、手が届く。 */
    private static boolean operable(LocalPlayer player, GroundVehicleEntity gun) {
        return gun.isAlive() && gun.isCrewed() && !gun.isWrecked() && gun.isWithinCrewReach(player);
    }

    /**
     * この右クリックが装填（あるいは分解）になるか。なるならハンドルは掴まない。
     *
     * <p>両手を見る。バニラが利き手から順に試すのと同じで、どちらかに入る弾があるならその1回は装填だ。
     *
     * <p>薬室が埋まっていれば入らない——人力装填の砲が持てるのは1回の装填分だけなので、そこが「弾を持った
     * まま照準できる」の分かれ目になる。込めた後は手に弾を持ったままハンドルを掴める。
     */
    private static boolean loading(LocalPlayer player, GroundVehicleEntity gun) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);

            if (held.getItem() instanceof WrenchItem) {
                return true;
            }

            if (held.getItem() instanceof AmmunitionItem round
                    && Magazine.types(gun, Armament.MAIN).contains(round.getAmmunitionId())
                    && Magazine.total(gun, Armament.MAIN) < gun.getRoundCapacity()) {
                return true;
            }
        }

        return false;
    }

    private static void forget() {
        cranking = null;
        manned = null;
        held = Crank.NONE;
        offered = Crank.NONE;
        useWasDown = false;
        wasAsking = false;
    }

    private GunCrewHandler() {
    }
}
