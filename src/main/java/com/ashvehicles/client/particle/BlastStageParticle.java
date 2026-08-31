package com.ashvehicles.client.particle;

import com.ashvehicles.particle.Effects;
import com.ashvehicles.particle.TintedParticleOption;
import com.ashvehicles.particle.TintedParticleType;
import com.ashvehicles.registry.ModParticles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 起爆の進行役。自分では何も描かず、爆発を構成する物を順番に撒く。
 *
 * <p>これが要る理由は、爆発が爆発に見えるかどうかは<em>何が出るか</em>ではなく<em>どの順で出るか</em>で決まる
 * からだ。以前は火球も煙も衝撃波も破片も、着弾した同じ1tickに同時に生まれていた。撒く物は正しいのに、結果は
 * 「展開する爆発」ではなく「爆発の写真」になる——最初のフレームで既に全てがそこにあり、あとは薄れるだけだからだ。
 * 本物の順序は、白熱、開いていく火球、そこから走り出す衝撃波、遅れて追い付く煙、放物線を描いて落ちてくる破片、
 * そして残り火である。
 *
 * <p>サーバー側で数tickかけて送るのではなくクライアントで進行させる。{@link ShockwaveParticle} が土埃に対して
 * 既にやっていることの一般化で、利点も同じだ。パケットは起爆1回につき1つで済み、進行のタイミングは各クライアント
 * 自身の tick に乗るのでラグで崩れず、そして {@code ClientLevel.addParticle} はここから撒く物に自動で長距離
 * フラグを与える（{@link com.ashvehicles.particle.TintedParticleType} 参照）ので、300m 上空から見ている
 * パイロットにも全段階が届く。
 *
 * <p>最初の段階だけはコンストラクタで走る。{@code Particle.tick} は先頭で {@code age} を上げるので、tick から
 * 見える最小の age は 1 であり、「起爆した瞬間」に相当する tick は tick の中には無い。閃光と飛散物は起爆と同時に
 * 出るべき物なので、パーティクルが生まれたその場で出す。
 *
 * <h2>規模</h2>
 *
 * <p>渡される {@code scale} は大きさではなく爆発規模そのもので、1 から {@link Effects#LARGEST} まで来る。
 * 兵装は {@link Effects#BIGGEST}（12）までしか使わないが、試験棒はその20倍以上まで開いている。そこを素直に
 * 比例させると2つ壊れる——粒が1個100ブロックの板になり、撒く数が数千に達する。よって、この3つを分けてある。
 *
 * <ul>
 * <li><b>距離</b>は爆発力に比例する。火球の半径も、破片が飛ぶ距離も、キノコ雲の高さも。大きい弾頭は大きい。
 * <li><b>粒の大きさ</b>は {@link #grain} を通る。12 までは比例、そこから上は緩やかに。規模を上げたときに
 *     欲しいのは「大きな火球」であって「巨大な粒がいくつか」ではない。
 * <li><b>数</b>は頭打ちにする。通常規模の弾頭はどの上限にも届かないので、既存の見え方は1粒も変わらない。
 * </ul>
 *
 * <p>そして規模が {@link #MUSHROOMS_ABOVE} を超えると、煙柱の代わりにキノコ雲が立つ。
 */
public class BlastStageParticle extends WeaponParticle {
    /** 全部が済むまでの tick 数。最後の残り火が消えるまで。キノコ雲が出る場合はもっと長い。 */
    private static final int SHOW = 46;

    // ------------------------------------------------------------------
    // 規模の扱い
    // ------------------------------------------------------------------

    /**
     * {@link Effects#BIGGEST} を超えた分、粒の大きさが伸びる指数。
     *
     * <p>1なら比例、0なら一切大きくならない。0.4 は、規模が32倍になったとき粒が4倍という配分——つまり残りの
     * 8倍は「数が増える」か「間隔が空く」で表現される。粒が大きくなりすぎず、かといって砂粒にもならない値。
     */
    private static final double OVERSIZE_GROWTH = 0.4;

    // ------------------------------------------------------------------
    // 火球
    // ------------------------------------------------------------------

    /** 火球が開ききるまでの tick 数。ここが遅いと爆発ではなく焚き火に見える。 */
    private static final int OPENS_OVER = 4;
    /** 開ききった時点の火球半径。爆発力1あたりブロック。 */
    private static final double FIREBALL_REACH = 0.17;
    /** 火球を構成する粒の数。爆発力1あたり、最低数、そして上限。 */
    private static final float FIRE_PER_POWER = 1.8F;
    private static final int FEWEST_FIRE = 4;
    private static final int MOST_FIRE = 64;
    /** 粒1つの大きさ。{@link #grain} 1あたり。 */
    private static final float FIRE_SIZE = 0.30F;
    /** 置いた粒に残す外向きの速度の上限（1tickあたりブロック）。殻の位置取りが本体で、これは飾り。 */
    private static final double MOST_PUSH = 0.6;
    /**
     * 火球の縦横比。1未満なので上下に潰れている。
     *
     * <p>地表で起きた爆発は球にならない。下は地面に止められ、行き場を失った分が横へ出る。真球で撒くと、どの爆発も
     * 「空中に浮かんだ火の玉」になり、地面に当たったのか空中で炸裂したのか区別が付かない。
     */
    private static final double FLATTEN = 0.62;
    /** その分、中心を少しだけ持ち上げる。火球が地面にめり込んで見えないように。 */
    private static final double SITS_ABOVE = 0.25;

    // ------------------------------------------------------------------
    // 衝撃波
    // ------------------------------------------------------------------

    /**
     * 衝撃波を出す tick。火球が開き始めた次の tick。
     *
     * <p>同時ではないことに意味がある。過圧の前面は火球の表面から出るので、火球が生まれてから走り出す。1tick
     * 遅らせるだけで、輪は「爆発と同時に描かれた円」ではなく「爆発から出た物」になる。
     */
    private static final int WAVE_AT = 1;
    /**
     * 爆発力1あたり衝撃波が走る距離（ブロック）。
     *
     * <p>大きさを決めるのはこれだけなので、弾頭が2倍なら輪も2倍に広がり、爆発する物は必ず何らかの大きさの輪を
     * 出す。ロケットなら数ブロック、大型爆弾なら30m四方。この1つの数値を上げれば全兵装の衝撃波が比例して大きく
     * なる。
     *
     * <p>意図的に、爆発の有効半径より遠くまで走らせている。ここで描いているのは外へ吹き出す土煙であり、現実でも
     * 実際に損害を与える範囲よりずっと遠くまで届く。
     *
     * <p>これは土埃が届く距離であって、見える環の大きさではない。環はこれより外を走る——
     * {@link ShockwaveParticle} の {@code RING_LEADS} 参照。
     */
    private static final float WAVE_REACH = 2.8F;
    /** 走っていく地面から少し浮かせる。輪が地面とピクセルを取り合わないように。 */
    private static final double WAVE_LIFT = 0.35;
    /** どれだけ規模を上げても、土埃がここより外へは出ない（ブロック）。 */
    private static final float MOST_WAVE = 200.0F;

    // ------------------------------------------------------------------
    // 煙
    // ------------------------------------------------------------------

    /**
     * 煙が火球に追い付き始める tick と、出し終わる tick。
     *
     * <p>この遅れが、火球を火球の色で見せている。同じ瞬間に撒くと煤煙が炎の上に重なり、最も明るいはずの最初の
     * 数tickが灰色になる。炎が冷えた場所から順に煙になるのが本来の順序だ。
     */
    private static final int SMOKE_FROM = 2;
    private static final int SMOKE_TO = 10;
    private static final float SMOKE_PER_POWER = 3.0F;
    private static final int FEWEST_SMOKE = 8;
    private static final int MOST_SMOKE = 140;
    private static final float SMOKE_SIZE = 0.42F;
    /** 煙が出る位置。火球半径に対する倍率。炎の外側、既に冷えた所。 */
    private static final double SMOKE_BEYOND = 1.15;
    /** 煙が外へ広がる速さと、上へ昇る速さ。{@link #grain} 1あたり。 */
    private static final double SMOKE_OUT = 0.012;
    private static final double SMOKE_UP = 0.010;

    /** 煙柱を立てる tick の範囲と間隔。キノコ雲が出る規模では、代わりに柱が立つのでこちらは出ない。 */
    private static final int COLUMN_FROM = 6;
    private static final int COLUMN_TO = 30;
    private static final int COLUMN_EVERY = 2;
    /** 立ち上がる速さ。これが無いと煙は爆心に留まり、数百m先からは何も見えない。 */
    private static final double COLUMN_RISE = 0.09;
    private static final float COLUMN_SIZE = 0.5F;
    /** 煙柱と残り火が爆心から散らばる範囲。爆発力1あたり。 */
    private static final double CRATER_SPREAD = 0.18;

    // ------------------------------------------------------------------
    // 飛散物
    // ------------------------------------------------------------------

    /** 火花の量。以前 {@code Effects.sparks} が出していたのと同じ。 */
    private static final int FEWEST_SPARKS = 5;
    private static final float SPARKS_PER_POWER = 3.5F;
    private static final int MOST_SPARKS = 90;
    private static final double SPARK_SPREAD = 0.05;
    private static final double SPARK_SPEED = 0.09;
    private static final double SPARK_SPEED_PER_POWER = 0.05;

    /** 煙の尾を引く燃えかす。爆発力1あたり、最低数、上限。 */
    private static final float CINDER_PER_POWER = 0.8F;
    private static final int FEWEST_CINDERS = 4;
    private static final int MOST_CINDERS = 48;
    /** それを投げる速さ（1tickあたりブロック）と、上へ寄せる分。 */
    private static final double CINDER_THROW = 0.22;
    private static final double CINDER_LOFT = 0.55;
    /** 吹き飛ぶ地面の塊。燃えていないので光らず、放物線を描いて落ち、地面で跳ねる。 */
    private static final float CHUNK_PER_POWER = 1.0F;
    private static final int FEWEST_CHUNKS = 3;
    private static final int MOST_CHUNKS = 48;
    private static final double CHUNK_THROW = 0.16;
    private static final double CHUNK_LOFT = 0.7;
    private static final float CHUNK_SIZE = 1.3F;
    /**
     * 投げる速さの上限（1tickあたりブロック）。
     *
     * <p>速度まで規模に比例させると、最大規模では破片が毎tick3ブロック以上動く。そこまで来ると弧が見えず、
     * 画面を横切る線にしかならない上、地形判定を持つ粒がその歩幅で動くのは安くない。
     */
    private static final double MOST_THROW = 1.6;

    // ------------------------------------------------------------------
    // 残り火
    // ------------------------------------------------------------------

    /** 爆心に残って燃える炎の tick の範囲と間隔。 */
    private static final int EMBERS_FROM = 10;
    private static final int EMBERS_TO = SHOW;
    private static final int EMBERS_EVERY = 3;
    private static final float EMBER_SIZE = 0.7F;
    /** 残り火の色。爆発の色ではなく、燃えている物の色。 */
    private static final int EMBER_FLAME = 0xFF8A2A;
    /**
     * 爆心の下、これだけ探して固い物が無ければ残り火も土の塊も出さない。
     *
     * <p>空中で炸裂したミサイルの下に炎が居座るのを防ぐためのもの。空中炸裂は何も置いて行かない——置いて行く
     * 地面が無いからだ。世界がロードされていない所でも同じ扱いにする。分からないなら、無い地面の上で燃やすより
     * 出さない方が正しい。
     */
    private static final int GROUND_WITHIN = 3;
    /** 地面が見付からなかったことを表す色。 */
    private static final int AIRBURST = -1;

    // ------------------------------------------------------------------
    // キノコ雲
    // ------------------------------------------------------------------

    /**
     * これを超えた規模でキノコ雲が立つ。
     *
     * <p>兵装の上限（{@link Effects#BIGGEST}、12）よりはっきり上に置いてある。500kg 爆弾がキノコ雲を作る世界
     * にはしたくない——あれは「大きな爆発」であって別の現象ではないし、爆撃のたびに10秒立ち続ける雲を残されると
     * 戦場が自分の煙で見えなくなる。ここを超える値を出せるのは試験棒だけだ。
     *
     * <p>public なのは、試験棒のスライダーが同じ線を表示するため。閾値を2箇所に書くと、片方を動かした日に
     * 「キノコ雲」と表示しながら煙柱が立つ棒ができる。
     */
    public static final float MUSHROOMS_ABOVE = 30.0F;

    /** 柱が立ち始める tick。火球が開き、煙が湧いた後。 */
    private static final int MUSHROOM_FROM = 6;
    /** 傘の高さ。爆発力1あたりブロックと、その上限。 */
    private static final double CLIMBS_TO = 0.85;
    private static final double MOST_CLIMB = 200.0;
    /** 傘の半径。同じく爆発力1あたりと上限。 */
    private static final double CAP_SPREAD = 0.42;
    private static final double MOST_SPREAD = 95.0;
    /** 柱の太さ。傘の半径に対する割合。 */
    private static final double STEM_SLIM = 0.20;

    /** 立ち上がりに使う tick 数。最低分と、高さ1ブロックあたりの追加分。 */
    private static final int CLIMB_BASE = 20;
    private static final double CLIMB_PER_BLOCK = 0.32;
    /** 傘が開ききるまでの tick 数と、開き終えてから消えるまで抱える tick 数。 */
    private static final int CAPS_OVER = 50;
    private static final int LINGERS = 40;

    /**
     * 柱と傘を何枚の層で作るか。
     *
     * <p>枚数であって間隔ではない。核は通常のキノコ雲の2.6倍の時間をかけて立ち上がるが、その分だけ層を増やすと
     * 雲の密度が2.6倍になってしまう。かかる時間で割って間隔を出しているので、どの規模でも雲は同じ枚数——つまり
     * 同じ粗さ——で組み上がり、変わるのは「1枚が置かれる間隔」だけになる。
     */
    private static final int STEM_LAYERS = 22;
    private static final int CAP_LAYERS = 18;

    /** 柱の層に持たせる上向きの速さ。押し上げているのは下から湧く新しい層なので、これは仕上げ。 */
    private static final double STEM_RISE = 0.05;
    /** 柱が上へ行くほど太くなる割合。まっすぐな円柱は煙突であって、噴き上がった物には見えない。 */
    private static final double STEM_FLARES = 0.45;

    /**
     * 傘の断面のどこまで下を使うか。0が最も広い高さで、上は +1、下はこの値まで。
     *
     * <p>下側があることがキノコ雲をキノコ雲にしている。上だけなら丸い雲だが、最も広い高さより<em>下</em>にも
     * すぼまりながら層が続くと、傘が柱にかぶさっている形になる。実映像でスカートと呼ばれる部分だ。
     */
    private static final double CAP_UNDER = 0.85;
    /** 傘の高さ方向の潰し。1だと球で、頭でっかちになる。実際の傘は平たい。 */
    private static final double CAP_SQUASH = 0.55;
    /** 傘の層に持たせる、わずかな上向き。 */
    private static final double CAP_LIFT = 0.012;
    /** 傘の内側に置く煙の大きさ。傘の半径に対する割合。 */
    private static final float CAP_CORE_GRAIN = 0.30F;

    /**
     * 凝結の棚。柱のどの高さに（傘の高さに対する割合）、傘の半径の何倍で置くか。
     *
     * <p>高収量の核実験の映像で最も目を引く形がこれだ。上がっていく柱の周りに、水平な白い棚が何段も並ぶ——
     * 気温と湿度が変わる高度ごとに水蒸気が凝結してできる皿で、下ほど広く、上へ行くほど小さい。柱と傘だけの
     * キノコ雲は「大きな煙」に見えるが、この段があると別の現象に見える。
     *
     * <p>煤ではなく水なので白い。柱と違って光らないのも、燃えている物ではないからだ。
     */
    private static final double[] SHELF_AT = {0.20, 0.37, 0.53, 0.68};
    private static final double[] SHELF_WIDE = {0.95, 0.78, 0.62, 0.48};
    /** 1段を何枚で作るか。1枚だと紙に見えるので、少しずらして重ねて厚みを出す。 */
    private static final int SHELF_PLATES = 2;
    /** その2枚をどれだけ離すか（傘の半径に対する割合）と、棚が漂う速さ。 */
    private static final double SHELF_THICK = 0.06;
    private static final double SHELF_DRIFT = 0.02;

    // ------------------------------------------------------------------
    // 核
    // ------------------------------------------------------------------

    /**
     * 核の時間を通常のキノコ雲の何倍に伸ばすか。
     *
     * <p>核とそれ以外を分けている一番大きな要素がこれだ。同じ形でも、7秒で開ききる雲は「大きな爆発」に見え、
     * 20秒かけて登り続ける雲は別の現象に見える。実物はさらに桁違いに遅い（数分）が、そこまで行くと今度は
     * 「止まっている絵」になる。
     */
    private static final double NUCLEAR_SLOWER = 2.6;
    /**
     * ウィルソン雲を出す tick の範囲。
     *
     * <p>核の映像で最も見分けの付く特徴で、しかも一瞬しか無い。過圧の前面が通り過ぎた後の負圧で空気中の水が
     * 凝結し、火球を包む白い球殻になって、圧が戻ると消える。これがあるかどうかが「大きな爆発」と「核」の
     * 分かれ目になる。
     */
    private static final int VEIL_FROM = 1;
    private static final int VEIL_TO = 9;
    private static final int VEIL_PUFFS = 10;
    /** 殻が広がりきる半径。火球半径に対する倍率。 */
    private static final double VEIL_BEYOND = 2.6;
    /**
     * 殻を作る粒の大きさ。火球半径に対する割合。
     *
     * <p>大きい。半径50ブロックの球面を粒で覆おうとすると、まともな数では埋まらないからだ——数で埋めるのを
     * 諦めて、薄く大きい粒を重ねる方を選んでいる。どうせ描きたいのは面ではなく、火球の周りに一瞬立つ靄だ。
     */
    private static final double VEIL_GRAIN = 1.0;

    /**
     * 火球そのものが浮き上がる tick の範囲と、1tickあたりの粒数。
     *
     * <p>通常の爆発では火球はその場で消え、後から煙が立つ。核では火球が浮力を持つほど熱いので、消える前に
     * 自分で上がり始める——柱は「煙が立った」のではなく「火球が引きずった跡」だ。ここを描くかどうかで、
     * 柱の根元が地面から生えているか、火の玉から続いているかが変わる。
     */
    private static final int LOFTS_FROM = 3;
    private static final int LOFTS_TO = 26;
    private static final int LOFT_PUFFS = 3;
    /** 浮き上がりきる高さ。傘の高さに対する割合。そこから先は煙が引き継ぐ。 */
    private static final double LOFTS_TO_FRACTION = 0.35;
    private static final double LOFT_RISE = 0.08;

    /**
     * ベースサージ。柱の根元から地表を這って広がる、二つ目の雲。
     *
     * <p>柱に吸い上げられなかった分が地面沿いに外へ出ていく物で、実際にはこれが最も遠くまで届く。爆心が
     * 「柱が立っている一点」ではなく「広い範囲がやられた場所」に見えるのは、この低い雲のおかげだ。
     */
    private static final int SURGE_FROM = 14;
    private static final int SURGE_EVERY = 6;
    private static final int SURGE_PUFFS = 3;
    /** どこまで広がるか。傘の半径に対する倍率。 */
    private static final double SURGE_BEYOND = 1.9;
    /** 這う高さ。傘の半径に対する割合。低い。地面を舐める雲なので。 */
    private static final double SURGE_LOW = 0.10;
    /** 外へ這う速さと、粒の大きさ（傘の半径に対する割合）。 */
    private static final double SURGE_CREEP = 0.05;
    private static final float SURGE_GRAIN = 0.18F;

    private final int colour;
    private final float power;
    /** 粒1つの大きさの基準。{@link #grain} 参照。 */
    private final float grain;
    /** 通常規模を超えた分の倍率。12以下では1。元から大きさが固定の物だけこちらを使う。 */
    private final float oversize;
    /**
     * 吹き飛ばす地面の色。爆心の下から取る。
     *
     * <p>毎tick世界に問い直さないよう最初に一度だけ調べる。{@link #AIRBURST} なら下に地面が無かったということ
     * で、その場合は塊も残り火も出ない。
     */
    private final int ground;

    private final boolean mushrooms;
    private final boolean nuclear;
    /** 傘が座る高さと、開ききったときの半径（ブロック）。 */
    private final double capAt;
    private final double capWide;
    /** 柱が立ち上がりきるまでの tick 数と、そこから傘が開ききるまでの tick 数。 */
    private final int climbs;
    private final int caps;
    /** 層を1枚置く間隔（tick）。枚数を決めてから時間で割った物。 */
    private final int stemEvery;
    private final int capEvery;
    /** 雲を作るパーティクル。核だけ、灼熱して冷める方を使う。 */
    private final TintedParticleType layerOf;
    /** 既に置いた凝結の棚の段数。 */
    private int shelvesLaid;

    private BlastStageParticle(ClientLevel level, double x, double y, double z,
            TintedParticleOption options, SpriteSet sprites) {
        super(level, x, y, z, options);
        this.colour = options.colour();
        this.power = Mth.clamp(options.scale(), 1.0F, Effects.LARGEST);
        this.grain = grain(this.power);
        this.oversize = oversize(this.power);
        this.ground = groundUnder(level, x, y, z);

        this.mushrooms = this.power >= MUSHROOMS_ABOVE;
        this.nuclear = this.power >= Effects.NUCLEAR;
        this.capAt = Math.min(this.power * CLIMBS_TO, MOST_CLIMB);
        this.capWide = Math.min(this.power * CAP_SPREAD, MOST_SPREAD);

        double slower = this.nuclear ? NUCLEAR_SLOWER : 1.0;

        this.climbs = CLIMB_BASE + (int) (this.capAt * CLIMB_PER_BLOCK * slower);
        this.caps = (int) (CAPS_OVER * slower);
        this.stemEvery = Math.max(this.climbs / STEM_LAYERS, 1);
        this.capEvery = Math.max(this.caps / CAP_LAYERS, 1);
        this.layerOf = this.nuclear ? ModParticles.NUCLEAR_LAYER.get() : ModParticles.CLOUD_LAYER.get();
        this.lifetime = this.mushrooms
                ? MUSHROOM_FROM + this.climbs + this.caps + (int) (LINGERS * slower)
                : SHOW;

        // 進行役自体は動かないし落ちない。爆心はそこに開いた穴であって、飛んでいく物ではない。
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.setSprite(sprites.get(this.random));

        // 起爆の瞬間。白熱した核と、外へ出ていく物。tick からは届かない唯一の段階だ。
        this.openFireball(0);
        this.throwOut();
    }

    @Override
    public void tick() {
        super.tick();

        if (this.age < OPENS_OVER) {
            this.openFireball(this.age);
        }

        if (this.age == WAVE_AT) {
            this.shockwave();
        }

        if (this.age >= SMOKE_FROM && this.age <= SMOKE_TO) {
            this.smoke();
        }

        // 煙柱とキノコ雲は同じ仕事の大小版なので、両方は出さない。
        if (this.mushrooms) {
            this.mushroom();
        } else if (this.age >= COLUMN_FROM && this.age <= COLUMN_TO && this.age % COLUMN_EVERY == 0) {
            this.column();
        }

        if (this.nuclear) {
            this.nuclearSigns();
        }

        if (this.ground != AIRBURST && this.age >= EMBERS_FROM && this.age <= EMBERS_TO
                && this.age % EMBERS_EVERY == 0) {
            this.embers();
        }
    }

    /**
     * 火球のこの段階分。開ききるまでの各 tick で、その時点の火球表面に粒を置く。
     *
     * <p>1つの大きな粒を膨らませるのではなく、外へ向かう殻を毎tick置き直す。膨らむ1枚の板は近くで見ると板だが、
     * 外へ出ていく粒の群れはどの距離から見ても膨らむ塊に見える。{@link BlastParticle} 自身も生涯で成長するので、
     * 2つの成長が重なって最初の2〜3tickに集中する。
     *
     * @param step 0 から {@link #OPENS_OVER} 未満まで。0 が起爆の瞬間の核
     */
    private void openFireball(int step) {
        int total = Math.min(FEWEST_FIRE + (int) (this.power * FIRE_PER_POWER), MOST_FIRE);
        int now = Math.max(total / OPENS_OVER, 1);
        // 開いた割合。最初は中心の白熱した核、最後の段階が最も外側。
        double opened = (double) (step + 1) / OPENS_OVER;
        double reach = FIREBALL_REACH * this.power * opened;
        // まだ開き切っていない分だけ外向きの速度を持たせる。最後の段階の粒はほぼ止まって見える。
        double push = Math.min(FIREBALL_REACH * this.power * (1.0 - opened) / OPENS_OVER, MOST_PUSH);

        for (int i = 0; i < now; i++) {
            Vec3 out = this.outward();

            this.level.addParticle(ModParticles.BLAST.get().of(this.colour, this.grain * FIRE_SIZE),
                    this.x + out.x * reach, this.y + out.y * reach + SITS_ABOVE, this.z + out.z * reach,
                    out.x * push, out.y * push, out.z * push);
        }
    }

    /** 起爆の瞬間に外へ出る物。火花、煙の尾を引く燃えかす、そして吹き飛ぶ地面の塊。 */
    private void throwOut() {
        int sparks = Math.min(FEWEST_SPARKS + (int) (this.power * SPARKS_PER_POWER), MOST_SPARKS);
        double speed = Math.min(SPARK_SPEED + this.power * SPARK_SPEED_PER_POWER, MOST_THROW);

        for (int i = 0; i < sparks; i++) {
            this.level.addParticle(ModParticles.SPARK.get().of(Effects.EMBER, 1.0F),
                    this.x + this.random.nextGaussian() * SPARK_SPREAD,
                    this.y + this.random.nextGaussian() * SPARK_SPREAD + SITS_ABOVE,
                    this.z + this.random.nextGaussian() * SPARK_SPREAD,
                    this.random.nextGaussian() * speed, this.random.nextGaussian() * speed,
                    this.random.nextGaussian() * speed);
        }

        // 燃えかすは弧を描いて飛ぶので、上へ強く寄せる。真横に投げると爆心の周りを滑るだけで放物線が出ない
        // ——それが「破片が降ってきた」と読めるかどうかの分かれ目になる。
        int cinders = Math.min(FEWEST_CINDERS + (int) (this.power * CINDER_PER_POWER), MOST_CINDERS);
        double flung = Math.min(CINDER_THROW * Math.sqrt(this.power), MOST_THROW);

        for (int i = 0; i < cinders; i++) {
            Vec3 thrown = this.lofted(flung, CINDER_LOFT);

            this.level.addParticle(ModParticles.CINDER.get().of(Effects.EMBER, this.oversize),
                    this.x, this.y + SITS_ABOVE, this.z, thrown.x, thrown.y, thrown.z);
        }

        if (this.ground == AIRBURST) {
            return;
        }

        int chunks = Math.min(FEWEST_CHUNKS + (int) (this.power * CHUNK_PER_POWER), MOST_CHUNKS);
        double heaved = Math.min(CHUNK_THROW * Math.sqrt(this.power), MOST_THROW);

        for (int i = 0; i < chunks; i++) {
            Vec3 thrown = this.lofted(heaved, CHUNK_LOFT);

            this.level.addParticle(ModParticles.RUBBLE.get().of(this.ground, CHUNK_SIZE * this.oversize),
                    this.x, this.y + SITS_ABOVE, this.z, thrown.x, thrown.y, thrown.z);
        }
    }

    /** 地表を走る環。1個のパーティクルが輪の描画と土埃の巻き上げを自分でやる。{@link ShockwaveParticle} 参照。 */
    private void shockwave() {
        float reach = Math.min(this.power * WAVE_REACH, MOST_WAVE);

        this.level.addParticle(ModParticles.SHOCKWAVE.get().of(Effects.DUST, reach),
                this.x, this.y + WAVE_LIFT, this.z, 0.0, 0.0, 0.0);
    }

    /**
     * 煙のこの tick 分。火球の外側に、外へ広がりながら昇る煤を置く。
     *
     * <p>出る量は時間とともに減る。煙が「炎の後を追って湧いた」ように見えるのは、最初が最も濃く、火が冷えるに
     * つれて細るからだ。一定量を出し続けると噴煙機になる。
     */
    private void smoke() {
        int span = SMOKE_TO - SMOKE_FROM + 1;
        int total = Math.min(FEWEST_SMOKE + (int) (this.power * SMOKE_PER_POWER), MOST_SMOKE);
        float left = 1.0F - (float) (this.age - SMOKE_FROM) / span;
        // 減っていく配分なので、平均が総数と合うよう2倍しておく。三角形の面積は同じ高さの長方形の半分。
        int now = Math.max(Math.round(total * left * 2.0F / span), 1);
        double reach = FIREBALL_REACH * this.power * SMOKE_BEYOND;

        for (int i = 0; i < now; i++) {
            Vec3 out = this.outward();

            this.level.addParticle(ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, this.grain * SMOKE_SIZE),
                    this.x + out.x * reach, this.y + out.y * reach + SITS_ABOVE, this.z + out.z * reach,
                    out.x * SMOKE_OUT * this.grain, SMOKE_UP * this.grain, out.z * SMOKE_OUT * this.grain);
        }
    }

    /**
     * 爆心から立ち上がる煙。
     *
     * <p>数百m先の誰かに「あそこで何かが爆発した」と告げるのはこれだ。広がるだけの煙は距離とともに背景に紛れる
     * が、垂直に伸びる柱は地平線に対して立つので、他に何も見えない距離でも読める。長くは残さない——燃え続けて
     * いる残骸（{@link com.ashvehicles.vehicle.WreckEffects} 参照）と違い、爆発が置いていく煙は数秒で風景に
     * 戻る。
     */
    private void column() {
        float left = 1.0F - (float) (this.age - COLUMN_FROM) / (COLUMN_TO - COLUMN_FROM + 1);
        double spread = this.power * CRATER_SPREAD;

        this.level.addParticle(
                ModParticles.BLAST_SMOKE.get().of(Effects.SOOT, this.grain * COLUMN_SIZE * (0.5F + left)),
                this.x + this.random.nextGaussian() * spread,
                this.y + SITS_ABOVE,
                this.z + this.random.nextGaussian() * spread,
                0.0, COLUMN_RISE * left, 0.0);
    }

    /** クレーターの底で燃え残る物。1つずつ、細りながら。 */
    private void embers() {
        float left = 1.0F - (float) (this.age - EMBERS_FROM) / (EMBERS_TO - EMBERS_FROM + 1);
        double spread = this.power * CRATER_SPREAD;

        this.level.addParticle(
                ModParticles.FIRE.get().of(EMBER_FLAME, EMBER_SIZE * this.oversize * (0.5F + left)),
                this.x + this.random.nextGaussian() * spread,
                this.y + SITS_ABOVE,
                this.z + this.random.nextGaussian() * spread,
                0.0, 0.0, 0.0);
    }

    // ------------------------------------------------------------------
    // キノコ雲
    // ------------------------------------------------------------------

    /**
     * キノコ雲のこの tick 分。柱が立ち、上まで届いたら傘が開く。
     *
     * <p>2つの段階に分けているのは、それが実際の順序だからだ。まず熱い気体が柱になって上がり、上がりきって
     * 周囲と釣り合った所で行き場を失って横へ広がる。傘が柱と同時に描かれるキノコ雲は、キノコ雲ではなく
     * 「キノコ雲の絵」に見える。
     */
    private void mushroom() {
        int step = this.age - MUSHROOM_FROM;

        if (step < 0) {
            return;
        }

        if (step < this.climbs) {
            if (step % this.stemEvery == 0) {
                this.stem((float) step / this.climbs);
            }

            return;
        }

        int opening = step - this.climbs;

        if (opening <= this.caps && opening % this.capEvery == 0) {
            this.cap((float) opening / this.caps);
        }
    }

    /**
     * 柱のこの1枚。先端が今いる高さに、水平な円盤を1枚置く。
     *
     * <p>先端は減速しながら上がる。{@link ShockwaveParticle} の環と同じ曲線で、理由も同じ——最初に持っていた
     * 勢いを使い果たしながら進む物は、みなこう動く。置いた層はほとんど動かないので、先端が登った跡がそのまま
     * 積み上がって柱になる。上へ行くほど太くしてあるのは、噴き上がった物がそう広がるからだ。
     *
     * @param climbed 立ち上がりのどこまで来たか。0〜1。1は傘の高さ
     */
    private void stem(float climbed) {
        double top = this.capAt * eased(climbed);
        double wide = this.capWide * STEM_SLIM * (1.0 + climbed * STEM_FLARES);

        this.level.addParticle(this.layerOf.of(Effects.SOOT, (float) wide),
                this.x, this.y + top, this.z, 0.0, STEM_RISE, 0.0);

        if (this.nuclear) {
            this.shelves(top);
        }
    }

    /**
     * 凝結の棚。柱の先端が決められた高さを越えるたびに、1段ずつ置いていく。
     *
     * <p>高収量の核実験の映像で最も目を引く形がこれだ。上がっていく柱の周りに水平な白い皿が何段も並ぶ——
     * 気温と湿度が変わる高度ごとに水蒸気が凝結してできる棚で、下ほど広く、上へ行くほど小さい。柱と傘だけの
     * キノコ雲は「大きな煙」に見えるが、この段があると別の現象に見える。
     *
     * <p>柱の一部ではなく、柱が<em>通り過ぎた</em>高度に残る物だ。だから置くのは1度きりで、置いた後は柱と
     * 一緒に上がっていかない。煤ではなく水なので白く、燃えている物ではないので光らない。
     */
    private void shelves(double top) {
        while (this.shelvesLaid < SHELF_AT.length
                && top >= this.capAt * SHELF_AT[this.shelvesLaid]) {
            double high = this.capAt * SHELF_AT[this.shelvesLaid];
            double wide = this.capWide * SHELF_WIDE[this.shelvesLaid];

            for (int plate = 0; plate < SHELF_PLATES; plate++) {
                // 2枚目を少し上に、少し小さく。皿1枚では紙に見えるが、ずらして重ねると厚みが出る。
                this.level.addParticle(
                        ModParticles.CLOUD_LAYER.get().of(Effects.DUST,
                                (float) (wide * (1.0 - plate * SHELF_THICK * 2.0))),
                        this.x,
                        this.y + high + plate * SHELF_THICK * this.capWide,
                        this.z,
                        0.0, SHELF_DRIFT, 0.0);
            }

            this.shelvesLaid++;
        }
    }

    /**
     * 傘のこの1枚。柱の頭に、断面上の高さを選んで円盤を1枚置く。
     *
     * <p>傘は球の一部として組んでいる。最も広い高さを0として、上は丸く閉じ、下は {@link #CAP_UNDER} まで
     * すぼまりながら続く。この<em>下側</em>があることがキノコ雲をキノコ雲にしている——上だけなら丸い雲だが、
     * 下にもすぼまる層が続くと、傘が柱にかぶさっている形になる。実映像でスカートと呼ばれる部分だ。
     *
     * <p>高さ方向は潰してある。球のままだと頭でっかちで、実際の傘はもっと平たい。
     *
     * @param opened 傘がどこまで開いたか。0〜1
     */
    private void cap(float opened) {
        double spread = this.capWide * eased(opened);
        double high = -CAP_UNDER + (1.0 + CAP_UNDER) * this.random.nextDouble();
        // 球の断面。0で最も広く、両端でゼロに閉じる。
        double profile = Math.sqrt(Math.max(0.0, 1.0 - high * high));
        double lift = this.capAt + high * this.capWide * CAP_SQUASH;

        this.level.addParticle(this.layerOf.of(Effects.SOOT, (float) (spread * profile)),
                this.x, this.y + lift, this.z, 0.0, CAP_LIFT, 0.0);

        if (!this.nuclear) {
            return;
        }

        // 層だけだと、傘が板の重なりに見える角度が残る。内側にひと掴みだけ煙を置いて厚みを与える。
        this.level.addParticle(
                ModParticles.NUCLEAR_CLOUD.get().of(Effects.SOOT, (float) (this.capWide * CAP_CORE_GRAIN)),
                this.x + this.random.nextGaussian() * spread * profile * 0.3,
                this.y + lift,
                this.z + this.random.nextGaussian() * spread * profile * 0.3,
                0.0, CAP_LIFT, 0.0);
    }

    /**
     * 核だけが持つもの。凝結の殻、浮き上がる火球、地表を這う雲。
     *
     * <p>3つとも「大きくする」ではなく「別の物を足す」形で入れてある。規模で核を表現する道が無い以上
     * （{@link Effects#NUCLEAR} 参照）、区別が付くとすればここだからだ。
     */
    private void nuclearSigns() {
        if (this.age >= VEIL_FROM && this.age <= VEIL_TO) {
            this.veil();
        }

        if (this.age >= LOFTS_FROM && this.age <= LOFTS_TO) {
            this.loft();
        }

        // ベースサージは柱が登っている間ずっと外へ出続ける。柱が吸い上げきれなかった分なので、
        // 柱が終われば供給も終わる。
        if (this.age >= SURGE_FROM && this.age <= MUSHROOM_FROM + this.climbs
                && this.age % SURGE_EVERY == 0) {
            this.surge();
        }
    }

    /**
     * ウィルソン雲。火球を包んで広がり、圧が戻ると消える白い球殻。
     *
     * <p>ここだけは球で撒く。{@link #outward} の潰した分布ではなく {@link #bearing} の真球——この殻を作るのは
     * 地面ではなく空気そのもので、地面に止められる理由が無いからだ。使うのは翼のベイパーと同じパーティクル。
     * 薄く、すぐ引き裂かれ、留まらない。まさにそれが要る。
     */
    private void veil() {
        float grown = (float) (this.age - VEIL_FROM + 1) / (VEIL_TO - VEIL_FROM + 1);
        double core = FIREBALL_REACH * this.power;
        double shell = core * VEIL_BEYOND * eased(grown);
        float size = (float) (core * VEIL_GRAIN);

        for (int i = 0; i < VEIL_PUFFS; i++) {
            Vec3 out = this.bearing();

            this.level.addParticle(ModParticles.VAPOUR.get().of(Effects.DUST, size),
                    this.x + out.x * shell, this.y + out.y * shell + SITS_ABOVE, this.z + out.z * shell,
                    0.0, 0.0, 0.0);
        }
    }

    /**
     * 浮き上がる火球。減速しながら上がり、上がりながら冷えて細る。
     *
     * <p>柱の根元がここから続く。煙が地面から立つのではなく、火の玉が引きずった跡が柱になる——それが核の
     * 立ち上がりの形だ。
     */
    private void loft() {
        float lifted = (float) (this.age - LOFTS_FROM + 1) / (LOFTS_TO - LOFTS_FROM + 1);
        double high = this.capAt * LOFTS_TO_FRACTION * eased(lifted);
        double wide = FIREBALL_REACH * this.power * (1.0 - lifted * 0.4);
        float cooling = 1.0F - lifted * 0.5F;

        for (int i = 0; i < LOFT_PUFFS; i++) {
            Vec3 out = this.outward();

            this.level.addParticle(
                    ModParticles.BLAST.get().of(this.colour, this.grain * FIRE_SIZE * cooling),
                    this.x + out.x * wide,
                    this.y + high + out.y * wide + SITS_ABOVE,
                    this.z + out.z * wide,
                    0.0, LOFT_RISE, 0.0);
        }
    }

    /** 地表を這って広がるベースサージ。柱より低く、柱より遠くへ。 */
    private void surge() {
        float spread = (float) (this.age - SURGE_FROM) / Math.max(this.climbs, 1);
        double out = this.capWide * SURGE_BEYOND * eased(Math.min(spread, 1.0F));

        for (int i = 0; i < SURGE_PUFFS; i++) {
            double turn = this.random.nextDouble() * Mth.TWO_PI;
            // 縁を揃えない。這う雲の輪郭が円だと、爆発ではなく描かれた円に見える。
            double reach = out * (0.55 + this.random.nextDouble() * 0.45);

            // 灼熱するのは柱と傘の中身であって、地面から剥がれた土埃ではない。だからここだけ光らない方の
            // 雲を使う。寿命も短いので、キノコ雲が立ち切る前にこちらは消える——実際そういう順序で消える。
            this.level.addParticle(
                    ModParticles.CLOUD.get().of(Effects.DUST, (float) (this.capWide * SURGE_GRAIN)),
                    this.x + Math.cos(turn) * reach,
                    this.y + this.random.nextDouble() * this.capWide * SURGE_LOW,
                    this.z + Math.sin(turn) * reach,
                    Math.cos(turn) * SURGE_CREEP, 0.0, Math.sin(turn) * SURGE_CREEP);
        }
    }

    /** 勢いを使い果たしながら進む物の進み方。最初が速く、常に減速する。 */
    private static double eased(float lived) {
        double left = 1.0 - lived;

        return 1.0 - left * left;
    }

    // ------------------------------------------------------------------
    // 共通
    // ------------------------------------------------------------------

    /** 球面上の一様な向き。 */
    private Vec3 bearing() {
        double up = this.random.nextDouble() * 2.0 - 1.0;
        double ring = Math.sqrt(Math.max(1.0 - up * up, 0.0));
        double turn = this.random.nextDouble() * Mth.TWO_PI;

        return new Vec3(ring * Math.cos(turn), up, ring * Math.sin(turn));
    }

    /** 同じものを、上下に {@link #FLATTEN} だけ潰して。地表で起きた爆発が撒く向き。 */
    private Vec3 outward() {
        Vec3 any = this.bearing();

        return new Vec3(any.x, any.y * FLATTEN, any.z);
    }

    /** 外へ、しかし上へ寄せて投げる速度。放物線を描かせるため。 */
    private Vec3 lofted(double speed, double loft) {
        double turn = this.random.nextDouble() * Mth.TWO_PI;
        double out = 0.4 + this.random.nextDouble() * 0.6;

        return new Vec3(Math.cos(turn) * out * speed,
                (loft + this.random.nextDouble() * loft) * speed,
                Math.sin(turn) * out * speed);
    }

    /**
     * 粒1つの大きさを決める規模。
     *
     * <p>{@link Effects#BIGGEST} までは爆発力そのもの——つまり兵装が出す全ての爆発は、この関数が無かった頃と
     * 1ピクセルも変わらない。そこから上だけ {@link #OVERSIZE_GROWTH} で潰す。半径は比例のままなので、規模を
     * 上げると火球は「同じくらいの粒がもっと広く散る」方向に育つ。それが正しい向きだ——現実の火球も、大きくなる
     * につれて中身が粗くなるのではなく、より細かい渦の集まりに見える。
     */
    private static float grain(float power) {
        return Math.min(power, Effects.BIGGEST) * oversize(power);
    }

    /** 通常規模を超えた分の倍率。{@link Effects#BIGGEST} 以下では 1。 */
    private static float oversize(float power) {
        return power <= Effects.BIGGEST
                ? 1.0F
                : (float) Math.pow(power / Effects.BIGGEST, OVERSIZE_GROWTH);
    }

    /** 爆心の真下、少し探した所にある物の色。無ければ {@link #AIRBURST}。 */
    private static int groundUnder(ClientLevel level, double x, double y, double z) {
        BlockPos at = BlockPos.containing(x, y, z);

        if (!level.hasChunkAt(at)) {
            return AIRBURST;
        }

        for (int down = 0; down <= GROUND_WITHIN; down++) {
            BlockPos probe = at.below(down);

            if (!level.getBlockState(probe).isAir()) {
                return level.getBlockState(probe).getMapColor(level, probe).col;
            }
        }

        return AIRBURST;
    }

    /** 何も描かない。ここにあるのは進行だけで、見える物は全部この下から出る。 */
    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.NO_RENDER;
    }

    public static ParticleProvider<TintedParticleOption> provider(SpriteSet sprites) {
        return (options, level, x, y, z, xd, yd, zd) ->
                new BlastStageParticle(level, x, y, z, options, sprites);
    }
}
