package com.ashvehicles.entity;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
     * 1機が保持する chunk 数の天井。実際に取る数は速度から決まり（{@link #corridorChunks} 参照）、
     * これはその計算が暴走しないための上限にすぎない。
     *
     * <p><b>守りたいのは距離ではなく時間だ。</b> 回廊が答えるべき問いは「機体は何ブロック先まで地面を
     * 持っているか」ではなく「あと何秒ぶんの地面を持っているか」で、後者を一定にするには前者が速度に
     * 比例していなければならない。{@link #LEAD_TICKS} はそのつもりで書かれているのに、長らくこの定数が
     * 固定の上限として先に効いていた——24個は384ブロックで、F-22 の最大速度では<em>0.5秒</em>。速い機体
     * ほど予告が短くなるという、意図と正反対の性質になっていた。
     *
     * <p>80 は {@link #LEAD_TICKS} ぶんを最高速の機体でも頭打ちにしない値。遅い機体はこれより遥かに
     * 少なくしか取らないので、天井であって既定値ではない。
     *
     * <p><b>伸ばしても生成の量は増えない。</b> 回廊は飛行経路に沿った幅1の線で、毎tick先端に増えるのは
     * 1個だけだ。速度が決めているのは毎秒いくつ新しい chunk に触るかで、この数が決めているのは<em>それを
     * どれだけ手前で頼むか</em>。増えるのは同時に保持している数であって、生成される総数ではない。
     */
    private static final int MOST_CHUNKS = 80;

    /**
     * 止まりかけの機体でも回廊が細りきらない下限（chunk 数）。
     *
     * <p>上限が速度で決まる以上、下限も要る。ホバリング中のヘリや着陸滑走中の機体は速度がほぼ0なので、
     * 素直に掛け算すると回廊が自分の1個まで縮む。そこは縮めてよい場所ではない——{@code getVelocity} は
     * 遅れて届いた地形に止められた tick に落ち込むことがあり（{@code AircraftEntity.deniedByLateWorld}
     * 参照）、その1tickで回廊を捨てると、次のtickにまた取り直すことになる。
     */
    private static final int LEAST_CHUNKS = 8;
    /**
     * 機体を囲む、何があっても確保する chunk の半径。
     *
     * <p>回廊の他の部分は「もう生成されている物しか取らない」という規律で動いている。生成を tick スレッド
     * へ載せないためで、それは正しい——先の地面は数tick遅れて届いてよく、着く頃に在れば足りる。だが機体の
     * すぐ周りは別だ。そこは当たり判定が問われる場所であり、着陸する場所であり、地面効果が働く場所であり、
     * そして {@code VehicleProjectile.simulated} が「弾が当たってよい場所」を決めるのに読む場所でもある
     * （あれが引くのは entity ticking で、それはチケット水準の話だ）。ここが1個だけだと、機体は自分の真下
     * を除いて何も無い空間を飛ぶ瞬間を持つ。
     *
     * <p>だからこの範囲だけは、まだ生成されていなくても要求する。取得は
     * {@link CorridorClaim#quietly} を通るので tick スレッドは止まらない——チケットが置かれ、生成器が
     * 自分のスレッドで作り、出来た時点で入る。約束しているのは「必ずチケットが在ること」であって
     * 「今すぐ地面が在ること」ではない。生成そのものを速くする手立ては無い
     * （{@code WeaponTicker} と同じく、そこは生成器の都合だ）。
     *
     * <p>3 で 7x7 = 49 chunk。プレイヤー1人がシミュレーション距離で開けている範囲より小さい。
     */
    private static final int PROMISED = 3;

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
     * 扉を開く長さ（tick）。この先までは「直進する」ではなく「行き得る先全部」を頒む。
     *
     * <p>30 は 1.5 秒。最高速の機体で 12 chunk 先までで、その間にフルに引けば機首は 30度ほど振れる
     * ——旋回の出口がまだ扉の中にある長さだ。これを伸ばすと、幅は距離の2乗で増える。
     */
    /**
     * 完成した地面を頼む長さ（tick）。ここまでは距離0、この先は素地だけ。
     *
     * <p>回廊より少し先まで要る。回廊は「もう在る chunk」しか確保しないので（生成を tick スレッドへ
     * 載せないため）、回廊が掴める完成品が常に前方に無ければ、機体は自分の真下1個ずつしか持てなくなる。
     */
    private static final double NEAR_TICKS = 60.0;
    /**
     * 奥の帯を頼む距離。負の値。
     *
     * <p>{@code DistanceManager.addRegionTicket} はチケット水準を {@code 33 - distance} で決めており、
     * 下限の検査が無い。だから負の距離は33より上の水準になり、{@code ChunkLevel.generationStatus} は
     * それを FULL <em>手前</em>の生成段階へ写す。つまりこの帯の chunk は、重い所——ノイズ、地表、洞窟、
     * 地物——まで生成器のスレッドで作られながら、{@code LevelChunk} には昇格しない。
     *
     * <p>買っているのは3つ。メインスレッドでの FULL 昇格が要らないこと。{@code ChunkEvent.Load} が
     * 走らないこと。そして<b>保存されない</b>こと——{@code ChunkMap.saveChunkIfNeeded} は
     * {@code LevelChunk} か {@code ImposterProtoChunk} でなければその場で降りるので、通り過ぎただけの
     * 土地がセーブに残らない。誰も見ない地形を保存しないという、この MOD が一度払った授業料と同じ判断だ。
     *
     * <p>機体が実際に着けば手前の帯が同じ chunk を距離0で頼み直し、残りの数段だけを踏んで完成する。
     */
    private static final int DEEP_DISTANCE = -1;
    /**
     * 扇を開く長さ（tick）。この先までは「直進する」ではなく「行き得る先全部」を頼む。
     *
     * <p>30 は1.5秒。その間にフルに引けば機首は30度ほど振れる——旋回の出口がまだ扇の中にある長さだ。
     * 伸ばせば幅は距離の2乗で増える。
     */
    private static final double FAN_TICKS = 30.0;
    /** 扉の半幅の上限（ブロック）。旋回率がこれを超えさせても、生成器にそれを育てる余力は無い。 */
    private static final double FAN_WIDEST = 48.0;

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

        // 奥の帯が実際にどの生成段階で止まるかを、起動時に1度だけ言わせる。
        //
        // バニラは負のチケット距離を自分では使わないので、ここは踏まれていない経路だ。33より上の水準が
        // FULL 手前の段階へ写ることはコードから読み取れる（DEEP_DISTANCE 参照）が、それが「地形として
        // 意味のある所まで作る段階」かどうかは生成ピラミッドの形次第で、ピラミッドはデータパックが
        // 触れる物ではないにせよバージョンで変わる。null や EMPTY が出たら、奥の帯は何も作っていない。
        AshVehicles.LOGGER.info("[chunk] 先読みの奥の帯は水準 {}（生成段階 {}）で止まる",
                33 - DEEP_DISTANCE, ChunkLevel.generationStatus(33 - DEEP_DISTANCE));
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
            aircraft.setPrefetched(prefetch(aircraft, level, aircraft.getPrefetched()));
        }

        // 保持・再利用する集合へ書き出す。答えはほぼ常に前 tick と同じで、捨てるために作る集合がこの呼び
        // 出しのコストの全部だから。コピーを作るのは確保が実際に動いたと分かった後、しかもチケットに1つも
        // 触れる前。だから chunk システムの内側から辿り着く物がこれを作りかけの状態で見ることは無い。
        // サーバースレッド専用で、機体の tick はそこで走る。
        Set<ChunkPos> scratch = SCRATCH;
        scratch.clear();

        // <b>人が乗っている限り、その足元は手放さない。</b>
        //
        // 上の shouldStayLoaded は「この機体はロードを続ける理由があるか」を機体の都合で答える——飛んで
        // いるか、エンジンが掛かっているか。乗員の都合は数えていなかった。だからスロットルを絞って接地
        // した瞬間、あるいは遅れて届いた地形に止められて速度の申告が落ちた瞬間、回廊は自分の1個ごと全部
        // 解放される。そこに座っているのはプレイヤーで、プレイヤーの足元の地面が消えてよい瞬間は無い。
        //
        // 乗員がいる時だけ余分に持つので、駐機場に並んだ無人機がワールドの寿命いっぱい chunk を開き続ける
        // ことにはならない。降りればこの行は効かなくなり、shouldStayLoaded が今まで通り片付ける。
        if (carriesSomeone(aircraft)) {
            around(aircraft, scratch);
        }

        if (flying) {
            ahead(aircraft, scratch);
            // 回廊の後に足す。ahead は自分の上限（corridorChunks）まで進むので、先に周囲を入れておくと
            // 上限を食い潰して線が1本も引かれなくなる。
            around(aircraft, scratch);

            // まだ生成されていない地面はこの tick では取らない。取れば tick スレッド上で生成することに
            // なるし、上の先読みが既に別スレッドで作っている。確保は chunk が存在して保持が帳簿処理になった
            // 後の tick で成立すればよい。
            //
            // 代償を問わない例外は機体を囲む PROMISED の範囲。それが無ければ機体は自分の真下しか持たずに
            // 飛ぶことになる。あそこは当たり判定と着陸と被弾判定が問われる場所なので、遅れて届くのでは
            // 間に合わない。
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

            // 訊くのは「地形がもう在るか」であって「チケット水準が足りているか」ではない。
            //
            // ここは長い間 hasChunk で訊いていて、その答えは上の意図と食い違っていた。
            // ServerChunkCache.hasChunk はホルダーのチケット水準を見るだけで
            // （{@code !chunkAbsent(holder, ChunkLevel.byStatus(FULL))}）、地形が生成されたかは見ない。
            // そして下の先読みが自分でチケットを置いている——つまり、この回廊が「まだ無い」と判断したくて
            // 訊いている当の chunk が、自分の先読みチケットのせいで「在る」と答えていた。判定は素通りし、
            // その chunk は確保へ回り、{@code ForcedChunkManager.forceChunk} は
            // {@code tickets.add} の直後に {@code level.getChunk(x, z)} を——生成付き・ブロッキングで
            // ——呼ぶ。非同期に頼んだ物を、同じ tick に同期で取り立てていたことになる。
            //
            // getChunkNow は待たずに読める chunk か null しか返さないので、これが本来訊きたかった問いだ。
            // この MOD が他の全ての場所で使っている物でもある（{@code VehicleProjectile.groundUnder}、
            // {@code Effects.groundIsThere}）。
            scratch.removeIf(pos -> !promised(pos, own) && !held.contains(pos)
                    && level.getChunkSource().getChunkNow(pos.x, pos.z) == null
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
            if (held.contains(pos)) {
                continue;
            }

            // 機体の真下も含めて、待たずに取る。
            //
            // ここは長い間「真下だけは待ってでも取る」だった。パイロットの足元の地面が数tick遅れて届く
            // のを許さない、という理屈で。だが最高速の機体は1〜2tickに1個の chunk を跨ぐので、その待ちは
            // 「跨ぐたびにサーバーの時計を止める」と同じ物になり、止まった分だけ操縦報告が溜まって、
            // 機体の位置がサーバー上で古くなる。足元を待った代金が、足元より大きい物を壊していた。
            //
            // 遅れて届く地面の扱いは機体側が持っている（AircraftEntity.beyondTheWorld と
            // flyOnThroughLateWorld。LateWorld 参照）。チケットは置くので、生成は先読みとプレイヤー自身の
            // チケットと同じく別スレッドで進み、届いた tick から普通に持つ。CorridorClaim 参照。
            CorridorClaim.quietly(level,
                    () -> CONTROLLER.forceChunk(level, aircraft, pos.x, pos.z, true, true));
        }

        return wanted;
    }

    /**
     * この速度の機体が保持すべき回廊の長さ（chunk 数）。
     *
     * <p>{@link #LEAD_TICKS} 秒ぶんの飛行を chunk に直し、両端を挟むだけ。速度が上がれば伸び、下がれば
     * 縮む——それがこの回廊に求められている唯一の振る舞いだ。上限は {@link #MOST_CHUNKS}、下限は
     * {@link #LEAST_CHUNKS}。
     */
    /**
     * 機体を囲む {@link #PROMISED} 半径の正方形を、確保する集合へ足す。
     *
     * <p>円ではなく正方形なのは、下の {@link #promised} が同じ形で答えるからだ。両者がずれると、足した
     * 端の chunk が「約束の外」と判定されて、まだ生成されていない間だけ落とされる——つまり一番要る時に
     * だけ約束が破れる。
     */
    private static void around(AircraftEntity aircraft, Set<ChunkPos> chunks) {
        ChunkPos own = aircraft.chunkPosition();

        for (int x = -PROMISED; x <= PROMISED; x++) {
            for (int z = -PROMISED; z <= PROMISED; z++) {
                chunks.add(new ChunkPos(own.x + x, own.z + z));
            }
        }
    }

    /** その chunk が、機体を囲む「必ず確保する」範囲の中か。{@link #around} と同じ形で答えること。 */
    private static boolean promised(ChunkPos pos, ChunkPos own) {
        return Math.abs(pos.x - own.x) <= PROMISED && Math.abs(pos.z - own.z) <= PROMISED;
    }

    private static int corridorChunks(double speed) {
        return reachChunks(speed, LEAD_TICKS, MOST_CHUNKS);
    }

    /**
     * その速度で {@code ticks} tick 進む間に跨ぐ chunk 数。両端の分を足し、天井と床で挟む。
     *
     * <p>回廊と先読みが同じ規則を使う。距離ではなく時間で予告を測るという規則で、違うのは秒数と天井
     * だけだ。2箇所に別々の式を書けば、片方だけを直した誰かが「速い機体ほど予告が短い」を作り直す。
     */
    private static int reachChunks(double speed, double ticks, int most) {
        return Mth.clamp((int) Math.ceil(speed * ticks / 16.0) + 2, LEAST_CHUNKS, most);
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
        int most = corridorChunks(speed);
        double samples = Math.min(speed * LEAD_TICKS, most * 16.0) / SAMPLE;

        for (int i = 0; i < samples && chunks.size() < most; i++) {
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
    private static Tiers prefetch(AircraftEntity aircraft, ServerLevel level, Tiers asked) {
        // 走らない tick では前回の要求をそのまま返す。ここで空の集合を返すと、次に走った時に「前回何を
        // 頼んだか」が消えていて、捨てた進路を解放できなくなる。
        if ((level.getGameTime() + aircraft.getId()) % PREFETCH_EVERY != 0) {
            return asked;
        }

        Vec3 velocity = aircraft.getVelocity();
        double speed = velocity.length();

        if (speed < 1.0E-3) {
            return asked;
        }

        Tiers wanted = new Tiers();

        double stepX = velocity.x * SAMPLE / speed;
        double stepZ = velocity.z * SAMPLE / speed;
        double x = aircraft.getX();
        double z = aircraft.getZ();
        double samples = Math.min(speed * PREFETCH_TICKS, reachChunks(speed, PREFETCH_TICKS,
                PREFETCH_CHUNKS) * 16.0) / SAMPLE;
        // 直前に見た chunk を、位置ではなく座標2つで覚える。線が伸びればサンプルは数百に増えるが、その
        // 大半は同じ chunk の2度目なので、捨てるためだけの ChunkPos をそこで作らない。回廊の歩き方と
        // 同じ理由だ。
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        // 手前と奥の境。ここまでは完成品を、ここから先は素地だけを頼む。deep 参照。
        double near = NEAR_TICKS * speed / SAMPLE;

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
            (i < near ? wanted.near : wanted.deep).add(new ChunkPos(chunkX, chunkZ));
        }

        // 扇は手前だけ。旋回の出口はすぐそこで、そこは完成した地面が要る場所だ。
        fan(aircraft, speed, stepX, stepZ, wanted.near);
        // 同じ chunk を両方の水準で持たない。手前が勝つ——低い方の水準が実際の生成を決めるので、
        // 奥のチケットは黙って無駄になるだけだが、解放の帳尻が合わなくなる。
        wanted.deep.removeAll(wanted.near);

        retile(level, asked.near, wanted.near, 0);
        retile(level, asked.deep, wanted.deep, DEEP_DISTANCE);

        return wanted;
    }

    /**
     * ある水準の要求を、前回の集合から今回の集合へ移す。
     *
     * <p>足す方は毎回全部に付け直す。付け直しは時刻の更新にしかならないが、その更新こそがチケットを
     * 生かしている——{@link #PREFETCH_TIMEOUT} はこの再要求があることを前提にした値だ。
     *
     * <p>引く方がこの関数の要点になる。放っておいてもチケットは切れるが、切れるのは
     * {@link #PREFETCH_TIMEOUT} の後で、その間ずっと、機体が捨てた進路の chunk が生成器の待ち行列に
     * 居座る。同じ水準の待ち行列は到着順なので、旋回した機体がこれから必要とする chunk は、さっき捨てた
     * 進路の全部の後ろへ並ぶ。飽和した生成器では、それがそのまま「旋回すると前だけが空になる」になる。
     */
    private static void retile(ServerLevel level, Set<ChunkPos> asked, Set<ChunkPos> wanted,
            int distance) {
        for (ChunkPos pos : wanted) {
            level.getChunkSource().addRegionTicket(PREFETCH, pos, distance, pos);
        }

        for (ChunkPos pos : asked) {
            if (!wanted.contains(pos)) {
                level.getChunkSource().removeRegionTicket(PREFETCH, pos, distance, pos);
            }
        }
    }

    /**
     * 機体がこの先で<em>到達し得る</em>幅を、進路の左右へ足す。
     *
     * <p>細い1本の線は「機体はこのまま直進する」という賭けだ。旋回すれば線は外れ、外れた線の chunk は
     * 到着順の待ち行列の中で、今度こそ必要になった chunk の前に居座る。ここで足すのは賭けではなく、
     * 「今の速度と旋回率で、この距離までに機体が横へ動ける範囲」——行き得る先の全部だ。
     *
     * <p>幅は距離とともに開く。半径 v/ω の旋回に入った機体の t tick 後の横変位は v/ω·(1−cos ωt) で、
     * それがこの扇の縁になる。近くでは細く、遠くで開く。
     *
     * <p><b>短く保つこと。</b> 生成器はパイロット自身の視界の四角に追い立てられて既に飽和している。そこへ
     * 幅を足せば、届く時刻が早くなるのではなく全部が等しく遅くなる。ここが買うのは「旋回し終えた瞬間に、
     * その先の地面が既に頼まれている」ことだけで、それ以上を買おうとしてはいけない。
     */
    private static void fan(AircraftEntity aircraft, double speed, double stepX, double stepZ,
            Set<ChunkPos> chunks) {
        // 曲がる速さは昇降舵が決める。バンクして引く——それが機体の曲がり方であり、横へ動ける速さの上限。
        double turn = Math.toRadians(Math.max(aircraft.getStats().handling().pitchRate(), 0.05));
        // 進路に直交する単位ベクトル。歩幅を含んでいる stepX/stepZ から割り戻す。
        double sideX = -stepZ / SAMPLE;
        double sideZ = stepX / SAMPLE;
        double x = aircraft.getX();
        double z = aircraft.getZ();
        double reach = FAN_TICKS * speed / SAMPLE;

        for (int i = 1; i <= reach && chunks.size() < PREFETCH_CHUNKS; i++) {
            x += stepX;
            z += stepZ;

            double ticks = i * SAMPLE / speed;
            double widest = Math.min(speed / turn * (1.0 - Math.cos(turn * ticks)), FAN_WIDEST);

            for (double side = SAMPLE; side <= widest; side += SAMPLE) {
                for (int hand = -1; hand <= 1; hand += 2) {
                    chunks.add(new ChunkPos(
                            SectionPos.blockToSectionCoord(Mth.floor(x + sideX * side * hand)),
                            SectionPos.blockToSectionCoord(Mth.floor(z + sideZ * side * hand))));
                }
            }
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
    /**
     * この機体に人が乗っているか。操縦席でも、他の席でも、無人機の回線の向こうでも同じ。
     *
     * <p>訊いているのは「操縦しているか」ではなく「そこに人がいるか」だ。後部席の砲手も、着陸して
     * エンジンを切った機体のパイロットも、足元の地面を必要とする点では変わらない。
     */
    private static boolean carriesSomeone(AircraftEntity aircraft) {
        for (Entity rider : aircraft.getPassengers()) {
            if (rider instanceof Player) {
                return true;
            }
        }

        return false;
    }

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

    /**
     * 先読みが1機のために頼んでいる chunk を、水準ごとに分けて持つ。
     *
     * <p>2つに分ける理由は解放にある。チケットを外すには足した時と同じ距離で頼まなければならないので、
     * どちらの水準で頼んだかを覚えていない限り、機体が向きを変えた時に外しようがない。
     */
    static final class Tiers {
        /** 完成品を頼んだ chunk。距離0。 */
        final Set<ChunkPos> near = new LinkedHashSet<>();
        /** 素地だけを頼んだ chunk。{@link #DEEP_DISTANCE}。 */
        final Set<ChunkPos> deep = new LinkedHashSet<>();
    }

    private AircraftChunkLoader() {
    }
}
