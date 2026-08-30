package com.ashvehicles.vehicle;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 飛ぶ物でも走る物でも、全機体のファイルが持つ4つのブロック。ゲームから見た大きさ、描き方、カメラ位置、
 * そして音。
 *
 * <p>以前は同じレコードが2つの定義に1つずつ、計2回書かれており、既に食い違い始めていた——同じフィールドに
 * 2つの既定値があり、片方にだけ修正が入っていた。機体と戦車で本当に違うのは機体と戦車そのもの、つまり翼・
 * 駆動系・砲塔であって、そのどれもここには無い。
 *
 * <p>片方の種類にしか意味の無いフィールドは単に optional にし、もう片方は書かない。機体は脚の作動音を
 * 指定し戦車は指定しない。戦車は転輪を並べ機体は並べない。誰も無意味な行を書かされず、1フィールドのために
 * 自分専用のレコードを持たされることもない。
 */
public final class VehicleChassis {
    private VehicleChassis() {
    }

    /**
     * Minecraft がエンティティを登録する素の直方体。エンティティ型の登録時に固定される。起動時に MOD
     * 同梱のファイルから読むので、他と違いデータパックからは変えられない。
     *
     * <p>Minecraft はエンティティを「底面が正方形の直立した箱」としてしか記述できず、15m の機体や 7m の
     * 戦車にとってそれは小屋だ。だから意図的に小さくしてある。胴体と翼根、あるいは車体だけを覆い、残りは
     * はみ出させる。本当の形は隣の {@code boxes} で、弾が当たる相手も、上に立つ床も、地上車両なら進行を
     * 止める相手も、こちらではなくそちら。
     *
     * @param shape 機体が実際に構成される箱。同じブロック内の {@code boxes} から読む。幅・高さと違い、
     *              ファイル内の他の項目と同様 {@code /reload} のたびに読み直される
     * @param trackingRange 他プレイヤーへこの機体が送られる距離（チャンク）
     * @param ghostRange 機体が送られ続ける距離（ブロック）。追跡距離を越え、プレイヤーがロードしている
     *                   chunk の縁を越えても報告され続け、ゴーストとして描かれる。高高度の機体は真下の
     *                   地面よりずっと遠くから見えるから。0 で制限なし。地上の物には用途が無い
     */
    public record Hitbox(float width, float height, int trackingRange, int ghostRange, VehicleShape shape) {
        public static final Hitbox DEFAULT = new Hitbox(4.0F, 2.0F, 12, 0, VehicleShape.NONE);

        public static final Codec<Hitbox> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("width").forGetter(Hitbox::width),
                Codec.FLOAT.fieldOf("height").forGetter(Hitbox::height),
                Codec.INT.optionalFieldOf("tracking_range", 12).forGetter(Hitbox::trackingRange),
                Codec.INT.optionalFieldOf("ghost_range", 0).forGetter(Hitbox::ghostRange),
                VehicleShape.MAP_CODEC.forGetter(Hitbox::shape)
        ).apply(instance, Hitbox::new));

        /** ある距離で報告が止まる機体かどうか。 */
        public boolean hasGhostLimit() {
            return this.ghostRange > 0;
        }
    }

    /**
     * 機体の描き方。ジオメトリ・テクスチャ・アニメーションのファイルは名前で見つかるので、{@code su_25}
     * という機体は指定しなくても {@code geo/entity/su_25.geo.json} と
     * {@code textures/entity/su_25.png} から描かれる。
     *
     * @param scale モデルへ一様に掛けるスケール。Minecraft の縮尺で作られていないモデル向け
     * @param bones ジオメトリのどのボーンがどの役割を担うか。キーは機体種別ごとに定義された役割名。
     *              書かれなかった物は単に動かない
     * @param roadWheels 装軌車両の転輪と起動輪のボーン。走行距離から求めた速度で全部一緒に回る。役割名で
     *                   はなくリストなのは、戦車の転輪は台数分あって互いに区別が要らないから
     *                   （レオパルト2 なら18個で、これを読む側はどれがどれか気にしない）
     * @param steeredWheels 操舵で向きが変わるボーン。装輪車なら前1〜2軸、装軌車なら該当なし。ここに載る
     *                      車輪はたいてい {@link #roadWheels} にも載る。両者は同じ車輪への別の問い
     *                      （どれだけ転がったか／どちらを向いているか）で、駆動する前輪は両方に該当する
     * @param steerLock フルロック時の切れ角（度）。省略した場合は0で、いくら操舵しても真っ直ぐのまま
     * @param track 履帯全体を組み立てる元になる1リンク。ジオメトリ内に他のパーツと同様に履帯が描かれて
     *              いる車両では省略する
     * @param propellers 回すプロペラのボーン。役割名ではなくリストなのは、4発機のプロペラは4枚あって互いに
     *                   区別が要らないから——戦車の転輪と同じ理由だ。回転の中心は各ボーン自身のジオメトリの
     *                   中心で、支点がそこに無くても構わない。実際、プロペラを複製して作ったモデルでは4枚が
     *                   同じ支点を共有していることの方が多く、そのまま支点周りに回せば3枚が遠くの1点を公転
     *                   する。{@code VehicleGeoModel.spinZ} 参照
     * @param slavedTurrets 主砲塔と同じ目標に指向される追加砲塔のボーン。同じ射撃指揮に従属した軍艦の
     *                      第2砲塔など。それぞれ自分のリング回りに主砲塔の照準へ旋回・俯仰するので、
     *                      揃って指向する。主砲塔自体は {@code turret}/{@code gun} ボーンでありここには
     *                      書かない。単砲の車両は空にする
     */
    public record Model(float scale, Map<String, String> bones, List<String> roadWheels,
            List<String> steeredWheels, float steerLock, Optional<Track> track, List<String> slavedTurrets,
            List<String> propellers) {
        public static final Model DEFAULT =
                new Model(1.0F, Map.of(), List.of(), List.of(), 0.0F, Optional.empty(), List.of(), List.of());

        public static final Codec<Model> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("scale", 1.0F).forGetter(Model::scale),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("bones", Map.of())
                        .forGetter(Model::bones),
                Codec.STRING.listOf().optionalFieldOf("road_wheels", List.of()).forGetter(Model::roadWheels),
                Codec.STRING.listOf().optionalFieldOf("steered_wheels", List.of())
                        .forGetter(Model::steeredWheels),
                Codec.FLOAT.optionalFieldOf("steer_lock", 0.0F).forGetter(Model::steerLock),
                Track.CODEC.optionalFieldOf("track").forGetter(Model::track),
                Codec.STRING.listOf().optionalFieldOf("slaved_turrets", List.of())
                        .forGetter(Model::slavedTurrets),
                Codec.STRING.listOf().optionalFieldOf("propellers", List.of()).forGetter(Model::propellers)
        ).apply(instance, Model::new));

        /** この機体に操舵で向きが変わる車輪があるか。 */
        public boolean isSteered() {
            return this.steerLock > 0.0F && !this.steeredWheels.isEmpty();
        }

        /** その役割に指定されたボーン名。該当する部位が無ければ空文字。 */
        public String bone(String role) {
            return this.bones.getOrDefault(role, "");
        }
    }

    /**
     * 1つのリンクから描画時に組み立てる履帯。
     *
     * <p>代案は多くの戦車モデルがやっていること。60本前後のボーンをリンク1つにつき1本、手作業で車輪の
     * 周りに並べ、作者が曲げられるよう親子付けする方式だ。それは車両1台につき半日の作業で、車輪が動いた
     * 瞬間に破綻し、そもそもアニメーションできない——リンクは置かれた場所にあるだけなので、履帯は回らない
     * か、60本全部にキーフレームを打つかの二択になる。
     *
     * <p>そこで、1リンクを何度も描く。リンクの配置は転輪自体から求める（ファイルは既に転輪を列挙している）。
     * 各転輪は自分のジオメトリが与える大きさで扱われ、履帯はその全部に張った帯になる。実物の履帯が起動輪・
     * 誘導輪・転輪に張られた帯であるのと同じ。モデル上で車輪を動かしても大きくしても、ここは1行も変えずに
     * 履帯が追従する。
     *
     * <p>左右とも同じリンクから作る。車輪は車体のどちら側にあるかで2群に分かれ、各群が自分の車輪の張り出し
     * 位置に自分の帯を得るので、車両に必要なリンクボーンは片側ごとではなく1本。
     *
     * @param link 1リンク分を持つボーン。履帯のリンク数だけ描かれる。子付けされた物も一緒に来るので、
     *             センターガイドやパッドの付いたリンクもここでは1ボーン
     * @param wheels 帯を張る対象のボーン。空なら転輪を使う。履帯が転輪でない非回転物（上部支持輪、履帯
     *               スキッド）に触れる場合や、ある車輪が帯を形作らず内側にある場合に指定する価値がある
     * @param pitch リンク間の距離（ブロック）。0 ならリンク自身のジオメトリから取る。ジオメトリから取った
     *              場合はリンクの長さそのものになり、隙間の無い履帯になる
     * @param spacing ピッチの倍率。隣と重ねたい、あるいは離したいリンク用。1未満なら重なって密になる
     * @param outset 帯が車輪のリムからどれだけ外側に出るか（ブロック）。空ならリンク自身の厚みの半分——
     *               リンクの内面が車輪に接する、本来あるべき位置になる
     * @param maxLinks 片側に描くリンク数の上限。ピッチがほぼ0になって1万個要求されるのを防ぐ保険であり、
     *                 調整用の数値ではない
     */
    public record Track(String link, List<String> wheels, float pitch, float spacing,
            Optional<Float> outset, int maxLinks) {
        public static final Codec<Track> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("link").forGetter(Track::link),
                Codec.STRING.listOf().optionalFieldOf("wheels", List.of()).forGetter(Track::wheels),
                Codec.FLOAT.optionalFieldOf("pitch", 0.0F).forGetter(Track::pitch),
                Codec.FLOAT.optionalFieldOf("spacing", 1.0F).forGetter(Track::spacing),
                Codec.FLOAT.optionalFieldOf("outset").forGetter(Track::outset),
                Codec.INT.optionalFieldOf("max_links", 256).forGetter(Track::maxLinks)
        ).apply(instance, Track::new));

        /** 帯を張る対象のボーン。ここに書かれた物、無ければ転輪。 */
        public List<String> wheelsOr(List<String> roadWheels) {
            return this.wheels.isEmpty() ? roadWheels : this.wheels;
        }
    }

    /**
     * カメラの位置。
     *
     * @param pos 追従カメラ。機体軸ではなく<em>視界</em>軸で測る。x が画面右、y が真上、z が視線方向で
     *            負が後方。これにより機体がどちらを向いていても画面内で静止する。
     *            {@link com.ashvehicles.client.ChaseCamera} 参照
     * @param tilt 追従視点を下向きに傾ける角度（度）。機体をたまたま通る水平線に沿ってではなく、上から
     *             見下ろすため。{@code pos.y} とは別物で、あちらは画面内で機体を下げるだけで他の写り方を
     *             変えない。こちらは視界全体を回し、それに伴い {@code pos} を測る軸も回るので、傾けると
     *             カメラが上がる。戦車には数度欲しい——戦う相手のいる地面は空より画面を占める価値がある。
     *             機体には不要で、あちらは空にこそ全てがある
     * @param cockpit 一人称視点の目の位置。機体軸で x が右、y が上、z が前。機体に固定されているので、
     *                視界は翼と一緒にロールし、車体と一緒に斜面へ傾く。砲塔付きの機体では砲塔上の点で、
     *                砲塔正面時の座標で書き、リング回りに一緒に振られる
     *                <p>自前の目を持たない座席はこの目を使う。座席が目を持てるようになる以前は、全機体の
     *                ファイルがこれだけを書いていた——{@link Seat} 参照
     */
    public record CameraMount(Vec3 pos, float tilt, Vec3 cockpit) {
        public static final CameraMount DEFAULT =
                new CameraMount(new Vec3(0.0, 2.5, -24.0), 0.0F, new Vec3(0.0, 2.5, 3.4));

        public static final Codec<CameraMount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("pos").forGetter(CameraMount::pos),
                Codec.FLOAT.optionalFieldOf("tilt", 0.0F).forGetter(CameraMount::tilt),
                Vec3.CODEC.fieldOf("cockpit").forGetter(CameraMount::cockpit)
        ).apply(instance, CameraMount::new));

        // 照準ポッド用の3つ目のカメラは意図的に置いていない。あの映像が撮られるのは機体上の場所では
        // なくポッド上の場所であり、ポッドがどのステーションに吊られているかは機体ファイルではなく機体を
        // 武装させた者が決めるから。EquipmentDefinition.lensAt 参照。
    }

    /**
     * 乗り込む場所と、そこにいる者が何を通して外を見るか。
     *
     * <p>以前、乗員位置は点でしかなく、対応する目は機体全体で1点だった。単座機には正しく、それ以外には
     * 間違っている。7人乗りの CV90 では下車兵の目が車長キューポラにあり、F-14 の後席は前席のキャノピー
     * から外を見て、駆逐艦は艦橋の8ブロック下に座らせた者に艦橋を見せていた。そこで目は座席の物とし、
     * 座席の隣に置いた。座席を動かしても増やしても両者が離れないように。
     *
     * <p>座席は今も裸の点として書けるし、そう書かれている大多数は従来通りの意味を保つ。目は
     * {@code camera.cockpit} にフォールバックし、視界も以前のまま。動かし続けるために書き直す必要は無く、
     * 機体は座席1つずつ改善していける。
     *
     * @param pos 乗員の位置。機体軸（x 右、y 上、z 前）でブロック単位。これは<em>足元</em>であって、
     *            そこから見る点ではない。最初の座席が操縦席
     * @param eye その乗員の目の位置。同じ軸で。空なら機体の {@code camera.cockpit}。座席からの高さでは
     *            なく絶対位置で与える。ハッチから身を乗り出した頭は、車体床にある足の真上には無いから
     * @param mount 目の取り付け先。空なら機体の既定（砲塔があれば砲塔、艦や機体なら船体）。座席ごとに
     *              書く価値があるのは、戦車の乗員が実際に違うから。車長の頭は砲塔上面から出ていて砲と
     *              一緒に回るが、操縦手の頭は前面装甲の中にあって回らない。これは<em>目</em>の話であって
     *              座席の話ではない。乗員の体をどこへ置くかは機体側の判断で、ここでは変えない
     */
    public record Seat(Vec3 pos, Optional<Vec3> eye, Optional<VehicleShape.Mount> mount) {
        /** 位置以外を何も言わない座席。ファイル内の裸の点はこれになる。 */
        public static Seat at(Vec3 pos) {
            return new Seat(pos, Optional.empty(), Optional.empty());
        }

        private static final Codec<Seat> SPELLED_OUT = RecordCodecBuilder.create(instance -> instance.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Seat::pos),
                Vec3.CODEC.optionalFieldOf("eye").forGetter(Seat::eye),
                VehicleShape.Mount.CODEC.optionalFieldOf("mount").forGetter(Seat::mount)
        ).apply(instance, Seat::new));

        /**
         * どちらの形式も受ける。従来から書けた裸の点と、位置以上を言うブロック。書き出しは座席が実際に
         * 必要とする形式で行うので、新しいことを何も言わないファイルの全座席が波括弧に包まれたりしない。
         */
        public static final Codec<Seat> CODEC = Codec.either(Vec3.CODEC, SPELLED_OUT).xmap(
                either -> either.map(Seat::at, seat -> seat),
                seat -> seat.saysMoreThanWhere()
                        ? com.mojang.datafixers.util.Either.right(seat)
                        : com.mojang.datafixers.util.Either.left(seat.pos()));

        private boolean saysMoreThanWhere() {
            return this.eye.isPresent() || this.mount.isPresent();
        }

        /** この座席の目。持たなければ機体共通の目。 */
        public Vec3 eyeOr(Vec3 machineWide) {
            return this.eye.orElse(machineWide);
        }

        /** この座席の目の取り付け先。指定が無ければ機体の既定。 */
        public VehicleShape.Mount mountOr(VehicleShape.Mount machineWide) {
            return this.mount.orElse(machineWide);
        }
    }

    /**
     * エンジンの音。音声そのものは他の Minecraft の音と同様、リソースパックの {@code sounds.json} と
     * {@code .ogg} にある。ここが言うのはどれを使い、どう鳴らすかだけ。
     *
     * <p>音声は次の順で探す。ここに書かれた {@code engine} イベント、無ければ機体名から作った名前
     * （{@code su_25} なら {@code ashvehicles:engine.su_25}）、それも無ければ MOD の既定。だから専用の
     * 音声を持たない機体も何らかの音は出すし、専用の音を与えるにはファイルを置いて {@code sounds.json}
     * に書くだけでよく、ここは変更不要。
     *
     * <p>音声は一定の設定で回るエンジンの定常ループであること。負荷の高さは音量と再生速度で表現し、
     * 音声を切り替えることでは表現しない。
     *
     * @param engine 使う音イベント。空なら機体名から探す
     * @param gear 機体の脚が作動する音のイベント。空なら機体名から探す。これもループで、脚が動いている
     *             間だけ、音量も再生速度も一定で鳴らす。以下の数値はエンジン専用。地上の物は持たない
     * @param volume 全開時、機体の真横での音量。1 が収録そのまま
     * @param idleVolume エンジンが回っている停止時の、上記に対する比率
     * @param pitchMin 停止時の再生速度
     * @param pitchMax 全開時の再生速度
     * @param range これより遠いと一切聞こえない距離（ブロック）。そこまで滑らかに減衰する。ジェットは
     *              見えるずっと前から聞こえるので数百ブロック欲しい。ディーゼルは開けた地形をよく通るが
     *              そこまでではない
     */
    public record Sound(Optional<ResourceLocation> engine, Optional<ResourceLocation> gear,
            float volume, float idleVolume, float pitchMin, float pitchMax, float range) {
        public static final Sound DEFAULT =
                new Sound(Optional.empty(), Optional.empty(), 1.0F, 0.35F, 0.7F, 1.25F, 512.0F);

        public static final Codec<Sound> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("engine").forGetter(Sound::engine),
                ResourceLocation.CODEC.optionalFieldOf("gear").forGetter(Sound::gear),
                Codec.FLOAT.optionalFieldOf("volume", DEFAULT.volume()).forGetter(Sound::volume),
                Codec.FLOAT.optionalFieldOf("idle_volume", DEFAULT.idleVolume()).forGetter(Sound::idleVolume),
                Codec.FLOAT.optionalFieldOf("pitch_min", DEFAULT.pitchMin()).forGetter(Sound::pitchMin),
                Codec.FLOAT.optionalFieldOf("pitch_max", DEFAULT.pitchMax()).forGetter(Sound::pitchMax),
                Codec.FLOAT.optionalFieldOf("range", DEFAULT.range()).forGetter(Sound::range)
        ).apply(instance, Sound::new));
    }

    /**
     * 機体のレーダーと、警戒受信機の可聴距離。
     *
     * <p>レーダーは機体が照準に使う方向だけを見る。その周りの円錐を掃引するので、誰かを見つけるには居そう
     * な方向を向く必要があり、目標から向きを外せば失探する。それが「空の地図」ではなく「レーダー」を持つ
     * 意味になる。機体では円錐は機首回りなのでパイロットが機体を向け、砲塔付き車両では砲身回りなので乗員が
     * 旋回させる——同じ計器が、その機体が照準に使う物に従っているだけ。
     * {@link com.ashvehicles.sensor.Sensors} 参照。
     *
     * <p>警戒受信機は逆で、円錐を一切持たない。他人のレーダーをどの方向からでも聞く。それこそが存在理由
     * で、この装置が扱うのは「見えていなかった物」であり、それは背後にある。
     *
     * @param range レーダーの探知距離（ブロック）。数百ではなく数kmで、それが機体同士が発見し合う距離
     *              であり、そこには他に見つける物が無いから。0以下ならレーダー非搭載で、レーダーの無い
     *              乗員はスコープを持たず、シーカーの届く相手しかロックできない
     * @param arc 掃引の半頂角（度）。機体が照準する方向からの角度。180 は円錐なし、つまり機体と一緒に
     *            ではなく自分の架台で回る装置に相当する
     * @param sweepTicks 画面を描き直す間隔。レーダーは連続的に見るのではなく掃引する。スコープにあるのは
     *                   最後に通過した時点の位置
     * @param warningRange 警戒受信機が反応する最大距離（ブロック）。レーダー自身の探知距離より寛大に取る。
     *                     自分に見える距離より遠くから照射されている状況こそ、知らせる価値がある
     */
    public record Radar(float range, float arc, int sweepTicks, float warningRange) {
        public static final Radar DEFAULT = new Radar(3000.0F, 55.0F, 10, 4000.0F);
        /** レーダーも受信機も持たない機体。地上を走る物の大半がこれ。 */
        public static final Radar NONE = new Radar(0.0F, 0.0F, 10, 0.0F);

        public static final Codec<Radar> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("range", DEFAULT.range()).forGetter(Radar::range),
                Codec.FLOAT.optionalFieldOf("arc", DEFAULT.arc()).forGetter(Radar::arc),
                Codec.INT.optionalFieldOf("sweep_ticks", DEFAULT.sweepTicks()).forGetter(Radar::sweepTicks),
                Codec.FLOAT.optionalFieldOf("warning_range", DEFAULT.warningRange()).forGetter(Radar::warningRange)
        ).apply(instance, Radar::new));

        /** そもそもレーダーを積んでいるか。 */
        public boolean fitted() {
            return this.range > 0.0F;
        }

        /** 掃引する理由があるか（レーダー、受信機、またはその両方）。 */
        public boolean exists() {
            return this.fitted() || this.warningRange > 0.0F;
        }

        /** 探す価値のある最大距離。レーダーと受信機の遠い方。 */
        public double reach() {
            return Math.max(this.range, this.warningRange);
        }
    }

    /**
     * 燃料タンク。積める量と、エンジンがそれを消す速さ。
     *
     * <p>機体も戦車も艦も同じ物を持つのでここにある。中身は全部「エンジンが働くと減る1つの数」で、種類ごとに
     * 違うのはその数の大きさだけだ。
     *
     * <p><b>消費はエンジン負荷に従う。</b> 速度でも移動距離でもない。全開のジェットは駐機してアイドルしている
     * ジェットの何倍も燃料を吸うし、それは前へ進んでいるかどうかとは無関係だ——垂直に上っている機体はまさに
     * それを最も速く消費する。だから {@code VehicleEntityBase.getEngineNote()}——各機械が既に「エンジンが
     * どれだけ働いているか」として答えている値——を燃料計算にもそのまま使う。エンジン音を決めている物と
     * 燃費を決める物が同じであるべきなのは、実機でも同じだ。
     *
     * <p><b>アイドルは無料ではないが、放置は無料だ。</b> 誰も乗っておらずスロットルも入っていない機械は
     * エンジンが止まっていると見なし、何も消費しない。野原に置き去りにした戦車が、翌週には空タンクで
     * 動かせなくなっている——それは誰も望まない現実味であり、燃料切れを「補給を怠った結果」ではなく
     * 「時間が経った結果」にしてしまう。
     *
     * @param capacity 満タンの量。0以下なら燃料の概念そのものを持たない機械になり、無限に走る。書かない
     *                 ファイルには既定値が入るので、これは「燃料を切りたい」と明示するための逃げ道
     * @param burnRate ミリタリー全開1tickあたりの消費量。既定値は容量1000に対する物で、全開でおよそ10分
     * @param idleFraction スロットルを絞りきったエンジンが、全開の何割を消費するか。0では「アイドル中は
     *                     完全に無料」になり、着陸してから給油するまでの猶予が無限になる
     * @param afterburnerRate 全再燃焼時の消費倍率。バーナーの対価のうち、熱と音に続く3つ目。戦闘機で3前後が
     *                        妥当で、それが「点けっぱなしで飛ぶ」を戦術ではなく失敗にする
     * @param refuelRate 燃料アイテム1個が入れる量
     */
    public record Fuel(float capacity, float burnRate, float idleFraction, float afterburnerRate,
            float refuelRate) {
        /** 燃料を書いていない機体向け。全開でおよそ10分、アイドルなら1時間以上。 */
        public static final Fuel DEFAULT = new Fuel(1000.0F, 0.083F, 0.12F, 3.0F, 250.0F);

        /**
         * 燃料を書いていない地上車両向け。全開でおよそ40分、アイドルならその何倍も走る。
         *
         * <p>機体の既定値より寛容にしてある。戦車は前線へ自走してから戦い、そして帰る。給油の間隔が飛行と
         * 同じでは、燃料は兵站ではなく雑務になる。再燃焼は持たないので、その倍率は1のままだ。
         *
         * <p>地上車両側ではなくここに置いてある。あちらに置くと、そちらのファイル先頭の {@code CODEC} が
         * 初期化される時点——同じクラスの、この定数より前の行——で {@code Powertrain.CODEC} が読みに来て
         * null を掴み、既定値 null の {@code optionalFieldOf} がワールド生成時に NPE で落ちる。別クラスの
         * 定数なら、最初に触れられた時点で必ず初期化済みになる。
         */
        public static final Fuel GROUND = new Fuel(1000.0F, 0.021F, 0.10F, 1.0F, 250.0F);

        /** 燃料を持たない機械。何も消費せず、給油もできない。 */
        public static final Fuel NONE = new Fuel(0.0F, 0.0F, 0.0F, 1.0F, 0.0F);

        public static final Codec<Fuel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("capacity", DEFAULT.capacity()).forGetter(Fuel::capacity),
                Codec.FLOAT.optionalFieldOf("burn_rate", DEFAULT.burnRate()).forGetter(Fuel::burnRate),
                Codec.FLOAT.optionalFieldOf("idle_fraction", DEFAULT.idleFraction())
                        .forGetter(Fuel::idleFraction),
                Codec.FLOAT.optionalFieldOf("afterburner_rate", DEFAULT.afterburnerRate())
                        .forGetter(Fuel::afterburnerRate),
                Codec.FLOAT.optionalFieldOf("refuel_rate", DEFAULT.refuelRate()).forGetter(Fuel::refuelRate)
        ).apply(instance, Fuel::new));

        /** そもそも燃料を積む機械か。容量を持たない物は、何も消費せず止まりもしない。 */
        public boolean fitted() {
            return this.capacity > 0.0F;
        }

        /**
         * この負荷で1tickに消える量。
         *
         * @param load エンジンがどれだけ働いているか（0〜1）。{@code getEngineNote()} の値
         * @param reheat 再燃焼の割合（0〜1）。持たないエンジンでは0
         */
        public float burn(float load, float reheat) {
            float idle = Mth.clamp(this.idleFraction, 0.0F, 1.0F);
            float working = idle + (1.0F - idle) * Mth.clamp(load, 0.0F, 1.0F);
            float burner = 1.0F + Mth.clamp(reheat, 0.0F, 1.0F) * (Math.max(this.afterburnerRate, 1.0F) - 1.0F);

            return Math.max(this.burnRate, 0.0F) * working * burner;
        }
    }
}
