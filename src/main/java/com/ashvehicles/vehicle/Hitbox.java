package com.ashvehicles.vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.joml.Matrix3f;
import org.joml.Quaternionf;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 機体が寝ている角度のまま寝ている箱と、それについて誰かが訊きうる全ての問いへの答え。
 *
 * <p>記述に Minecraft の形状は一切使わない。{@code AABB} でも {@code VoxelShape} でもなく、それらから
 * 組み立ててもいない。中心・3つの半長・3本の軸であり、以下の判定は全部それに対して書かれている。それが
 * この class の存在意義だ。ゲームの形状はどれも世界に軸が揃っているので、ゲーム側の形状で傾いた箱を記述
 * するには、周りに直立した箱を描くか（それは斜面に傾いた車体が履帯の前の楔形の空気を埋めることを意味す
 * る）、小さな直立箱を斜面に沿って積むか（それは階段であり、どこでも正確でない）しかない。
 *
 * <p>ここで唯一の {@code AABB} は {@link #reach} で、これは形ではない。本当の判定を走らせる前に「探す
 * 価値のある領域」を示す物で、直立した箱が本当に得意な唯一の仕事がそれ。
 *
 * <p>判定は3つ。この MOD が当たり判定に対してすることは全部このどれか:
 *
 * <ul>
 * <li>{@link #clip} — 線分が箱へ最初に入る点。射撃、十字線、狙う物すべて。箱自身の軸でのスラブ法で、
 *     平行な面の対が3組あり、各対が「線分のうち面の間にある区間」を与え、3つ全部が重なる区間が箱の中。</li>
 * <li>{@link #sweep} — 動いている直立した箱が、この箱に触れるまでどこまで進めるか。プレイヤーが車体を
 *     すり抜けるのを止め、傾いた甲板の上に立たせ、機体自身の箱を世界に対して止める。分離軸定理を、
 *     yes/no ではなく時間の区間として走らせる。2つの凸形状は、影の間に隙間のある軸が1本でもある間だけ
 *     離れているので、最後の軸の隙間が尽きる瞬間が接触の瞬間になる。</li>
 * <li>{@link #contains} — 点が中にあるか。1つ目と同じ射影を、線分抜きで。</li>
 * </ul>
 */
public final class Hitbox {
    /**
     * ある軸方向の移動量がこれ未満なら移動なしとして扱い、これより短い軸は軸なしとして扱う。長さの二乗
     * が手元にある場面ではこの値の二乗と比べる。
     */
    private static final double NOTHING = 1.0E-9;

    /**
     * 「移動中に入った」ではなく「最初から中にいた」と数えるまでに、どれだけ箱へ食い込んでいてよいか。
     *
     * <p>丸め誤差1つ分だけ。箱の上に乗っている物は、そこへ運んだ移動の余りの分だけ中に入っており、それ
     * でもそこは床であり続けなければならない。
     */
    private static final double SETTLED = 1.0E-7;

    /** 世界の3軸。直立した箱が揃っている軸。 */
    private static final Vec3[] WORLD = {
            new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 1.0)
    };

    private final Vec3 centre;
    private final Vec3 half;
    /** 箱自身の3軸を世界座標の単位ベクトルで。正規直交なので転置がそのまま逆変換になる。 */
    private final Vec3[] axes;
    /** {@link #sweep} が試す15方向。変わらないので一度だけ計算する。 */
    private final Vec3[] directions;
    private final AABB reach;

    public Hitbox(Vec3 centre, Vec3 size, Quaternionf rotation) {
        Matrix3f matrix = rotation.get(new Matrix3f());

        this.centre = centre;
        this.half = size.scale(0.5);
        this.axes = new Vec3[] {
                column(matrix, 0), column(matrix, 1), column(matrix, 2)
        };
        this.directions = directions(WORLD, this.axes);

        // 箱が世界の各軸方向へ届く距離。自分の各軸が「半長×その軸のその方向成分」だけ寄与する。
        double x = this.span(this.half, 0);
        double y = this.span(this.half, 1);
        double z = this.span(this.half, 2);

        this.reach = new AABB(centre.x - x, centre.y - y, centre.z - z,
                centre.x + x, centre.y + y, centre.z + z);
    }

    private Hitbox(Vec3 centre, Vec3 half, Vec3[] axes, Vec3[] directions, AABB reach) {
        this.centre = centre;
        this.half = half;
        this.axes = axes;
        this.directions = directions;
        this.reach = reach;
    }

    public Vec3 centre() {
        return this.centre;
    }

    /**
     * この箱が収まる直立した箱。
     *
     * <p>検索用であって判定用ではない。これはまさに、この class が「機体として扱うのをやめる」ために存在
     * している空気そのもの。ここに置いてある唯一の理由は、世界が直立した箱で索引されており、検索には何か
     * 渡す必要があるから。
     */
    public AABB reach() {
        return this.reach;
    }

    /** 全周に余裕を付けた同じ箱。掠りを許す判定はこの形で要求する。 */
    public Hitbox grow(double margin) {
        if (margin == 0.0) {
            return this;
        }

        // ゼロより先へは行かせない。残りより大きく縮めと言われた箱は、裏返った箱ではなく余裕ゼロの箱。
        Vec3 half = new Vec3(Math.max(this.half.x + margin, NOTHING),
                Math.max(this.half.y + margin, NOTHING), Math.max(this.half.z + margin, NOTHING));

        return new Hitbox(this.centre, half, this.axes, this.directions,
                new AABB(this.centre.x - this.span(half, 0), this.centre.y - this.span(half, 1),
                        this.centre.z - this.span(half, 2), this.centre.x + this.span(half, 0),
                        this.centre.y + this.span(half, 1), this.centre.z + this.span(half, 2)));
    }

    /**
     * 同じ箱を平行移動した物。軸は保つ。軸は箱の中で作るのが高くつく部分であり、平行移動でまさに変わら
     * ない部分。
     */
    public Hitbox move(Vec3 offset) {
        return new Hitbox(this.centre.add(offset), this.half, this.axes, this.directions,
                this.reach.move(offset));
    }

    /** 点が箱の中にあるか。箱自身の軸で測る。 */
    public boolean contains(Vec3 point) {
        Vec3 from = point.subtract(this.centre);

        return Math.abs(from.dot(this.axes[0])) <= this.half.x
                && Math.abs(from.dot(this.axes[1])) <= this.half.y
                && Math.abs(from.dot(this.axes[2])) <= this.half.z;
    }

    /**
     * 点が箱のどのあたりにあるか。各半長に対する比率で、中心が0、面上が1、外は1超。
     *
     * <p>{@link #contains} と同じ3つの射影を、比較せずそのまま返す。用途は「弾が機体のどこに当たったか」
     * を伝えること。世界座標の点は機体が動いた瞬間に古くなるが、入った箱に対する比率なら、その後車体が
     * どれだけ走ろうと砲塔がどれだけ回ろうと正しいまま。
     *
     * <p>軸は箱自身の物で、構築順のまま。つまり x は箱自身の幅方向に沿い、機体座標の中ではその左側へ
     * 向く。
     */
    public Vec3 within(Vec3 point) {
        Vec3 from = point.subtract(this.centre);

        return new Vec3(
                from.dot(this.axes[0]) / Math.max(this.half.x, NOTHING),
                from.dot(this.axes[1]) / Math.max(this.half.y, NOTHING),
                from.dot(this.axes[2]) / Math.max(this.half.z, NOTHING));
    }

    /**
     * 線分が箱へ最初に入る点。外れていれば空。
     *
     * <p>箱自身の軸でのスラブ法。対向する面の各対が線分を「その間にある区間」へ削り、3対を通すと箱の中の
     * 区間が残る。途中で区間が空になれば外れ。内側から始まる線分は開始点をそのまま返す。これを訊く側が
     * 期待する挙動でもある。
     */
    public Optional<Vec3> clip(Vec3 from, Vec3 to) {
        Vec3 along = to.subtract(from);
        Vec3 start = from.subtract(this.centre);
        double first = 0.0;
        double last = 1.0;

        for (int axis = 0; axis < 3; axis++) {
            double offset = start.dot(this.axes[axis]);
            double speed = along.dot(this.axes[axis]);
            double half = this.half(axis);

            if (Math.abs(speed) < NOTHING) {
                // 面へ向かうのではなく面に沿って走っている場合。全長にわたって対の間にあるか、一度も
                // 無いかのどちらか。
                if (offset < -half || offset > half) {
                    return Optional.empty();
                }

                continue;
            }

            double near = (-half - offset) / speed;
            double far = (half - offset) / speed;

            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }

            first = Math.max(first, near);
            last = Math.min(last, far);

            if (first > last) {
                return Optional.empty();
            }
        }

        return Optional.of(from.add(along.scale(first)));
    }

    /**
     * 箱の表面がその点でどちらを向いているか。点が乗っている面の外向き単位法線。
     *
     * <p>3対の面のうちどれに最も近いかで求める。測るのは箱自身の軸で、傾いた箱が「面」を持つのはその意味
     * においてだけ。面上の点はその軸方向に半長ぴったり、他の2軸方向にはそれ未満なので、3つの余裕のうち
     * 最小の物が面を特定し、オフセットの符号が対のどちら側かを決める。
     *
     * <p>{@link #clip} が返した点に対して訊けば、これは弾が到達した装甲板であり、その法線と弾道の成す角
     * が「食い込むか弾かれるか」の全てを決める。{@code Ricochet} 参照。
     *
     * <p>答えの正確さは点の正確さ止まり。箱のかなり内側の点や角の外の点でも最も近い面を返すが、それは
     * 厳密な答えの無い問いへの妥当な答え。点を切り出したのと同じ箱（拡大した物も含む）に対して訊くこと。
     * さもないと稜線付近で余裕が別の面を指す。
     */
    public Vec3 normalAt(Vec3 point) {
        Vec3 from = point.subtract(this.centre);
        Vec3 face = this.axes[0];
        double nearest = Double.MAX_VALUE;
        double side = 1.0;

        for (int axis = 0; axis < 3; axis++) {
            double offset = from.dot(this.axes[axis]);
            double margin = Math.abs(this.half(axis) - Math.abs(offset));

            if (margin < nearest) {
                nearest = margin;
                face = this.axes[axis];
                side = offset < 0.0 ? -1.0 : 1.0;
            }
        }

        return face.scale(side);
    }

    /**
     * 直立した箱が、この箱に触れるまで移動のどれだけを進めるか。移動量に対する比率で、1 なら一度も触れ
     * ず、0 なら開始時点で止められる。
     *
     * <p>分離軸定理を時間方向へ掃引した物。2つの凸形状は、影が重ならない方向が1つでも見つかる時に限り
     * 離れている。箱対箱なら15方向を試せば十分——世界の3軸、この箱の3軸、そしてその各対が作る9本（一方の
     * 稜線と他方の稜線が交わる方向）。
     *
     * <p>単なる判定ではなく掃引になるのは、影が動いているから。各方向について2つの影は移動のある区間で
     * 重なり、その前後では離れている。形状が接触するのは<em>全ての</em>方向が同時に重なっている区間で、
     * その区間の始まりが接触の瞬間。影が一度も重ならない方向が1つでもあればそこで打ち切り——まったく
     * 接触しない。
     *
     * @param box 動いている直立した箱
     * @param motion どこまで行こうとしているか。オフセットで
     */
    public double sweep(AABB box, Vec3 motion) {
        Vec3 half = new Vec3(box.getXsize() * 0.5, box.getYsize() * 0.5, box.getZsize() * 0.5);

        return this.sweep(box.getCenter(), half, WORLD, this.directions, motion);
    }

    /**
     * 自分の角度で寝ている別の箱に対する同じ物。この箱に触れるまで移動のどれだけを進めるか。
     *
     * <p>甲板に着艦する機体がまさにこれ。2つの形状のどちらも直立しておらず、どちらもゲームには記述でき
     * ない。直立の場合より難しい所は無く、試すべき方向のリストが長くなるだけ——片方が世界の軸を借りる代わ
     * りに、両方の箱が自分の3軸を出すようになるので。
     *
     * @param other 動いている方の箱。こちらは静止している
     */
    public double sweep(Hitbox other, Vec3 motion) {
        return this.sweep(other.centre, other.half, other.axes,
                directions(this.axes, other.axes), motion);
    }

    /**
     * 何かがこの箱に触れるまで移動のどれだけを進めるか。
     *
     * <p>分離軸定理を時間方向へ掃引した物。2つの凸形状は、影が重ならない方向が1つでも見つかる時に限り
     * 離れている。箱対箱なら、それぞれが揃っている3軸と、その各対が作る9本（一方の稜線と他方の稜線が
     * 交わる方向）を試せば十分。
     *
     * <p>単なる判定ではなく掃引になるのは、影が動いているから。各方向について2つの影は移動のある区間で
     * 重なり、その前後では離れている。形状が接触するのは<em>全ての</em>方向が同時に重なっている区間で、
     * その区間の始まりが接触の瞬間。影が一度も重ならない方向が1つでもあればそこで打ち切り。
     */
    private double sweep(Vec3 theirCentre, Vec3 theirHalf, Vec3[] theirAxes, Vec3[] directions,
            Vec3 motion) {
        Vec3 between = this.centre.subtract(theirCentre);
        double first = Double.NEGATIVE_INFINITY;
        double last = Double.POSITIVE_INFINITY;

        for (Vec3 direction : directions) {
            // この方向について、2つの影が各自の中心の両側へどれだけ伸びるか。
            double spread = spread(direction, theirAxes, theirHalf)
                    + spread(direction, this.axes, this.half);
            double apart = direction.dot(between);
            double closing = direction.dot(motion);

            if (Math.abs(closing) < NOTHING) {
                // この方向には近づきも離れもしない。移動全体を通して分離しているか、何も言うことが
                // 無いかのどちらか。
                if (Math.abs(apart) > spread) {
                    return 1.0;
                }

                continue;
            }

            double near = (apart - spread) / closing;
            double far = (apart + spread) / closing;

            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }

            first = Math.max(first, near);
            last = Math.min(last, far);

            if (first > last) {
                return 1.0;
            }
        }

        if (first > 1.0 || last < 0.0) {
            return 1.0;
        }

        // 開始時点で既に中にいる場合。相手が箱へ入ったのではなく箱が相手へ動いてきたということで、
        // 斜面に落ち着く車体が甲板上の人に対して毎秒20回やっていること。より深く入らない方向へは
        // どこへでも動いてよい。
        if (first < -SETTLED) {
            return this.deeper(between, theirHalf, theirAxes, directions, motion) ? 0.0 : 1.0;
        }

        return Math.max(first, 0.0);
    }

    /**
     * 箱の内側から出発する移動が、さらに深く入る方向かどうか。
     *
     * <p>既に中にいる物に対して訊く価値のある唯一の問いで、素通しにはできない。素通しにすると、箱は一度
     * 追い付いた相手に対して二度と床でなくなる。車体が斜面へわずかに上がると甲板上の全員がわずかに中へ
     * 入り、その瞬間から戦車をすり抜けて落ちる。逆に一律拒否はもっと悪い。機体が向いた先の物が永久にそこ
     * へ固定される——どの方向も「内側から始まる方向」なので。
     *
     * <p>そこで、2つを分離しうる全方向のうち、最も浅く入っている方向を「出口」とし、それに逆らわない移動
     * を許す。甲板から真上へ、甲板に沿って、その中間はすべて可。甲板へ向かって下は不可。これは何かに
     * 埋まった物を解放するために押し出すべき方向であり、答えが tick 間で安定する理由は、面の浅い所ではその
     * 方向が面自身の法線になるから。
     */
    private boolean deeper(Vec3 between, Vec3 theirHalf, Vec3[] theirAxes, Vec3[] directions,
            Vec3 motion) {
        Vec3 out = null;
        double shallowest = Double.MAX_VALUE;

        for (Vec3 direction : directions) {
            double spread = spread(direction, theirAxes, theirHalf)
                    + spread(direction, this.axes, this.half);
            double apart = direction.dot(between);
            double depth = spread - Math.abs(apart);

            if (depth < shallowest) {
                shallowest = depth;
                // この箱の中心から遠ざかる向き。`between` は動く側からこの箱へ向かうので、それと同じ
                // 向きの方向はこの箱の内側を指し、出口は反対側になる。
                out = apart > 0.0 ? direction.reverse() : direction;
            }
        }

        return out != null && motion.dot(out) < 0.0;
    }

    /** 指定の軸と半長を持つ箱が、ある方向へ両側にどれだけ伸びるか。 */
    private static double spread(Vec3 direction, Vec3[] axes, Vec3 half) {
        return Math.abs(direction.dot(axes[0])) * half.x
                + Math.abs(direction.dot(axes[1])) * half.y
                + Math.abs(direction.dot(axes[2])) * half.z;
    }

    /**
     * 直立した箱が、両者の現在位置でこの箱に触れているか。{@link #sweep} と同じ方向群を、時間方向では
     * なく一度だけ問う。隙間のある方向が1本あれば離れていると言える。
     */
    public boolean overlaps(AABB box) {
        Vec3 half = new Vec3(box.getXsize() * 0.5, box.getYsize() * 0.5, box.getZsize() * 0.5);
        Vec3 between = this.centre.subtract(box.getCenter());

        for (Vec3 direction : this.directions) {
            double spread = spread(direction, WORLD, half) + spread(direction, this.axes, this.half);

            if (Math.abs(direction.dot(between)) > spread) {
                return false;
            }
        }

        return true;
    }

    /**
     * 2つの箱の間で試す価値のある方向。それぞれが揃っている3軸と、その各対が作る9本。
     *
     * <p>9本のうち、元の対が同じ向きを指している物は落とす。互いに重なった2軸は方向を作らないから。失う
     * 物も無い——その2本は既にリストに入っている。直立した箱に対してまっすぐ立っている箱は9本全部を落とす。
     */
    private static Vec3[] directions(Vec3[] theirs, Vec3[] mine) {
        List<Vec3> found = new ArrayList<>(15);

        for (Vec3 axis : theirs) {
            found.add(axis);
        }

        for (Vec3 axis : mine) {
            found.add(axis);
        }

        for (Vec3 one : theirs) {
            for (Vec3 two : mine) {
                Vec3 crossed = one.cross(two);

                if (crossed.lengthSqr() > NOTHING) {
                    found.add(crossed.normalize());
                }
            }
        }

        return found.toArray(new Vec3[0]);
    }

    private double half(int axis) {
        return axis == 0 ? this.half.x : axis == 1 ? this.half.y : this.half.z;
    }

    /** 指定の半長を持つ箱が、世界の1軸方向へどれだけ伸びるか。 */
    private double span(Vec3 half, int world) {
        return Math.abs(component(this.axes[0], world)) * half.x
                + Math.abs(component(this.axes[1], world)) * half.y
                + Math.abs(component(this.axes[2], world)) * half.z;
    }

    private static double component(Vec3 of, int axis) {
        return axis == 0 ? of.x : axis == 1 ? of.y : of.z;
    }

    /** 回転行列の1列。回された軸の1本を世界から見た物。 */
    private static Vec3 column(Matrix3f matrix, int axis) {
        return new Vec3(matrix.get(axis, 0), matrix.get(axis, 1), matrix.get(axis, 2));
    }
}
