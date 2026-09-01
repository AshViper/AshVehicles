package com.ashvehicles.client.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3f;

import com.ashvehicles.AshVehicles;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * MOD の機体を描く元になる3つのファイルを見つけ、その場所を記憶する。
 *
 * <p>ここで機体名を名指しする物は無い。{@code su_25} という名の機体や車両は、場所を教えられずとも
 * {@code geo/entity/su_25.geo.json} と {@code textures/entity/su_25.png} から描かれる。機体のIDは、それに関する
 * 全てが見つかる名前だからだ。
 *
 * <p><b>答えを保持する理由。</b>これらは導出された名前——ディレクトリ、機体自身のパス、接尾辞——であり、1つ組む
 * ごとに文字列連結と1文字ずつの検証が要る。1度なら何でもないが、画面上の全機体の全フレームで——GeckoLib が要求
 * するのはそれだ——さらに全ゴーストの全tickでとなると相当な量になる。しかも答えは決して変わらない。IDは
 * エンティティ生成時に確定する。
 */
public abstract class VehicleGeoModel<T extends Entity & GeoEntity> extends GeoModel<T> {
    protected static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private record Files(ResourceLocation geometry, ResourceLocation texture, ResourceLocation animation) {
    }

    private static final Map<ResourceLocation, Files> FILES = new ConcurrentHashMap<>();

    /** この機体に関する全てが見つかるID。 */
    protected abstract ResourceLocation idOf(T animatable);

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return geometryFile(this.idOf(animatable));
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return textureFile(this.idOf(animatable));
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return animationFile(this.idOf(animatable));
    }

    /**
     * アニメーションが名指ししジオメトリに無いボーンは、ポーズ用コードが見つけられないボーンと同様に飛ばす。誰かが
     * 改名したせいでベイ扉やハッチが失われることは、クライアントを落とすに値しない。代替案——全ファイルがジオメトリ
     * と完全に同期していなければ誰もプレイできない——は誰も選ばない取引だ。
     */
    @Override
    public boolean crashIfBoneMissing() {
        return false;
    }

    public static ResourceLocation geometryFile(ResourceLocation id) {
        return files(id).geometry();
    }

    public static ResourceLocation textureFile(ResourceLocation id) {
        return files(id).texture();
    }

    public static ResourceLocation animationFile(ResourceLocation id) {
        return files(id).animation();
    }

    private static Files files(ResourceLocation id) {
        return FILES.computeIfAbsent(id, name -> new Files(
                file("geo/entity/", name, ".geo.json"),
                file("textures/entity/", name, ".png"),
                file("animations/entity/", name, ".animation.json")));
    }

    /**
     * その機体のファイル。名前空間は機体自身の物を使う——MOD 本体の機体なら {@code ashvehicles}、
     * コンテンツパックの機体ならそのパックの名前空間で、モデルとテクスチャはパックの
     * {@code assets/<名前空間>/} から読まれる。
     */
    private static ResourceLocation file(String directory, ResourceLocation id, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), directory + id.getPath() + suffix);
    }

    /**
     * ボーンを、絶対角へ設定するのではなく、ジオメトリファイルが置いた位置<em>から</em>回す。
     *
     * <p>これが「回る車輪」と「何かが触れた瞬間にハブから飛び出す車輪」の違いの全てだ。転輪は大抵横倒しで作られ、
     * ファイル内の回転で起こされる。回転を直接設定するとそれを捨てて車輪をまた寝かせてしまう。GeckoLib 自身の
     * アニメーションも同じ値へ加算する——{@code AnimationProcessor} 参照。キーフレームを補間し初期スナップショットを
     * 加える——ので、コードでポーズを付けるボーンもそうすべきだ。
     */
    protected static void rotateX(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotX(found.getInitialSnapshot().getRotX() + degrees * DEG_TO_RAD));
    }

    protected static void rotateY(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotY(found.getInitialSnapshot().getRotY() + degrees * DEG_TO_RAD));
    }

    protected static void rotateZ(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotZ(found.getInitialSnapshot().getRotZ() + degrees * DEG_TO_RAD));
    }

    /**
     * ボーンを、自分のジオメトリの<em>中心</em>の周りに Z 軸回りで回す。支点がそこに無くても。
     *
     * <p>プロペラのためにある。プロペラは1枚作って複製するのが普通で、複製されたボーンは支点まで一緒に持って
     * くる——AC-130 の4枚は全部が右外側エンジンの支点を共有している。そのまま {@link #rotateZ} で回せば、
     * 正しく回るのは1枚だけで、残り3枚は16ブロック離れた1点を公転する。
     *
     * <p>支点 Q 周りの回転を中心 P 周りの回転にするのは、ボーン自身の平行移動 {@code (I − R)(P − Q)} だ。
     * GeckoLib はボーンを「オフセット → 支点へ → 回転 → 支点から戻す」の順で置く（{@code RenderUtil}）ので、
     * その差をオフセットへ入れれば回転の中心だけが移る。中心と支点は {@link BakedGeometry} から取る——どちらも
     * このフレームのポーズではなくジオメトリファイルが落ち着く姿勢から求まるので、毎フレーム同じ答えになり、
     * 自分が付けたポーズを読み返して積み上がることもない。
     *
     * <p>差はモデル軸で取るので、この補正が正しいのは<b>回転を持たない親にぶら下がったボーン</b>——プロペラは
     * 例外なくそれだ。親が回っていれば、差は親の軸で測り直す必要がある。
     */
    protected static void spinZ(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> {
            BoneSnapshot rest = BakedGeometry.rest(found);
            float radians = degrees * DEG_TO_RAD;

            found.setRotZ(rest.getRotZ() + radians);

            Vector3f centre = BakedGeometry.centreOf(found);
            Vector3f pivot = BakedGeometry.pivotOf(found);
            float dx = centre.x() - pivot.x();
            float dy = centre.y() - pivot.y();

            if (dx * dx + dy * dy < 1.0E-8F) {
                // 支点が既に中心にある。まっとうに作られたプロペラはここで終わる。
                return;
            }

            float cos = Mth.cos(radians);
            float sin = Mth.sin(radians);
            float offsetX = dx - (dx * cos - dy * sin);
            float offsetY = dy - (dx * sin + dy * cos);

            // ブロックからモデル単位へ。x はボーンのオフセットが負方向へ効く（RenderUtil 参照）ので反転する。
            found.setPosX(rest.getOffsetX() - offsetX * BakedGeometry.UNITS);
            found.setPosY(rest.getOffsetY() + offsetY * BakedGeometry.UNITS);
        });
    }

    /**
     * {@link #spinZ} と同じ処理を、ボーン自身の Y 軸周りに。
     *
     * <p>回転面が水平に作られたプロペラのため。ティルトローター機のナセルは<em>ホバー姿勢</em>で作られること
     * があり、そのとき円板は水平に寝ている——その法線はボーンの Z ではなく Y だ。どちらの軸で回すかは模型の
     * 作られ方の話なので、機体ファイルが {@code model.propeller_axis} で言う。
     *
     * <p>ナセルが傾けば円板も一緒に傾き、この回転はナセルの<em>内側</em>で起きるので、両方の姿勢で正しく
     * 回り続ける。円板の法線がボーンの固定された1軸である限り、それは姿勢に依らない。
     */
    protected static void spinY(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> {
            BoneSnapshot rest = BakedGeometry.rest(found);
            float radians = degrees * DEG_TO_RAD;

            found.setRotY(rest.getRotY() + radians);

            Vector3f centre = BakedGeometry.centreOf(found);
            Vector3f pivot = BakedGeometry.pivotOf(found);
            float dz = centre.z() - pivot.z();
            float dx = centre.x() - pivot.x();

            if (dx * dx + dz * dz < 1.0E-8F) {
                return;
            }

            float cos = Mth.cos(radians);
            float sin = Mth.sin(radians);
            // Y 軸周りでは (z, x) が回る組になる。順序が (x, z) でないのは右手系の向きに合わせるため。
            float offsetZ = dz - (dz * cos - dx * sin);
            float offsetX = dx - (dz * sin + dx * cos);

            found.setPosZ(rest.getOffsetZ() + offsetZ * BakedGeometry.UNITS);
            found.setPosX(rest.getOffsetX() - offsetX * BakedGeometry.UNITS);
        });
    }

    /** ボーンを、ジオメトリファイルが置いた位置から自身の Z 方向へ動かす。 */
    protected static void slideZ(GeoModel<?> model, String bone, float units) {
        pose(model, bone, found -> found.setPosZ(found.getInitialSnapshot().getOffsetZ() + units));
    }

    // ------------------------------------------------------------------
    // ボーン自身の軸ではなく機体の軸でポーズを付ける
    // ------------------------------------------------------------------

    /**
     * 同じ3つの回転と移動を、ボーン自身ではなく<em>機体</em>の軸周りに行う——ジオメトリがどの座標系で作られていても、
     * 部品が要求された方向へ動くようにするためだ。
     *
     * <p><b>何が問題か。</b>ボーンは親の内側で回り、その親もさらに親の内側で回り、ルートまで続く。その連鎖のどこか
     * にある回転は、ボーンの軸を一緒に運んでしまう。ここのモデルの半分は後ろ向きに作られてルートボーンの半回転で
     * 向き直されており、そうしたルートの下の車輪や砲身は自身の +X が機体の左を指す。両方の種類のモデルへ X 軸周りの
     * 同じ回転を要求すると、一方は砲を上げ車輪を前へ回し、他方は砲を下げ車輪を後ろへ回す。履帯が逆走することも、
     * 上がるべき砲が下がることも、原因は常にこれだ。
     *
     * <p>ルートだけではない。車輪ボーンはしばしば自前の半回転を持つ。一度作って側面に並べて再利用できるようにする
     * ためだ。あるモデルは21個のうち8個をそうし、残り13個はそうしていない。車両単位のフラグでは表現できない。だから
     * 答えはボーン単位で求める。
     *
     * <p><b>どう対処するか。</b>回転を適用する前に、実際にその回転が起きる軸を親の連鎖を辿って機体自身の座標系へ
     * 運び、それが逆向きを指していたら角度を反転する。だから呼び出し元は「右へ据える」「車輪を進める」と要求すれば
     * そうなる。作者がファイルをどちら向きに残したかを誰も知る必要は無い。
     *
     * <p>運ぶのは静止ポーズであってこのフレームのポーズではない——旋回した砲塔も上下が正しい砲塔のままだし、その下
     * の砲はどの方位でも同じ向きに俯仰する。
     *
     * <p>要求した軸と直交して出てきた軸——ボーンが90度回っていて、X 軸周りの回転が部品を転がす代わりに横へ倒す
     * ような場合——は符号では直せないので放置する。それは Blockbench で直すモデルであって、ここで直す物ではない。
     */
    protected static void turnAboutX(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> turnAboutX(found, degrees));
    }

    /** 同じ処理を、名前ではなくボーンを渡して行う版。@see #slideAlongY */
    protected static void turnAboutX(GeoBone bone, float degrees) {
        bone.setRotX(rest(bone).getRotX() + machineSignX(bone) * degrees * DEG_TO_RAD);
    }

    /** @see #turnAboutX */
    protected static void turnAboutY(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> found.setRotY(
                rest(found).getRotY() + machineSignY(found) * degrees * DEG_TO_RAD));
    }

    /**
     * 可変翼専用の鉛直軸回転。正の角度が、そのボーンがどちら側の翼でも<em>翼端を機体後方へ</em>運ぶ。
     *
     * <p>{@link #turnAboutY} では足りない。あちらが直すのは回転<em>軸</em>の反転だけで、鉛直軸は親が半回転
     * していようと鉛直のままだから常に「反転無し」と答える。だが翼で決め手になるのは軸ではなく<em>立ち位置</em>
     * だ——同じ向きの鉛直回転が、右翼では翼端を後ろへ、左翼では前へ運ぶ。左右はロール名から信じない
     * （L と名の付いたボーンが右側にあるのはこの MOD では実績のある事故だし、ミラーで作った模型は名前ごと
     * 写る）。代わりにジオメトリに訊く:
     *
     * <ul>
     * <li>どちら側の翼か——ピボットの X の符号。翼のピボットは翼根、つまり必ず自分の側にある。</li>
     * <li>機体後方が局所でどちらか——{@link #machineSignZ}。半回転したルートの下では側と後方が同時に
     *     裏返るので、積は物理的に正しい向きへ戻る。</li>
     * </ul>
     *
     * <p>回転で傾いた親の下のピボット符号までは追わない（GeckoLib のピボットはモデル絶対座標）。翼は胴体
     * 直下に付く物で、そうでないモデルはまず翼から Blockbench で見直すべき物だ。
     */
    protected static void sweepAboutY(GeoModel<?> model, String bone, float degrees) {
        pose(model, bone, found -> {
            float direction = -sign(found.getPivotX()) * machineSignZ(found);

            found.setRotY(rest(found).getRotY() + direction * degrees * DEG_TO_RAD);
        });
    }

    /** @see #turnAboutX */
    protected static void slideAlongZ(GeoModel<?> model, String bone, float units) {
        pose(model, bone, found -> slideAlongZ(found, units));
    }

    /** 同じ処理を、名前ではなくボーンを渡して行う版。@see #slideAlongY */
    protected static void slideAlongZ(GeoBone bone, float units) {
        bone.setPosZ(rest(bone).getOffsetZ() + machineSignZ(bone) * units);
    }

    /**
     * {@link #slideAlongZ} と同様の処理を機体の鉛直方向に対して行い、名前ではなくボーンを渡す版。
     *
     * <p>名前ではなくボーンを渡すのは、この呼び出し元——車体が上で動く間に地面へ戻される転輪——が、動かす量を知る前
     * にボーンの位置を知る必要があるからだ。車両の車輪ごとに毎フレーム同じボーンを2回引くのは無料ではない。
     */
    protected static void slideAlongY(GeoBone bone, float units) {
        bone.setPosY(rest(bone).getOffsetY() + machineSignYSlide(bone) * units);
    }


    /**
     * {@link #machineSignZ} と同様、Y 方向の移動用。ボーン自身の回転はどれも関与しない。理由も同じで、オフセット
     * はボーンの軸ではなく親の軸で起きるからだ。
     */
    private static float machineSignYSlide(GeoBone bone) {
        return sign(intoMachine(bone, new Vector3f(0.0F, 1.0F, 0.0F)).y());
    }


    /**
     * このボーンの X 軸周りの回転が、機体の X 軸周りの同じ向きの回転として現れるか。
     *
     * <p>ボーン自身の Z と Y の回転は関与し、自身の X は関与しない。GeckoLib はボーンの行列を Z→Y→X の順に組む
     * ——{@code RenderUtil.rotateMatrixAroundBone} 参照——ので、X は3つのうち最も内側であり、その回転が起きる軸は
     * ボーン自身の X を他の2つに通した物になる。
     */
    private static float machineSignX(GeoBone bone) {
        BoneSnapshot rest = rest(bone);
        Vector3f axis = new Vector3f(1.0F, 0.0F, 0.0F).rotateY(rest.getRotY()).rotateZ(rest.getRotZ());

        return sign(intoMachine(bone, axis).x());
    }

    /** {@link #machineSignX} と同様、Y 軸周りの回転用。外側にあるのはボーン自身の Z 回転だけ。 */
    private static float machineSignY(GeoBone bone) {
        Vector3f axis = new Vector3f(0.0F, 1.0F, 0.0F).rotateZ(rest(bone).getRotZ());

        return sign(intoMachine(bone, axis).y());
    }

    /**
     * {@link #machineSignX} と同様、Z 方向の移動用。
     *
     * <p>ボーン自身の回転はどれも関与しない。ボーンの位置オフセットは、支点へ出して回す前に適用される——ここでも
     * {@code RenderUtil.prepMatrixForBone}——ので、移動はボーンの軸ではなく親の軸で起きる。
     */
    private static float machineSignZ(GeoBone bone) {
        return sign(intoMachine(bone, new Vector3f(0.0F, 0.0F, 1.0F)).z());
    }

    /** ボーンの親の軸で表された方向を、親の連鎖を辿って機体の軸へ運ぶ。 */
    private static Vector3f intoMachine(GeoBone bone, Vector3f axis) {
        for (GeoBone up = bone.getParent(); up != null; up = up.getParent()) {
            BoneSnapshot rest = rest(up);

            axis.rotateX(rest.getRotX()).rotateY(rest.getRotY()).rotateZ(rest.getRotZ());
        }

        return axis;
    }

    private static float sign(float of) {
        return of < 0.0F ? -1.0F : 1.0F;
    }

    /**
     * ジオメトリファイルがボーンを残した位置。GeckoLib がまだスナップショットを撮っていないボーンには撮らせる。
     * 何も動かしていないボーンなら同じ答えになる。
     */
    protected static BoneSnapshot rest(GeoBone bone) {
        BoneSnapshot rest = bone.getInitialSnapshot();

        if (rest == null) {
            bone.saveInitialSnapshot();
            rest = bone.getInitialSnapshot();
        }

        return rest;
    }

    private static void pose(GeoModel<?> model, String bone, java.util.function.Consumer<GeoBone> what) {
        if (bone.isEmpty()) {
            return;
        }

        model.getBone(bone).ifPresent(what);
    }
}
