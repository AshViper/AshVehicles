package com.ashvehicles.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.vehicle.Hitbox;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * この MOD の機体がどこにいるか、そして機体を構成する箱に対して世界が問うこと全部。
 *
 * <p>ゲームには問わないし教えもしない。ゲームの衝突は直立した箱で動き、それ以外を渡せない。だから箱は
 * ここで、レベルごとの機体一覧として保持し、重要な2つの問いに直接答える。動いている物が機体にぶつかるまで
 * どこまで進めるか（{@link #limit}）と、機体を貫いて狙った線が何に当たるか（{@link #pick}）。問い合わせて
 * くる mixin 群が、これと Minecraft をつなぐ接合部の全部。
 *
 * <p><b>レベルに訊かず機体一覧を持つ理由。</b> どちらの問いも、動く物すべてに毎tick、狙われる物すべてに
 * 毎フレーム投げられる。毎回レベルへ近傍エンティティを訊けば、ゲームが自前の衝突について既にやっている同じ
 * 仕事をもう一度やることになる。1つのレベルに機体が多数いることは無く、手元に持っているので、通常の答え
 * ——「近くには1機もいない」——は3要素のリストを歩くコストで済む。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class Hitboxes {
    /**
     * 機体への段差乗り上げをどこまで試す価値があるか。バニラと同じく、何かの上に立っていなければ一切試さ
     * ない。空中で船体をよじ登る者が出ないように。
     */
    private static final double NOTHING = 1.0E-7;

    /**
     * 足元が機体の箱にどれだけ近ければ「その上に立っている」と見なすか（ブロック）。
     *
     * <p>0.1。1tick分の落下より少し大きい。これより小さいと、完全に静止している者が丸め誤差の向き次第で
     * 毎回艦から落とされる。ずっと大きくすると、浮いている甲板に運ばれることになる。
     */
    private static final double CONTACT = 0.1;

    /**
     * こすって越える地面が無い状態。全ブロックが形状を止める。それが「空中にいる」ということ。
     * {@link #throughBlocks(Entity, Hitbox, Vec3, double)} 参照。
     */
    public static final double UNDERSIDE_NONE = Double.NEGATIVE_INFINITY;

    /**
     * レベルごとの機体一覧。
     *
     * <p>レベルを弱参照キーにしてあるので、消えたレベルは自分のリストを連れて消える。各リストはそのレベルが
     * tick されるスレッドの持ち物——クライアントとサーバーは別レベル・別リスト——で、共有されているのはそれ
     * らを吊るすマップだけ。そこだけを同期する。
     */
    private static final Map<Level, Set<VehicleEntityBase>> MACHINES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * これより遅い機体は誰も轢かない（ブロック/tick）。
     *
     * <p>0.15 は時速11km、人が歩くよりやや速い程度。駐機した機体に歩いてぶつかった者や、微速で寄せている
     * 車両の脇に立っている者が傷つかないための下限であり、丸め誤差で機体が「動いた」と報告する分もここで
     * 落ちる。
     */
    private static final double RUN_OVER_FLOOR = 0.15;

    /**
     * 轢いた時の打撃の、速度1ブロック/tickあたりの量。
     *
     * <p>20。装甲車が巡航速度（0.5、時速36km）で7、対空車両の最高速（1.1、時速79km）で19——無防備な人が
     * 一撃で倒れる量だ。機体はこれより桁が違い、最高速では轢かれた側に議論の余地が無くなる。それが正しい。
     */
    private static final double RUN_OVER_RATE = 20.0;

    /**
     * 機体の中にいると見なすために、被害者の足元から上げる高さ（ブロック）。
     *
     * <p><b>足元だけを見てはいけない。</b> 上に乗って運ばれている者を判定する {@link #resting} は足元の薄い
     * 層を見るが、轢かれた者も同じ判定を通ってしまう——車体の箱は地面まで下りているので、真正面に立って
     * いた者の足元は当然その箱と重なる。運ばれる者と轢かれる者を分けるのは足の位置ではなく<em>体</em>の
     * 位置だ。甲板の上に立つ者の体は箱の外（上）にあり、轢かれた者の体は箱の中にある。
     */
    private static final double RUN_OVER_BODY = CONTACT * 2.0;

    private Hitboxes() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof VehicleEntityBase machine) {
            in(event.getLevel()).add(machine);
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof VehicleEntityBase machine) {
            in(event.getLevel()).remove(machine);
        }
    }

    private static Set<VehicleEntityBase> in(Level level) {
        return MACHINES.computeIfAbsent(level, ignored -> Collections.newSetFromMap(new WeakHashMap<>()));
    }

    // ------------------------------------------------------------------
    // ぶつかられる側
    // ------------------------------------------------------------------

    /**
     * 世界自身の衝突が既に許可した移動のうち、この MOD の機体が口を出した後に残る分。
     *
     * <p>渡されるのは要求された移動ではなく Minecraft が決着させた移動。両者は別々の障害物であり、どちらも
     * 相手が奪った移動を返してはいけないから。ここから出てくる物が、傾いた甲板の上のプレイヤーを支え、船体
     * への進入を止める。
     *
     * @param mover 動いている物。自分の機体はその物にとっても、それに乗っている物にとっても障害ではない。
     *              さもないと搭乗者が座っている席から押し出される
     * @param box 現在位置
     * @param wanted 世界が既に制限した後の移動
     */
    public static Vec3 limit(Entity mover, AABB box, Vec3 wanted) {
        if (wanted.lengthSqr() == 0.0) {
            return wanted;
        }

        // 機体を止めるのは、登録上の素の直方体ではなく本当に持っている形状だ——甲板に降りる機体はそこへ
        // 車輪で触れるのであって、Minecraft の思う小屋で触れるのではない。それ以外の物は自分の箱そのもの
        // で、プレイヤーにとってはそれが真値。
        List<Hitbox> mine = own(mover);
        AABB area = (mine.isEmpty() ? box : union(mine)).expandTowards(wanted);
        List<Hitbox> theirs = near(mover, area);

        if (theirs.isEmpty()) {
            return wanted;
        }

        if (!mine.isEmpty()) {
            return resolve(theirs, mine, wanted);
        }

        Vec3 allowed = resolve(theirs, box, wanted);

        if (allowed.x == wanted.x && allowed.z == wanted.z) {
            return allowed;
        }

        return step(mover, theirs, box, wanted, allowed);
    }

    /** 動いている物自身を構成する箱。この MOD の機体でなければ空。 */
    private static List<Hitbox> own(Entity mover) {
        if (!(mover instanceof VehicleEntityBase machine)) {
            return List.of();
        }

        List<Hitbox> found = null;

        for (VehiclePart part : machine.getParts()) {
            Hitbox box = part.hitbox();

            if (box == null || part.isPylon()) {
                continue;
            }

            if (found == null) {
                found = new ArrayList<>();
            }

            found.add(box);
        }

        return found == null ? List.of() : found;
    }

    private static AABB union(List<Hitbox> boxes) {
        AABB all = boxes.get(0).reach();

        for (int i = 1; i < boxes.size(); i++) {
            all = all.minmax(boxes.get(i).reach());
        }

        return all;
    }

    /**
     * 判定する価値があるだけ近い全機体の箱。
     *
     * <p>各機体の箱は最後に置かれた場所——その機体自身の tick が残した場所——にある。ゲームが自前の多パーツ
     * エンティティに対してやっていることと同じで、プレイヤーが見ている描画とも同じなので、射撃と足音は同じ
     * 箱に着く。
     */
    private static List<Hitbox> near(Entity mover, AABB area) {
        Set<VehicleEntityBase> machines = MACHINES.get(mover.level());

        if (machines == null || machines.isEmpty()) {
            return List.of();
        }

        Entity riding = mover.getRootVehicle();
        List<Hitbox> found = null;

        for (VehicleEntityBase machine : machines) {
            AABB bounds = machine.placedBounds();

            if (machine == mover || machine == riding || machine.isRemoved()
                    || bounds == null || !bounds.intersects(area)) {
                continue;
            }

            for (VehiclePart part : machine.getParts()) {
                Hitbox box = part.hitbox();

                if (box == null || part.isPylon() || !box.reach().intersects(area)) {
                    continue;
                }

                if (found == null) {
                    found = new ArrayList<>();
                }

                found.add(box);
            }
        }

        return found == null ? List.of() : found;
    }

    /**
     * 移動を1軸ずつ、Minecraft が自前の移動を解く順——まず垂直、次に水平2軸のうち短い方——で解く。
     *
     * <p>この順は恣意的ではない。落下を先に決着させることが「同じ tick で面に着地してその上を歩く」ことを
     * 可能にし、水平2軸のうち短い方を先に取ることが「押し付けられた壁に引っ掛からず滑る」ことを可能にする。
     */
    private static Vec3 resolve(List<Hitbox> boxes, AABB box, Vec3 wanted) {
        double x = wanted.x;
        double y = wanted.y;
        double z = wanted.z;

        if (y != 0.0) {
            y = along(boxes, box, y, 1);

            if (y != 0.0) {
                box = box.move(0.0, y, 0.0);
            }
        }

        boolean acrossFirst = Math.abs(x) < Math.abs(z);

        if (acrossFirst && z != 0.0) {
            z = along(boxes, box, z, 2);

            if (z != 0.0) {
                box = box.move(0.0, 0.0, z);
            }
        }

        if (x != 0.0) {
            x = along(boxes, box, x, 0);

            if (x != 0.0) {
                box = box.move(x, 0.0, 0.0);
            }
        }

        if (!acrossFirst && z != 0.0) {
            z = along(boxes, box, z, 2);
        }

        return new Vec3(x, y, z);
    }

    /**
     * 動いている側自身が箱でできている場合の同じ処理。この MOD の形状同士が出会う全ての組み合わせ——甲板に
     * 対する機体、機体に対する甲板——がこれ。
     *
     * <p>一方の全箱を他方の全箱に対して試すので、判定数は聞こえる通り多い。だから移動区間で互いに近くもない
     * 組は先に捨てる。
     */
    private static Vec3 resolve(List<Hitbox> theirs, List<Hitbox> mine, Vec3 wanted) {
        double x = wanted.x;
        double y = wanted.y;
        double z = wanted.z;

        if (y != 0.0) {
            y = along(theirs, mine, y, 1);

            if (y != 0.0) {
                mine = shifted(mine, new Vec3(0.0, y, 0.0));
            }
        }

        boolean acrossFirst = Math.abs(x) < Math.abs(z);

        if (acrossFirst && z != 0.0) {
            z = along(theirs, mine, z, 2);

            if (z != 0.0) {
                mine = shifted(mine, new Vec3(0.0, 0.0, z));
            }
        }

        if (x != 0.0) {
            x = along(theirs, mine, x, 0);

            if (x != 0.0) {
                mine = shifted(mine, new Vec3(x, 0.0, 0.0));
            }
        }

        if (!acrossFirst && z != 0.0) {
            z = along(theirs, mine, z, 2);
        }

        return new Vec3(x, y, z);
    }

    private static double along(List<Hitbox> theirs, List<Hitbox> mine, double distance, int axis) {
        Vec3 motion = motion(distance, axis);
        double least = 1.0;

        for (Hitbox ours : mine) {
            AABB swept = ours.reach().expandTowards(motion);

            for (Hitbox hitbox : theirs) {
                if (!hitbox.reach().intersects(swept)) {
                    continue;
                }

                least = Math.min(least, hitbox.sweep(ours, motion));

                if (least == 0.0) {
                    return 0.0;
                }
            }
        }

        return distance * least;
    }

    private static List<Hitbox> shifted(List<Hitbox> boxes, Vec3 offset) {
        List<Hitbox> moved = new ArrayList<>(boxes.size());

        for (Hitbox box : boxes) {
            moved.add(box.move(offset));
        }

        return moved;
    }

    /** 全箱に対して掃引した後、1軸方向の移動がどれだけ残るか。 */
    private static double along(List<Hitbox> boxes, AABB box, double distance, int axis) {
        Vec3 motion = motion(distance, axis);
        double least = 1.0;

        for (Hitbox hitbox : boxes) {
            least = Math.min(least, hitbox.sweep(box, motion));

            if (least == 0.0) {
                return 0.0;
            }
        }

        return distance * least;
    }

    /**
     * 機体に止められ、かつ地面に立っている物のための段差乗り上げ1回分。
     *
     * <p>Minecraft は自前の障害物についてこれを、ここが問われる時点で既に終わっている移動の内側でやって
     * いる。だから機体の箱は自分でやらなければ、プレイヤーは普通に歩いて越えられるはずの履帯の縁で急停止
     * する。動きは同じ——空いている分だけ持ち上げ、そこから水平移動をやり直し、道が空いている分だけ降ろす。
     *
     * <p>持ち上げた位置が空いているかは、採用する前に世界へ訊く。ここには誰もブロックのことを知らないし、
     * 背後に壁のある履帯へ乗り上げて誰かを壁の中へ入れるわけにはいかないから。
     */
    private static Vec3 step(Entity mover, List<Hitbox> boxes, AABB box, Vec3 wanted, Vec3 allowed) {
        double reach = mover.maxUpStep();

        if (reach <= 0.0 || !(mover.onGround() || wanted.y < 0.0 && allowed.y != wanted.y)) {
            return allowed;
        }

        AABB from = box.move(0.0, allowed.y, 0.0);
        double lift = along(boxes, from, reach, 1);

        if (lift <= NOTHING) {
            return allowed;
        }

        AABB raised = from.move(0.0, lift, 0.0);
        Vec3 over = resolve(boxes, raised, new Vec3(wanted.x, 0.0, wanted.z));

        if (over.horizontalDistanceSqr() <= allowed.horizontalDistanceSqr()) {
            return allowed;
        }

        double settle = along(boxes, raised.move(over.x, 0.0, over.z), -lift, 1);
        Vec3 stepped = new Vec3(over.x, allowed.y + lift + settle, over.z);

        // 上の処理は誰もブロックの状況を知らない。そしてブロックの中で終わる段差乗り上げは、乗り上げが
        // 起きなかった場合より悪い。
        return mover.level().noCollision(mover, box.move(stepped)) ? stepped : allowed;
    }

    // ------------------------------------------------------------------
    // 動きながら上に立たれる側
    // ------------------------------------------------------------------

    /**
     * 機体の上に立っている物を一緒に運ぶ。
     *
     * <p>Minecraft が運ぶのは乗り物に<em>座っている</em>物だけ。「上に立っている」という概念を持っていない。
     * 甲板の上のプレイヤーは、ゲームから見れば空中に立っているプレイヤーであり、甲板が足元から滑り出ていく
     * のはゲームにとって当然の展開だ。航行中の空母にとってはそれが問題の全部なので、ここでやる——毎tick、
     * 機体のどれかの箱の上に乗っている物を、その機体が動いた分だけ動かし、回った分だけ回す。
     *
     * <p>動かすだけでなく回し、しかも<em>機体の中心回りに</em>回す。10度振れた甲板は、船首に立つ者を中央部
     * に立つ者よりずっと大きく運ぶ。本人の向きも一緒に回すので、甲板に沿って向いていたプレイヤーは後も甲板
     * に沿って向いている。足元で船が回っていくのを眺めることにはならない。
     *
     * <p><b>誰の仕事か。</b> 各側は自分が担当している物だけを動かす。それが両者の喧嘩を防ぐ。プレイヤーは
     * 自分のクライアントが運んで結果を報告し、サーバーはそれ以外を運んでクライアントへ伝える。両側が全員分
     * を回すことが、動く足場をカクつかせ人を突き飛ばす原因だ。サーバーの考える甲板とクライアントの考える
     * 甲板は決して完全には一致せず、負けた方が毎秒20回補正される。
     *
     * <p><b>落下ではない物。</b> 下方向へ運ばれることは落下ではなく、運搬の分担がどうであれ両側にそう伝え
     * る。降下する甲板はその上の者の足元から動き、また戻される。Minecraft の勘定ではそれが毎tick1tick分の
     * 自由落下になる。巡航から降りる機体は、指一本動かしていない人間の下に100ブロック分を積み上げ、降下が
     * 水平に戻った瞬間に一括請求する。この距離はクライアント側だけでなくサーバー側でも捨てる必要がある——
     * それが誰かを殺すかどうかはサーバーの計算であり、それはクライアント自身の集計ではなくクライアントが
     * 報告する移動から回るから。
     *
     * @param from 箱を最後に配置した時点での機体中心の位置
     * @param shift それ以降に進んだ距離
     * @param turn それ以降に回った角度（度）
     */
    static void carry(VehicleEntityBase machine, Vec3 from, Vec3 shift, float turn) {
        AABB bounds = machine.placedBounds();

        if (bounds == null) {
            return;
        }

        Vec3 now = machine.position();

        for (Entity rider : machine.level().getEntities(machine, bounds.inflate(1.0), Hitboxes::carriable)) {
            if (rider.getRootVehicle() == machine || !resting(machine, rider)) {
                continue;
            }

            rider.resetFallDistance();

            if (!owns(rider)) {
                continue;
            }

            // 機体に対する相対位置を回し、機体の現在位置に対して置き直す。回転と平行移動の2手ではなく
            // 1回の移動にするので、進路上の障害物は2回ではなく1回だけ止める。
            Vec3 at = rider.position();
            Vec3 want = now.add(turned(at.subtract(from), turn));

            rider.move(MoverType.SELF, want.subtract(at));

            if (turn != 0.0F) {
                bringRound(rider, turn);
            }
        }
    }

    /**
     * 機体が今の1歩で体ごと押しのけた物を傷つける。
     *
     * <p>サーバー限定。呼ぶ側が保証すること。ここが与えるのは実ダメージで、それを決めるのはクライアントの
     * 仕事ではない。
     *
     * <p><b>運ぶことと轢くことは別の判定だ。</b> {@link #carry} は甲板に足を乗せている者を探す。こちらは
     * 体が箱の<em>中</em>に入っている者を探す。同じ物を2つの目的で読むと、車体の箱が地面まで下りている
     * 以上、正面に立っていた者が「運ばれている」ことになって永久に無傷になる。分ける鍵は足ではなく体で、
     * それが {@link #RUN_OVER_BODY} の意味になる。
     *
     * <p>翼下のパイロンは数えない。あそこにぶら下がっているのは兵装で、それが人を殺すのは投下された後だ。
     *
     * <p>重複して当たらないことはバニラが見ている。{@code LivingEntity.hurt} は無敵時間の内側の2度目を
     * 落とすので、車体の下に留まった者は毎tickではなく1秒に1度傷つく。
     *
     * @param shift この tick に機体が進んだ距離
     */
    static void runOver(VehicleEntityBase machine, Vec3 shift) {
        double speed = shift.length();

        if (speed <= RUN_OVER_FLOOR) {
            return;
        }

        AABB bounds = machine.placedBounds();

        if (bounds == null) {
            return;
        }

        float damage = (float) ((speed - RUN_OVER_FLOOR) * RUN_OVER_RATE);
        DamageSource source = machine.runOverSource();

        // 走ってきた分だけ後ろへ広げて探す。1tickに十数ブロック進む機体では、終点の箱だけを見ると
        // 通り過ぎた相手が丸ごと網から漏れる。
        for (Entity victim : machine.level().getEntities(machine,
                bounds.inflate(1.0).expandTowards(shift.scale(-1.0)), Hitboxes::carriable)) {
            if (!(victim instanceof LivingEntity) || victim.getRootVehicle() == machine) {
                continue;
            }

            if (inside(machine, victim)) {
                victim.hurt(source, damage);
            }
        }
    }

    /** その物の体が、機体のいずれかの箱の中にあるか。足元は数えない。{@link #runOver} 参照。 */
    private static boolean inside(VehicleEntityBase machine, Entity victim) {
        AABB box = victim.getBoundingBox();
        AABB body = new AABB(box.minX, box.minY + RUN_OVER_BODY, box.minZ, box.maxX, box.maxY, box.maxZ);

        for (VehiclePart part : machine.getParts()) {
            Hitbox hitbox = part.hitbox();

            if (hitbox != null && !part.isPylon() && hitbox.overlaps(body)) {
                return true;
            }
        }

        return false;
    }

    /**
     * その物がこの MOD の機体に接しているか。上に立っている、寄りかかっている、押されて動かされている。
     *
     * <p>機体がやり得たことで誰かが傷つこうとしている時に問う。機体はゲームが存在を知らない「動く壁」で
     * あり、動く壁に押し付けられた結果——下へ運ばれて着地する、丘の斜面へ押し込まれる、押し詰められる——は
     * どれも「出所を示す物が何も付いていない普通のダメージ」として届く。ダメージが届いた瞬間にその物の隣に
     * 立っていたという事実が、得られる中で最も近い答えだ。しかもそれが正しい頻度は十分高く、代案——乗って
     * いる機体のせいで乗員が静かに死んでいく——を保つ価値は無い。
     *
     * <p>足元だけでなく、足元の判定と同じ僅かな余裕を付けた箱全体で見る。翼に今甲板から払われた者は、その
     * 時点で何の上にも立っていなかったのだから。
     */
    public static boolean touching(Entity entity) {
        Set<VehicleEntityBase> machines = MACHINES.get(entity.level());

        if (machines == null || machines.isEmpty()) {
            return false;
        }

        AABB box = entity.getBoundingBox().inflate(CONTACT);
        Entity riding = entity.getRootVehicle();

        for (VehicleEntityBase machine : machines) {
            AABB bounds = machine.placedBounds();

            if (machine.isRemoved() || bounds == null || !bounds.intersects(box)) {
                continue;
            }

            if (machine == riding) {
                return true;
            }

            for (VehiclePart part : machine.getParts()) {
                Hitbox hitbox = part.hitbox();

                if (hitbox != null && !part.isPylon() && hitbox.overlaps(box)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * パーツは機体そのもの、搭乗者は座席の管轄、飛んでいる弾は誰の足も乗せていない。残りが問い合わせる
     * 価値のある対象。
     *
     * <p><b>弾を外すのは速度のためだけではない。</b> 銃口で生まれた弾は、その瞬間まだ機体の中にいる——
     * 主翼の下のパイロン、砲身、機首。{@link #resting} が見るのは「足元のごく薄い層がどれかの箱に重なる
     * か」だけなので、弾はそこで甲板に立っている乗員として数えられ、{@code carry} が
     * {@code Entity.move(MoverType.SELF, ...)} を呼んでいた。
     *
     * <p>弾にとってあれは害でしかない。{@code Entity.move} は自前の衝突を回し、落下距離が0でなければ
     * 移動全長にブロック光線を撃ち（{@code Entity.java:642-648}）、{@code checkInsideBlocks} まで通る。
     * 弾は自分の飛行と当たり判定を {@link VehicleProjectile#tick} で完結させており、そこは一歩ごとに
     * 「その地面は待たずに読めるか」を確かめてから世界に触る。甲板の運搬はその規律の外から、同じ tick に
     * もう一度、確かめずに世界へ触っていた。
     */
    private static boolean carriable(Entity rider) {
        return !(rider instanceof VehiclePart) && !(rider instanceof VehicleProjectile)
                && !rider.isPassenger() && !rider.isRemoved();
    }

    /**
     * この物をどちらの側が動かすか。
     *
     * <p>プレイヤーを動かすのは自分のクライアントだけ——サーバーは位置について本人の申告を受け入れるので、
     * サーバーも動かせば既に動かしたクライアントと言い争うことになる。それ以外は担当している側が動かす。
     * 甲板に駐機した機体なら、パイロットがいればそのクライアント、いなければサーバー。
     */
    private static boolean owns(Entity rider) {
        if (rider instanceof Player player) {
            return player.level().isClientSide && player.isLocalPlayer();
        }

        return rider.isControlledByLocalInstance();
    }

    /**
     * その物が機体のどれかの箱に足を乗せているか。
     *
     * <p>問い合わせるのは足元の薄い層だけで、全身ではない。全身にすれば、岸壁から船体に寄りかかっていた者
     * まで運び去ってしまう。それは「上に立っている」とは別のこと。
     */
    private static boolean resting(VehicleEntityBase machine, Entity rider) {
        AABB box = rider.getBoundingBox();
        AABB feet = new AABB(box.minX, box.minY - CONTACT, box.minZ, box.maxX, box.minY + CONTACT, box.maxZ);

        for (VehiclePart part : machine.getParts()) {
            Hitbox hitbox = part.hitbox();

            if (hitbox != null && !part.isPylon() && hitbox.overlaps(feet)) {
                return true;
            }
        }

        return false;
    }

    /** その物自身の向きを足元の甲板と一緒に回し、頭の向きも一緒に回す。 */
    private static void bringRound(Entity rider, float turn) {
        rider.setYRot(rider.getYRot() + turn);
        rider.yRotO += turn;

        if (rider instanceof LivingEntity living) {
            living.yHeadRot += turn;
            living.yHeadRotO += turn;
            living.yBodyRot += turn;
            living.yBodyRotO += turn;
        }
    }

    /**
     * 垂直軸回りに振ったオフセット。甲板が人の下で回るのはこの向きだけ。
     *
     * <p>Minecraft の方位が回る向きに合わせてある。何も考えずに式を書いた時の回り方とは違う。方位が増える
     * と機首は +Z から −X へ向かうので、船首に立つ人も同じ向きへ動く。逆ではない。
     */
    static Vec3 turned(Vec3 offset, float degrees) {
        if (degrees == 0.0F) {
            return offset;
        }

        double angle = Math.toRadians(degrees);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        return new Vec3(offset.x * cos - offset.z * sin, offset.y, offset.z * cos + offset.x * sin);
    }

    // ------------------------------------------------------------------
    // 狙われる側
    // ------------------------------------------------------------------

    /**
     * 世界を貫いて狙った線が、この MOD の機体のうち最初に当たる物。どれにも当たらなければ null。
     *
     * <p>全ての射撃、全ての十字線が通る。機体の箱はゲーム自身の探索へそもそも提供されていない
     * （{@link VehiclePart#isPickable} 参照）ので、これは同じ命中についての第2の意見ではなく、唯一の意見。
     *
     * @param looker 狙っている物。乗っている機体も、その機体の箱も、この物にとって障害ではない
     * @param margin 箱の周りに許す余裕。呼び出し側が他の判定対象に許している値と合わせる
     * @param filter 呼び出し側の「何に当ててよいか」の判断
     */
    public static EntityHitResult pick(Level level, Entity looker, Vec3 from, Vec3 to, double margin,
            Predicate<Entity> filter) {
        Set<VehicleEntityBase> machines = MACHINES.get(level);

        if (machines == null || machines.isEmpty()) {
            return null;
        }

        // 余裕は線の側に一度だけ乗せる。{@code A.inflate(m).intersects(B)} と {@code A.intersects(B.inflate(m))}
        // は同じ判定なので、箱1つごとに膨らませた箱を作る必要は無い——1回作れば足りる。ここは1発の射撃に
        // つき1度呼ばれる場所で、100発/秒の機関砲は1tickに5〜7発を送り出し、その全部が寿命の間ずっと毎tick
        // ここを通る。ロード済みの地面の外ではなおさらだ。当たって消える地面がそこには無いので、外へ出た
        // 弾は射程いっぱい——数百発が同時に——飛び続ける。
        AABB along = new AABB(from, to).inflate(margin * 2.0);
        Entity riding = looker == null ? null : looker.getRootVehicle();
        VehiclePart nearest = null;
        Vec3 where = null;
        double closest = Double.MAX_VALUE;

        for (VehicleEntityBase machine : machines) {
            // 機体ごとに1回だけ。placedBounds は自分の全ての箱の和なので、これが線に触れない機体は箱を
            // 1つも試す必要が無い。近くを歩く物の判定（near 参照）は元からこうしている。射線から遠い機体
            // ——普通はワールドにいるほぼ全部——が、20〜40個の箱を数えられずに1回で外れる。
            AABB bounds = machine.placedBounds();

            if (machine == looker || machine == riding || machine.isRemoved()
                    || bounds == null || !bounds.intersects(along)) {
                continue;
            }

            for (VehiclePart part : machine.getParts()) {
                Hitbox box = part.hitbox();

                if (box == null || !box.reach().intersects(along) || !filter.test(part)) {
                    continue;
                }

                Vec3 hit = box.grow(margin).clip(from, to).orElse(null);

                if (hit == null) {
                    continue;
                }

                double distance = from.distanceToSqr(hit);

                if (distance < closest) {
                    closest = distance;
                    nearest = part;
                    where = hit;
                }
            }
        }

        return nearest == null ? null : new EntityHitResult(nearest, where);
    }

    // ------------------------------------------------------------------
    // 世界にぶつかる側
    // ------------------------------------------------------------------

    /**
     * 機体の箱がブロックにぶつかるまで、機体がどこまで動けるか。
     *
     * <p>掃引は他と同じで、役割が逆になるだけ。動いているのが箱、静止しているのがブロックで、計算上は
     * 「ブロックが逆向きに来る」形になる。ブロックは今も世界にブロックとして問い合わせる——ブロックは世界
     * そのものであり、本当に直立した箱だから。
     */
    public static Vec3 throughBlocks(Entity machine, Hitbox hitbox, Vec3 motion) {
        return throughBlocks(machine, hitbox, motion, UNDERSIDE_NONE);
    }

    /**
     * 同じ処理に、「これ以下なら地面をぶつからずにこすって越える」高さを与えた版。
     *
     * <p>車輪で立っている機体にとって、立っている地面は障害物ではない——それは床であり、床は既に機体を
     * 支えている。ただし機体の形状は全部が車輪より上にあるわけではない。離陸で機首を上げた機体は尾部を車輪
     * より下へ出し、普通に滑走路へ掃引すればその尾部は壁になる。機体は自分が転がっている地面に急停止させ
     * られ、それは斜面への激突として読まれ、そう扱われる。
     *
     * <p>だから車輪より高くならないブロックは形状を一切止めない。それで緩む物は無い。機体の素の直方体は
     * その同じ車輪の上に乗っており、{@code move} が通常通り世界に対して決着させるので、床は今も機体を支え
     * るし、そこへの降下は今も着地になる。できなくなるのは「降着装置の下にある地面に急停止させられる」こと
     * だけで、それこそ降着装置が存在する唯一の理由。
     *
     * @param underside これ以下ならぶつからずにこすって越える高さ。空中の機体は
     *                  {@link #UNDERSIDE_NONE} を渡し、その場合は全部にぶつかる
     */
    public static Vec3 throughBlocks(Entity machine, Hitbox hitbox, Vec3 motion, double underside) {
        if (motion.lengthSqr() == 0.0) {
            return motion;
        }

        List<AABB> blocks = above(blocksAround(machine, hitbox.reach().expandTowards(motion)), underside);

        if (blocks.isEmpty()) {
            return motion;
        }

        double x = motion.x;
        double y = motion.y;
        double z = motion.z;

        if (y != 0.0) {
            y = through(blocks, hitbox, y, 1);
            hitbox = hitbox.move(new Vec3(0.0, y, 0.0));
        }

        boolean acrossFirst = Math.abs(x) < Math.abs(z);

        if (acrossFirst && z != 0.0) {
            z = through(blocks, hitbox, z, 2);
            hitbox = hitbox.move(new Vec3(0.0, 0.0, z));
        }

        if (x != 0.0) {
            x = through(blocks, hitbox, x, 0);
            hitbox = hitbox.move(new Vec3(x, 0.0, 0.0));
        }

        if (!acrossFirst && z != 0.0) {
            z = through(blocks, hitbox, z, 2);
        }

        return new Vec3(x, y, z);
    }

    private static double through(List<AABB> blocks, Hitbox hitbox, double distance, int axis) {
        Vec3 motion = motion(-distance, axis);
        double least = 1.0;

        for (AABB block : blocks) {
            least = Math.min(least, hitbox.sweep(block, motion));

            if (least == 0.0) {
                return 0.0;
            }
        }

        return distance * least;
    }

    /** その箱が今いる場所に収まる余地があるか。重なってよい余裕を差し引いて判定する。 */
    public static boolean clearOfBlocks(Entity machine, Hitbox hitbox, double margin) {
        return clearOfBlocks(machine, hitbox, margin, UNDERSIDE_NONE);
    }

    /**
     * 同じ処理に、「これ以下のブロックは機体を埋めた世界ではなく機体が立っている床である」高さを与えた版。
     *
     * <p>{@link #throughBlocks(Entity, Hitbox, Vec3, double)} がこすって越えるのと同じ線に、別の問いを
     * 投げる物。機体の形状は全部が車輪より上にあるわけではない。接地直前にフレアを掛けた機体は尾部を車輪
     * よりかなり下へ出すし、バンクしていれば翼端がそこに来る。だから毎回の着陸で50cm 分の機体が滑走路の中
     * にいる。それが下から見た「地面に立っている」の姿であって、世界に埋められた機体ではない。
     *
     * @param underside これ以下のブロックを床と見なす高さ。空中の機体は {@link #UNDERSIDE_NONE} を渡し、
     *                  その場合は重なった物すべての中にいることになる
     */
    public static boolean clearOfBlocks(Entity machine, Hitbox hitbox, double margin, double underside) {
        Hitbox room = hitbox.grow(-margin);

        for (AABB block : blocksAround(machine, room.reach())) {
            if (block.maxY > underside && room.overlaps(block)) {
                return false;
            }
        }

        return true;
    }

    /** そのうち、機体の形状を止める価値があるだけ高いブロックだけ。 */
    private static List<AABB> above(List<AABB> blocks, double height) {
        if (height == UNDERSIDE_NONE || blocks.isEmpty()) {
            return blocks;
        }

        List<AABB> found = new ArrayList<>(blocks.size());

        for (AABB block : blocks) {
            if (block.maxY > height) {
                found.add(block);
            }
        }

        return found;
    }

    /** 世界のある範囲で判定する価値のある全ブロック面を、素の直方体として。 */
    private static List<AABB> blocksAround(Entity machine, AABB area) {
        List<AABB> found = new ArrayList<>();

        for (VoxelShape shape : machine.level().getBlockCollisions(machine, area)) {
            found.addAll(shape.toAabbs());
        }

        return found;
    }

    private static Vec3 motion(double distance, int axis) {
        return switch (axis) {
            case 0 -> new Vec3(distance, 0.0, 0.0);
            case 1 -> new Vec3(0.0, distance, 0.0);
            default -> new Vec3(0.0, 0.0, distance);
        };
    }
}
