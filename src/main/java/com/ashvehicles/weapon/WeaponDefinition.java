package com.ashvehicles.weapon;

import java.util.Optional;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.Vec3;

/**
 * 兵装1つを JSON だけで記述した物。{@code data/ashvehicles/weapon/} にファイルを置けば起動時に MOD が
 * アイテムを登録し、どの機体のパイロンにも吊れるようになる。機体ファイル側が内蔵兵装として名指しすること
 * もできる。
 *
 * <p>機体と同じく、起動時に「何が存在するか」を知るために一度、{@code /reload} のたびにデータパックから
 * もう一度読まれるので、再起動なしで兵装を調整できる。
 *
 * <p>速度は MOD 内の他と同じく1tickあたりブロック。
 *
 * @param type 兵装の種類。飛び方と必要な物が決まる
 * @param item MOD がアイテムを登録すべきか。機体に内蔵された砲を持ち歩く理由は無いが、ポッドにはある
 * @param ammo 満載時に架台1つが持つ発数
 * @param ammoItem どの弾薬アイテムで補給するか。空なら発射方式から判定する。{@link #ammoKind()} 参照
 * @param firing 撃ち方
 * @param projectile 撃つ物
 * @param guidance 誘導方式。誘導する兵装のみ。無ければ誘導しない
 * @param requires これを撃つ前に機体が積んでいなければならないポッドの種別。{@link #requires()} 参照
 * @param sound 音
 * @param nation この兵装の国籍。機体の {@code airframe.nation} と同じ書き方。書かなければ国籍を持たない
 *               汎用品で、どの機体も自分の物として扱う（増槽がそう）
 */
public record WeaponDefinition(Type type, boolean item, int ammo, Optional<AmmoKind> ammoItem,
        Optional<GunClass> gunClass,
        Firing firing, Projectile projectile, Optional<Guidance> guidance,
        Optional<EquipmentDefinition.Kind> requires, SoundSetup sound, float drag, float mass,
        Optional<Cluster> cluster, Optional<String> nation) {

    /** {@code RRGGBB}。先頭の # は有っても無くてもよい。この種のファイルでの色表記はすべてこれ。 */
    static final Codec<Integer> COLOUR = Codec.STRING.comapFlatMap(
            text -> {
                try {
                    return DataResult.success(Integer.parseInt(text.replace("#", ""), 16));
                } catch (NumberFormatException exception) {
                    return DataResult.error(() -> "Not a colour: " + text);
                }
            },
            colour -> String.format("%06X", colour));

    public static final Codec<WeaponDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Type.CODEC.optionalFieldOf("type", Type.GUN).forGetter(WeaponDefinition::type),
            Codec.BOOL.optionalFieldOf("item", true).forGetter(WeaponDefinition::item),
            Codec.INT.fieldOf("ammo").forGetter(WeaponDefinition::ammo),
            AmmoKind.CODEC.optionalFieldOf("ammo_item").forGetter(WeaponDefinition::ammoItem),
            // この砲が何であるか。戦車砲・榴弾砲・機関砲・機関銃のどれかで、書けば、その種類のために
            // 書かれた弾種しか入らなくなる。省略すれば種類を持たない砲で、車両ファイルが並べた弾種を
            // そのまま受け付ける——この欄が書かれる前の全兵装がそうであり、弾種を使わない砲には要らない。
            //
            // 口径の数値ではないことに理由がある。GunClass 参照。
            GunClass.CODEC.optionalFieldOf("gun_class").forGetter(WeaponDefinition::gunClass),
            Firing.CODEC.fieldOf("firing").forGetter(WeaponDefinition::firing),
            Projectile.CODEC.fieldOf("projectile").forGetter(WeaponDefinition::projectile),
            Guidance.CODEC.optionalFieldOf("guidance").forGetter(WeaponDefinition::guidance),
            EquipmentDefinition.Kind.CODEC.optionalFieldOf("requires").forGetter(WeaponDefinition::requires),
            SoundSetup.CODEC.optionalFieldOf("sound", SoundSetup.DEFAULT).forGetter(WeaponDefinition::sound),
            // 吊っている間ずっと機体が払う代償。省略すれば0で、これを書く前の全兵装がそうだった。
            //
            // 単位は機体ファイルの {@code wing.drag} と同じで、あちらへ直接足される。つまり戦闘機の
            // 0.00003 前後が「機体まるごと1機分の抗力」であり、ここに書く値はその一部でなければならない。
            // 増槽1本で1割、つまり 0.000003 あたりが妥当な出発点だ。桁を1つ間違えると機体は飛ばなくなる
            // ——見慣れない大きさなので、書く前に対象機体の wing.drag を必ず見ること。
            Codec.FLOAT.optionalFieldOf("drag", 0.0F).forGetter(WeaponDefinition::drag),
            // 満載のこれ1つが量る重さ（kg）。実物の重量をそのまま書く——AIM-9 は 85、FAB-500 は 500、
            // 20連装のロケットポッドはポッド自体と中身を足した 380。省略すれば0で、これを書く前の全兵装が
            // そうだった。
            //
            // 抗力と違い、これは実在の単位だ。機体側の {@code airframe.mass} と {@code airframe.payload}
            // が同じ kg で書かれており、この値はそちらへ直接足される。だから桁を間違えても「飛ばなくなる」
            // のではなく「吊れなくなる」——それは見れば分かる間違いだ。
            //
            // <b>残弾では減らない。</b>吊っているのはケースの方で、20発撃ったロケットポッドも空の筒として
            // 同じ場所にぶら下がっている。撃ち尽くしたミサイルや爆弾はレールから消える（{@code expend} 参照）
            // ので、そちらは投下した瞬間に軽くなる。空になった増槽が軽くならないのは正しく、だからこそ
            // 落とすことに意味がある。
            Codec.FLOAT.optionalFieldOf("mass", 0.0F).forGetter(WeaponDefinition::mass),
            Cluster.CODEC.optionalFieldOf("cluster").forGetter(WeaponDefinition::cluster),
            // 国籍。吊れるかどうかは決めず、出撃盤のプリセットが自国の物を先に選ぶのに使うだけ。
            // {@code AircraftDefinition.Airframe#uses} 参照。
            Codec.STRING.optionalFieldOf("nation").forGetter(WeaponDefinition::nation)
    ).apply(instance, WeaponDefinition::new));

    /**
     * クラスター弾頭。1発の終わりに、もっと小さい弾を大量に撒く。
     *
     * <p><b>子弾は別の兵装ファイルだ。</b>威力も爆発規模も落下も、親の弾を書いたのとまったく同じ書き方で
     * 書かれる。ここが名前で指すだけなので、同じ子弾を別の親から撒くことも、子弾だけ差し替えることもできる
     * ——この MOD で「何かを撃つ物」が全部そうしている通りだ。
     *
     * <p><b>親は撒くだけで、穴は開けない。</b>面を制圧するのが弾頭であって1点を潰すのではないので、親の
     * {@code explosion} は小さいか0であるべきで、破壊力は子弾の数×子弾の規模から出る。同じ重さの単弾頭と
     * 比べた時の差がそれだ——1つの深い穴か、広い範囲の浅い穴か。
     *
     * <p><b>撒く高さを決めるのは信管だ。</b>撒布界の広さは、子弾が落ちている間に横へ流れる距離——つまり
     * {@code spread} と落下時間の積——なので、高さが無ければ何発撒いても1点に落ちる。高さの出どころは2つある。
     *
     * <p>目標を持つ弾は {@code guidance.proximity} が決める。目標からどれだけ手前で炸裂するかであり、急角度で
     * 落ちてくる弾ではそれがそのまま散布高度になる。座標へ飛ぶ弾道弾がこれだ。
     *
     * <p>目標を持たない弾——投下されるだけのクラスター爆弾——は {@code open} が決める。地面までの高さがこれを
     * 切った時に開く、実物の散弾筒に付いている近接信管そのものだ。0 なら開傘高度を持たず、触れた所で開く。
     *
     * @param submunition 子弾の兵装ID
     * @param count 何発撒くか
     * @param spread 1発ごとに横へ与える速度（1tickあたりブロック）。落下時間と掛かって撒布界の広さになる
     * @param inherit 親の速度をどれだけ引き継ぐか。0なら真下に落ち、1なら親と同じ勢いで前へ飛ぶ
     * @param open 真下の地面までがこの高さ（ブロック）を切ったら開く。0 なら着弾まで開かない
     */
    public record Cluster(ResourceLocation submunition, int count, float spread, float inherit, float open) {
        public static final Codec<Cluster> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("submunition").forGetter(Cluster::submunition),
                Codec.INT.optionalFieldOf("count", 12).forGetter(Cluster::count),
                Codec.FLOAT.optionalFieldOf("spread", 0.3F).forGetter(Cluster::spread),
                Codec.FLOAT.optionalFieldOf("inherit", 0.25F).forGetter(Cluster::inherit),
                Codec.FLOAT.optionalFieldOf("open", 0.0F).forGetter(Cluster::open)
        ).apply(instance, Cluster::new));
    }

    /**
     * ゲームが読めるファイルが1つも無い兵装に使う値。撃ちはするのでゲームは動き続けるが、誰も本物の兵装と
     * は思わない物。
     */
    public static final WeaponDefinition FALLBACK = new WeaponDefinition(Type.GUN, true, 100, Optional.empty(),
            Optional.empty(),
            new Firing(5.0F, 1.0F, 1, 0.0F, Optional.empty()), Projectile.DEFAULT, Optional.empty(),
            Optional.empty(), SoundSetup.DEFAULT, 0.0F, 0.0F, Optional.empty(), Optional.empty());

    /**
     * 引き金を押し続けている間撃ち続けるか、それとも1押し1発か。
     *
     * <p>この違いは実のところ兵装の種類の話ではない。だからこそ規則ではなくフィールドになっている。決める
     * のは発射速度で、毎秒50発の機関砲は押しっぱなしにする物、7秒に1発の120mm は押す物——そしてどちらも
     * 「gun」だ。省略すれば gun は自動、それ以外は1押し1発になり、それはこのフィールドが存在する前に MOD
     * 内の全兵装が意味していた挙動そのもの。
     *
     * <p>機体のパイロンと車両の内蔵砲の両方が読むので、どちらに付けても同じ挙動になる。
     */
    public boolean isAutomatic() {
        return this.firing.automatic().orElse(this.type == Type.GUN);
    }

    /**
     * この砲がどの弾薬アイテムから補給されるか。
     *
     * <p><b>砲では常に空。</b> 砲へ入る物は弾種ファイルが1弾種1つで書き、どれが入るかは砲の種類が決める
     * （{@link GunClass} 参照）ので、汎用の箱で賄う道はもう無い。答えが返るのは発射筒に吊り込む物だけだ。
     *
     * <p>書きたいファイルは明示でき、無ければ兵装の種類から判定する。missile は
     * シーカーが見ている物で分かれる——空の物を追うヘッド（熱・レーダー）は対空ミサイル、地の物を狙うヘッド
     * （レーザー・視線）は対地ミサイル、座標へ飛ぶ物はただのミサイル。それ以外の筒物はロケット。これで MOD
     * 内の全兵装が、どのファイルにも1行足さずに正しく分類される。覆したければ {@code ammo_item} を書く——
     * ドラムから1発ずつ装填するリボルバーカノンはそうしたいだろうし、レーダーで地を狙う変わり種のミサイルも
     * 同じ。
     *
     * <p>これは機体の<em>内蔵</em>兵装の補給元。機体のパイロンに吊った物は兵装自体（既にアイテム）から補給
     * される。{@code WeaponMounts.draw} 参照。
     */
    public Optional<AmmoKind> ammoKind() {
        if (this.ammoItem.isPresent()) {
            return this.ammoItem;
        }

        return switch (this.type) {
            // 砲に汎用の弾薬箱は無い。入る物は弾種ファイルが1つずつ書き、どれが入るかは砲の種類が決める。
            // AmmunitionDefinition と GunClass 参照。
            case GUN -> Optional.empty();
            case MISSILE -> Optional.of(this.guidance.map(seek -> switch (seek.seeker()) {
                case HEAT, RADAR -> AmmoKind.ANTI_AIR_MISSILE;
                case LASER, BEAM -> AmmoKind.ANTI_GROUND_MISSILE;
                case POINT -> AmmoKind.MISSILE;
            }).orElse(AmmoKind.MISSILE));
            default -> Optional.of(AmmoKind.ROCKET);
        };
    }

    /**
     * この砲が受け付ける弾種の種類。種類を名乗っていない兵装では空で、そのときは制限が無い。
     *
     * <p>車両ファイルが並べた弾種のうち、ここと食い違う物は弾倉に現れない。戦車砲に機関砲弾を並べた
     * ファイルは、その1行が無かったかのように動く。{@link com.ashvehicles.weapon.Magazine} 参照。
     */
    public Optional<GunClass> takes() {
        return this.gunClass;
    }

    /** この兵装が何かへ向かって誘導するか。つまり誘導先を必要とするか。 */
    public boolean isGuided() {
        return this.guidance.isPresent();
    }

    /**
     * この兵装を撃つ前に機体が積んでいなければならないポッドの種別。あれば。
     *
     * <p>レーザー誘導爆弾とは何か——尾翼と機首のシーカーを持つ爆弾で、誰かが目標に指示器を当てていなければ
     * 狙う相手が一切無い物だ。ポッドがその指示器なので、{@code "requires": "targeting_pod"} と書くファイル
     * は「この兵装は対の片方であり、もう片方が無ければ動かない」と言っている。ラックには吊れるし計器にも
     * 出るしレンチで外せもする。ただ撃てないだけ。
     *
     * <p><b>ポッドを積める場所でのみ有効。</b> MOD 内でこの種の物が付くのは機体の専用ステーションだけなの
     * で、判定するのは {@link WeaponMounts} だけ。地上車両の内蔵兵装（{@link BuiltInGun}、
     * {@link TurretLauncher}）はいかなるステーションも持たず、要求を満たすことも「満たしていない」と告げ
     * られることもできない。つまりこのフィールドを持つ兵装を車両の砲に指定すると、動くべきでない物が動く
     * 状態で武装させることになる。やらないこと。
     */
    public Optional<EquipmentDefinition.Kind> requires() {
        return this.requires;
    }

    /**
     * 発射によって兵装自体がパイロンから無くなるか。
     *
     * <p>ミサイルや爆弾は、そこに吊られている物そのものだ。放てばレールは空になる。ポッドは中身が空でも
     * 付いたままの容器で、砲は機体構造の一部。だから使用後に描かれなくなるのは最初の種類だけで、地上要員が
     * 次を吊るまでの間だけ。
     */
    public boolean leavesRail() {
        return this.type == Type.MISSILE || this.type == Type.BOMB;
    }

    /**
     * 撃つのではなく投下する物か。投下する物は機体の速度だけを持って離れ、機首方向への自前の加速を持た
     * ない。
     */
    public boolean isDropped() {
        return this.type == Type.BOMB;
    }

    /**
     * 兵装の種類。違いは撃った物の挙動にある。gun の弾はただ飛び、rocket の弾は先にモーターで押され、
     * missile の弾はさらに誘導する。
     */
    public enum Type implements StringRepresentable {
        /** 弾を多く速く、引き金を引いている間ずっと流し続ける。 */
        GUN("gun"),
        /** 無誘導・モーター推進で、レールを離れた時に向いていた方向へ行く。 */
        ROCKET("rocket"),
        /** 同じだが、発射時にパイロットがロックしていた相手へ誘導する。 */
        MISSILE("missile"),
        /**
         * 撃つのではなく投下する物。機体の速度だけを持って離れ、あとは重力に任される。着弾点は投下の瞬間
         * に、機体の速度・高度・水平の度合いで決まる。これを狙うとは、機体を飛ばすことに他ならない。
         */
        BOMB("bomb"),
        /**
         * 増槽。撃たない兵装であり、ここに並んでいるのはそのためだ。
         *
         * <p>吊るのはパイロンで、ラックの位置に収まり、レンチで外せて、レーダー反射を増やし、抗力を生む
         * ——兵装が持つ性質を全部持っている。違うのは中身が炸薬ではなく燃料だという1点だけなので、兵装で
         * ないことにすると、パイロンもラックも位置も搭載構成も全部もう一度書く羽目になる。
         *
         * <p>{@code ammo} が燃料の量だ。発数を数えるのと同じ数値で、同じように減り、同じように
         * {@code WeaponItem} のスタックへ往復する。半分使った増槽を外して持ち歩けるのはそのおかげで、
         * それは実機の運用そのものでもある。
         */
        TANK("tank");

        public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

        private final String name;

        Type(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        /** 引き金1押しで1発か、押している間撃ち続けるか。 */
        public boolean isSingleShot() {
            return this != GUN;
        }

        /** 引き金と関係があるか。増槽は吊られているだけで、選択もされず撃たれもしない。 */
        public boolean isFired() {
            return this != TANK;
        }
    }

    /**
     * @param roundsPerSecond 引き金を引いている間の発射速度。1tickあたりの発数が整数である必要は無い。
     *                        端数は架台側が数える
     * @param spread 弾が出る円錐の半頂角（度）。0 ならレーザーのように真っ直ぐ
     * @param salvo 1発分の消費で同時に出る数。ロケットポッドは一度に複数を連射し、ミサイルレールは1発
     * @param salvoSpread 一斉射内での追加散布（度）。{@code spread} に上乗せされる。ロケットの一斉射が
     *                    1つの穴ではなく面を覆う理由
     * @param automatic 引き金を引いている間撃ち続けるか。空なら既定。
     *                  {@link WeaponDefinition#isAutomatic()} 参照
     */
    public record Firing(float roundsPerSecond, float spread, int salvo, float salvoSpread,
            Optional<Boolean> automatic) {
        public static final Codec<Firing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("rounds_per_second").forGetter(Firing::roundsPerSecond),
                Codec.FLOAT.optionalFieldOf("spread", 0.5F).forGetter(Firing::spread),
                Codec.INT.optionalFieldOf("salvo", 1).forGetter(Firing::salvo),
                Codec.FLOAT.optionalFieldOf("salvo_spread", 0.0F).forGetter(Firing::salvoSpread),
                Codec.BOOL.optionalFieldOf("automatic").forGetter(Firing::automatic)
        ).apply(instance, Firing::new));

        /**
         * 発射間隔（tick）。
         *
         * <p>整数である必要は無い。架台が端数を数えるので、20 を割り切れない発射速度でも平均するとファイル
         * の値になる。1tickに1発より速い兵装は代わりに {@code salvo} で書く。連装架台が物理的にそういう物
         * だから——2本の砲身が同時に放つのであって、1本が2倍速く撃つのではない。
         */
        public float ticksPerRound() {
            return 20.0F / Math.max(this.roundsPerSecond, 1.0E-3F);
        }
    }

    /**
     * 兵装から出ていく物。
     *
     * <p>gun の弾は砲口で全速度を与えられ、そこから減速する。rocket や missile はゆっくり出てしばらく
     * モーターに押される。だから静止状態から撃っても加速していくし、発射直後の一瞬は機体が自分のロケットを
     * 追い越せる。
     *
     * <p>モーターは点火の瞬間に全推力へ達する必要は無い。{@code spool_ticks} は推力を0から立ち上げるので、
     * ミサイルは速度へ飛びつくのではなく積み上げていく。省略すればレールを離れた時点で全開。従来通り。
     *
     * @param damage 直接当たった相手へのダメージ。プレイヤー20点分と同じ単位。機体は数百点あり、点数通りに
     *               受ける
     * @param speed 出ていく速度（1tickあたりブロック）。機体自身の速度が加算される
     * @param thrust 全開時のモーターによる加速度（1tick二乗あたりブロック）
     * @param burnTicks モーターの燃焼時間。モーターを持たない物は0
     * @param spoolTicks 点火後 {@code thrust} に達するまでの時間。0 なら最初の tick から全開
     * @param topSpeed モーターが出せる最高速度（1tickあたりブロック）
     * @param gravity 落下加速度（1tick二乗あたりブロック）
     * @param range 見捨てられるまでの飛翔距離（ブロック）。0以下なら決して見捨てられない。tick を数える物
     *              が無く、終わらせるのは何かに当たることだけ——当たる物が無ければ世界の底を抜けて落ちる。
     *              ミサイルの重力なら、射程が与える数秒ではなく1〜2分の飛翔になる
     * @param explosion 着弾点で起こす爆発。TNT の4と同じ単位。ただ当たるだけの物は0
     * @param blast 爆風が機体に与える打撃（点）。爆心から {@code explosion} ブロックまでは全部、そこから2倍の距離で0。
     *              0（書かない場合）はバニラの爆発の式に任せる。書いた弾のバニラの爆発は機体に効かなくなり、
     *              人やモブには今まで通り効く。{@link #blastAt} と {@code VehicleProjectile.blastMachines} 参照
     * @param tracer 描画色。{@code RRGGBB}
     * @param ricochet 装甲が食い込ませず弾くのに必要な入射角。装甲板の法線からの度数で、0 が直角命中、
     *                 90 が表面に沿った掠り。長い侵徹体は掠り角近くまで食い込むので大きな値を、小さな弾は
     *                 傾斜を転がるので小さな値を取る。0（フィールドを書かない場合）は「決して弾かれない」
     *                 の意味で、成形炸薬や、貫通ではなく接触で炸裂する物には正しい。
     *                 {@link com.ashvehicles.weapon.Ricochet} 参照
     * @param penetration 抜ける装甲の厚さ（mm）。当たった面の {@code plate} 以上なら威力をそのまま渡し、
     *                    足りなければ半分。0（書かない場合）はどんな装甲にも止められ、装甲の無い面にだけ
     *                    全部が効く。{@link com.ashvehicles.weapon.Penetration} 参照
     * @param trail 後ろに残す煙。残すなら
     */
    public record Projectile(float damage, float speed, float thrust, int burnTicks,
            int spoolTicks, float topSpeed,
            float gravity, float range, float explosion, float blast, int tracer, float ricochet,
            float penetration, float drag, float turnDrag,
            Optional<Trail> trail) {

        /**
         * 空気が奪う速さの係数。失う量は {@code drag × 速さ²}（1tickあたり）。
         *
         * <p>2乗なのは実際にそうだからで、そこが効く。最も速い瞬間に最も激しく削られ、遅くなるほど緩む。
         *
         * <p><b>これは実在の量である。</b> 弾道学の減速度は {@code a = ρ·Cd·A/(2m) · v²} で、係数
         * {@code ρ·Cd·A/(2m)} の単位は 1/m。1ブロック＝1mなので、その値をそのまま書けばよい——
         * blocks/tick で測っても m/s で測っても同じ数になる（長さの単位が同じで、時間の単位が両辺で
         * 打ち消し合うため）。だから兵装ファイルの {@code drag} は「調整用のつまみ」ではなく、
         * 弾の直径・質量・抗力係数から出てくる1つの数値だ。例:
         *
         * <ul>
         *   <li>7.62mm 弾（9.5g）— {@code 0.0009}。838 m/s が 500m で 0.75 秒</li>
         *   <li>20mm 機関砲弾（100g）— {@code 0.00045}。1030 m/s が 1000m で 1.23 秒</li>
         *   <li>120mm APFSDS（4.6kg の長棒）— {@code 0.000035}。1670 m/s が 2000m で 1.24 秒</li>
         * </ul>
         *
         * <p>この係数が {@link #lifetime()} にも効く。遅くなっていく弾が {@code range} まで届くのに要る
         * tick 数は距離÷初速ではない。
         *
         * <p><b>ミサイルでは燃焼中に効かない。</b> ファイルの {@code thrust} と {@code top_speed} は既に
         * 「空気の中でその機体が出せる性能」として書かれた値であり、そこへさらに抗力を足せば全ミサイルの
         * 最高速が黙って下がる。足りていなかったのは燃焼<em>後</em>で、そこだけを足す。既定値は Mach 4 で
         * 燃え尽きたミサイルが20秒ほどで Mach 1.5 付近まで落ちる値であって、<b>弾の値ではない</b>——
         * ミサイルの細長い機体が基準なので、書き忘れた砲はほぼ真空を飛ぶ。砲には必ず自分の値を書くこと。
         */
        public static final float DEFAULT_DRAG = 0.00006F;

        /**
         * 舵を切った分だけ余分に奪われる速さの係数。失う量は {@code turn_drag × 速さ × 旋回角}
         * （1tickあたり、角はラジアン）。
         *
         * <p>誘導弾が機動で振り切れるのはこれがあるからだ。旋回は無料ではない——実物のミサイルは急旋回の
         * たびに速度を失い、失った速度は二度と戻らない（モーターは既に燃え尽きている）。だから「早く曲げ
         * させる」ことが防御になり、機動とレバーを引くタイミングの両方に意味が生まれる。
         *
         * <p>既定値は、最高速で舵をいっぱいに切り続けたミサイルが1秒で速さの1/4を失う程度。1度の回避
         * 機動で仕留められはしないが、終末で大きく曲げさせられた弾は目に見えて鈍る。上げすぎると、交差
         * 目標へ撃った弾が加速する前に自分の舵で失速する——0.35 では実際にそうなった。
         */
        public static final float DEFAULT_TURN_DRAG = 0.12F;

        /**
         * {@code range} を省略（または0）にした弾でも、これ以上は飛ばさないという上限（tick）。
         *
         * <p>5分。{@link #lifetime()} 参照——「無制限」が本当に無制限だと、ロード済みの世界の外へ出た1発が
         * 永久に飛び続けてサーバーの帳簿から二度と消えない。
         */
        public static final int UNBOUNDED_LIFETIME = 6000;

        public static final Projectile DEFAULT = new Projectile(2.0F, 20.0F, 0.0F, 0, 0,
                0.0F, 0.02F, 200.0F, 0.0F, 0.0F, 0xFFC864, 0.0F, 0.0F, DEFAULT_DRAG, DEFAULT_TURN_DRAG,
                Optional.empty());

        /**
         * 煙の完全な記述と、かつてはそれが全部だった素の {@code true} の両方を読む。だから古い兵装ファイル
         * は従来通りの意味を保ち普通の煙を得るし、新しいファイルはモーターの煙の色を指定できる。
         */
        private static final Codec<Optional<Trail>> TRAIL =
                Codec.either(Codec.BOOL, Trail.CODEC).xmap(
                        either -> either.map(
                                on -> on ? Optional.of(Trail.DEFAULT) : Optional.<Trail>empty(),
                                Optional::of),
                        trail -> trail.<Either<Boolean, Trail>>map(Either::right)
                                .orElseGet(() -> Either.left(false)));

        public static final Codec<Projectile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("damage").forGetter(Projectile::damage),
                Codec.FLOAT.fieldOf("speed").forGetter(Projectile::speed),
                Codec.FLOAT.optionalFieldOf("thrust", 0.0F).forGetter(Projectile::thrust),
                Codec.INT.optionalFieldOf("burn_ticks", 0).forGetter(Projectile::burnTicks),
                Codec.INT.optionalFieldOf("spool_ticks", 0).forGetter(Projectile::spoolTicks),
                Codec.FLOAT.optionalFieldOf("top_speed", 0.0F).forGetter(Projectile::topSpeed),
                Codec.FLOAT.optionalFieldOf("gravity", 0.02F).forGetter(Projectile::gravity),
                Codec.FLOAT.optionalFieldOf("range", 300.0F).forGetter(Projectile::range),
                Codec.FLOAT.optionalFieldOf("explosion", 0.0F).forGetter(Projectile::explosion),
                // この1行でグループが16項目——DFU の上限そのもの。次に何かを足すなら入れ子か mapPair にすること
                // （[[fuel-system-shape]] と同じ穴）。
                Codec.FLOAT.optionalFieldOf("blast", 0.0F).forGetter(Projectile::blast),
                COLOUR.optionalFieldOf("tracer", 0xFFC864).forGetter(Projectile::tracer),
                Codec.FLOAT.optionalFieldOf("ricochet", 0.0F).forGetter(Projectile::ricochet),
                Codec.FLOAT.optionalFieldOf("penetration", 0.0F).forGetter(Projectile::penetration),
                Codec.FLOAT.optionalFieldOf("drag", DEFAULT_DRAG).forGetter(Projectile::drag),
                Codec.FLOAT.optionalFieldOf("turn_drag", DEFAULT_TURN_DRAG).forGetter(Projectile::turnDrag),
                TRAIL.optionalFieldOf("trail", Optional.empty()).forGetter(Projectile::trail)
        ).apply(instance, Projectile::new));

        /** この弾がそもそも装甲に弾かれ得るか、それとも常に食い込むか。 */
        public boolean canRicochet() {
            return this.ricochet > 0.0F;
        }

        /** この弾の爆風が、爆心から {@code distance} ブロックの機体に与える打撃。{@link #blastAt(double, double, double)}。 */
        public double blastAt(double distance) {
            return blastAt(this.blast, this.explosion, distance);
        }

        /**
         * 爆風の打撃の法則。この MOD に1つだけ在る場所で、弾（{@code VehicleProjectile.blastMachines}）も AI の見込み
         * （{@code ai/air/KillScore}）もここを通る。
         *
         * <p>爆心から {@code power} ブロックまでは {@code blast} のまま、そこから {@code power × 2}——バニラの爆発が人を
         * 傷つける半径と同じ——で0まで直線で落ちる。<b>至近弾の値を「至近」の幅ごと保つための平らな芯</b>で、バニラの式の
         * ように爆心から1ブロック外れただけで目減りしない。FAB-250（威力 8.3）なら 8.3 ブロックまで満額、16.6 で0。
         *
         * @param blast 満額（点）。0以下なら常に0
         * @param power 爆発の威力（{@code explosion}）
         * @param distance 爆心から機体の一番近い箱の表面まで（ブロック）
         */
        public static double blastAt(double blast, double power, double distance) {
            if (blast <= 0.0 || power <= 0.0) {
                return 0.0;
            }

            double reach = power * 2.0;

            if (distance >= reach) {
                return 0.0;
            }

            return distance <= power ? blast : blast * (reach - distance) / (reach - power);
        }

        /**
         * この tick に空気が奪う速さ（1tickあたりブロック）。{@code drag × 速さ²}。
         *
         * <p>抗力の法則がこの MOD に1つだけ在る場所。弾も、ミサイルの惰性区間も、照準器の予測もここを
         * 通る。同じ式が何箇所かに書き写されていると、印の位置と弾の行き先が静かにずれていく。
         */
        public double airLoss(double speed) {
            return this.drag * speed * speed;
        }

        /**
         * 1tick 空気の中を飛んだ後の速度。奪われるのは速さだけで、向きは変わらない。
         *
         * <p>落下はここに含まない。抗力は速度の線上に、重力は下向きに働く別々の力で、混ぜると
         * 「速い弾ほど落ちない」という間違いになる。呼び手が続けて重力を引く。
         */
        public Vec3 slowedByAir(Vec3 velocity) {
            double speed = velocity.length();
            double lost = this.airLoss(speed);

            if (lost <= 0.0 || speed < 1.0E-6) {
                return velocity;
            }

            return velocity.scale(Math.max(0.0, speed - lost) / speed);
        }

        /**
         * この弾が {@code flown} ブロック飛んだ後に残っている威力の割合。1.0 が砲口。
         *
         * <p><b>抗力から直接出る。</b> {@code v(x) = v0·e^(-k·x)} であり運動エネルギーは速さの2乗に
         * 比例するので、残る割合は {@code e^(-2·k·x)}。弾を実際に飛ばして今の速度を測るのと同じ答えに
         * なるが、こちらは跳弾でも撃った機体の速度でも重力でも動かない——<em>空気が取った分だけ</em>を
         * 表す。だから装甲が取った分（{@link com.ashvehicles.weapon.Ricochet#energy}）と掛け合わせても
         * 二重に数えない。
         *
         * <p>直線距離ではなく飛んだ距離を渡すこと。山なりに撃った砲弾では両者が大きく違い、空気が削るのは
         * 通った長さの方だ。
         *
         * <p><b>炸薬を持つ弾は減衰しない。</b> 榴弾や成形炸薬の威力は自分の中の化学エネルギーで決まって
         * おり、着弾時に何 m/s で飛んでいたかとは関係が無い——155mm 榴弾は 20km 先でも同じだけ効く。
         * 距離で弱くなるのは、持っている物が速度しかない弾だけだ。{@link #losesPowerWithRange} 参照。
         */
        public float energyAfter(double flown) {
            if (flown <= 0.0 || !this.losesPowerWithRange()) {
                return 1.0F;
            }

            return (float) Math.exp(-2.0 * this.drag * flown);
        }

        /**
         * この弾が距離で威力を失うか。
         *
         * <p><b>判定は {@code explosion} が持っている。</b> 炸薬を持つ弾は自分のエネルギーで効くので
         * 減衰せず、持たない弾は速度そのものが威力なので減衰する。専用のフィールドを足さないのは、この
         * 2つが実際に同じことだからだ——だが結び付いていることは知っておく必要がある。<b>運動弾に
         * {@code explosion} を少しでも書くと、その弾は距離で弱くならなくなる。</b>
         */
        public boolean losesPowerWithRange() {
            return this.drag > 0.0F && this.explosion <= 0.0F;
        }

        /** 発射後にモーターが押し続けるか。 */
        public boolean hasMotor() {
            return this.burnTicks > 0 && this.thrust > 0.0F;
        }

        /**
         * 見捨てられるまでの生存 tick 数。到達すべき距離から求める。動力のある物は「モーターが達する速度」
         * で射程を、惰性の物は「発射時の速度から抗力で落ちていく速度」で射程を進むものとして計算する。
         *
         * <p>射程が0以下なら決して見捨てず、tick カウントとしてはこれが上限になる。その種の弾を終わらせる
         * のは、何かに当たるか、モーター燃焼後に世界の底を抜けて落ちるか。後者は必ず来る（惰性の弾を支える
         * 物はここに無い）が、射程が許す数秒ではなく分単位でやって来る。だからこれは射程を省略して偶然そう
         * なるのではなく、ファイルに明示して選ぶ物。省略した場合は従来通り300ブロック。
         *
         * <p><b>そして射程が一度もそうでなかった点。</b> tick への換算はモーターが<em>達する</em>速度で
         * 行っており、そこへ立ち上がる途中の速度ではない。だから4秒かけてスプールするミサイルは、ファイルが
         * 約束しているように見える距離のかなり内側で見捨てられる。ここで文字通りの意味を持つ唯一の値が
         * 「無制限」。
         *
         * <p><b>ただし無制限には底が要る。</b> 「世界の底を抜けて落ちる」はロード済みの世界の中でしか
         * 成り立たない前提だった。その外では弾はブロックに一切問い合わせないので何にも当たらず、
         * {@code gravity} が 0.0002 級のミサイルが上向きに——ロックせずに——撃たれれば、落ちてくるまでに
         * 数時間かかる。その間ずっと {@code WeaponTicker} が毎tick tick を渡し続け、撃つたびに1発ずつ
         * 積み上がって二度と減らない。だから上限は {@link #UNBOUNDED_LIFETIME} で止める。ファイルの中で
         * 最も長く飛ぶ物（{@code grim_2_missile}、60km を毎tick 88ブロック＝約680tick）の10倍近くあるので、
         * 「射程無制限」の意味は何も変わらない。変わるのは、当たらなかった1発がいつか必ず終わること。
         */
        public int lifetime() {
            if (this.range <= 0.0F) {
                return UNBOUNDED_LIFETIME;
            }

            double pace = Math.max(this.hasMotor() ? Math.max(this.topSpeed, this.speed) : this.speed,
                    1.0E-3F);

            // モーターを持つ物は燃焼中に抗力を受けないので、従来通り「射程÷速度」でよい。
            if (this.hasMotor() || this.drag <= 0.0F) {
                return Math.max(1, (int) Math.round(this.range / pace));
            }

            // <b>抗力を持つ弾は遅くなっていく。</b> だから射程まで飛ぶのに要る tick 数は距離÷初速では
            // なく、それより多い。{@code dv/dt = -k·v²} を距離で読み直すと {@code dv/dx = -k·v} なので
            // {@code v(x) = v0·e^(-k·x)}、そこから積分して {@code t = (e^(k·R) - 1) / (k·v0)}。
            // {@code k → 0} で従来の {@code R/v0} に戻るので、抗力を書かないファイルは何も変わらない。
            //
            // これを直さないと、抗力を足した瞬間に全ての砲の実効射程が黙って縮む——弾はファイルに書いて
            // ある距離のかなり手前で見捨てられ、なぜ届かないのかはどこにも出ない。
            double ticks = Math.expm1(this.drag * this.range) / (this.drag * pace);

            return (int) Math.max(1.0, Math.min(ticks, UNBOUNDED_LIFETIME));
        }
    }

    /**
     * モーターが後ろに残す煙。
     *
     * <p>見える物が2つあるので、半分ずつある。噴煙は今ノズルから出ている物——濃く、近く、まだミサイルと
     * 一緒に動いており、モーターが燃えている間だけ存在する。航跡はその噴煙が1秒前に変化した物で、ミサイルが
     * いた場所に留まり広がっていく。燃え尽きたモーターには何も描かない。それがロケットの煙が弾道飛行へ移った
     * 地点で途切れる理由であり、見ている者がそれを見分けられる理由。
     *
     * @param colour 航跡本体の色。{@code RRGGBB}。後方の冷えた煙
     * @param exhaust ノズルの噴煙。より熱く、たいてい濃い
     * @param density 1ブロック飛ぶごとに置く煙の数。1未満なら意図的に隙間が空く
     * @param size 1つあたりの大きさ。標準に対する倍率
     * @param flame ノズルの炎。書かなければ煙の大きさから引く。{@link Flame} 参照
     */
    public record Trail(int colour, int exhaust, float density, float size, Optional<Flame> flame) {
        /** {@code "trail": true} としか書かない兵装ファイルが得る値。 */
        public static final Trail DEFAULT =
                new Trail(0xD8D5CD, 0x9A958B, 2.0F, 1.0F, Optional.empty());

        public static final Codec<Trail> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                COLOUR.optionalFieldOf("colour", DEFAULT.colour()).forGetter(Trail::colour),
                COLOUR.optionalFieldOf("exhaust", DEFAULT.exhaust()).forGetter(Trail::exhaust),
                Codec.FLOAT.optionalFieldOf("density", DEFAULT.density()).forGetter(Trail::density),
                Codec.FLOAT.optionalFieldOf("size", DEFAULT.size()).forGetter(Trail::size),
                Flame.CODEC.optionalFieldOf("flame").forGetter(Trail::flame)
        ).apply(instance, Trail::new));

        /**
         * この煙を出しているモーターの炎。
         *
         * <p>書いてあればその値、無ければ<b>煙の大きさから引いた炎</b>。燃えているモーターには必ず火が
         * あり、それを書き忘れた兵装ファイルというものは無い——書かれていないのは大きさだけで、それは
         * 既にここにある。{@code size} は「このモーターがどれだけの物か」を各ファイルが既に述べた値なので、
         * 炎の寸法をそこから引けば、どの兵装も自分の煙に見合った火を持つ。{@link Flame#matching} 参照。
         *
         * <p>名前が {@link #flame()} でないのは、そちらがコーデックの読み書きする「ファイルに書いてある
         * 方」だからだ。撒く側・照らす側が見るのは常にこちら。
         */
        public Flame fire() {
            return this.flame.orElseGet(() -> Flame.matching(this.size));
        }
    }

    /**
     * 燃えているモーターそのもの。煙ではなく火の方。
     *
     * <p>{@link Trail} と分けてあるのは、煙が「モーターが残した物」であるのに対しこちらは「モーターが今
     * 出している物」だからだ。煙は空中に留まって航跡になり、火はノズルから数ブロックで終わる。そして火の
     * 方だけが<b>光源</b>である——燃えている兵装は、飛びながら自分の煙を内側から照らす。
     *
     * <p><b>燃えているモーターは全部これを持つ。</b> 書かなければ煙の大きさから寸法を引く
     * （{@link #matching}）。実物のモーターに「火の出ない物」は無いので、書かれていないのは値だけであって
     * 現象ではない。ファイルに書くのは、既定から外したいときだけ。
     *
     * <p><b>ただし {@link #wash} だけは既定で0だ。</b> あれは見ている者の画面全体を染める物で、燃えている
     * 事実ではなく規模の話になる——空対空ミサイルのモーターで風景の色が変わったら、それは光ではなく演出だ。
     * 弾道弾のように「発射で周りが明るくなる」規模の物だけがファイルで書いて起こす。
     *
     * @param colour 炎の色。{@code RRGGBB}。芯は生まれた瞬間だけ白熱し、そこからこの色を通って落ちる
     * @param size 炎1粒の大きさ。標準に対する倍率
     * @param length 炎がノズルの後方どこまで届くか（ブロック）。ミサイルが1tickにそれ以上飛ぶなら、
     *        飛んだ分まで伸ばして隙間を埋める。{@code spawnFlame} 参照
     * @param glow 周囲の煙を照らす半径（ブロック）。{@link com.ashvehicles.client.MotorLight} 参照
     * @param wash 見ている者の画面をどれだけ染めるか。0で染めない。1で既定の強さ。実際の濃さは距離と
     *        その場の明るさが決めるので、ここは「そもそもそういう規模の物か」を述べる値
     */
    public record Flame(int colour, float size, float length, float glow, float wash) {
        /** 何も書かなかったときの色と、煙の大きさ1に対する炎の寸法。 */
        public static final Flame DEFAULT = new Flame(0xFFB25A, 1.0F, 2.5F, 12.0F, 0.0F);

        public static final Codec<Flame> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                COLOUR.optionalFieldOf("colour", DEFAULT.colour()).forGetter(Flame::colour),
                Codec.FLOAT.optionalFieldOf("size", DEFAULT.size()).forGetter(Flame::size),
                Codec.FLOAT.optionalFieldOf("length", DEFAULT.length()).forGetter(Flame::length),
                Codec.FLOAT.optionalFieldOf("glow", DEFAULT.glow()).forGetter(Flame::glow),
                Codec.FLOAT.optionalFieldOf("wash", DEFAULT.wash()).forGetter(Flame::wash)
        ).apply(instance, Flame::new));

        /**
         * その大きさの煙を出しているモーターの炎。
         *
         * <p>寸法を全部 {@code size} に比例させる。兵装ファイルの {@code trail.size} は既に「このモーターは
         * どれだけの物か」を述べているので、そこから引けば TOW の 0.8 とグリム2の 2.0 が、同じ式で
         * それぞれらしい火になる。比例定数は {@link #DEFAULT} が持っている。
         */
        public static Flame matching(float size) {
            return new Flame(DEFAULT.colour(), DEFAULT.size() * size, DEFAULT.length() * size,
                    DEFAULT.glow() * size, DEFAULT.wash());
        }
    }

    /**
     * ミサイルが発射対象をどう見つけるか。
     *
     * <p>ロックは発射前に行うパイロットの仕事だ。シーカーの視野内かつ射程内の相手に機首を乗せ、成立するまで
     * 保持する。放たれた後のミサイルは独りで、できることは旋回性能に縛られる。ミサイルが追える以上に強く
     * 曲がる目標には外れる。ここには無条件に命中する物は一つも無い。
     *
     * @param turnRate ミサイルが飛行経路を曲げられる角度（1tickあたり度）
     * @param lockAngle シーカー視野の半頂角（度）。機首基準
     * @param lockRange シーカーが見える距離（ブロック）
     * @param lockTicks ロック成立までに視野内へ保持し続ける必要のある時間
     * @param trackAngle 自分の機首からどれだけ外れても目標を追い続けるか（度）。これを超えると失探し、
     *                   以後は弾道飛行になる
     * @param proximity 炸裂する近接距離（ブロック）。ミサイルは当たる必要が無い
     * @param navGain 比例航法の名の由来である航法定数。視線の回転をただ打ち消すのではなく何倍で打ち消しに
     *                行くか。実物のシーカーヘッドは3〜5を軸に作られている。それを大きく超えると、ミサイルは
     *                追尾のちらつき一つ一つを「目標が実際に動いた」かのように扱い、それはそれで別種の外れ方
     *                になる
     * @param armTicks 発射から近接信管が生きるまでの時間（tick）。安全装置。これより前は目標の横を通っても
     *                 炸裂しないので、レールを離れた瞬間の自機や、密集隊形の僚機の鼻先で破裂することが無い
     * @param reacquireTicks 失探後、シーカーが視野内を探し続ける時間（tick）。この間に目標（または視野に
     *                       入った別の有効目標）を捉え直せば追跡を再開し、捉え直せなければ自爆する。
     *                       0 なら旧来通り——失探は永久で、弾はロケットとして飛び続ける
     * @param seduction デコイ1つが1tickにこのシーカーを奪う基本確率。実際の確率はここから、デコイの残り
     *                  寿命で目減りし、目標自身の熱量／反射断面積で割られる——燃え盛るアフターバーナーの
     *                  横のフレアは、冷えた排気の横の同じフレアより分が悪い
     */
    public record Guidance(float turnRate, float lockAngle, float lockRange, int lockTicks,
            float trackAngle, float proximity, float navGain, Seeker seeker,
            int armTicks, int reacquireTicks, float seduction) {

        /**
         * シーカーが何を見ているか。つまり何に騙されるか。
         *
         * <p>対抗手段が2種類ある意味の全部がこれ。ロックされたと告げられたパイロットには、どちらのレバーを
         * 引くか決める1〜2秒がある。間違えて引けばミサイルは何も変わらないまま。
         */
        public enum Seeker implements StringRepresentable {
            /** 熱源に向かう。代わりにフレアを追う。 */
            HEAT("heat"),
            /** レーダー反射に向かう。代わりにチャフの雲を追う。 */
            RADAR("radar"),
            /**
             * 誰かが目標に当て続けている光点へ向かう。代わりに追う物は無い。どちらのレバーも効かない。
             * フレアも金属箔の雲も、これが見ている物ではないから。
             *
             * <p>そこまで騙されにくいことの代償は、機体がその「当て続ける物」を積まねばならないこと。
             * このシーカーを持つ兵装には {@code "requires": "targeting_pod"} を併記すべきで
             * （{@link WeaponDefinition#requires} 参照）、それが無ければ尾翼はあるが誘導の当てが無い
             * 爆弾になる。
             */
            LASER("laser"),
            /**
             * 誰かが地面に置いた座標へ向かう。追う物は<em>点</em>であって物ではない。
             *
             * <p>レーザーとの違いは、当て続ける者が要らないことだ。あちらは飛翔中ずっと誰かが光を当てている
             * 必要があり、だから照準ポッドを積んだ機体の兵装になる。こちらは発射の瞬間に座標を受け取り、以後
             * 何も見ない——弾道ミサイルが実際にそうする通りで、発射機は撃った後その場を離れてよい。
             *
             * <p><b>だから捕捉という手順が無い。</b>シーカーが空を掃引することも、進行度が閉じることも、
             * 追われている側の警戒受信機が鳴ることも無い。乗員がすることは目標を1つ選ぶことだけで、そこは
             * 物である必要すら無い——丘でも、交差点でも、まだ誰も居ない座標でもよい。
             */
            POINT("point"),
            /**
             * 射手が照準を向けている線へ向かう。視線誘導。
             *
             * <p>捕捉という手順が無いのは {@link #POINT} と同じだが、行き先が固定されないところが違う。狙って
             * いる線は毎tick更新されるので、飛んでいる弾は照準が動けば付いてくる——だから射手は着弾まで照準を
             * 目標に置き続けなければならないし、逆に飛行中に別の物へ振り向けることもできる。有線誘導の対戦車
             * ミサイルがまさにそれで、当たるかどうかは撃った後の射手の手にかかっている。
             *
             * <p><b>近接信管を持たせないこと。</b>弾が追っているのは照準線上の遠い一点であって目標ではないので、
             * 距離で炸裂させる意味が無い。{@code proximity} は 0 にして、触れた物で炸裂させる——成形炸薬弾頭の
             * 実際の信管であり、線に乗った弾が目標へ行き着けば当たる。
             */
            BEAM("beam");

            public static final Codec<Seeker> CODEC = StringRepresentable.fromEnum(Seeker::values);

            private final String name;

            Seeker(String name) {
                this.name = name;
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }

            /**
             * その種類の対抗手段が、このシーカーが目標の代わりに追う物かどうか。熱源追尾ならフレア、
             * レーダー追尾ならチャフ、光点を見ているヘッドにはどちらでもない。
             */
            public boolean fooledBy(boolean flare) {
                return switch (this) {
                    case HEAT -> flare;
                    case RADAR -> !flare;
                    case LASER, POINT, BEAM -> false;
                };
            }

            /**
             * 追う相手を、シーカー自身が見つけるのではなく人が据えるか。
             *
             * <p>据える側の2つ——光点と座標——をまとめて問える1箇所。どちらも「発射の瞬間に渡された物へ行く」
             * 弾であり、シーカーが捉えた物を渡す経路とは別の経路で目標を受け取る。両者を別々に列挙していると、
             * 片方だけを足した箇所が静かに間違う。
             */
            public boolean laid() {
                return this == LASER || this == POINT || this == BEAM;
            }
        }

        public static final Codec<Guidance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.optionalFieldOf("turn_rate", 6.0F).forGetter(Guidance::turnRate),
                Codec.FLOAT.optionalFieldOf("lock_angle", 15.0F).forGetter(Guidance::lockAngle),
                Codec.FLOAT.optionalFieldOf("lock_range", 220.0F).forGetter(Guidance::lockRange),
                Codec.INT.optionalFieldOf("lock_ticks", 20).forGetter(Guidance::lockTicks),
                Codec.FLOAT.optionalFieldOf("track_angle", 75.0F).forGetter(Guidance::trackAngle),
                Codec.FLOAT.optionalFieldOf("proximity", 2.5F).forGetter(Guidance::proximity),
                Codec.FLOAT.optionalFieldOf("nav_gain", 3.5F).forGetter(Guidance::navGain),
                Seeker.CODEC.optionalFieldOf("seeker", Seeker.HEAT).forGetter(Guidance::seeker),
                Codec.INT.optionalFieldOf("arm_ticks", 8).forGetter(Guidance::armTicks),
                Codec.INT.optionalFieldOf("reacquire_ticks", 40).forGetter(Guidance::reacquireTicks),
                Codec.FLOAT.optionalFieldOf("seduction", 0.2F).forGetter(Guidance::seduction)
        ).apply(instance, Guidance::new));
    }

    /**
     * 発砲音の探し方はエンジン音と同じ。ここで指定したイベント、無ければ兵装名から作った名前
     * （{@code <namespace>:weapon.<name>}）、それも無ければ兵装の種類ごとの既定。発砲中は何発撃っていても
     * 1tickに1回鳴らす——{@code interval} を書いた兵装はその tick 数に1回。
     *
     * @param fire 音イベント。空なら兵装名から探す
     * @param volume その音がどこまで届くか。{@link #carry()} 参照——名前に反して音量ではない
     * @param pitch 再生速度
     * @param gain 録音そのものの大きさに対する補正。{@link #gain()} 参照
     * @param interval 撃ち続けている間に鳴らし直す間隔（tick）。{@link #interval()} 参照
     */
    public record SoundSetup(Optional<ResourceLocation> fire, float volume, float pitch, float gain, int interval) {
        public static final SoundSetup DEFAULT = new SoundSetup(Optional.empty(), 2.0F, 1.0F, 1.0F, 1);

        /**
         * 音量1点あたり、その兵装が聞こえる距離（ブロック）。
         *
         * <p>機体のすぐ横での音量（{@link #volume()}）とは無関係。現実の火砲は谷を越えて聞こえるし、機体は
         * その谷をいくつも跨いで戦う。だから重要なのは数百ブロックという数字の方で、遠方での音量はそこから
         * 計算する。{@link com.ashvehicles.client.sound.WeaponSounds} 参照。
         */
        private static final float CARRY_PER_VOLUME = 160.0F;

        /** この兵装が聞こえる距離（ブロック）。 */
        public float carry() {
            return Math.max(this.volume, 0.0F) * CARRY_PER_VOLUME;
        }

        /** その逆。届かせたい距離（ブロック）を volume 欄の値に直す。 */
        public static float volumeForCarry(float blocks) {
            return Math.max(blocks, 0.0F) / CARRY_PER_VOLUME;
        }

        /**
         * 音の送信をゲームへ依頼する時に渡す「音量」。
         *
         * <p>作り話であり、唯一使える作り話でもある。サーバーは {@code max(volume, 1) * 16} ブロック以内の
         * 全員にだけ音を送るので、この音量は実のところ音量ではない——「音がどこまで届くべきか」を伝える唯一
         * の手段だ。届いた値をそのまま鳴らせば耳をつんざくので、クライアントはこの値を捨て、距離から本当の
         * 音量を計算する。
         */
        public float packetVolume() {
            return Math.max(this.carry() / 16.0F, 1.0F);
        }

        /**
         * 発生地点で聞いたときの音量。1.0 が「録音そのままの大きさ」。
         *
         * <p><b>{@link #volume} とは別の物であり、別でなければならない。</b>あちらは到達距離であって音量では
         * ない。かつては耳に届く大きさもそこから求めていた——遠くまで届く音は近くでも大きい、という一本の
         * 尺度だ。大抵は害が無かった。ほとんどの兵装は 1 を超える値を持ち、サウンドエンジンは音量を 1 で
         * 頭打ちにするからだ。だが 10 ブロックしか届かせたくない音では破綻する。到達距離を切り詰めた瞬間、
         * 手元での音量まで 16分の1 になり、聞こえるはずの範囲で何も聞こえなくなる。
         *
         * <p>だから距離は {@link #volume}、大きさはこちら。既定は 1.0。書かなければ録音のまま鳴る。
         */
        public float gain() {
            return Math.max(this.gain, 0.0F);
        }

        /**
         * 撃ち続けている間、発砲音を何 tick に1回鳴らすか。既定は 1——撃った tick ごと。
         *
         * <p><b>録音が1発ぶんか、連射ぶんかで決まる。</b>1発を録った 0.3 秒の音は、毎 tick 鳴らして初めて連射に
         * 聞こえる。連射そのものを録った音（GAU-8 の 2 秒）を同じように鳴らすと、同じ連射が 40 本重なって
         * 1 つの轟音に潰れ、音の枠も食い潰す。そういう録音には、減衰が始まるまでの長さを tick で書く——前の
         * 1 本が消え始める所へ次の 1 本が入り、切れ目なく続く。
         *
         * <p>数えるのは撃ち続けている間だけで、引き金を引き直した最初の tick は間隔の途中でも鳴らす
         * （{@link FireSoundPacing}）。離した後も録音は最後まで鳴るので、短く切った連射でも録音の長さぶん聞こえる。
         */
        public int interval() {
            return Math.max(this.interval, 1);
        }

        public static final Codec<SoundSetup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.optionalFieldOf("fire").forGetter(SoundSetup::fire),
                Codec.FLOAT.optionalFieldOf("volume", DEFAULT.volume()).forGetter(SoundSetup::volume),
                Codec.FLOAT.optionalFieldOf("pitch", DEFAULT.pitch()).forGetter(SoundSetup::pitch),
                Codec.FLOAT.optionalFieldOf("gain", DEFAULT.gain()).forGetter(SoundSetup::gain),
                Codec.INT.optionalFieldOf("interval", DEFAULT.interval()).forGetter(SoundSetup::interval)
        ).apply(instance, SoundSetup::new));
    }
}
