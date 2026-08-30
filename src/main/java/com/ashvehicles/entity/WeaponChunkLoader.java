package com.ashvehicles.entity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * 飛翔中の弾に、着弾する先を与える。
 *
 * <p>兵装は地面を狙うが、ロード済みの世界の縁の外には狙う地面が無い。chunk はプレイヤーの周りにしか存在
 * せず、機体は自分が飛ぶ回廊しか持たない。機体が撃った物はレールを離れて1〜2tickでそこを出るし、戦車は
 * 自分の確保が決して届かない斜面を撃つ。{@link VehicleProjectile} はその外でも飛び続ける
 * （{@code isAlwaysTicking} 参照）が、誰もロードしていないブロックについては問い合わせない。問い合わせれ
 * ばその場でメインスレッドが地形を生成し、空を横切るミサイル1発につき「1tickあたり30ブロック幅の、作り
 * たての世界の回廊」ができてしまうから。
 *
 * <p>そこで弾は代わりに正しい方法——チケット——で要求し、これから飛ぶ地面を開いたまま保持する。高高度から
 * 投下された爆弾がそこへ降りる頃には当たる地面が存在しており、突き抜けるのではなく叩き割る。400ブロック先
 * の稜線へ向けた連射は、稜線がまだ作られていない空虚な空気ではなく稜線に届く。
 *
 * <p><b>撃った物は全部が地面を確保する。</b> 以前は<em>投下</em>物だけだった。確保が1発1チケットで、機銃
 * は毎秒20発だったから。それが買ったのは「明らかに動かない兵装」だった。弾は斜面を通り抜け、その裏の空中で
 * 寿命を迎える。その様子のどこにも、パイロットに「chunk がロードされていない」と読める要素は無い。消えるべ
 * きだったのは機銃の射程ではなく確保のコストの方で——下記参照——どちらにせよ兵装ファイルは
 * {@code chunk_loading} で明示できる。
 *
 * <p><b>チケット1枚が買う物。</b> 1 chunk より多い。確保は外側へ伝播するので、確保した chunk から2 chunk
 * 以内の地面もロードされる。これが「動くか動かないか」の分かれ目だ——弾は絶えず chunk 境界を跨ぎ、飛行の
 * 各ステップはそれが覆う全区間に対して判定される。確保を1tickではなく半tick先に置くのは、ステップの端では
 * なく中央にまたがらせるため。戦車砲弾は1tickで35ブロック進み、判定されるのはその線の全体だ。
 *
 * <p><b>確保は1つではなく2つ。</b> 確保が返る時点で存在するのは実際に指定した chunk だけで、その周りの輪は
 * 要求されてバックグラウンドで作られる。その輪こそ弾が1tick後に飛ぶ場所であり、先に着いた弾は穴を飛ぶので
 * はない——ブロック参照が tick スレッド上で地面の完成を待つ。それこそこのファイル全体が避けるために存在
 * する停止だ。だから各弾は次のステップだけでなくその次のステップも1tick早く確保し、chunk システムはその
 * 1tickを自分のペースで使える。追加コストは無い。ある tick の遠い確保は次の tick の近い確保であり、既に
 * 保持されている物を見つけるのは「数値が1増える」ことであって chunk を作ることではない。1tickで chunk を
 * 跨がない爆弾は同じ chunk を2回指定し、1つだけ保持する。
 *
 * <p><b>これを現実的にしている物。</b> 3つある。素朴な実装——1発1チケットを毎tick動かす——は「サーバーに
 * 世界を生成させる方法」だからだ:
 *
 * <ul>
 * <li><b>弾は共有する。</b> 確保は発ごとではなくワールド全体で chunk ごとに数える。だから1本の線を進む
 * 連射は、そこに何発並んでいてもその線の代金を1回だけ払う。同じ chunk を通る2発目のコストは「数値が1増え
 * る」こと。
 * <li><b>地面はロードするが tick させない。</b> 弾はどこにいても自分で tick するので、ここで chunk を
 * tick させる必要は無い。ランダム tick も湧きも、誰も立っていない土地での延焼も要らない。弾が外で欲しかっ
 * たのはロードされていることだけ——当たる物があること。
 * <li><b>新規の地面は配給制。</b> 誰かが既にロードしている chunk の確保はほぼ無料。誰も持っていない chunk
 * の確保はその場で、呼び出しが返る前に、ディスクから読むか無から作る。それは1tickに決まった数しかやらない。
 * 断られた弾はその tick を盲目で飛び、次の tick で再度要求する。代償は「当てるはずだった斜面をたまに1発
 * 逃す」ことで、全員が立っている tick を止めることは決してない。
 * </ul>
 *
 * <p>そして全体への上限。毎秒100発を3km 先まで撃つ兵装を積んだパックが出てきた場合に備えて、
 * {@link #MOST_CHUNKS} を超えるとワールドは確保を配らなくなる。空に残っている物は、これらが全部無かった頃
 * と同じように飛ぶ——つまり風景を突き抜けて。
 *
 * <p><b>生成は前もって済ませる。</b> 上の確保は保証であり、同時に要求の全部でもあった——そして確保は
 * <em>同期的</em>に応じられる。NeoForge は呼び出しが返る前に指定 chunk をロードするので、誰も生成したこと
 * のない土地では tick スレッド上のワールド生成になる。速い弾1発につき1tickに1〜2回、抑えている物は
 * {@link #LOADS_PER_TICK} だけ。さらに悪いことに、確保はステップの2点を指定し、その間の chunk は背景の輪に
 * 頼っている。そして新規地形こそ、輪がその競争に負ける場所だ。{@code spanIsLoaded} が何tickも false を返し
 * 続け、弾は道中の斜面を全部通り抜ける。2つの欠陥は1つの欠陥——地形が必要になった瞬間に作られている——なの
 * で、直し方も1つ。弾が到達する数秒前に、飛行経路へ素の region チケットの列を敷き、経路上の<em>全</em>
 * chunk を指定する。そのチケットは非同期なので生成器が自分のスレッドで作業する。自分でタイムアウトするので、
 * ミサイルが旋回して離れた線は勝手に解放される。そして決して保存されない。上の確保がその地面を欲しがる頃
 * には、欲しがることは「数値が1増える」ことになっており、着弾判定が測る区間は端から端までロードされている。
 * 狙った弾の前に稜線を戻すのがそれだ。{@code AircraftChunkLoader} の先読みと同じ手口、同じ理由。
 *
 * <p>{@link AircraftChunkLoader} と共有せず並べて書いてあるのは、両者が欲しい物が違うから——機体は tick
 * する地面の回廊を、群れとしてではなく1機として保持する。ただし<em>いつ</em>どちらのメソッドを呼ぶかに
 * ついての2つの規則は両者で同じで、それは下の各メソッドに書いてある。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class WeaponChunkLoader {
    /**
     * 近い方の確保を弾のどれだけ前に置くか。飛行 tick 数で。
     *
     * <p>半tick。確保がステップの端ではなく中央に来て、ロード済みの地面が判定対象の線の両側へ等しく届く
     * ように。1tick分にすると確保はステップの先端に来る。それは爆弾には正しいが、1tickで35ブロック進む
     * 戦車砲弾には際どい。自分の飛行経路の後ろ半分が、確保の届く縁に来てしまう。
     */
    private static final double NEAR = 0.5;

    /**
     * 遠い方の確保。飛行 tick 数で、次の次のステップの中央。
     *
     * <p>飛ぶ1tick前に要求する。確保した chunk の周りの地面——確保が返る前ではなくバックグラウンドで作られ
     * る部分——が、弾の到着時に「待つ物」ではなく「既にある物」になるように。上の「確保は2つ」の項参照。
     */
    private static final double FAR = 1.5;

    /**
     * 1発が地面を開いたまま保持できる最長時間（tick）。
     *
     * <p>30秒。ここで最も遅い物が必要とする時間の数倍だ（世界の天井から投下された爆弾は150tick以内に着く）。
     * 規則ではなく保険である。兵装の寿命は射程と速度から計算されるが、爆弾の「速度」はラックから押し出され
     * る勢いであって落下速度ではないので、計算上は20分になってしまう。これに到達する物は本来無い。到達した
     * 物は、誰かが気付くまで chunk を開き続けるはずだった物だ。
     */
    private static final int LONGEST_HOLD = 600;

    /**
     * 1つのワールドの兵装群が合計で開いておける chunk 数の上限。
     *
     * <p>発数ではなく確保数で数える。まとめて数える意味がそこにある——1本の線へ撃つ機銃は、空に1発あろうと
     * 40発あろうとその線が通る chunk を保持する。
     *
     * <p><b>64では1本の射線にすら足りなかった。</b> 射程1000ブロックの機銃は、その線が斜めなら 88 chunk を
     * 通る（1000 / 16 × √2）。毎秒60発・寿命38tickなら常時110発以上がその線に並んでおり、全員が自分の下を
     * 確保するので、保持数は線の長さそのものになる。つまり<em>一連射で天井に当たる</em>。そして当たり方が
     * 悪い。後方の弾が既に保持している chunk は数が増えるだけで通るのに対し、新しい地面を要求するのは
     * 先頭の弾——目標へ差し掛かっている弾——だけなので、断られるのは常にそちらだ。撃った本人には「弾が
     * 目標の斜面をすり抜けた」ようにしか見えない。128 は最も長い機銃の射線1本と、同時に飛ぶ数発の重い物が
     * 収まる数。
     *
     * <p>ここを超えても、今は当たらなくなるわけではない。着弾判定が要求するのは弾の<em>線</em>の上の地面
     * だけで、それは先読みが——上限も配給も無しに、1 chunk 1チケットで——敷いている。確保が買うのはその
     * 手前の「同期的に在ることの保証」であって、判定そのものではない。
     * {@code VehicleProjectile.spanIsLoaded} 参照。
     */
    private static final int MOST_CHUNKS = 128;

    /**
     * 1つのワールドの兵装群が1tickに要求できる「誰もロードしていない chunk」の数。
     *
     * <p>その1つ1つが、他の全部を待たせながらディスクから読むか無から作る chunk だ。8 は「複数の弾が同時に
     * 新規の土地を横切る」には十分で、「一斉射が時計を止める」には少ない。溢れた分は次の tick で再要求し、
     * その頃には隣が確保した地面が既にあって無料で取れる。先読みが先行して走っている今、これは実働予算では
     * なく保険。先読み済みの地面への確保はコスト0で、この数には数えない。
     */
    private static final int LOADS_PER_TICK = 8;

    /**
     * 先読みが確保よりどれだけ先まで届くか。飛行 tick 数と chunk 数の両方で。
     *
     * <p>3秒・500m 強。新規地形は別スレッドでも生成に数秒かかるので、線の先端は弾の到着からそれだけ前に
     * 要求しておく必要がある。chunk 数の上限は、速い弾の要求がマップ全体にならないようにする物。これすら
     * 追い越す弾は、まだ作られている最中の場所を盲目で飛ぶ——従来通り——し、着弾判定は数 chunk 先で再び
     * ロード済みの地面を見つける。
     */
    private static final double PREFETCH_TICKS = 60.0;
    private static final int PREFETCH_CHUNKS = 40;

    /**
     * 各弾が先読みを向け直す間隔（tick）。{@link #SAMPLE} ブロックごとにサンプリングして線上の全 chunk を
     * 指定する——それがこの作業の要点で、「2点の指定と期待混じりの輪」こそこれが塞ぐために存在する穴だ。
     * チケットの付け直しは時刻の更新にしかならないので、1本の線に並んだ連射は同じ chunk を何度も指定するが
     * コストは1回あたりマップ参照1回。エンティティ ID でずらしてあるので、連射は要求を tick 間に分散させる。
     */
    private static final int PREFETCH_EVERY = 4;
    /** 経路をサンプリングする間隔（ブロック）。半 chunk なので経路上の chunk を飛ばさない。 */
    private static final double SAMPLE = 8.0;

    /**
     * 先読みチケットが自分を生かしておく時間（tick）。更新数回分の長さがあり、連射の後方や、ミサイルが
     * 旋回して離れた線の先が、開いたまま残らず数秒で解放される程度に短い。
     */
    private static final int PREFETCH_TIMEOUT = 200;

    /**
     * 先読みチケット。完全生成・非tick・自動解放。NeoForge の強制ロードではなくバニラの region チケットを
     * 使うのは、これが確保の持たない性質を全部持つから——非同期、自動失効、非保存——であり、それこそが飛行
     * 経路へ十数枚ばら撒いても安全な理由。
     */
    private static final TicketType<ChunkPos> PREFETCH = TicketType.create(
            AshVehicles.MODID + ":weapon_prefetch",
            Comparator.comparingLong(ChunkPos::toLong), PREFETCH_TIMEOUT);

    /**
     * 各ワールドの兵装群が開いている物。サーバースレッド専用で、全弾の tick と全アンロードコールバックは
     * そこで走る。
     *
     * <p>弱参照で保持するので、消えたワールドは自分の帳簿を連れて消える。その際に片付ける物は無い。チケット
     * はレベルの持ち物で、レベルと運命を共にする。
     */
    private static final Map<ServerLevel, Claims> CLAIMS = new WeakHashMap<>();

    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "weapon"),
            (level, helper) -> {
                // チケットは再起動を生き延びるが、空にある物は生き延びない。何かの拍子に生き延びた物は
                // 次の tick で再要求するし、それ以外は「永久に地面を開き続ける」のをやめる。所有者の種類は
                // 2つ——今の確保は対象 chunk 自身が持ち主だが、それ以前に保存されたワールドには当時の
                // 「発ごとの持ち主」がまだ残っている。
                helper.getBlockTickets().keySet().forEach(helper::removeAllTickets);
                helper.getEntityTickets().keySet().forEach(helper::removeAllTickets);
            });

    @SubscribeEvent
    public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * 弾の確保を、これから飛ぶ地面へ移す。地面を確保しない種類の弾なら解放する。chunk が変わった時しか何も
     * しない——投下物なら長い落下の間に数回、発射物ならほぼ毎tick。ただしその場合も、その地面を最初に欲し
     * がった弾でない限り「マップ内の数値が1減って別の数値が1増える」だけ。
     *
     * <p>呼ぶのは弾自身の tick からだけ。チケットの取得はその場で chunk をロードし、それが chunk システムの
     * 更新ループを走らせる。そのループの中——エンティティのロード／アンロードコールバックはそこから飛ぶ——
     * から呼べば反復の途中で再入し、サーバーを落とす。コールバックからは {@link #release} を使うこと。
     *
     * @param hold 弾が保持している物。呼び出し後の保持内容へその場で更新される
     */
    public static void update(VehicleProjectile shot, Hold hold) {
        if (!(shot.level() instanceof ServerLevel level)) {
            return;
        }

        ChunkPos near = null;
        ChunkPos far = null;

        if (shouldStayLoaded(shot)) {
            // 確保より前に。この tick が知り得た最も早い時点で、線の先端のことを生成器へ伝えるため。
            prefetch(shot, level);

            near = along(shot, NEAR);
            far = along(shot, FAR);

            // 1tickに1 chunk より遅い物は同じ地面を2回指定する。その場合は確保1つが欲しかった物の全部
            // なので、遠い側の枠は二重に数えず空のままにする。
            if (far.equals(near)) {
                far = null;
            }
        }

        if (Objects.equals(near, hold.near) && Objects.equals(far, hold.far)) {
            return;
        }

        Claims claims = CLAIMS.computeIfAbsent(level, ignored -> new Claims());
        ChunkPos wasNear = hold.near;
        ChunkPos wasFar = hold.far;

        // 先に手放す。ただし完全に要らなくなった地面だけ。前 tick に前方へ確保していた地面は、たいてい今
        // 飛んでいる場所そのものだ。それを手放してすぐ要求し直すのは、チケットを2回取って途中で chunk を
        // 解放することになる。
        drop(claims, level, wasNear, near, far);
        drop(claims, level, wasFar, near, far);

        hold.near = keep(claims, level, near, wasNear, wasFar);
        hold.far = keep(claims, level, far, wasNear, wasFar);
    }

    /**
     * 弾が保持している地面を全部手放し、新たに要求しない。サーバースレッドのどこからでも安全に呼べる。
     * chunk システム自身のコールバックの中からも。チケットを落とすのはレベル変更を予約するだけで、何も
     * ロードしない。
     *
     * @param hold 弾が保持している物。空にされる
     */
    public static void release(VehicleProjectile shot, Hold hold) {
        if (hold.near == null && hold.far == null) {
            return;
        }

        if (shot.level() instanceof ServerLevel level) {
            Claims claims = CLAIMS.get(level);

            if (claims != null) {
                drop(claims, level, hold.near, null, null);
                drop(claims, level, hold.far, null, null);
            }
        }

        hold.near = null;
        hold.far = null;
    }

    /** 弾が以前保持していた chunk を手放す。ただし今欲しい2つのどちらかなら残す。 */
    private static void drop(Claims claims, ServerLevel level, @Nullable ChunkPos had,
            @Nullable ChunkPos near, @Nullable ChunkPos far) {
        if (had != null && !had.equals(near) && !had.equals(far)) {
            claims.drop(level, had);
        }
    }

    /**
     * 弾が今欲しい chunk を取る。既に保持していれば何もしない。
     *
     * @return その chunk。要らない場合、またはこの tick にワールドがそこまで応じない場合は null。その場合
     *         は何も保持しておらず、弾は次の tick で再要求する
     */
    @Nullable
    private static ChunkPos keep(Claims claims, ServerLevel level, @Nullable ChunkPos wanted,
            @Nullable ChunkPos wasNear, @Nullable ChunkPos wasFar) {
        if (wanted == null) {
            return null;
        }

        if (wanted.equals(wasNear) || wanted.equals(wasFar)) {
            return wanted;
        }

        return claims.take(level, wanted) ? wanted : null;
    }

    /** 今から指定 tick 数だけ飛んだ時点で弾がいる chunk。 */
    private static ChunkPos along(VehicleProjectile shot, double ticks) {
        Vec3 at = shot.position().add(shot.getDeltaMovement().scale(ticks));

        return new ChunkPos(BlockPos.containing(at));
    }

    /**
     * 弾がこれから数秒で飛ぶ経路上の全 chunk を、静かにバックグラウンドで生成器へ要求する。上の確保がその
     * どれかを欲しがる頃には——そして着弾判定がその上で測られる頃には——生成が済んでいるように。クラス冒頭
     * の説明参照。
     *
     * <p>弾の最初の tick では時計が何と言おうと必ず要求する。弾は生涯が数秒しかなく、その最初の数秒を順番
     * 待ちに使う余裕は無い。訊くべき線ができた瞬間に訊く必要がある。そこから逸れていく誘導弾は、その後
     * 数tickごとに向け直す。
     */
    private static void prefetch(VehicleProjectile shot, ServerLevel level) {
        if (shot.age > 1 && (level.getGameTime() + shot.getId()) % PREFETCH_EVERY != 0) {
            return;
        }

        Vec3 velocity = shot.getDeltaMovement();
        double speed = velocity.length();

        if (speed < 1.0E-3) {
            return;
        }

        // AircraftChunkLoader.ahead と同じく、点の列ではなく2つの数値として歩く。これは空にある全弾で
        // 走るので、1サンプルごとに Vec3 を作るのは整数2つ分の答えに対してゴミが多すぎる。水平成分だけを
        // 見る——chunk は柱なので、爆弾の落下が「どの chunk の上にいるか」を変えるのは、それが横方向へ
        // 運ぶ分だけ。
        double stepX = velocity.x * SAMPLE / speed;
        double stepZ = velocity.z * SAMPLE / speed;
        double x = shot.getX();
        double z = shot.getZ();
        double samples = Math.min(speed * PREFETCH_TICKS, PREFETCH_CHUNKS * 16.0) / SAMPLE;
        ChunkPos last = null;

        for (int i = 0; i < samples; i++) {
            x += stepX;
            z += stepZ;

            ChunkPos pos = new ChunkPos(SectionPos.blockToSectionCoord(Mth.floor(x)),
                    SectionPos.blockToSectionCoord(Mth.floor(z)));

            // 距離0。指定 chunk を最後まで生成し、周囲は昇格させず、tick もさせない。線上の全 chunk を
            // 個別に指定する。確保が決してやらなかったことであり、速い弾の下の区間が到着時にロード済みで
            // ある理由の全部。
            if (!pos.equals(last)) {
                level.getChunkSource().addRegionTicket(PREFETCH, pos, 0, pos);
                last = pos;
            }
        }
    }

    private static boolean shouldStayLoaded(VehicleProjectile shot) {
        return !shot.isRemoved() && shot.age <= LONGEST_HOLD && shot.getWeapon().loadsChunks();
    }

    /**
     * 1発が開いている物。これから踏むステップの下の地面と、その次のステップの下の地面。
     *
     * <p>集合ではなく名前付きの枠2つにしてあるのは、2つしか無く今後も増えないから。そしてある tick の遠い
     * 側は次の tick のほぼ必ず近い側になり、それは「作り直す確保」ではなく「放っておく確保」だから。保持する
     * のは弾自身。要らなくなった時を知っているのは弾だけなので。
     */
    public static final class Hold {
        @Nullable
        private ChunkPos near;
        @Nullable
        private ChunkPos far;
    }

    /**
     * 1つのワールドの兵装群が開いている地面と、その各区画を何発が要求しているか。
     *
     * <p>所有ではなく計数にしてあり、それが機銃を現実的にしている。チケットの持ち主は chunk 自身——chunk の
     * 隅のブロックが、chunk システムへ渡す持ち主になる——なので、射線に何発並んでいてもその線の各 chunk は
     * 1回だけ確保され、最後の1発が通り過ぎた時に解放される。
     */
    private static final class Claims {
        /** 保持中の chunk と、それぞれを欲しがっている弾数。0 の項目は持たない。 */
        private final Map<ChunkPos, Integer> held = new HashMap<>();

        /** {@link #loads} を数えている tick と、その tick で既に使った分。 */
        private long tick = Long.MIN_VALUE;
        private int loads;

        /**
         * ある chunk への1発分の関心を加える。最初の1発ならチケットも取る。
         *
         * @return その chunk が今保持されているか。ワールドがそこまで応じない場合は false で、その場合は
         *         何も確保されておらず、弾には手放す物も無い
         */
        boolean take(ServerLevel level, ChunkPos pos) {
            Integer wanting = this.held.get(pos);

            if (wanting != null) {
                this.held.put(pos, wanting + 1);

                return true;
            }

            if (this.held.size() >= MOST_CHUNKS || !this.affordable(level, pos)) {
                return false;
            }

            // ロードするが tick させない。弾はどこにいても自分で tick するし、ここで欲しいのは当たる地面
            // であって、誰もいないのにフル稼働する田園ではない。
            CONTROLLER.forceChunk(level, pos.getWorldPosition(), pos.x, pos.z, true, false);
            this.held.put(pos, 1);

            return true;
        }

        /** 1発分の関心を取り下げ、最後の1つだったなら chunk を解放する。 */
        void drop(ServerLevel level, ChunkPos pos) {
            Integer wanting = this.held.get(pos);

            if (wanting == null) {
                return;
            }

            if (wanting > 1) {
                this.held.put(pos, wanting - 1);

                return;
            }

            this.held.remove(pos);
            CONTROLLER.forceChunk(level, pos.getWorldPosition(), pos.x, pos.z, false, false);
        }

        /**
         * この tick に、そのワールドがその chunk を要求されて耐えられるか。
         *
         * <p>既にそこに在る地面は常に許容される。確保はそれを保つだけで、保つのは帳簿処理だから。まだ無い
         * 地面は {@code forceChunk} の中の {@code level.getChunk} が呼び出しの返る前に読むか作るので、
         * tick と tick の間に行う数を制限する。
         *
         * <p>訊くのは「チケットが在るか」ではなく「chunk が在るか」。{@code hasChunk} はチケット水準しか
         * 見ないので、先読みが線上の全 chunk にチケットを置いた後は——地形はまだ生成器のスレッドで作られて
         * いる最中でも——常に true を返す。それでは配給が一度も働かないまま、確保のたびに tick スレッドが
         * その生成の完了を待つことになる。ここが数えるべきなのは待つ回数であり、待つかどうかを決めるのは
         * chunk が既に在るかどうかだ。
         */
        private boolean affordable(ServerLevel level, ChunkPos pos) {
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) {
                return true;
            }

            long now = level.getGameTime();

            if (now != this.tick) {
                this.tick = now;
                this.loads = 0;
            }

            if (this.loads >= LOADS_PER_TICK) {
                return false;
            }

            this.loads++;

            return true;
        }
    }

    private WeaponChunkLoader() {
    }
}
