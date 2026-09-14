package com.ashvehicles.ai.navigation;

import com.ashvehicles.entity.BlockCrusher;
import com.ashvehicles.entity.GroundVehicleEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * AI の前方の地面を読み、抜けられる方角を選ぶ。
 *
 * <p><b>経路探索ではない。</b> 目的地までの道を解くのではなく、「この向きへ何ブロック走れるか」を数方向
 * について測り、一番遠くまで行けて、しかも行きたい方角に一番近い物を選ぶ——車両の前に生えた数本の触角だ。
 * 道を選ぶのは {@link RoutePlanner} で、これはその通過点へ向かう1 tick を決める。
 *
 * <p><b>測り方は車両の接地判定と同じ基準にしてある。</b> 段差を登れるかどうかは
 * {@code suspension.climb_height}、落ちるかどうかは同じ値の下向き（{@code GroundVehicleEntity.rest}
 * 参照）。だから「AI は行けると判断したのに車体は登れない」が起きない。<b>ハイトマップは使わない</b>
 * ——あれは世界の一番上の面しか知らないので、橋の下・トンネルの中・切通しを走っている車両に対して
 * 「頭上の橋が壁だ」と答える。代わりに車両自身の高さの周りだけを縦に数ブロック読む。
 *
 * <p><b>ブロックは chunk から直接読む。</b> {@code Level.getBlockState} は無い chunk を<em>その場で
 * 生成する</em>ので、前方16ブロックを覗くだけで tick スレッド上のワールド生成になりうる
 * （[[explosions-generate-chunks]] と同じ穴）。{@code getChunkNow} で取れた chunk だけを読み、取れない
 * 方向は「まだ知らない」として素通りさせる——どのみちそこへ着く頃には自分のチケットがロードしている。
 * 経路の側はそこに未知の代償を付ける（{@link RoutePlanner}）。
 *
 * <p><b>触角は隙間を跨がない。</b> 町の柱の列、1ブロック幅の塀、建物の角——車体の幅の内に1ブロックでも
 * 立っていれば車両は止まるのに、以前の触角は2.5ブロックおきに、車体の中心と両端だけを読んでいたので、
 * 間に落ちた柱が見えなかった（2026-09-13、町の中で立ち往生する AI）。今は1.5ブロックおきに、近い側は
 * 車体の幅を1ブロック未満の刻みで読む。
 *
 * <p><b>水は床ではなく、深さを持つ。</b> 水のブロックは動きを遮らないので、以前の読みは川の底を「4ブロック下の
 * 床」と答え、AI は川へ降りてそのまま水の中を走った（2026-09-13）。今は床の上に積もった水を数え
 * （{@link Ground#water}）、車体が沈む深さ（{@value #DEEP}）の水は、渡ると決めていない限り壁として扱う。
 */
public final class Obstacles {
    /** 測る方角。行きたい向きからの差（度）。左右対称で、順に広がる。 */
    private static final float[] FAN = {0.0F, 20.0F, -20.0F, 42.0F, -42.0F, 70.0F, -70.0F, 100.0F, -100.0F};

    /** 1歩の長さ（ブロック）と歩数。掛けた値が触角の長さ。 */
    private static final double STEP = 1.5;
    private static final int STEPS = 10;

    /** 足元が消えたと見なす落差（ブロック）。崖と深い水がここで落ちる。 */
    public static final double DROP = 4.0;

    /**
     * 抜け出すときに降りてよい落差（ブロック）。
     *
     * <p>地上車両は落ちても壊れない（落下の衝撃は残骸にしか効かない——{@code VehicleEntityBase.tick}）。普段
     * {@link #DROP} で止めているのは「降りたら登って戻れない所へ行かない」ためで、屋根に登って降りられなくなった
     * 車両には、その約束を守る理由がもう無い。
     */
    public static final double ESCAPE_DROP = 10.0;

    /** 車体が沈むと見なす、床の上の水の深さ（ブロック）。 */
    public static final int DEEP = 2;

    /** 車幅に足す余裕（ブロック）。履帯の外側がブロックの角に触れるだけでも車両は止まる。 */
    public static final double MARGIN = 0.4;

    /**
     * 車体の腹が始まる高さ（登坂能力の上に足す分）。{@code GroundVehicleEntity.CRUSH_CLEARANCE} と同じ値。
     *
     * <p>この線より上にある物は<b>車体の中</b>で、破壊力に届けば押し通れる（土手も木も丸太の壁も、装軌車両は
     * 削って進む——{@code GroundVehicleEntity.crushesThrough}）。この線より下は「乗り越える地面」で、そこで
     * 消えるのは薙ぎ倒せる物（柵・草・葉）だけだ。<b>2つを取り違えると AI は必ずどちらかで嘘をつく</b>
     * ——下で耐性を使えば登れない土手を「通れる」と言い、上で使わなければ車両が平然と抜ける土手を壁と呼ぶ。
     */
    private static final double BELLY = 0.5;

    /** 車体の幅を隙間なく当てる歩数（近い側）。これより先は両端だけ。 */
    private static final int WIDE_STEPS = 5;

    /** 車体の幅を当てる横の刻みの上限（ブロック）。1未満なら、1ブロック幅の柱を必ずどれかが踏む。 */
    public static final double LANE = 0.9;

    /** 縦に読む最大ブロック数の安全弁。抜け出すときの深い落差まで読める長さ。 */
    private static final int COLUMN = 16;

    /** 行きたい向きから離れることの代償（ブロックあたり度）。遠回りと向き違いの交換レート。 */
    private static final double TURN_COST = 0.06;

    /** ここまで詰まっていたら手詰まり（ブロック）。呼んだ側が後退へ回す。 */
    public static final double DEAD_END = 3.75;

    /**
     * 1つの列の読み。床の高さと、そこを通るのに何かを薙ぎ倒す必要があったか、床の上に水が何ブロックあるか。
     *
     * <p>床が {@link Double#NaN} なら壁。<b>「通れない」と「掘って通る」と「水の底を通る」は別の答え</b>で、道を
     * 引く側は後の2つにそれぞれの代償を付ける（{@link RoutePlanner}）。
     */
    public record Ground(double floor, boolean dug, int water) {
        /** 通れない列。 */
        public static final Ground WALL = new Ground(Double.NaN, false, 0);

        public boolean blocked() {
            return Double.isNaN(this.floor);
        }

        /** 車体が沈む深さの水の底か。 */
        public boolean deep() {
            return this.water >= DEEP;
        }
    }

    /**
     * 選んだ方角と、その先に開いていると<b>確かめた</b>距離。
     *
     * <p>真っ直ぐが開いていたときは、確かめた所（向かう点まで）で数えるのをやめている——その先が開いているとは
     * 言っていない。以前はここに触角の長さ（15）を入れていたので、呼んだ側は次に測るまで10 tick 走り、5ブロックしか
     * 確かめていない所を9ブロック進んで岸から水へ落ちた（2026-09-13）。
     */
    public record Heading(float yaw, double clearance) {
        /** 前が塞がっていて、曲がっても抜けられないか。 */
        public boolean stuck() {
            return this.clearance < DEAD_END;
        }
    }

    private Obstacles() {
    }

    /** 触角の長さ。これ以上先は見ない。 */
    public static double look() {
        return STEP * STEPS;
    }

    /**
     * 行きたい方角の代わりに、実際に走ってよい方角。
     *
     * <p>真っ直ぐが {@code reach} まで開いていればそのまま返す。塞がっていれば、左右へ広げながら「一番遠くまで
     * 行けて、行きたい向きに一番近い」方角を選ぶ。<b>どの方角も開いていなければ、開いていないと答える</b>
     * （{@link Heading#stuck}）——後退させるかどうかは呼んだ側の判断で、ここでは決めない。
     *
     * <p><b>真っ直ぐは向かう点までしか問わない。</b> 5ブロック先の通過点へ向かう車両が、15ブロック先の建物を
     * 見て今曲がれば、通過点を外して戻る蛇行になる。その建物は、道がそこで曲がることで既に避けてある。
     *
     * @param reach 向かっている点までの距離（ブロック）
     * @param drop  降りてよい落差（ブロック）。普段は {@link #DROP}、抜け出すときは {@link #ESCAPE_DROP}
     * @param wade  深い水へ入ってよいか。既に水の中にいるか、道が水を渡ると決めたときだけ
     */
    public static Heading around(GroundVehicleEntity vehicle, float wanted, double reach, double drop, boolean wade) {
        double need = Math.min(look(), Math.max(reach, DEAD_END + STEP));
        double straight = clearance(vehicle, wanted, need, drop, wade);

        if (straight >= need) {
            return new Heading(wanted, straight);
        }

        float best = wanted;
        double bestOpen = straight;
        double bestScore = straight;

        for (float offset : FAN) {
            if (offset == 0.0F) {
                continue;
            }

            float candidate = Mth.wrapDegrees(wanted + offset);
            double open = clearance(vehicle, candidate, look(), drop, wade);
            // 遠くまで行ける方が良いが、行きたい向きから離れるほど割り引く。同じだけ開いている2方向なら
            // 目的地に近い側へ寄る。
            double score = open - Math.abs(offset) * TURN_COST;

            if (score > bestScore) {
                bestScore = score;
                bestOpen = open;
                best = candidate;
            }
        }

        return new Heading(best, bestOpen);
    }

    /**
     * その方角へ何ブロック走れるか。{@code limit} まで開いていれば、そこで数えるのをやめる。
     *
     * <p>車両と同じように<b>地面を辿って歩く</b>。1歩ごとにその足元の高さを取り直すので、1ブロックずつ
     * 上がっていく坂は最後まで開いているし、同じ高さでも1歩で登れない壁はそこで止まる。
     */
    public static double clearance(GroundVehicleEntity vehicle, float yaw, double limit, double drop, boolean wade) {
        Level level = vehicle.level();
        double climb = vehicle.getStats().suspension().climbHeight();
        float crush = vehicle.getStats().crush().resistance();
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians);
        double dz = Math.cos(radians);
        // 車体は点ではない。<b>車幅ぴったりでは足りない</b>。履帯の外側がブロックの角に触れる位置でも車両は
        // 止まるので、少し広く見る。
        double half = vehicle.getBbWidth() * 0.5 + MARGIN;
        int lanes = Math.max(2, (int) Math.ceil(2.0 * half / LANE));
        double sideX = -dz;
        double sideZ = dx;
        double ground = vehicle.getY();

        for (int step = 1; step <= STEPS; step++) {
            double along = step * STEP;
            double x = vehicle.getX() + dx * along;
            double z = vehicle.getZ() + dz * along;
            Ground centre = probe(level, x, z, ground, climb, crush, drop);

            if (closed(centre, wade)) {
                return along - STEP;
            }

            if (half > 0.5) {
                // 横は同じ1歩の別の列なので、その歩の床から問う——横へ傾いた地面で、真ん中より1段高い端を
                // 壁と呼ばないように。近い側は幅を隙間なく、遠い側は両端だけ。
                if (step <= WIDE_STEPS) {
                    for (int lane = 0; lane <= lanes; lane++) {
                        double offset = -half + lane * (2.0 * half / lanes);

                        if (Math.abs(offset) > 1.0E-3 && closed(probe(level, x + sideX * offset,
                                z + sideZ * offset, centre.floor(), climb, crush, drop), wade)) {
                            return along - STEP;
                        }
                    }
                } else if (closed(probe(level, x + sideX * half, z + sideZ * half, centre.floor(), climb, crush,
                        drop), wade) || closed(probe(level, x - sideX * half, z - sideZ * half, centre.floor(), climb,
                        crush, drop), wade)) {
                    return along - STEP;
                }
            }

            ground = centre.floor();

            if (along >= limit) {
                return along;
            }
        }

        return look();
    }

    private static boolean closed(Ground ground, boolean wade) {
        return ground.blocked() || (!wade && ground.deep());
    }

    /** その列で、車両が次に立つことになる面と、そこへ入るのに薙ぎ倒す物があるか。普通の落差で。 */
    static Ground probe(Level level, double x, double z, double ground, double climb, float crush) {
        return probe(level, x, z, ground, climb, crush, DROP);
    }

    /**
     * その列で、車両が次に立つことになる面と、そこへ入るのに薙ぎ倒す物と、面の上の水の深さ。登れない壁と、
     * 落ちるほどの落差では {@link Ground#WALL}。
     *
     * <p>読むのは車両自身の高さの周りだけ。{@code ground + climb} から下向きに、{@code ground - drop}
     * まで。<b>見付からないことと壁であることを同じ答えにしている</b>のは、AI にとってどちらも「そこへは
     * 行かない」だからだ。
     *
     * <p>{@link RoutePlanner} も同じ物差しで格子を歩く。触角と道の探索が別の基準で地形を読むと、「道はあると
     * 言ったのに1歩目で壁だった」が起きる。
     *
     * @param drop 降りてよい落差（ブロック）
     */
    static Ground probe(Level level, double x, double z, double ground, double climb, float crush, double drop) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        ChunkAccess chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));

        // まだロードされていない土地は「まだ知らない」。塞がっていることにはしない——ここで生成させれば
        // tick スレッドの上でワールドを作ることになるし、着く頃には自分のチケットが持ってくる。
        if (chunk == null) {
            return new Ground(ground, false, 0);
        }

        int from = Mth.floor(ground + climb);
        int to = Math.max(Mth.floor(ground - drop), chunk.getMinBuildHeight());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        boolean dug = false;
        int water = 0;

        for (int y = from, read = 0; y >= to && read < COLUMN; y--, read++) {
            at.set(blockX, y, blockZ);

            BlockState state = chunk.getBlockState(at);

            if (!state.blocksMotion()) {
                // 床の真上に続いている水だけを数える。水の下に空気があれば、その水は床の上ではない。
                water = state.getFluidState().is(FluidTags.WATER) ? water + 1 : 0;

                continue;
            }

            // <b>薙ぎ倒せる物は最初から無い物として読む。</b> 柵も壁も生垣も、車両はそこを通るときに
            // 薙ぎ倒していく（{@code BlockCrusher.mown}）。壁として読むと、AI は自分が3秒で通れる牧場を
            // 大回りして避けることになる。
            //
            // <b>耐性判定は使わない。</b> あれは車体の中の物に対する問いで、地面の高さで使うと土も砂も
            // 木材も「通れる」ことになる——AI だけが登れない土手を通れると言い張る。運転する側が地面の
            // 高さで消す物と、ここで無いことにする物は、同じ1つの述語でなければならない。
            if (BlockCrusher.mown(level, at, state)) {
                continue;
            }

            double top = y + 1.0;

            // <b>車体の中に入る物は押し通る。</b> 土手も木立も丸太の壁も、装軌車両は削って進む
            // （{@code GroundVehicleEntity.crushesThrough}）。ここでそれを壁と読むと、AI は人が平然と
            // 抜ける土手を大回りして避け、林の中へは一歩も入らない。
            if (top > ground + climb + BELLY && BlockCrusher.crushable(level, at, state, crush)) {
                dug = true;
                water = 0;

                continue;
            }

            // 登れる高さに収まっているか。収まらない物が壁であり、建物の角も石壁もここで落ちる。
            return top - ground > climb ? Ground.WALL : new Ground(top, dug, water);
        }

        return Ground.WALL;
    }

    /**
     * 車両が今どれだけ水に浸かっているか。足元のブロックから上へ、続いている水のブロックの数。
     *
     * <p>車体の中心の1列だけを読む。岸に半分乗り上げた車両は、中心が陸なら浸かっていない——そこから先は
     * 走れば出られる。
     */
    public static int waterDepth(Level level, double x, double y, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        ChunkAccess chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(blockX), SectionPos.blockToSectionCoord(blockZ));

        if (chunk == null) {
            return 0;
        }

        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int depth = 0;

        // 床に置かれた車体の y は床の上面そのものなので、少しだけ持ち上げてから切り捨てる。
        for (int blockY = Mth.floor(y + 0.01); depth < COLUMN; blockY++, depth++) {
            if (!chunk.getFluidState(at.set(blockX, blockY, blockZ)).is(FluidTags.WATER)) {
                break;
            }
        }

        return depth;
    }
}
