package com.ashvehicles.client.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.ashvehicles.vehicle.Ride;
import com.ashvehicles.vehicle.VehicleChassis;

import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * 装軌車両の履帯を1つのリンクから組み立て、車両が既に指定している車輪の周りへ敷く。
 *
 * <p>履帯についてジオメトリが描いている物は、リンク1つ以外に何も無い。帯は車輪を囲む張った帯——ベルトを複数の
 * プーリーへ張ったときの形であり、履帯とはまさにそれだ——であり、車輪はベイク済みモデルから、自身のジオメトリが与える
 * 大きさと位置のまま読む。だから Blockbench で車輪を動かしても、大きい物へ替えても、まったく別の戦車でも、ここや車両
 * ファイルを変更する必要は無い。
 *
 * <p>帯は車両が走る平面上で求める。周回は「車輪の前後位置」と「地面からの高さ」で記述され、第3軸が決めるのは車輪が
 * 車体のどちら側にあるかだけだ。車輪はそれによって2群へ自動的に分かれ、各群が自分の車輪の張り出し距離に応じた帯を
 * 得る。1台につきリンクボーン1つで足り、側面ごとに要らない理由はそれだ。
 *
 * <p><b>作者がやるべきこと。</b>リンクを1つ、専用ボーンの中に、平らに寝かせてボーンの Z 方向へ伸ばして作る。置かれた
 * 場所から帯が求める場所へ描かれるので、どこに作ったかは問題にならない。大きさは問題になる。周回のピッチはそこから取る
 * からだ。
 *
 * <p><b>処理コストの所在。</b>形状はジオメトリだけで決まるので、モデルごとに1回求めてベイク済みモデル自体に紐付けて
 * 保持する。つまりリソースリロードは新しいモデルをベイクするので、古い物を捨てろと指示せずとも新しい形状が得られる。
 * 毎フレーム残るのはリンク1つにつき帯上の点1つと、そこへ置く行列だけだ。
 */
public final class TrackBelt {
    /**
     * 車輪角が進んでいるとき履帯がどちらへ流れるか。モデルが回していないリンクボーンの場合の値。
     *
     * <p>このモデルが回しているかどうかは {@link Shape#travelSign} が持つ——帯はリンクの親の軸で敷かれるが、ここの
     * モデルの半分は全てをルートボーンにぶら下げてそこで半回転させており、それが親の軸を車両の軸に対して反転させる。
     * 車輪自体は既に両方の種類で同じ向きに転がるようにしてある。{@link VehicleGeoModel#turnAboutX} 参照。この対が
     * 無いと、履帯が一方向へ、その下の車輪が逆方向へ動くことになる。
     *
     * <p><em>全車両で一斉に</em>履帯が逆走するならここを反転する。1台だけ逆走するのはこれではない。
     */
    private static final float TRAVEL_SIGN = 1.0F;

    /**
     * 帯を張る際、各車輪を何点で表すか。
     *
     * <p>帯は車輪群の凸包であり、円の集合の凸包を取る最も安価で正直な方法は、円周上の標本点の凸包を取ることだ。24点なら
     * 周回の角が真円の約1%内側に入るが、それは転輪上で1ピクセル未満であり、リンクの厚みに対してはまったくの無だ。
     */
    private static final int ARC_STEPS = 24;

    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    /** リンク1つを、あるべき場所へ置いた後どう描くか。 */
    public interface LinkDrawer {
        void draw(GeoBone link);
    }

    /**
     * 1モデル上の全ての帯の形状と、リンクが自身のボーン内のどこにあるか。
     *
     * @param belts 車体の片側につき1つ。車輪が一直線に並ぶ車両では全体で1つ
     * @param linkCentre リンク自身のジオメトリの中心。リンクボーン自身の軸、ブロック単位。帯に載るのはボーンの支点
     *                   ではなくこの点だ。支点から片側へずらして作られたリンクは、さもないと周回からはみ出す
     * @param travelSign 帯を敷いた軸が車両と同じ向きか逆向きか。ルートボーンが回されたモデルでは -1。
     *                   {@link #TRAVEL_SIGN} 参照
     */
    private record Shape(List<Belt> belts, Vector3f linkCentre, float travelSign) {
        static final Shape NONE = new Shape(List.of(), new Vector3f(), 1.0F);
    }

    /**
     * 閉じた帯1つ。点の環と、帯に沿った各点までの距離。
     *
     * @param x この側の周回が車両中心からどれだけ外側に位置するか
     * @param z 周回の各点の前後位置（ブロック、リンクボーンの親の軸）
     * @param y 同じ点の高さ
     * @param run 帯に沿った各点までの距離。最後の要素は1周分
     * @param links 1周に入るリンク数。{@link #links} 参照
     * @param pitch リンク間の距離。1周をリンク数で割った値なので、最後のリンクが最初のリンクとぴったり合う
     * @param rollRadius 車輪が回る半径。車輪角を走行距離へ変換するのに使う。{@link #rollRadius} 参照
     */
    private record Belt(float x, float[] z, float[] y, float[] run, int links, float pitch,
            float rollRadius) {
        /**
         * 帯に沿って指定距離（折り返しあり）進んだ位置を、{@code (z, y)} として {@code into} へ書き込む。
         */
        void pointAt(float distance, Vector3f into) {
            float length = this.run[this.run.length - 1];
            float along = distance % length;

            if (along < 0.0F) {
                along += length;
            }

            int low = 0;
            int high = this.run.length - 1;

            while (low + 1 < high) {
                int mid = (low + high) >>> 1;

                if (this.run[mid] <= along) {
                    low = mid;
                } else {
                    high = mid;
                }
            }

            float span = this.run[low + 1] - this.run[low];
            float t = span <= 0.0F ? 0.0F : (along - this.run[low]) / span;
            int next = (low + 1) % this.z.length;

            into.set(this.z[low] + (this.z[next] - this.z[low]) * t,
                    this.y[low] + (this.y[next] - this.y[low]) * t, 0.0F);
        }
    }

    /**
     * これまでに求めた形状を、算出元のベイク済みモデルに紐付けて保持する。
     *
     * <p>弱参照で、キーはモデルオブジェクト自体。おかげでリロードが無料で正しくなる。GeckoLib はリロードしたジオメトリ
     * から新しいモデルをベイクし、新しいモデルはこれとは別物なので、実際に描かれるジオメトリから形状が求め直される。
     * 呼び出し元が全てレンダースレッドなのでロック無しで保持する。
     */
    private static final Map<BakedGeoModel, Map<VehicleChassis.Track, Shape>> SHAPES = new WeakHashMap<>();

    private TrackBelt() {
    }

    /** このボーンが、車両の履帯を組み立てる元となるリンクかどうか。 */
    public static boolean isLink(VehicleChassis.Model setup, GeoBone bone) {
        VehicleChassis.Track track = setup.track().orElse(null);

        return track != null && track.link().equals(bone.getName());
    }

    /**
     * 車両の履帯全体を描く。1つのリンクボーンを各帯に沿って移動させ、各リンクの位置で {@code drawer} へ渡す。
     *
     * <p>終わったらボーンはジオメトリが置いた位置へ戻す。画面上の同種の全車両と、各車両への全パスで共有される1つの
     * ボーンなので、周回上に置き去りにされたボーンは次のパスの起点になってしまう。
     *
     * <p>周回は、上で車体が動いている間も地面に留まる。モデル全体がサスペンションの動きで pose stack 上を揺れる——
     * {@link Ride} 参照——し、帯も他と同様モデルの子なので、放っておけば車体と一緒に上下し、車両が段差を越えるたび履帯
     * が地面から浮く。よって周回の各点も、転輪と同様、車体の動きが持ち上げた分だけちょうど戻す。すると帯は長さ方向に
     * たわむが、それはトーションバー上を動く車輪の上で実物の履帯がやることそのものだ。
     *
     * @param wheelAngle 転輪の回転角（度）。周回はその車輪が転がった距離だけスクロールするので、リンクが車輪上で滑って
     *                   見えることはない。1回転ごとに0へ戻るが、周回がそれに耐えられるのは {@link #rollRadius} の丸め
     *                   のおかげだ
     * @param ride バネ上の車体の変位
     * @param wheelTravel 転輪が動いてよい距離（ブロック）。周回を戻す量の上限であり、それを超えると車輪と一緒にストッパー
     *                    に当たる
     * @return 描く周回があったか。false なら呼び出し元がボーンを作られたまま描く。帯を張る車輪が無いモデルにはそれが
     *         正直な答えだ
     */
    public static boolean draw(BakedGeoModel model, VehicleChassis.Model setup, GeoBone link,
            float wheelAngle, Ride ride, float wheelTravel, LinkDrawer drawer) {
        Shape shape = shapeOf(model, setup, link);

        if (shape.belts().isEmpty()) {
            return false;
        }

        Vector3f here = new Vector3f();
        Vector3f next = new Vector3f();
        Vector3f pivot = new Vector3f();
        Vector3f fromPivot = new Vector3f();
        Matrix4f turn = new Matrix4f();
        boolean sprung = !ride.isLevel() && wheelTravel > 0.0F;

        for (Belt belt : shape.belts()) {
            float travel = TRAVEL_SIGN * shape.travelSign() * wheelAngle * DEG_TO_RAD * belt.rollRadius();

            for (int i = 0; i < belt.links(); i++) {
                float along = travel + i * belt.pitch();

                belt.pointAt(along, here);
                belt.pointAt(along + belt.pitch(), next);

                if (sprung) {
                    here.y -= plant(shape, belt, here.x(), ride, setup.scale(), wheelTravel);
                    next.y -= plant(shape, belt, next.x(), ride, setup.scale(), wheelTravel);
                }

                place(link, shape.linkCentre(), belt.x(), here, next, pivot, fromPivot, turn);
                drawer.draw(link);
            }
        }

        restore(link);

        return true;
    }

    /**
     * 車体の動きが周回上の1点をどれだけ持ち上げたか（モデルのブロック単位）。呼び出し元が同じ量だけ戻せるようにする。
     *
     * <p>帯はモデルではなくリンクボーンの親の軸で敷かれるし、ここのモデルの半分は全てを半回転したルートボーンへ
     * ぶら下げている——それはまさに {@link Shape#travelSign} が周回の進行方向のために既に測っている物だ。同じ値が
     * 帯上の点を {@link Ride#liftOf} が働く軸へ戻す。
     *
     * @param z 周回上のどこに点があるか。帯自身の軸で
     */
    private static float plant(Shape shape, Belt belt, float z, Ride ride, float scale, float wheelTravel) {
        float lift = ride.liftOf(shape.travelSign() * belt.x(), shape.travelSign() * z, scale);
        float stop = wheelTravel / Math.max(scale, 0.01F);

        return Mth.clamp(lift, -stop, stop);
    }

    /**
     * リンクを帯上の2点の間に置く。その間の周回に沿って寝るよう回し、自身のジオメトリの中心がその区間の中央に来るよう
     * 移動させる。
     *
     * <p>回すのは X 軸周りだけ。履帯のリンクは周回に沿って傾く以外のことはしないし、1つの平面上の形である周回とは、
     * 全リンクがその平面上に乗る周回だからだ。
     */
    private static void place(GeoBone link, Vector3f centre, float x, Vector3f here, Vector3f next,
            Vector3f pivot, Vector3f fromPivot, Matrix4f turn) {
        BoneSnapshot rest = BakedGeometry.rest(link);
        float dz = next.x() - here.x();
        float dy = next.y() - here.y();

        // X 軸周りに a 回すとボーン自身の +Z は (0, −sin a, cos a) へ移るので、リンクを区間に沿わせる回転は、正弦が
        // 立ち上がりの符号を反転した値になる物だ。
        float rotX = rest.getRotX() + (float) Math.atan2(-dy, dz);

        link.updateRotation(rotX, rest.getRotY(), rest.getRotZ());

        pivot.set(link.getPivotX(), link.getPivotY(), link.getPivotZ()).div(BakedGeometry.UNITS);
        fromPivot.set(centre).sub(pivot);
        turn.identity().rotateZ(rest.getRotZ()).rotateY(rest.getRotY()).rotateX(rotX)
                .transformPosition(fromPivot);

        // ボーンの最終位置は、オフセット＋支点＋支点からリンク本体までの距離を回した物になる。よってオフセットは、
        // 目標位置からそれらを引いた残りだ。オフセットの X は反転して適用される——RenderUtil.translateMatrixToBone
        // 参照——ので、その項だけ符号が逆になっている。
        float wantZ = (here.x() + next.x()) * 0.5F;
        float wantY = (here.y() + next.y()) * 0.5F;

        link.updatePosition(
                -(x - pivot.x() - fromPivot.x()) * BakedGeometry.UNITS,
                (wantY - pivot.y() - fromPivot.y()) * BakedGeometry.UNITS,
                (wantZ - pivot.z() - fromPivot.z()) * BakedGeometry.UNITS);
    }

    /** リンクボーンを、ジオメトリファイルが残した位置へ正確に戻す。 */
    private static void restore(GeoBone link) {
        BoneSnapshot rest = BakedGeometry.rest(link);

        link.updateRotation(rest.getRotX(), rest.getRotY(), rest.getRotZ());
        link.updatePosition(rest.getOffsetX(), rest.getOffsetY(), rest.getOffsetZ());
    }

    // ------------------------------------------------------------------
    // 形状の算出
    // ------------------------------------------------------------------

    private static Shape shapeOf(BakedGeoModel model, VehicleChassis.Model setup, GeoBone link) {
        VehicleChassis.Track track = setup.track().orElse(null);

        if (track == null) {
            return Shape.NONE;
        }

        return SHAPES.computeIfAbsent(model, ignored -> new HashMap<>())
                .computeIfAbsent(track, ignored -> build(model, setup, track, link));
    }

    /**
     * モデル上の全リンクの位置を1回だけ求める。
     *
     * <p>描画中のモデルではなく、作られたままのモデルから読む。回っている転輪も同じ場所の転輪であり、回転する車輪から
     * 毎フレーム周回を導出し直せば、同じ答えに辿り着くために毎秒20回同じ作業を払うことになる。
     */
    private static Shape build(BakedGeoModel model, VehicleChassis.Model setup,
            VehicleChassis.Track track, GeoBone link) {
        BakedGeometry.Bounds linkBox = BakedGeometry.bounds(link, new Matrix4f());

        if (linkBox == null) {
            return Shape.NONE;
        }

        // ジオメトリファイルが残した向きで見たリンク自身の寸法。周回方向の長さがピッチであり、厚みが、リンクの内側面が
        // リムに触れるために帯を車輪からどれだけ離すべきかを決める。
        BakedGeometry.Bounds asBuilt = BakedGeometry.bounds(link, BakedGeometry.restTransform(link));
        float pitch = (track.pitch() > 0.0F ? track.pitch() : asBuilt.sizeZ()) * track.spacing();
        float outset = track.outset().orElse(asBuilt.sizeY() * 0.5F);

        if (pitch < 0.001F) {
            return Shape.NONE;
        }

        // 帯全体を記述する軸。リンクボーンの親の軸なので、変換を追加せずリンクを帯へ載せられる。それらが車両に対して
        // どちら向きかが「逆走する周回」の分かれ目なので、ここで一緒に読んでおく。
        Matrix4f intoLink = BakedGeometry.toRoot(link.getParent()).invert();
        float travelSign = handedness(intoLink);
        List<Wheel> wheels = wheels(model, track.wheelsOr(setup.roadWheels()), intoLink);

        if (wheels.size() < 2) {
            return Shape.NONE;
        }

        List<Belt> belts = new ArrayList<>(2);

        for (List<Wheel> side : sides(wheels)) {
            Belt belt = belt(side, outset, pitch, track.maxLinks());

            if (belt != null) {
                belts.add(belt);
            }
        }

        return new Shape(belts, linkBox.centre(), travelSign);
    }

    /**
     * 帯の座標系のある軸が、車両と同じ向きを指すか逆向きを指すか。半回転したルートボーンにぶら下がるモデルでは逆になる。
     */
    private static float handedness(Matrix4f intoLink) {
        return intoLink.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F)).x() < 0.0F ? -1.0F : 1.0F;
    }

    /**
     * 帯を張る対象の車輪1つ。中心位置と大きさを、リンクボーンの親の軸で持つ。変換を追加せずリンクを帯へ載せるためだ。
     */
    private record Wheel(float x, float y, float z, float radius) {
    }

    private static List<Wheel> wheels(BakedGeoModel model, List<String> names, Matrix4f intoLink) {
        List<Wheel> found = new ArrayList<>(names.size());

        for (String name : names) {
            GeoBone bone = model.getBone(name).orElse(null);

            if (bone == null) {
                continue;
            }

            BakedGeometry.Bounds box =
                    BakedGeometry.bounds(bone, new Matrix4f(intoLink).mul(BakedGeometry.toRoot(bone)));

            if (box == null) {
                continue;
            }

            // 転輪は円盤なので、その大きさは車両が走る平面内でどこまで届くかだ。2つの寸法のどちらがそれかは車輪の
            // 作り方と起こし方次第であり、大きい方を取れば誰も気にせずに済む。
            float radius = Math.max(box.sizeY(), box.sizeZ()) * 0.5F;

            if (radius > 0.0F) {
                found.add(new Wheel(box.centre().x(), box.centre().y(), box.centre().z(), radius));
            }
        }

        return found;
    }

    /**
     * 車輪を車体の左右2群へ、全体の中心のどちら側にあるかで振り分ける。
     *
     * <p>全車輪が片側に寄る車両——片側の車輪しか指定していないモデルや、一直線に並ぶ物——は、周回1つと空の周回1つでは
     * なく周回1つとして返る。
     */
    private static List<List<Wheel>> sides(List<Wheel> wheels) {
        float middle = 0.0F;

        for (Wheel wheel : wheels) {
            middle += wheel.x();
        }

        middle /= wheels.size();

        List<Wheel> left = new ArrayList<>();
        List<Wheel> right = new ArrayList<>();

        for (Wheel wheel : wheels) {
            (wheel.x() < middle ? left : right).add(wheel);
        }

        if (left.isEmpty() || right.isEmpty()) {
            return List.of(wheels);
        }

        return List.of(left, right);
    }

    /** 片側の車輪に帯を1つ張り、1周に入るリンク数を求める。 */
    private static Belt belt(List<Wheel> side, float outset, float pitch, int maxLinks) {
        if (side.size() < 2) {
            return null;
        }

        float[] pz = new float[side.size() * ARC_STEPS];
        float[] py = new float[pz.length];
        int at = 0;
        float x = 0.0F;

        for (Wheel wheel : side) {
            float radius = wheel.radius() + outset;
            x += wheel.x();

            for (int step = 0; step < ARC_STEPS; step++) {
                double angle = 2.0 * Math.PI * step / ARC_STEPS;

                pz[at] = wheel.z() + radius * (float) Math.cos(angle);
                py[at] = wheel.y() + radius * (float) Math.sin(angle);
                at++;
            }
        }

        int[] ring = hull(pz, py);

        if (ring.length < 3) {
            return null;
        }

        float[] z = new float[ring.length];
        float[] y = new float[ring.length];
        float[] run = new float[ring.length + 1];

        for (int i = 0; i < ring.length; i++) {
            z[i] = pz[ring[i]];
            y[i] = py[ring[i]];
        }

        for (int i = 0; i < ring.length; i++) {
            int next = (i + 1) % ring.length;

            run[i + 1] = run[i] + (float) Math.hypot(z[next] - z[i], y[next] - y[i]);
        }

        float length = run[ring.length];
        int links = links(length, pitch, Math.max(maxLinks, 3), radius(side));
        float spaced = length / links;

        return new Belt(x / side.size(), z, y, run, links, spaced, rollRadius(side, spaced));
    }

    /**
     * 帯1周に入るリンク数。
     *
     * <p>入るだけ、ただし数個の増減を許す——その増減こそが要点だ。
     *
     * <p>リンク間隔で割り切れてほしい物が2つある。1つは帯そのもの。最後のリンクが最初のリンクと合わねばならないからで、
     * これは交渉の余地が無い。よって間隔は1周を描画リンク数で割った値になる。もう1つは車輪の円周。車両は走行距離を転輪
     * 1回転分までしか数えず、そこで0へ戻すからだ——回る車輪にはそれで足りる。1回転した車輪は元の位置の車輪だ——が、
     * 履帯にとっては、余ったリンクの端数の分だけ周回全体が後ろへ跳ぶことを意味する。履帯が1周する間に8回か9回もだ。
     *
     * <p>両方を厳密に満たすリンク数は存在しない。だが数を1つ2つ動かしても周回の見た目はほとんど変わらない——リンクは
     * 作られた大きさで描かれるので、変わるのは面一に並ぶか数%重なるかだけだ——一方で、車輪側に余るリンクの端数は1周期
     * まるごと動く。そこで、正直な値の1/20以内の候補を試し、余りが最小になる物を採る。レオパルトでは、車輪に対する5%の
     * ずれが0.5%になる。
     */
    private static int links(float length, float pitch, int maxLinks, float radius) {
        int ideal = Math.min(Math.max(Math.round(length / pitch), 3), maxLinks);
        int span = Math.max(Math.round(ideal * 0.05F), 1);
        float turn = (float) (2.0 * Math.PI) * radius;
        int best = ideal;
        float least = Float.MAX_VALUE;

        for (int count = Math.max(ideal - span, 3); count <= Math.min(ideal + span, maxLinks); count++) {
            float spaced = length / count;
            float over = Math.abs(Math.max(Math.round(turn / spaced), 1) * spaced - turn);

            if (over < least) {
                least = over;
                best = count;
            }
        }

        return best;
    }

    /**
     * 周回をスクロールさせる半径。中間的な車輪の半径を、1周が整数リンクになるよう丸めた物だ——{@link #links} が既に
     * 丸め量をほぼ0にする個数を選んでいる。残った分は、跳躍ではなく車輪に対するゆっくりしたずれとして周回が受け流す。
     */
    private static float rollRadius(List<Wheel> side, float pitch) {
        float turn = (float) (2.0 * Math.PI) * radius(side);
        int links = Math.max(Math.round(turn / pitch), 1);

        return links * pitch / (float) (2.0 * Math.PI);
    }

    /** 中間的な車輪の半径。1つだけ寸法の違う誘導輪が片側全体を代表しないようにするため。 */
    private static float radius(List<Wheel> side) {
        float[] radii = new float[side.size()];

        for (int i = 0; i < radii.length; i++) {
            radii[i] = side.get(i).radius();
        }

        Arrays.sort(radii);

        return radii[radii.length / 2];
    }

    /**
     * 標本点の凸包を反時計回りに、monotone chain 法で求める——それが帯だ。複数のプーリーへ張ったベルトは、まさにその
     * 凸包だからである。
     *
     * @return 凸包の点の添字を順に並べた物
     */
    private static int[] hull(float[] px, float[] py) {
        Integer[] order = new Integer[px.length];

        for (int i = 0; i < px.length; i++) {
            order[i] = i;
        }

        Arrays.sort(order, (a, b) -> px[a] != px[b]
                ? Float.compare(px[a], px[b])
                : Float.compare(py[a], py[b]));

        int[] chain = new int[px.length * 2];
        int size = 0;

        // 周回の下側を、左から右へ。
        for (int i = 0; i < order.length; i++) {
            while (size >= 2 && cross(px, py, chain[size - 2], chain[size - 1], order[i]) <= 0.0F) {
                size--;
            }

            chain[size++] = order[i];
        }

        // そして上側を戻る。下側へ食い込んではならないので、下限を設けてある。
        int floor = size + 1;

        for (int i = order.length - 2; i >= 0; i--) {
            while (size >= floor && cross(px, py, chain[size - 2], chain[size - 1], order[i]) <= 0.0F) {
                size--;
            }

            chain[size++] = order[i];
        }

        // 最後の点は最初の点と同じになる。
        return Arrays.copyOf(chain, Math.max(size - 1, 0));
    }

    private static float cross(float[] px, float[] py, int origin, int a, int b) {
        return (px[a] - px[origin]) * (py[b] - py[origin]) - (py[a] - py[origin]) * (px[b] - px[origin]);
    }

}
