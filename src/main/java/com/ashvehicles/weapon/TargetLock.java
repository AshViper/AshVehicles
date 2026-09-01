package com.ashvehicles.weapon;

import java.util.List;

import javax.annotation.Nullable;

import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.entity.VehicleProjectile;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.sensor.Contact;
import com.ashvehicles.vehicle.VehicleChassis;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 乗員がシーカーで捉えている相手と、その進捗。
 *
 * <p>ロックはミサイルの仕事ではなく乗員の仕事だ。シーカーの視野内かつ射程内の何かに照準線を乗せ、そこへ
 * 保持し続ける。外れればシーカーは最初からやり直す。それがミサイル発射を「働いて得る物」にし、目標には
 * 逃げ道を与える——成立前に視線を切るか視野外へ出れば、撃たれずに済む。
 *
 * <p><b>誰の照準線かはこのクラスの関知するところではない。</b> パイロットは機体を向け、発射機の乗員は
 * 砲塔を旋回させて車体は一切動かさない。どちらも同じ問い——兵装が見ている線から目標がどれだけ外れている
 * か——に行き着くので、訊く相手は {@link VehicleEntityBase#getAimDirection} であり、以下はどちらでも同じ
 * ように動く。
 *
 * <p>全部サーバー側にある。兵装が何を向いているかを決めてよいのはサーバーだけだから。結果は機体の同期
 * データへ写して計器が描けるようにする。クライアントは目標を選ばず、サーバーが選んだ物を見るだけ。
 */
public final class TargetLock {
    /** 見失った目標をシーカーが諦めるまで保持する tick 数。 */
    private static final int GRACE_TICKS = 10;
    /** デコイがシーカーの見ている相手を隠すのに必要な近さ（ブロック）。 */
    private static final double SCREENED = 24.0;

    /**
     * シーカーが毎tick自力で見る距離（ブロック）。
     *
     * <p>この内側の物は到着した瞬間に見つかる。格闘戦に必要なのはそれ。外側は代わりに
     * {@link #SWEEP_TICKS} ごとに掃引する（{@link #candidates} 参照）。
     */
    private static final double NEAR_REACH = 192.0;

    /**
     * {@link #NEAR_REACH} より外の空を新しい候補について掃引する間隔（tick）。
     *
     * <p>毎tickではない理由が全部これ。箱の中身をレベルに問い合わせるコストは、中に何があるかではなく
     * <em>箱の大きさ</em>で決まる。レベルは16ブロックにつき1列のエンティティセクションを歩くので、ロック
     * 距離4.5km のシーカー——空対空ミサイルのファイルが要求する値——は毎秒20回、世界を500列以上、しかも
     * 空にいる武装機体1機ごとに歩くことになる。あの1行がサーバーで最も高価な処理だった。
     *
     * <p>この掃引が抑制<em>しない</em>のはシーカー自身だ。見つけた候補は毎tick、その tick の位置で照準線
     * に対して測り直されるので、ロックの成立・保持・喪失は従来通り。動くのは「遠方の機体に最初に気付く
     * 瞬間」だけで、最大0.5秒——ロック時間が秒単位で測られること、そしてその距離ではパイロットが機首を
     * 素早く振るのではなく安定させていることを考えれば十分小さい。
     */
    private static final int SWEEP_TICKS = 10;

    private final VehicleEntityBase vehicle;
    @Nullable
    private Entity target;
    /** 目標を視野内に保持した tick 数。兵装の {@code lock_ticks} に達したらロック成立。 */
    private int held;
    /** 目標を最後に見てからの tick 数。一瞬のぶれでロックを捨てないため。 */
    private int missing;
    private boolean locked;
    /** 前tickにロックキーが押されていたか。押しっぱなしから1押しを切り出すための記憶。 */
    private boolean wasWanted;
    /**
     * 直近の遠距離掃引が見つけた物。毎tick、各自の現在位置で検討し直す。保持は最大 {@link #SWEEP_TICKS}
     * で、使うたびに {@link #couldTarget} で再判定するので、死んだ物や去った物が撃たれることはない。
     */
    private List<Entity> distant = List.of();
    /** 遠距離掃引からの tick 数と、その時の探知距離。より広いシーカーになれば即座に掃引し直す。 */
    private int sinceSweep = Integer.MAX_VALUE / 2;
    private double sweptTo;

    public TargetLock(VehicleEntityBase vehicle) {
        this.vehicle = vehicle;
    }

    @Nullable
    public Entity target() {
        return this.target;
    }

    /** ミサイルが受け取れるだけの時間、シーカーが目標を保持したか。 */
    public boolean isLocked() {
        return this.locked && this.target != null;
    }

    /**
     * シーカーが何かを捉えて作業中——目標を取ってから手に入れるまでの数秒——の間 true。
     *
     * <p>そこは他に何も変わらないまま {@link #progress} だけが変わる唯一の区間であり、したがって
     * クライアントへ毎tick伝えなければならない唯一の区間でもある。ロックが閉じる間、目標もロック状態も
     * 変わらないので、その2つしか報告しない機体は「捉えた瞬間」から「手に入れた瞬間」まで何も送らない。
     * すると画面上の枠も耳のトーンも、待ち時間の間ずっと静止したまま最後に飛ぶ。
     * {@code WeaponMounts.tick} 参照。
     */
    public boolean isClosing() {
        return this.target != null && !this.locked;
    }

    /** ロックの進捗。0から1まで。閉じていく間に計器が描く値。 */
    public float progress(WeaponDefinition.Guidance guidance) {
        if (this.target == null) {
            return 0.0F;
        }

        return this.locked ? 1.0F
                : Math.min(1.0F, this.held / (float) this.lockTicks(guidance, this.target));
    }

    /**
     * この機体がシーカーに捉えさせるまで保持し続ける必要のある tick 数。兵装自身の値を、機体に付いている
     * 補助装備の分だけ短くし、狙っている相手が積んでいるジャマーの分だけ延ばした物。
     *
     * <p><b>待ち時間は両側の搭載構成で決まる。</b> 照準ポッドは自分の側から短くし、相手のジャマーは向こう
     * 側から延ばす。同じミサイル、同じ距離でも、電子妨害を吊った機体を捉え続けるには何倍も長く照準線に
     * 乗せ続けねばならず、その間に相手は旋回して視野の外へ出られる。妨害が効くのはレーダーシーカーだけ
     * ——{@link #jamming} 参照。
     *
     * <p>1を下回らせない。待ち時間を割り切って消すほど優秀なポッドでも1tickは残すため、そしてここで0除算
     * を起こさないため。両側で同じ計算をする——クライアントは自機と目標の両方のステーション搭載内容を
     * 知らされているので、サーバーと同じ値に辿り着き、画面上の枠は実際のロック速度で閉じる。
     *
     * @param against 捉えようとしている相手。まだ誰も取っていなければ null で、妨害は掛からない
     */
    private int lockTicks(WeaponDefinition.Guidance guidance, @Nullable Entity against) {
        float gain = Math.max(0.01F, this.vehicle.lockRateGain());
        float jam = Math.max(0.01F, jamming(guidance, against));

        return Math.max(1, Math.round(Math.max(guidance.lockTicks(), 1) * jam / gain));
    }

    /**
     * その相手がこのシーカーのロックをどれだけ遅らせるか。
     *
     * <p>妨害するのは電波であって熱でも光でもない。フレアがレーダーシーカーに見えないのと同じ理由で、
     * ジャマーは熱源追尾ヘッドとレーザー目標指示に対しては何もしない。それが機体1機に複数の対抗手段を
     * 積む意味であり、正しいレバーを選ぶ意味でもある。
     */
    private static float jamming(WeaponDefinition.Guidance guidance, @Nullable Entity against) {
        return against != null && guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR
                ? AircraftEntity.lockDelay(against)
                : 1.0F;
    }

    /**
     * 1tick分の捜索。いつでも自由に新しい目標を取ってよい版。発射機の乗員がずっとやってきたこと——何かへ
     * 旋回すればシーカーがそれを取り、見ることとロックすることの間に何も挟まらない。
     *
     * @param guidance 現在選択中の兵装のシーカー。無ければ null
     * @return クライアントへ伝えるべき変化があったか
     */
    public boolean tick(@Nullable WeaponDefinition.Guidance guidance) {
        return this.tick(guidance, true);
    }

    /**
     * 1tick分の捜索。捕捉は乗員の押下で始まり、押下で終わる。
     *
     * <p>ロックキーの1押しが、その瞬間に照準線と射程の中にいる物を掴む——視野に何も無い押下は何も掴まない。
     * 掴んでいる間は<em>その相手だけ</em>を追う。新しい目標は取らないし、隣により中央の何かが現れても
     * 乗り換えない。実物のシーカーは航跡を1本追っている装置で、勝手に次の獲物へ移ったりしないからだ。
     * もう1押しで手放す——それがロックを<em>外す</em>手順。
     *
     * <p>熱源シーカーだけは押下を見ない。IR ヘッドは受動素子で、視野に入った熱を勝手に掴む——切る物が
     * 無いので切れない。従来通り自動で捕捉し、自動で乗り換える。
     *
     * @param guidance 現在選択中の兵装のシーカー。無ければ null
     * @param wantsLock ロックキーが今押されているか。立ち上がりをここで検出するので、呼び出し側は
     *                  押しっぱなしをそのまま渡してよい
     * @return クライアントへ伝えるべき変化があったか
     */
    public boolean tick(@Nullable WeaponDefinition.Guidance guidance, boolean wantsLock) {
        Entity was = this.target;
        boolean wasLocked = this.locked;
        // 立ち上がり検出。クライアントの入力は毎tickの押下状態で届き、パケットの都合で同じ状態を2tick
        // 見ることがある。エッジをここで取れば、1押しが「掴んで即座に手放す」に化けることは無い。
        boolean pressed = wantsLock && !this.wasWanted;

        this.wasWanted = wantsLock;

        if (guidance == null) {
            this.clear();

            return was != null || wasLocked;
        }

        // 自動なのは熱源シーカー、ただし誰かが操縦している間だけ。IR ヘッドが受動素子なのは本当だが、
        // この行の実体は索敵の箱の問い合わせで、そのコストは箱の大きさで払う（bestCandidate 参照）。
        // 乗り手のいない機体まで毎tick空を掃かせると、駐機場に並んだ数だけ「サーバーで最も高価な処理」
        // が常時走る——押している間しか掃かなかった頃には存在しなかった負荷だ。無人機の遠隔操縦者は
        // getAviator が数えるので、ドローンのシーカーはこれまで通り生きている。
        boolean automatic = guidance.seeker() == WeaponDefinition.Guidance.Seeker.HEAT
                && this.vehicle.getAviator() != null;

        // トグルの「切」。追っている物があれば、押下はそれを手放す合図。
        if (!automatic && pressed && this.target != null) {
            this.clear();

            return was != null || wasLocked;
        }

        boolean acquiring = automatic || (pressed && this.target == null);

        if (this.target == null && !acquiring) {
            return false;
        }

        // 取得の tick だけ空を探す。それ以外の tick は、追っている当人がまだ見えているかだけを問う。
        Entity best = acquiring ? this.bestCandidate(guidance)
                : this.stillSees(guidance, this.target) ? this.target : null;

        // デコイに紛れて見失った場合。何も無かったのとまったく同じに扱うので猶予時間が走り、雲が薄れた
        // 瞬間に復帰するのではなくロックが落ちる。
        if (best != null && this.screened(best, guidance)) {
            best = null;
        }

        if (best != null && best == this.target) {
            // まだ捉えている。ロックが進む。
            this.missing = 0;
            this.held++;
            this.locked = this.held >= this.lockTicks(guidance, best);
        } else if (best != null && this.target == null) {
            this.target = best;
            this.held = 1;
            this.missing = 0;
            this.locked = this.lockTicks(guidance, best) <= 1;
        } else if (best != null) {
            // より良い相手が現れたか、前の相手が消えた。新しい相手で最初からやり直す。来るのは自動捕捉の
            // シーカーだけ——押下で掴む物は取得の tick に目標を持っていない。
            this.target = best;
            this.held = 1;
            this.missing = 0;
            this.locked = false;
        } else if (this.target != null && ++this.missing > GRACE_TICKS) {
            this.clear();
        }

        return this.target != was || this.locked != wasLocked;
    }

    /**
     * 追っている相手がまだこのシーカーに見えているか。保持の判定であり、新しい候補は一切見ない。
     *
     * <p>供給源は取得（{@link #bestCandidate}）と同じ2本。シーカー自身の視野と距離、そしてレーダー追尾弾
     * ならレーダーが捉え続けている航跡。取得で使った線がそのまま保持の線になるので、掴めた相手を同じ場所で
     * 「見えない」と言い出すことは無い。
     */
    private boolean stillSees(WeaponDefinition.Guidance guidance, Entity target) {
        if (!this.couldTarget(target)) {
            return false;
        }

        Vec3 gap = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0)
                .subtract(this.vehicle.position());
        double distance = gap.length();

        if (distance < 1.0E-3) {
            return false;
        }

        double alignment = gap.scale(1.0 / distance).dot(this.vehicle.getAimDirection(1.0F));
        double seeker = guidance.lockRange() * Math.max(0.0F, this.vehicle.seekerRangeGain());
        double ownAngle = Math.cos(Math.toRadians(guidance.lockAngle()));

        if (alignment >= ownAngle && distance <= reachAgainst(guidance, target, seeker)) {
            return true;
        }

        // シーカー単独では外れているが、レーダーが走査範囲で捉え続けているならそれで足りる。
        VehicleChassis.Radar radar = this.vehicle.radar();

        if (guidance.seeker() != WeaponDefinition.Guidance.Seeker.RADAR || !radar.fitted()
                || alignment < Math.cos(Math.toRadians(radar.arc()))) {
            return false;
        }

        for (Contact contact : this.vehicle.getSensors().contacts()) {
            if (contact.id() == target.getId()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 目標が今放出した物の中で、シーカーが目標を見失ったか。
     *
     * <p>対抗手段は発射後だけでなく発射前にも効き、こちらは「そもそも撃てるか」を決める半分だ。ロック警報
     * を見て正しいレバーを引いたパイロットは、生き延びるのではなく発射自体を封じる。間違えたレバーは何も
     * 封じない——フレアはレーダーシーカーに見えず、金属箔の雲は熱源追尾に見えない。
     *
     * <p>数えるのは<em>目標</em>の近くにある物だけ。空の反対側で他人の機体の後ろに漂うデコイは、この
     * シーカーとその見ている相手の間には無い。
     */
    private boolean screened(Entity target, WeaponDefinition.Guidance guidance) {
        AABB box = target.getBoundingBox().inflate(SCREENED);

        return !this.vehicle.level()
                .getEntitiesOfClass(CountermeasureEntity.class, box, decoy -> decoy.fools(guidance.seeker()))
                .isEmpty();
    }

    /** 捉えていた物を忘れる。選択中の兵装が何もロックできない場合に使う。 */
    public void clear() {
        this.target = null;
        this.held = 0;
        this.missing = 0;
        this.locked = false;
        this.distant = List.of();
        this.sinceSweep = Integer.MAX_VALUE / 2;
    }

    /**
     * シーカー視野の中で最も中央にある物。機体に最も近い物ではなく照準線に最も近い物を採る。乗員が向けて
     * いる先こそ撃つつもりの相手だから。
     *
     * <p><b>届く距離は1つではなく2つの数値で決まる。</b> 兵装自身の {@code lock_range} はシーカーが単独で
     * 出せる距離で、熱源追尾ミサイルなら数百ブロック。レーダーの無い機体ではそれが全て。レーダーを積んだ
     * 機体はもっとできる。レーダーが捉えている物は、レーダーが捉えている距離で取れる。それがレーダーの
     * 役目だから——シーカーは航跡を「渡されて」おり、自分で見つけているのではない。
     *
     * <p>これが2つの計器を一致させる。無ければ、パイロットは600ブロック先の目標をスコープで見ながら機首を
     * まっすぐ向け、「シーカーには何も見えない」と告げられる。機体は物のありかを完全に知っていながら撃つ
     * のを拒む、という状態になる。
     *
     * <p>これは描画とは一切関係ない。全部サーバー側で走り、そこでは空中の機体はどこにいてもロードされて
     * いる。自分の chunk を開いたまま保持するので、1000ブロック先でも頭上と同じように見つかる。クライアン
     * トの描画距離が決めるのは「シーカーが捉えた物をパイロットが<em>見られる</em>か」だけで、機体は通常の
     * レンダラーが諦めたずっと後までゴーストとして描かれる。
     */
    @Nullable
    private Entity bestCandidate(WeaponDefinition.Guidance guidance) {
        Vec3 bore = this.vehicle.getAimDirection(1.0F);
        Vec3 from = this.vehicle.position();
        // 兵装自身の探知距離に、機体が積んでいる補助装備の分を掛ける。照準ポッドの効果はまさにこの数値
        // だけ——同じミサイルを、より遠くで。
        double seeker = guidance.lockRange() * Math.max(0.0F, this.vehicle.seekerRangeGain());
        double ownAngle = Math.cos(Math.toRadians(guidance.lockAngle()));

        // レーダー追尾弾はレールを離れるまで自前の視野と呼べる物を持たない。アクティブになる距離まで
        // 近づくまで何も見えず、それ以前は弾ではなくレーダーの仕事だ。だからこの種の弾の捜索範囲を広げる
        // のはレーダー自身の走査範囲。{@code lock_range} が「弾が単独でそこまで届く」からではなく距離で
        // 広がるのと同じ理屈。誰のレーダーにも誘導されない熱源追尾は、レーダーに何が見えていようと自分の
        // 狭い視野を守る。
        VehicleChassis.Radar radar = this.vehicle.radar();
        double radarAngle = guidance.seeker() == WeaponDefinition.Guidance.Seeker.RADAR && radar.fitted()
                ? Math.cos(Math.toRadians(radar.arc()))
                : ownAngle;
        Aim aim = new Aim(from, bore, Math.min(ownAngle, radarAngle));

        // 近距離ではシーカーが自力で、しかも何でも見つける。機体、プレイヤー、視野に迷い込んだ生き物。
        // 毎tick行う。物が突然現れるのはこの距離だし、この大きさの箱を問い合わせるコストはほぼ無いから。
        AABB box = this.vehicle.getBoundingBox().inflate(Math.min(seeker, NEAR_REACH));

        for (Entity candidate : this.vehicle.level().getEntities(this.vehicle, box, this::couldTarget)) {
            aim.consider(candidate, reachAgainst(guidance, candidate, seeker), ownAngle);
        }

        // それより遠くは、新規掃引ではなく直近の掃引結果から。SWEEP_TICKS 参照。
        for (Entity candidate : this.candidates(seeker)) {
            if (this.couldTarget(candidate)) {
                aim.consider(candidate, reachAgainst(guidance, candidate, seeker), ownAngle);
            }
        }

        // さらに遠くではレーダーが渡してくる物だけを取る。空をもう一度掃引するのではなく目標一覧として
        // 訊くのは、この距離では重要だからだ。レーダーの届く範囲は km 単位で、これは毎tick走る。その大きさ
        // の箱を、レーダーが既に見つけた十数個のために毎秒20回歩くことになる。判定は弾の視野ではなく
        // レーダー自身の走査範囲で行う——上記参照——それがレーダー追尾兵装で機首から大きく外れた目標を
        // 取れる理由の全て。
        for (Contact contact : this.vehicle.getSensors().contacts()) {
            Entity candidate = this.vehicle.level().getEntity(contact.id());

            if (candidate != null && this.couldTarget(candidate)) {
                aim.consider(candidate, Double.MAX_VALUE, radarAngle);
            }
        }

        return aim.best;
    }

    /**
     * {@link #NEAR_REACH} より外でシーカーが届き得る物すべて。直近の掃引が古くなったら掃引し直し、その間
     * は同じ物をそのまま返す。
     *
     * <p>このリストはあくまで<em>候補</em>のリスト。そのうちどれを実際に捉えるかは、呼び出し側がその tick
     * の位置から毎tick決める。
     */
    private List<Entity> candidates(double seeker) {
        if (seeker <= NEAR_REACH) {
            this.distant = List.of();

            return List.of();
        }

        // 掃引時より広いシーカーになっている場合、その範囲はまだ一度も掃引していない。
        if (++this.sinceSweep < SWEEP_TICKS && seeker <= this.sweptTo) {
            return this.distant;
        }

        this.sinceSweep = 0;
        this.sweptTo = seeker;

        AABB box = this.vehicle.getBoundingBox().inflate(seeker);
        List<Entity> found = this.vehicle.level().getEntities(this.vehicle, box, this::couldTarget);

        this.distant = found.isEmpty() ? List.of() : found;

        return this.distant;
    }

    /**
     * このシーカーがその特定の目標に対して出せる距離。
     *
     * <p>各シーカーは目標自身の被探知性を相手にしており、しかも見ている物が違う。レーダー反射を追う
     * シーカーが相手にするのは反射断面積で、見つけたレーダーと同じ。熱を追う物が相手にするのは排気で、
     * レーダーを散らすよう機体を整形してもその熱には何の効果も無い。それがステルス機の取引だ——遠距離では
     * 極めて見つけにくく、熱源追尾を持つ何かが近づいて見られる距離になれば当たりやすさは変わらない。
     *
     * <p>後者に対してパイロットにできるのはミリタリー推力で飛ぶこと。アフターバーナーは大きな推力と大きな
     * 熱を意味し、その熱は機体単体よりずっと遠くから見える。
     * {@link AircraftEntity#infraredSignature} 参照。
     *
     * <p>どちらの係数も1を超えない。これは数値の趣味ではない。ここで<em>検討される</em>のは上の掃引が
     * 見つけた物だけで、その掃引はシーカー自身の探知距離と同じ大きさの箱だ。それを超える距離は、誰も見て
     * いない空へ手を伸ばすことになる。
     */
    private static double reachAgainst(WeaponDefinition.Guidance guidance, Entity candidate, double seeker) {
        return switch (guidance.seeker()) {
            case RADAR -> seeker * AircraftEntity.visibility(candidate);
            case HEAT -> seeker * AircraftEntity.heatVisibility(candidate);
            // 目標指示は人間がカメラ越しに物を見ること。反射断面積が小さくても排気が冷たくても見えにくく
            // はならないので、ここでは何も割り引かず、ポッド自身の到達距離が答えの全部になる。
            case LASER, POINT, BEAM -> seeker;
        };
    }

    /** 1つずつ提示される候補のうち、照準線に最も近い物を保持する。 */
    private static final class Aim {
        private final Vec3 from;
        private final Vec3 nose;
        private double bestAlignment;
        @Nullable
        private Entity best;

        private Aim(Vec3 from, Vec3 nose, double widest) {
            this.from = from;
            this.nose = nose;
            this.bestAlignment = widest;
        }

        /**
         * @param minAlignment この候補が通ってよい最も狭い一致度。{@link #bestAlignment} 自身の下限とは
         *                     限らない——同じ {@code Aim} に提示される、より広い視野を持つ供給源があっても、
         *                     この候補は自分の狭い方の基準で判定される。{@link #bestCandidate} 参照
         */
        private void consider(Entity candidate, double reach, double minAlignment) {
            Vec3 middle = candidate.position().add(0.0, candidate.getBbHeight() * 0.5, 0.0);
            Vec3 gap = middle.subtract(this.from);
            double distance = gap.length();

            if (distance > reach || distance < 1.0E-3) {
                return;
            }

            double alignment = gap.scale(1.0 / distance).dot(this.nose);

            if (alignment >= minAlignment && alignment > this.bestAlignment) {
                this.bestAlignment = alignment;
                this.best = candidate;
            }
        }
    }

    /**
     * シーカーが見る対象。生き物か、他の機体、そして飛んでくる誘導ミサイル。見ている本人の機体、それに
     * 乗っている者、そしてミサイル以外の発射物は対象外——機関砲の1発ずつをロック候補に載せれば、撃ち合いの
     * 間スコープが弾で埋まる。
     */
    private boolean couldTarget(Entity candidate) {
        if (candidate == this.vehicle || WeaponMounts.isPartOf(this.vehicle, candidate)) {
            return false;
        }

        // 誘導ミサイルは迎撃対象。ただし自分のレールから出た物は違う——発射直後のシーカーが最も強く
        // 見るのは、まさに今自分が撃った1本だから。
        if (candidate instanceof RocketEntity missile) {
            return missile.isInterceptable() && !missile.wasFiredBy(this.vehicle);
        }

        if (candidate instanceof VehicleProjectile) {
            return false;
        }

        if (!candidate.isAlive() || candidate.isSpectator()) {
            return false;
        }

        // 他の機体に乗っている者は単独の目標ではない。機体ではなく乗員を取ったシーカーは、同じ空域を
        // 指しながら間違ったことを言う。スコープは機体をただの目標として表示し、ミサイルは中の人間を追う。
        // 操縦桿から手を離せば、ミサイルは落ちていく体を追うことになる。
        if (candidate.getVehicle() instanceof VehicleEntityBase) {
            return false;
        }

        // 燃え尽きた機体にミサイルを使う価値は無い。目標のままにすれば空で最もロックしやすい物になる
        // ——機動せず、フレアも出さず、どこへも行かない——ので、シーカーは今撃ってきている機体ではなく
        // 既に撃墜した機体に落ち着いてしまう。
        if (candidate instanceof VehicleEntityBase machine) {
            return !machine.isWrecked();
        }

        // 標的ドローンは生き物でも機体でもないが、ロックされるために存在する物。
        if (candidate instanceof TargetDroneEntity) {
            return true;
        }

        return candidate instanceof LivingEntity;
    }

    /** 計器が必要とする物。どのエンティティか、そしてシーカーが既に捉えたか。 */
    public void save(CompoundTag tag) {
        if (this.target != null) {
            tag.putInt("Target", this.target.getId());
            tag.putBoolean("Locked", this.locked);
            tag.putInt("Held", this.held);
        }
    }

    /**
     * サーバーが送ってきた内容を読み戻す。使われるのはクライアントだけで、エンティティは通信で届いた ID
     * から引く。
     */
    public void load(CompoundTag tag) {
        if (!tag.contains("Target")) {
            this.clear();

            return;
        }

        this.target = this.vehicle.level().getEntity(tag.getInt("Target"));
        this.locked = tag.getBoolean("Locked");
        this.held = tag.getInt("Held");
        this.missing = 0;
    }
}
