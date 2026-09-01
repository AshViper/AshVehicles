package com.ashvehicles.entity;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * 全員の chunk の縁を越えて飛んだ機体を、空に留め続ける。
 *
 * <p>これが無いと、その先の長距離処理には材料が何も無い。Minecraft が chunk をロードしておくのはプレイヤー
 * の周りだけで、アンロードされた chunk の中のエンティティは存在しない。飛ぶのをやめ、追跡機構が報告する物
 * もクライアントが描く物も無くなる。だから機体は自分がいる chunk のチケットを持ち、進みながら手渡していく。
 * そうすれば誰からどれだけ離れても飛び続け、見え続ける。
 *
 * <p>1機が持つのは1本の回廊で、しかも実際にどこかへ向かっている間だけ。駐機すれば手放すし、放棄された機体
 * もエンジンが落ちて接地すればすぐ手放す。だからコストは「これまでに作られた機体数」ではなく「今空にいる
 * 機体数」で頭打ちになる。
 *
 * <p><b>2種類の要求。</b> 保持チケットの回廊は約束だ——機体の下の地面と、その進路の数秒先は、何があろうと
 * ロードされ tick している。ただし保持チケットの取得は同期的で、NeoForge は呼び出しが返る前に指定 chunk を
 * ロードする。誰も訪れたことのない土地ではそれは「tick スレッド上で走るワールド生成の全部」であり、戦闘機
 * の速度では「1tickに1回サーバーが時計を止めて風景を建てる」ことになる。だから新しい地面はまず安い方法で
 * 要求する——飛行経路のかなり先へ置く素の region チケットで、chunk を<em>予約</em>するだけ。生成は生成器
 * が自分のスレッドで行う。回廊がその地面を保持したくなる頃には既に存在しており、保持はただの帳簿処理に
 * なる。先読みチケットは自前のタイムアウトを持つので、機体が要求した後に旋回して離れた地面は勝手に解放
 * される。追跡する物は無く、必要も無い。
 *
 * <p>よって、まだ生成されていない回廊 chunk は取らない——先読みが既に作っているので、確保は1〜2tick後に
 * 無料で成立する。例外は2つだけ。機体が今いる chunk と、次の tick で入る chunk は代償を問わず取る。この
 * 2つが「飛行機」と「文鎮」の分かれ目だから。
 *
 * <p><b>チケットが再起動を生き延びるのは意図的。</b> 飛行を再起動の向こうへ運ぶのがそれだ。保存された
 * チケットが回廊を戻し、回廊が chunk を戻し、chunk が機体を戻し、機体は飛び続ける。機体は保持している集合
 * を自分の NBT に保存する（{@code AircraftEntity} 参照）ので、ディスクから戻った機体はどのチケットが自分の
 * 物かを正確に知り、今も欲しい物と突き合わせる。それが「飛行より長生きするチケット」を防いでいる。同じ理由
 * で、単にアンロードされただけの機体もチケットを保持し続ける。そこで手放すのはデッドロックだった。チケット
 * こそが、その機体を再びロードする唯一の手段だったから。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class AircraftChunkLoader {
    /** 保持する確保が飛行経路のどこまで届くか。飛行 tick 数で。 */
    private static final double LEAD_TICKS = 30.0;
    /** 経路をサンプリングする間隔（ブロック）。半 chunk なので経路上の chunk を飛ばさない。 */
    private static final double SAMPLE = 8.0;
    /**
     * 1機が保持する chunk 数の上限。飛行経路に沿った1本の線なので、24個並べても「領域」ではなく「回廊」
     * になる。しかも機体が止まった瞬間に全部手放される。
     *
     * <p>12だった。{@link #LEAD_TICKS} は速度に比例して伸びると書いてあるのに、この上限が低すぎて
     * 実際には伸びていなかった——192mは毎秒6.4ブロックで頭打ちになる値で、この MOD で一番遅い機体でも
     * 巡航でそこを越える。F-22 の最大速度（毎tick 37.5ブロック）では回廊の予告は0.26秒しかなく、機体は
     * 自分の回廊より速く飛んでいた。24なら同じ速度で0.5秒、時速1000km級で1.4秒になる。
     *
     * <p>倍にした代償は、飛んでいる1機あたりのロード済み chunk 数がそのまま倍になること。tick あたりの
     * 仕事は変わらない——毎tick増えるのは先端の1個だけで、増やしたのは「いつロードするか」であって
     * 「何回ロードするか」ではない。
     */
    private static final int MOST_CHUNKS = 24;

    /** これ未満なら残骸は落ち終わってただ横たわっている、という速度の二乗。 */
    private static final double STOPPED = 1.0E-4;

    /**
     * 先読みが保持回廊よりどれだけ先まで届くか。飛行 tick 数と chunk 数の両方で。
     *
     * <p>6秒・3km。先端で要求した地面が、回廊が保持したくなるまでに数秒の生成時間を得られる距離であり、
     * 新規 chunk が実際に必要とする時間でもある。tick だけでなく chunk 数でも頭打ちにしてあるので、速い
     * ジェットは「マップ全部」ではなく「より長い予告」を要求することになる。
     *
     * <p>5秒・750mだった。その距離は、機体が速いほど<em>短い</em>予告になる——750mは毎秒2.7秒分の
     * 予告に見えるが、それは毎tick 7.5ブロックの機体の話で、F-22 の全速では1.0秒しかない。生成器が
     * 手付かずの地面を1秒で数十 chunk 建てることはないので、そこで機体は自分が要求した地面に追い付いて
     * いた。3kmなら全速でも2.7秒、時速1800km以下では上限に当たらず、常に6秒の予告になる。
     *
     * <p>先読みチケットは非同期・非tick・自動失効なので、伸ばした分の代償は生成器の仕事だけだ。しかも
     * その仕事は捨てられない——線の上の地面は、機体が旋回しない限り実際に飛ぶ場所である。
     */
    private static final double PREFETCH_TICKS = 120.0;
    private static final int PREFETCH_CHUNKS = 192;

    /**
     * 各機体が先読みを向け直す間隔（tick）。チケットの寿命はこの30倍なので、消える遥か前に更新される。
     * これより頻繁に要求しても、既にあるチケットを付け直すだけ。
     */
    private static final int PREFETCH_EVERY = 4;

    /**
     * 先読みチケットが自分を生かしておく時間（tick）。更新間隔を数回分またげるだけ長く、急旋回で捨てられた
     * 回廊が「どこでもないロード済みの帯」として居座らず数秒で消えるだけ短い。
     */
    private static final int PREFETCH_TIMEOUT = 300;

    /**
     * 先読みチケット。完全生成・非tick・自動解放。
     *
     * <p>NeoForge の強制ロードではなくバニラの region チケットを使う理由は3つあり、実のところ1つだ。非同期
     * である——追加すれば chunk を予約して返り、生成は生成器が自分のスレッドで行う。自分でタイムアウトする
     * ——機体がこれまでに行った全旋回の先の空が1時間後もロードされたままにならない。そして決して保存されない
     * ——再起動はきれいな状態から始まる。保持回廊が持たない性質ばかりで、だから2つを併用する。
     */
    private static final TicketType<ChunkPos> PREFETCH = TicketType.create(
            AshVehicles.MODID + ":aircraft_prefetch",
            Comparator.comparingLong(ChunkPos::toLong), PREFETCH_TIMEOUT);

    /** {@link #update} が作業に使う集合。使い回すが、埋めた呼び出しを越えて保持することは無い。 */
    private static final Set<ChunkPos> SCRATCH = new LinkedHashSet<>();

    /**
     * 検証コールバックを意図的に持たない。以前はあり、「まだ飛んでいる物は次の tick でまた要求する」という
     * 理屈でワールド読み込み時に保存済みチケットを全部落としていた。アンロードされた機体に次の tick は無い。
     * 保存されたチケットこそがその機体を再ロードする唯一の手段であり、それを落とすことで、停止時に空中に
     * いた全機体がアンロードされた chunk の中で永久に凍り付いた。今はチケットを残し、それ自身がロードする
     * 持ち主の機体が、自分の NBT と突き合わせて要らなくなった物を手放す。
     */
    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "aircraft"));

    @SubscribeEvent
    public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * 機体のチケットを、これから上空に来る地面へ移す。あるいはロードしておく理由が無ければ解放する。毎tick
     * 呼んで安い。chunk 集合が変わった時しか何もしないので。
     *
     * <p>呼ぶのは機体自身の tick か、サーバーがパイロットの移動報告を処理する場所から。どちらも素のサーバー
     * スレッドのコードだ。決して呼んではいけないのは chunk システム自身の更新ループの中（エンティティの
     * ロード／アンロードコールバックはそこから飛ぶ）。チケットの取得はその場で chunk をロードし、それがその
     * ループを走らせるので、反復の途中で再入するとサーバーが落ちる。コールバックからは {@link #release} を
     * 使うこと。
     *
     * @param held 機体が現在保持している chunk
     * @return この呼び出し後に保持している chunk。次の tick で渡し返す
     */
    public static Set<ChunkPos> update(AircraftEntity aircraft, Set<ChunkPos> held) {
        if (!(aircraft.level() instanceof ServerLevel level)) {
            return held;
        }

        // 1tick に1本。回廊は1tick に1度引き直せば足りるのに、呼ばれる回数はそうではない——パケットは
        // 固まって届き、その1つ1つが移動として処理され、その1つ1つがここへ来る。詰まった直後には数十本
        // 分が同じ tick に着く。
        //
        // 「答えが同じなら equals() で短絡するので安い」は、答えが同じなら正しい。だが固まって届いた報告
        // は毎回ちがう位置を告げるので、答えは毎回ちがう。数十回ぶんの確保と解放を1tick で行うことになり、
        // その1回1回が chunk に触る。つまり詰まるほど1tick の仕事が増える——自分で自分を育てる種類の停止で
        // あり、まさに詰まった直後に一番効く。
        if (!aircraft.claimCorridorTick()) {
            return held;
        }

        boolean flying = shouldStayLoaded(aircraft);

        // 回廊の後ではなく前に要求する。この tick が知り得た最も早い時点で、新しい地面のことを生成器へ
        // 伝えるため。
        if (flying) {
            prefetch(aircraft, level);
        }

        // 保持・再利用する集合へ書き出す。答えはほぼ常に前 tick と同じで、捨てるために作る集合がこの呼び
        // 出しのコストの全部だから。コピーを作るのは確保が実際に動いたと分かった後、しかもチケットに1つも
        // 触れる前。だから chunk システムの内側から辿り着く物がこれを作りかけの状態で見ることは無い。
        // サーバースレッド専用で、機体の tick はそこで走る。
        Set<ChunkPos> scratch = SCRATCH;
        scratch.clear();

        if (flying) {
            ahead(aircraft, scratch);

            // まだ生成されていない地面はこの tick では取らない。取れば tick スレッド上で生成することに
            // なるし、上の先読みが既に別スレッドで作っている。確保は chunk が存在して保持が帳簿処理になった
            // 後の tick で成立すればよい。
            //
            // 代償を問わない例外は機体の下の1個だけになった。それが無ければ機体は存在しなくなるので、
            // ここは今も無条件で取る。
            //
            // 次の1歩の chunk は、パイロットが飛ばしている間は取らない。最高速度の機体にとって「次の1歩」
            // は2〜3 chunk 先で、そのどれもがまだ無い土地では、毎tick 数十ミリ秒の生成を tick スレッドへ
            // 積むことになる。サーバーが遅れる、報告が溜まる、溜まった報告が処理される、その1本1本がまた
            // 新しい地面を要求する——止まるまで加速する輪だ。「最高速度で未踏の土地へ入ると機体が空中で
            // 止まる」はこれで説明が付く。
            //
            // 飛ばしている者がいれば、その先読みは要らない。機体を運んでいるのはサーバーの tick ではなく
            // 操縦側の報告で、報告は chunk がロードされているかに関わらず届き続ける
            // （{@code PilotChunkGateMixin} 参照）。先の地面は先読みが3km 手前から別スレッドで作っており、
            // 機体が着く頃には存在している。無人で飛んでいる機体——投棄された機体、無人機、落下中の残骸
            // ——は自分の tick でしか動かないので、そちらには今まで通り次の1歩も渡す。
            boolean flown = aircraft.getAviator() != null;
            Vec3 step = aircraft.position().add(aircraft.getVelocity());
            ChunkPos own = aircraft.chunkPosition();
            ChunkPos next = new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(step.x)),
                    SectionPos.blockToSectionCoord(Mth.floor(step.z)));

            scratch.removeIf(pos -> !pos.equals(own) && !held.contains(pos)
                    && !level.getChunkSource().hasChunk(pos.x, pos.z)
                    && (flown || !pos.equals(next)));
        }

        if (scratch.equals(held)) {
            scratch.clear();

            return held;
        }

        Set<ChunkPos> wanted = scratch.isEmpty() ? Set.of() : Set.copyOf(scratch);
        scratch.clear();

        for (ChunkPos pos : held) {
            if (!wanted.contains(pos)) {
                CONTROLLER.forceChunk(level, aircraft, pos.x, pos.z, false, true);
            }
        }

        for (ChunkPos pos : wanted) {
            if (!held.contains(pos)) {
                CONTROLLER.forceChunk(level, aircraft, pos.x, pos.z, true, true);
            }
        }

        return wanted;
    }

    /**
     * 機体が今いる地面と、これから来る地面。
     *
     * <p>chunk 1つで足りるのはゆっくり流している機体だけで、そうでない機体にはまるで足りない。チケットは
     * chunk をそこへ置くのではなく要求する物で、chunk システムはディスクから読むか無から作るかしなければ
     * ならず、それは速い機体が今いる1 chunk を横切る2tickよりずっと長くかかる。追い付けない時にパイロット
     * が見るのは、自分の周りに到着してくる斜面だ。
     *
     * <p>だから確保は機体の下に留まらず飛行経路に沿って走る。不意打ちではなく予告になるだけ先まで、経路上の
     * chunk を飛ばさないだけ細かく、そして上限付きで。速度とともに無制限に伸びる確保は「サーバーに世界を
     * 生成させる方法」だから。
     *
     * <p>静止していればコストは無い。駐機中の機体がサンプリングするのは1 chunk——駐機している chunk だけ。
     */
    private static void ahead(AircraftEntity aircraft, Set<ChunkPos> chunks) {
        chunks.add(aircraft.chunkPosition());

        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        if (speed < 1.0E-3) {
            return;
        }

        // 半 chunk ずつ進む。飛行経路がグリッドに対してどう横たわっていても経路上の物を取りこぼさない
        // ように。点の列ではなく2つの数値として歩くのは、空にいる全機体が毎tick十数回サンプリングするから。
        // 1サンプルごとに Vec3 と BlockPos を作るのは、整数2つ分の答えに対してゴミが多すぎる。
        double stepX = velocity.x * SAMPLE / speed;
        double stepZ = velocity.z * SAMPLE / speed;
        double x = aircraft.getX();
        double z = aircraft.getZ();
        double samples = Math.min(speed * LEAD_TICKS, MOST_CHUNKS * 16.0) / SAMPLE;

        for (int i = 0; i < samples && chunks.size() < MOST_CHUNKS; i++) {
            x += stepX;
            z += stepZ;
            chunks.add(new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(x)),
                    SectionPos.blockToSectionCoord(Mth.floor(z))));
        }
    }

    /**
     * 飛行経路の数秒先の地面を、静かにバックグラウンドで生成器へ要求する。回廊がそのどれかを保持したくなる
     * 頃には生成が済んでいるように。誰も訪れたことのない土地を戦闘機が全速で横断できるのはこれのおかげだ。
     * 世界はサーバーのスレッドで機体の下に建てられるのではなく、生成器のスレッドで機体の前方に建てられる。
     *
     * <p>毎tickではなく数tickごとに向け直す——チケットの付け直しは時刻の更新にしかならないし、4tickで線は
     * ほとんど動かない。エンティティ ID でずらしてあるので、編隊は全機が同じ tick に要求せず tick を分け
     * 合う。
     */
    private static void prefetch(AircraftEntity aircraft, ServerLevel level) {
        if ((level.getGameTime() + aircraft.getId()) % PREFETCH_EVERY != 0) {
            return;
        }

        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        if (speed < 1.0E-3) {
            return;
        }

        double stepX = velocity.x * SAMPLE / speed;
        double stepZ = velocity.z * SAMPLE / speed;
        double x = aircraft.getX();
        double z = aircraft.getZ();
        double samples = Math.min(speed * PREFETCH_TICKS, PREFETCH_CHUNKS * 16.0) / SAMPLE;
        // 直前に要求した chunk を、位置ではなく座標2つで覚える。線が3kmになってサンプルは数百に増えた
        // ——その大半は同じ chunk の2度目なので、捨てるためだけの ChunkPos をそこで作らない。回廊の
        // 歩き方と同じ理由だ。
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;

        for (int i = 0; i < samples; i++) {
            x += stepX;
            z += stepZ;

            int chunkX = SectionPos.blockToSectionCoord(Mth.floor(x));
            int chunkZ = SectionPos.blockToSectionCoord(Mth.floor(z));

            if (chunkX == lastX && chunkZ == lastZ) {
                continue;
            }

            lastX = chunkX;
            lastZ = chunkZ;

            // 距離0。指定 chunk を最後まで生成し、周囲は昇格させず、tick もさせない。tick は機体が到達
            // した時に回廊自身の確保が連れてくる。先読みが買っているのは「生成が既に済んでいること」だけ。
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);

            level.getChunkSource().addRegionTicket(PREFETCH, pos, 0, pos);
        }
    }

    /**
     * 機体が保持している chunk を全部手放し、新たに要求しない。サーバースレッドのどこからでも安全に呼べる。
     * chunk システム自身のコールバックの中からも。チケットを落とすのはレベル変更を予約するだけで、何も
     * ロードしない。
     *
     * @param held 機体が現在保持している chunk
     * @return 空集合。呼び出し後に保持している物
     */
    public static Set<ChunkPos> release(AircraftEntity aircraft, Set<ChunkPos> held) {
        if (!held.isEmpty() && aircraft.level() instanceof ServerLevel level) {
            for (ChunkPos pos : held) {
                CONTROLLER.forceChunk(level, aircraft, pos.x, pos.z, false, true);
            }
        }

        return Set.of();
    }

    /** 飛んでいるか、少なくとも飛ぼうとしているか。駐機中の機体は他の物と一緒にアンロードしてよい。 */
    private static boolean shouldStayLoaded(AircraftEntity aircraft) {
        if (aircraft.isRemoved()) {
            return false;
        }

        // 全損機が chunk を開いたまま保つのは落下中だけで、動きが止まった瞬間に手放す。普通の判定を使う
        // と多くの場合まったく手放さない。斜面に引っ掛かった残骸や、車輪ではなく翼の上に横たわった残骸は
        // 決して「接地している」と報告せず、開けた土地で撃墜された機体1機がワールドの寿命いっぱい chunk を
        // 開き続けることになる。
        if (aircraft.isWrecked()) {
            return aircraft.getVelocity().lengthSqr() > STOPPED;
        }

        return !aircraft.onGround() || aircraft.getThrottle() > 0.0F;
    }

    private AircraftChunkLoader() {
    }
}
