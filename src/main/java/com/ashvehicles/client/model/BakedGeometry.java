package com.ashvehicles.client.model;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

/**
 * ベイク済みモデルを作者が残したまま読む。ボーンのジオメトリが実際にどこにあるか、そしてボーン自身の軸をモデルの
 * 軸へ移す行列。
 *
 * <p>ここで扱うのは描画中のフレームではなくモデルの<em>静止</em>ポーズだ。回っている転輪も同じ場所の転輪だし、
 * 旋回した砲塔も同じ高さの砲塔だ。呼び出し元が知りたいのは作者が部品を置いた場所であり、それはフレームごとに変わ
 * らない——だからアニメーションがボーンにさせている事ではなく、初期スナップショットから求める。
 *
 * <p>変換は GeckoLib がボーンを描くときの並べ方とまったく同じ——{@code RenderUtil.prepMatrixForBone} 参照——なので、
 * ここで求めた点は pose stack がそのボーンのジオメトリを置く点になる。この等価性こそこのクラスの価値の全てだ。
 * 履帯の敷設もサスペンションも、モデルが描かれるのと同じ空間に物を置かねばならず、ジオメトリファイルから順序を
 * 手作業で再導出すれば両者はいずれ食い違う。
 */
public final class BakedGeometry {
    /** 1ブロックあたりのモデル単位。ジオメトリファイルの全数値はこの単位で書かれる。 */
    public static final float UNITS = 16.0F;

    /**
     * 各ボーン自身のジオメトリがモデル軸でどこまで届くか、どこで回るかを、算出元のボーンをキーに保持する。
     * {@link #REACHES} に無いことは「自前のジオメトリを持たないボーン」を意味し、それも立派な答えとしてそのまま
     * 扱う。
     *
     *
     * <p>弱参照で、キーはボーンオブジェクト自体。おかげでリソースリロードが無料で正しくなる。GeckoLib はリロード
     * したジオメトリから新しいボーンをベイクし、新しいボーンはこれとは別物なので、実際に描かれるジオメトリから
     * 答えが求め直される。呼び出し元が全てレンダースレッドなのでロック無しで保持する。
     */
    private static final Map<GeoBone, Optional<Bounds>> REACHES = new WeakHashMap<>();
    private static final Map<GeoBone, Vector3f> PIVOTS = new WeakHashMap<>();

    private BakedGeometry() {
    }

    /**
     * ボーン自身のジオメトリの中心がモデル軸のどこにあるか（ブロック単位）。
     *
     * <p>支点ではなくジオメトリの方。転輪では支点が車軸なので両者は大抵近いが、回転中心から片側へずらして作られた
     * ボーンでは近くない——そして部品に合わせて何かを置く呼び出し元が欲しいのは部品の方だ。
     *
     * <p>自前の立方体を持たないボーンは支点へフォールバックする。そうしたボーンが自分の位置について言えるのは
     * それだけだからだ。
     */
    public static Vector3f centreOf(GeoBone bone) {
        return reachOf(bone).map(Bounds::centre).orElseGet(() -> new Vector3f(pivotOf(bone)));
    }

    /**
     * ボーン自身のジオメトリがモデル軸でどこまで届くか（ブロック単位）。持たないボーン——子を運ぶためだけに吊られた
     * 素の親——では空。
     *
     * <p>自分の物であって子の物ではない。呼び出し元がここから知りたいのは部品自体の位置であり、砲塔の箱は砲塔で
     * あって、砲塔＋車首方向へ突き出た砲身ではない。
     */
    public static Optional<Bounds> reachOf(GeoBone bone) {
        return REACHES.computeIfAbsent(bone, found -> Optional.ofNullable(bounds(found, toRoot(found))));
    }

    /**
     * ボーンが回る中心点。モデル軸・ブロック単位。
     *
     * <p>ボーンの支点は親の軸でもボーン<em>自身</em>の軸でも同じ位置にある——回転も拡大も支点を中心に適用され、
     * どちらも支点を動かさない——ので、{@link #toRoot} で上へ運ぶだけで答えの全てになる。
     */
    public static Vector3f pivotOf(GeoBone bone) {
        return PIVOTS.computeIfAbsent(bone, found -> toRoot(found).transformPosition(
                new Vector3f(found.getPivotX(), found.getPivotY(), found.getPivotZ()).div(UNITS)));
    }

    /** {@code into} を適用した後、ボーンのジオメトリがどこまで届くか（ブロック単位）。 */
    public record Bounds(Vector3f min, Vector3f max) {
        public Vector3f centre() {
            return new Vector3f(this.min).add(this.max).mul(0.5F);
        }

        public float sizeY() {
            return this.max.y() - this.min.y();
        }

        public float sizeZ() {
            return this.max.z() - this.min.z();
        }
    }

    /**
     * ボーン自身の立方体が埋める箱を {@code into} に通した物。
     *
     * <p>ファイルに書かれた寸法ではなく、立方体が実際に描かれる隅から取る。立方体は描画前に自身の支点周りに回され
     * るし、車輪は大抵寝かせて作ってから起こすからだ。回した隅の箱こそ画面に出る箱だ。
     *
     * @return 自前のジオメトリを持たないボーンでは null
     */
    public static Bounds bounds(GeoBone bone, Matrix4f into) {
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        boolean any = false;

        for (GeoCube cube : bone.getCubes()) {
            Matrix4f matrix = cubeTransform(cube, into);

            for (GeoQuad quad : cube.quads()) {
                if (quad == null) {
                    continue;
                }

                for (GeoVertex vertex : quad.vertices()) {
                    Vector3f corner = matrix.transformPosition(new Vector3f(vertex.position()));

                    min.min(corner);
                    max.max(corner);
                    any = true;
                }
            }
        }

        return any ? new Bounds(min, max) : null;
    }

    /** 立方体は描画前に自身の支点周りに回される。これがその回転だ。 */
    private static Matrix4f cubeTransform(GeoCube cube, Matrix4f into) {
        return new Matrix4f(into)
                .translate((float) cube.pivot().x() / UNITS, (float) cube.pivot().y() / UNITS,
                        (float) cube.pivot().z() / UNITS)
                .mul(rotation((float) cube.rotation().x(), (float) cube.rotation().y(),
                        (float) cube.rotation().z()))
                .translate((float) -cube.pivot().x() / UNITS, (float) -cube.pivot().y() / UNITS,
                        (float) -cube.pivot().z() / UNITS);
    }

    /**
     * ボーン自身の軸をモデル軸へ移す行列。このフレームでアニメーションがボーンに取らせているポーズではなく、
     * ジオメトリファイルが落ち着くポーズから求める。
     */
    public static Matrix4f toRoot(GeoBone bone) {
        if (bone == null) {
            return new Matrix4f();
        }

        return toRoot(bone.getParent()).mul(restTransform(bone));
    }

    /**
     * そのうちボーン1つ分のステップ。GeckoLib がボーンを描くときの並べ方とまったく同じ——オフセット、支点へ移動、
     * 回転、拡大、支点から戻す。{@code RenderUtil} 参照。
     */
    public static Matrix4f restTransform(GeoBone bone) {
        BoneSnapshot rest = rest(bone);
        float pivotX = bone.getPivotX() / UNITS;
        float pivotY = bone.getPivotY() / UNITS;
        float pivotZ = bone.getPivotZ() / UNITS;

        return new Matrix4f()
                .translate(-rest.getOffsetX() / UNITS, rest.getOffsetY() / UNITS, rest.getOffsetZ() / UNITS)
                .translate(pivotX, pivotY, pivotZ)
                .mul(rotation(rest.getRotX(), rest.getRotY(), rest.getRotZ()))
                .scale(rest.getScaleX(), rest.getScaleY(), rest.getScaleZ())
                .translate(-pivotX, -pivotY, -pivotZ);
    }

    /** Z→Y→X の順。GeckoLib がボーンも立方体もこの順で回す。 */
    public static Matrix4f rotation(float x, float y, float z) {
        return new Matrix4f().rotateZ(z).rotateY(y).rotateX(x);
    }

    /**
     * ジオメトリファイルがボーンを残した位置。GeckoLib がまだスナップショットを撮っていないボーンには撮らせる。
     * 何も動かしていないボーンなら同じ答えになる。
     */
    public static BoneSnapshot rest(GeoBone bone) {
        BoneSnapshot rest = bone.getInitialSnapshot();

        if (rest == null) {
            bone.saveInitialSnapshot();
            rest = bone.getInitialSnapshot();
        }

        return rest;
    }
}
