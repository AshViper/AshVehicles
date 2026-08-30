package com.ashvehicles.entity;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 車両が突き進む対象と、その破壊。
 *
 * <p>問いは2つあり、両者の答えは一致していなければならない。さもないと車両は「誰も片付けない物の前で止ま
 * る」か「そのまま残る物へ突っ込む」かのどちらかになる。だから同じ体積に同じ規則で問う。{@link #opens} は
 * 1歩を確定する前に移動処理が問う物、{@link #crush} は車両が実際にどこまで行ったかについて後からサーバー
 * が問う物。
 *
 * <p><b>どちらの側がどちらを問うか。</b> 運転手が乗っている車両はその運転手のクライアントで計算される
 * （{@link GroundVehicleEntity} 参照）ので、{@code opens} はそこで、クライアントが既に持っているブロック
 * 状態とタグを使って答え、車両はその結果に基づいて動く。その側では何も壊さず、サーバーへ何も問い合わせない。
 * サーバーは毎tick車体自身の体積を掃引して見つけた物を壊す。それはクライアントが「進入できる」と判断した
 * のと同じブロック集合であり、クライアントに自分の位置以上の何かを委ねずに得られている。
 *
 * <p><b>何を「進路上」と数えるか。</b> 素の直方体ではなく車体そのもの。車両が実際に持つ接地形状を、向いて
 * いる方向へ回し、自身のサスペンションが寝かせた平面に沿わせ、その平面の1段上から砲塔上面まで。その段より
 * 下は全部「地面」——乗り越える縁石であり、立っている斜面であり——そこの固体には決して触れない。戦車が
 * 出会う斜面ごとに塹壕を掘っていかない理由の全部がこれ。
 *
 * <p><b>ただしそこに生えている物は別。</b> 下草だけはその線より下でも、履帯の高さまで壊す。履帯の高さの
 * 下草こそ普通の状況であり要点だからだ——草原を横切った戦車は草原に轍を残す。そこで使えるのは
 * {@link #CRUSHABLE} タグだけで、耐性判定は決して使わない。地面高さでそれをやれば、MOD 内の全車両が世界
 * に溝を掘って進むことになる。
 */
public final class BlockCrusher {
    /**
     * 破壊力に関わらずどの車両の下でも倒れる物。草、作物、葉、苗木——世界の下草。
     *
     * <p>耐性判定と分けてあるのは別の概念だから。耐性は「どれだけ頑丈に作られているか」を言う値で、壁に問う
     * には正しい。生垣に問えば正しい答えを間違った理由で返し、破壊力の小さい軽車両が問えば端的に間違った
     * 答えを返す。生えている物が装軌車を止めるべきではないので、生えている物には問わない。
     *
     * <p>タグにしてあるので、パック側はここに一切触れずに「他に何が該当するか」を言える。
     */
    public static final TagKey<Block> CRUSHABLE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "crushable"));

    /**
     * ブロックが「車体の中に立っている」ではなく「触れているだけ」と数えられるまでの、車体端からの距離
     * （ブロック）。
     *
     * <p>ごく僅かな値だが、置く価値がある。車体の側面はブロック境界にぴったり乗ることが偶然より遥かに多い
     * ——この車両群は1ブロックや半ブロック単位で設計されている——し、その境界の向こうの列は車体に線で接する
     * だけで面積の重なりはゼロだ。それを数えると、全車両が自分より左右1ブロックずつ広い道を切り開くことに
     * なる。
     */
    private static final double GRAZE = 1.0E-4;

    private BlockCrusher() {
    }

    /**
     * 車体が占める体積を、地上車両が本当に持っている形で表した物。
     *
     * <p>箱ではなく向きを持った長方形で、しかも傾いている。{@code rise} と {@code tilt} は車体下面が前へ
     * 1ブロック進むごと・右へ1ブロック行くごとにどれだけ上がるかで、斜面に乗った車両ではそれが斜面自身の
     * 勾配になる。これがこの体積の床を「地面に食い込む」のではなく「地面に沿う」ものにしている。勾配を外せ
     * ば、土手を向いた戦車は土手が自分の中にあることに気付いてそれを食べてしまう。
     *
     * @param at 車両の原点。このモデル群では履帯の間、地面の高さ
     * @param forward 進行方位。水平で単位長
     * @param halfWidth 車体幅の半分（ブロック）
     * @param front 原点から前方へ車体が届く距離、{@code back} は後方へ届く距離（負の数で書く）
     * @param rise 車体下面が前へ1ブロックあたり上がる量、{@code tilt} は右へ1ブロックあたりの同じ物
     * @param belly 車体の平面から、車両の胴体が始まる高さ。1段＋少し。これより下は「突き進む」のではなく
     *              「乗り越える」地面
     * @param roof その平面から体積が終わる高さ。車両の最上部
     */
    public record Body(Vec3 at, Vec3 forward, double halfWidth, double front, double back,
            double rise, double tilt, double belly, double roof) {
    }

    /**
     * 車両が、自分を止めている物を自力で突破できるか。車体の中に何かがあり、そこにある物が全部壊れる場合に
     * 限り真。
     *
     * <p>問われるのは移動が既に拒否した1歩についてだけなので、条件の両方が効く。車体の中に何も無いなら、
     * その1歩を拒否したのは車両に壊せる物ではなかった——たいていは登るには少し高すぎる段差——ので拒否は
     * 有効なまま。何かあっても壊れないなら、それも有効なまま。
     *
     * <p>数えるのは実際に車両を止める物だけ。当たり判定形状を持たないブロック——草、花、作物——は壊せるか
     * どうかに関わらず誰の邪魔にもなっておらず、それを数えると「一度も止められていない麦畑を粉砕して進んだ」
     * と車両が主張することになる。
     */
    public static boolean opens(Level level, Body body, float limit) {
        // 下の走査から設定される。走査は2つのことを同時に返せないので、「何かが壊れたか」は「何かが持ち
        // こたえたか」の答えのもう半分になる。
        boolean[] gives = {false};

        boolean holds = walk(level, body, (pos, state, inBody) -> {
            if (!inBody || state.getCollisionShape(level, pos).isEmpty()) {
                return false;
            }

            if (!crushable(level, pos, state, limit)) {
                return true;
            }

            gives[0] = true;

            return false;
        });

        return !holds && gives[0];
    }

    /**
     * 車両の中に立っている物のうち、十分柔らかい物を全部壊し、それ以外は残す。
     *
     * <p>{@code destroyBlock} 経由なので、1つ1つが破壊時の音と破片を出す。膨大な数に見えるが実際は違う。
     * 体積は車両が最初にそこへ届いた tick で片付き、その後は走行中の車両が1tick分の速度で進入する薄い層
     * だけが対象になる。
     */
    public static void crush(Level level, Entity by, Body body, float limit, boolean drops) {
        walk(level, body, (pos, state, inBody) -> {
            boolean give = inBody
                    ? crushable(level, pos, state, limit)
                    : growing(level, pos, state);

            if (give) {
                level.destroyBlock(pos.immutable(), drops, by);
            }

            return false;
        });
    }

    /**
     * 車両が届く全ブロックに対し、どれかが真を返すまで問う。
     *
     * <p>{@code inBody} が体積の2つの部分を区別する。車両の胴体の中のブロックなら true、その下——履帯の
     * 高さ、生えている物しか壊れない領域——なら false。
     */
    @FunctionalInterface
    private interface Test {
        boolean of(BlockPos pos, BlockState state, boolean inBody);
    }

    /**
     * 車両が届くブロックを歩き、いずれかが判定に真を返したかを報告する。
     *
     * <p>1つの箱としてではなく列ごとに歩く。体積の床は車体が寝ている平面に沿っており、その平面の高さは列ご
     * とに違うから。水平方向の判定は車両自身の軸での単純な長方形で、その列が前後にどれだけ・左右にどれだけ
     * 離れているかは2つの内積からそのまま出る。
     */
    private static boolean walk(Level level, Body body, Test test) {
        Vec3 forward = body.forward();
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        Vec3 at = body.at();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 列の中心が車体の外へどれだけ出ていても「車体が立っている列」と見なすか。ブロックは正方形で車体
        // の軸は回っているので、許容すべきはその軸方向で測ったブロックの幅——車両が世界の軸に沿っていれば
        // 半ブロック、2軸の中間を向いていれば0.7ブロック。半分しか許さないと、斜めに走る車両は自分の中に
        // ブロックを残す。車両の2軸とも同じ値でよい。2本目は1本目を90度回した物なので。
        double reach = 0.5 * (Math.abs(forward.x) + Math.abs(forward.z)) - GRAZE;

        // 回転した長方形が入り得る世界の正方形。求めるのが安く、間違っていても安い。中の全列に対して下で
        // 厳密な判定を行うので。
        double span = Math.max(Math.abs(body.front()), Math.abs(body.back())) + body.halfWidth() + reach;
        int fromX = Mth.floor(at.x - span);
        int toX = Mth.floor(at.x + span);
        int fromZ = Mth.floor(at.z - span);
        int toZ = Mth.floor(at.z + span);

        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                double dx = x + 0.5 - at.x;
                double dz = z + 0.5 - at.z;
                double along = dx * forward.x + dz * forward.z;
                double sideways = dx * right.x + dz * right.z;

                if (along > body.front() + reach || along < body.back() - reach
                        || Math.abs(sideways) > body.halfWidth() + reach) {
                    continue;
                }

                double plane = at.y + along * body.rise() + sideways * body.tilt();
                // ブロックは自分の座標から上1mを所有するので、車両の胴体が最初に届くのは「床が入っている
                // ブロック」、最後は「天井の下のブロック」になる。この向きで書けば、ちょうどブロック境界
                // から始まる胴体はその境界の下のブロックに触れない——段差乗り越え高さが1ブロックの車両が、
                // 1ブロックの縁石を壊さずに乗り越えられる理由。
                int floor = Mth.floor(plane);
                int belly = Mth.floor(plane + body.belly());
                int roof = Mth.ceil(plane + body.roof()) - 1;

                for (int y = floor; y <= roof; y++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (!state.isAir() && test.of(pos, state, y >= belly)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** そのブロックが、指定の破壊力を持つ車両に対して壊れるか。 */
    private static boolean crushable(Level level, BlockPos pos, BlockState state, float limit) {
        if (!breakable(level, pos, state)) {
            return false;
        }

        // 爆発に対して返す値ではなくブロック自身の値を使う。ここに爆発は無いからだ。状態＋爆発を取る
        // 形式の問い合わせは「訊いてくる爆発」の存在を前提としており、渡された物を実際に読む MOD もある。
        return state.is(CRUSHABLE) || state.getBlock().getExplosionResistance() <= limit;
    }

    /** そのブロックが、他に何も壊せない履帯でも押し倒す類の物か。 */
    private static boolean growing(Level level, BlockPos pos, BlockState state) {
        return breakable(level, pos, state) && state.is(CRUSHABLE);
    }

    /**
     * 数値が何と言おうと車両が決して壊さない2種類。
     *
     * <p>ブロックエンティティを持つ物は誰かの設備——チェスト、かまど、機械に繋がったホッパー——であり、それ
     * を壊す車両は「コンテナの中身を無に捨てる」ことになる。押し潰した物はたいてい何も落とさないので。そして
     * 世界が破壊不能と印を付けた物は破壊不能のまま。壁に低い耐性を与えつつ硬度で立たせている MOD のために。
     */
    private static boolean breakable(Level level, BlockPos pos, BlockState state) {
        return !state.hasBlockEntity() && state.getDestroySpeed(level, pos) >= 0.0F;
    }
}
