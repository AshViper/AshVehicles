package com.ashvehicles.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.ashvehicles.vehicle.VehicleShape;

/**
 * 機体の形から起こした凝結の形。ベイパーコーンの殻と、主翼上面のベイパーを置く面。
 *
 * <p>以前のコーンは胴体の太さから求めた1つの円だった。実機のコーンは円ではない——胴体と主翼の付け根を
 * 包む、横に潰れた殻で、機体を正面から見た輪郭をそのまま膨らませた形をしている。だからここでは当たり判定の
 * 箱を正面へ投影し、コーンの位置の前後に掛かる箱の輪郭を中心から放射状に測る。主翼まで全部拾うと十字形に
 * なるので、胴体の太さの {@link #WING_REACH} 倍で頭を打ち、角度方向に均してから外へ膨らませる。
 *
 * <p>箱から作るので定義ファイルは何も書かなくてよい。{@code effects.cone} と {@code cone_radius} を書いた
 * 機体では、中心はその点に、輪郭は平均半径がその値になるよう縮尺される（形は箱のまま）。
 *
 * <p>形は箱が変わらない限り同じなので、機体が1度だけ組んで持つ（{@link AircraftEntity}）。
 */
final class VapourShape {
    /** 輪郭を測る方位の数。 */
    static final int SPOKES = 48;
    /** 輪郭が胴体の太さの何倍まで主翼へ伸びてよいか。それより外の翼はコーンに入らない。 */
    private static final double WING_REACH = 2.4;
    /** 輪郭を膨らませる量。固定分と、半径に比例する分。コーンは機体の表面から少し離れて立つ。 */
    private static final double INFLATE = 0.35;
    private static final double INFLATE_SCALE = 0.18;
    /** 殻が後ろへ伸びる長さ。機体全長に対する割合。 */
    private static final double DEPTH = 0.22;
    /** 殻の後端が前端より何割広がるか。コーンは後ろへ開くベルの形をしている。 */
    private static final double FLARE = 0.35;
    /** 箱から胴体を読めなかった機体のコーン半径。 */
    private static final double FALLBACK_RADIUS = 3.0;

    /** 殻の前端の中心。機体座標系。 */
    final Vec3 centre;
    /** 方位ごとの輪郭の半径。角度 0 が右、π/2 が上。 */
    final double[] radii;
    /** 最も大きい半径。粒の大きさと、周に沿った一様な撒き方に使う。 */
    final double largest;
    /** 輪郭の周長。粒の数をこれに比例させる。 */
    final double perimeter;
    /** 殻の奥行き（ブロック）。 */
    final double depth;
    /** 主翼上面。右翼側の箱だけを持ち、左は x を反転して使う。 */
    final List<Panel> wings;

    /** 主翼上面の一区画。機体座標系で、x は外側（正）、y は上面の高さ。 */
    record Panel(double inner, double outer, double top, double front, double back) {
    }

    private VapourShape(Vec3 centre, double[] radii, double depth, List<Panel> wings) {
        this.centre = centre;
        this.radii = radii;
        this.depth = depth;
        this.wings = wings;
        double largest = 0.0;
        double perimeter = 0.0;

        for (int i = 0; i < SPOKES; i++) {
            largest = Math.max(largest, radii[i]);
            perimeter += radii[i] * (Math.PI * 2.0 / SPOKES);
        }

        this.largest = largest;
        this.perimeter = perimeter;
    }

    /** 方位 {@code angle}（ラジアン）での輪郭の半径。間は線形に補う。 */
    double radius(double angle) {
        double at = Mth.positiveModulo(angle / (Math.PI * 2.0), 1.0) * SPOKES;
        int i = (int) at;
        double t = at - i;

        return Mth.lerp(t, this.radii[i % SPOKES], this.radii[(i + 1) % SPOKES]);
    }

    /** 殻の上の1点。{@code along} は 0 が前端、1 が後端。機体座標系。 */
    Vec3 shell(double angle, double along) {
        double r = this.radius(angle) * (1.0 + FLARE * along);

        return new Vec3(this.centre.x + Math.cos(angle) * r,
                this.centre.y + Math.sin(angle) * r,
                this.centre.z - this.depth * along);
    }

    /**
     * 箱から形を組む。
     *
     * @param boxes 当たり判定の箱
     * @param fallbackCentre 箱が無い機体のコーン中心
     * @param cone ファイルが書いたコーンの中心。無ければ null
     * @param coneRadius ファイルが書いたコーンの半径。0 なら箱のまま
     */
    static VapourShape of(List<VehicleShape.Box> boxes, Vec3 fallbackCentre, Vec3 cone, double coneRadius) {
        List<Extent> extents = new ArrayList<>(boxes.size());
        double front = -Double.MAX_VALUE;
        double back = Double.MAX_VALUE;
        double widest = -1.0;
        double wingZ = fallbackCentre.z;
        double halfBody = 0.0;
        double bodyY = fallbackCentre.y;

        for (VehicleShape.Box box : boxes) {
            Extent e = Extent.of(box);
            extents.add(e);
            front = Math.max(front, e.maxZ);
            back = Math.min(back, e.minZ);

            // 胴体は中心線に掛かる箱。最も太いものの高さをコーンの中心に取る。
            if (e.minX <= 0.0 && e.maxX >= 0.0) {
                double half = Math.max(e.maxX - e.minX, e.maxY - e.minY) / 2.0;

                if (half > halfBody) {
                    halfBody = half;
                    bodyY = (e.minY + e.maxY) / 2.0;
                }
            }

            // 前後の位置は最も横へ張り出した箱（主翼）の中心。凝結は翼上面の最も速い気流の所に出る。
            double reach = Math.max(Math.abs(e.minX), Math.abs(e.maxX));

            if (reach > widest) {
                widest = reach;
                wingZ = (e.minZ + e.maxZ) / 2.0;
            }
        }

        if (halfBody <= 0.0) {
            double[] round = new double[SPOKES];
            java.util.Arrays.fill(round, coneRadius > 0.0 ? coneRadius : FALLBACK_RADIUS);

            return new VapourShape(cone != null ? cone : fallbackCentre, round, 2.0, List.of());
        }

        double length = front - back;
        Vec3 centre = cone != null ? cone : new Vec3(0.0, bodyY, wingZ);
        double depth = Math.max(length * DEPTH, 1.0);
        // 殻の前端から奥行きの分だけ後ろまでに掛かる箱が輪郭を作る。前端の少し前も拾う——コーンの頭は
        // 衝撃波の立つ所で、そこには主翼の前縁がある。
        double sliceFront = centre.z + depth * 0.3;
        double sliceBack = centre.z - depth;
        double cap = Math.max(halfBody, 0.5) * WING_REACH;
        double[] radii = new double[SPOKES];

        for (int i = 0; i < SPOKES; i++) {
            double angle = i * Math.PI * 2.0 / SPOKES;
            double dx = Math.cos(angle);
            double dy = Math.sin(angle);
            double far = 0.0;

            for (Extent e : extents) {
                if (e.maxZ < sliceBack || e.minZ > sliceFront) {
                    continue;
                }

                // 翼は左右対称なので、右側の方位では右へ、左側では左へ張り出した側を見る（x の絶対値）。
                far = Math.max(far, exit(Math.abs(dx), dy, e.minAbsX(), e.maxAbsX(),
                        e.minY - centre.y, e.maxY - centre.y));
            }

            radii[i] = Math.min(far > 0.0 ? far : halfBody, cap);
        }

        radii = smooth(smooth(radii));

        for (int i = 0; i < SPOKES; i++) {
            radii[i] += INFLATE + radii[i] * INFLATE_SCALE;
        }

        if (coneRadius > 0.0) {
            double mean = 0.0;

            for (double r : radii) {
                mean += r / SPOKES;
            }

            for (int i = 0; i < SPOKES; i++) {
                radii[i] *= coneRadius / mean;
            }
        }

        return new VapourShape(centre, radii, depth, wings(extents, halfBody));
    }

    /**
     * 中心から方位 (dx, dy) へ伸ばした半直線が、x-y の矩形から出る距離。当たらなければ 0。
     * 矩形は中心から見た座標で、dx は 0 以上（右半分へ折り返してある）。
     */
    private static double exit(double dx, double dy, double minX, double maxX, double minY, double maxY) {
        double near = 0.0;
        double far = Double.MAX_VALUE;

        if (Math.abs(dx) < 1.0E-9) {
            if (minX > 0.0 || maxX < 0.0) {
                return 0.0;
            }
        } else {
            double a = minX / dx;
            double b = maxX / dx;
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
        }

        if (Math.abs(dy) < 1.0E-9) {
            if (minY > 0.0 || maxY < 0.0) {
                return 0.0;
            }
        } else {
            double a = minY / dy;
            double b = maxY / dy;
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
        }

        return near <= far && far > 0.0 && far < Double.MAX_VALUE ? far : 0.0;
    }

    /** 隣り合う方位で均す。箱の角がそのまま殻の角にならないように。 */
    private static double[] smooth(double[] radii) {
        double[] out = new double[SPOKES];

        for (int i = 0; i < SPOKES; i++) {
            out[i] = (radii[(i + SPOKES - 1) % SPOKES] + radii[i] * 2.0 + radii[(i + 1) % SPOKES]) / 4.0;
        }

        return out;
    }

    /**
     * 主翼上面の区画。中心線から離れた、平たい（幅が厚みの 3 倍を超える）箱を主翼と読む。尾翼も拾うが、
     * 尾翼の上面にも凝結は出るので困らない。
     */
    private static List<Panel> wings(List<Extent> extents, double halfBody) {
        List<Panel> panels = new ArrayList<>();

        for (Extent e : extents) {
            double inner = e.minAbsX();
            double outer = e.maxAbsX();
            double thick = e.maxY - e.minY;

            if (outer - inner > thick * 3.0 && outer > halfBody * 1.2 && e.maxX > 0.0) {
                panels.add(new Panel(Math.max(inner, halfBody * 0.8), outer, e.maxY,
                        e.maxZ, e.minZ));
            }
        }

        return List.copyOf(panels);
    }

    /** 回転を掛けた後の箱の機体座標系での広がり。 */
    private record Extent(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        static Extent of(VehicleShape.Box box) {
            Vec3 o = box.offset();
            Vec3 h = box.size().scale(0.5);
            Vec3 r = box.rotation();
            Quaternionf q = new Quaternionf().rotationYXZ((float) Math.toRadians(r.y),
                    (float) Math.toRadians(r.x), (float) Math.toRadians(r.z));
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

            for (int c = 0; c < 8; c++) {
                Vector3f p = q.transform(new Vector3f(
                        (float) ((c & 1) == 0 ? -h.x : h.x),
                        (float) ((c & 2) == 0 ? -h.y : h.y),
                        (float) ((c & 4) == 0 ? -h.z : h.z)));
                minX = Math.min(minX, o.x + p.x);
                maxX = Math.max(maxX, o.x + p.x);
                minY = Math.min(minY, o.y + p.y);
                maxY = Math.max(maxY, o.y + p.y);
                minZ = Math.min(minZ, o.z + p.z);
                maxZ = Math.max(maxZ, o.z + p.z);
            }

            return new Extent(minX, maxX, minY, maxY, minZ, maxZ);
        }

        /** 中心線からの最も近い横距離。中心線を跨ぐ箱は 0。 */
        double minAbsX() {
            return this.minX <= 0.0 && this.maxX >= 0.0 ? 0.0 : Math.min(Math.abs(this.minX), Math.abs(this.maxX));
        }

        double maxAbsX() {
            return Math.max(Math.abs(this.minX), Math.abs(this.maxX));
        }
    }
}
