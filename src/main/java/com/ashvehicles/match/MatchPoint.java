package com.ashvehicles.match;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * 拠点1つ。旗竿の座標と、その周りの円と、今どの陣営が持っているか。
 *
 * <p><b>拠点を置いた試合が拠点制圧になる。</b> 種目を選ぶ設定は無い。1つも置かなければ撃ち合うだけの
 * デスマッチで、置けばチケットが拠点の数で減り始める（{@link Deathmatch#bleed}）。同じ試合の途中で
 * 拠点を足すこともできる——その瞬間から流血が始まるというだけだ。
 *
 * <p><b>制圧は時間であって人数ではない。</b> 中に何人いても、掛かる時間は同じ。人数で早くすると、
 * 既に優勢な側がさらに速く回すことになり、押し返す側が押し返せなくなる。人数が効くのは「2つの陣営が
 * 同時に中にいるか」だけで、そのときは進みが止まる（拮抗）。
 */
public final class MatchPoint {
    /** 制圧に掛かる時間（tick）。20秒。 */
    public static final int CAPTURE_TICKS = 400;

    /** 誰もいない拠点の進みが戻る速さ。掛けた時間の半分で消える。 */
    public static final int DECAY = 2;

    /** 既定の半径（ブロック）。旗竿の周り、だいたい飛行機1機ぶんの広さ。 */
    public static final double DEFAULT_RADIUS = 16.0;

    private final BlockPos pos;
    private String name;
    private double radius;

    /** 今の持ち主。中立なら null。 */
    @Nullable
    private String owner;

    /**
     * 試合開始時に戻る持ち主。運営が {@code /tdm point owner} で据えた初期配置で、据えていなければ
     * null（＝開始のたびに中立へ戻る）。
     *
     * <p><b>試合中の制圧はこれを書き換えない。</b> 押し込んで取った旗は、次の試合の開始でちゃんと
     * 返る。これが無いと、1試合目に全部取った側が2試合目を全拠点保持から始めることになる。
     */
    @Nullable
    private String home;

    /** 制圧を進めている陣営。誰も進めていなければ null。 */
    @Nullable
    private String taking;

    /** {@link #taking} が積み上げた tick。{@link #CAPTURE_TICKS} に届くと持ち主が替わる。 */
    private int progress;

    /** 今この瞬間、2つ以上の陣営が中にいるか。計器が「拮抗」と出すためだけに持つ。 */
    private boolean contested;

    public MatchPoint(BlockPos pos, String name, double radius) {
        this.pos = pos.immutable();
        this.name = name;
        this.radius = radius;
    }

    public BlockPos pos() {
        return this.pos;
    }

    public String name() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double radius() {
        return this.radius;
    }

    public void setRadius(double radius) {
        this.radius = Math.max(radius, 1.0);
    }

    @Nullable
    public String owner() {
        return this.owner;
    }

    /** 今の持ち主を据える。制圧が通る道で、初期配置は動かさない。 */
    public void setOwner(@Nullable String owner) {
        this.owner = owner;
        this.taking = null;
        this.progress = 0;
    }

    @Nullable
    public String home() {
        return this.home;
    }

    /** 初期配置を据える。運営のコマンドが通る道で、今の持ち主も一緒に動く。 */
    public void setHome(@Nullable String owner) {
        this.home = owner;
        this.setOwner(owner);
    }

    /** 試合開始時の姿へ戻す。持ち主は初期配置、取りかけは無かったことに。 */
    public void reset() {
        this.setOwner(this.home);
        this.contested = false;
    }

    @Nullable
    public String taking() {
        return this.taking;
    }

    public int progress() {
        return this.progress;
    }

    public boolean contested() {
        return this.contested;
    }

    public void setContested(boolean contested) {
        this.contested = contested;
    }

    /** 制圧の進み具合（0〜1）。計器が出す唯一の数。 */
    public float fraction() {
        return Math.min((float) this.progress / CAPTURE_TICKS, 1.0F);
    }

    /**
     * その陣営が中にいる間の1歩。
     *
     * @return 持ち主が替わったなら true
     */
    public boolean advance(String team, int ticks) {
        if (team.equals(this.owner)) {
            // 自陣の拠点に立っているだけ。取り返しかけていた分だけが戻る。
            this.progress = Math.max(this.progress - ticks * DECAY, 0);

            if (this.progress == 0) {
                this.taking = null;
            }

            return false;
        }

        if (!team.equals(this.taking)) {
            // 別の陣営が取りかけていた拠点。積み上がった分をまず崩してから、自分の分を積む。
            this.progress -= ticks;

            if (this.progress > 0) {
                return false;
            }

            this.taking = team;
            this.progress = -this.progress;
        } else {
            this.progress += ticks;
        }

        if (this.progress < CAPTURE_TICKS) {
            return false;
        }

        this.owner = team;
        this.taking = null;
        this.progress = 0;

        return true;
    }

    /** 誰も中にいない拠点。取りかけは消えていく。 */
    public void idle(int ticks) {
        this.progress = Math.max(this.progress - ticks * DECAY, 0);

        if (this.progress == 0) {
            this.taking = null;
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();

        tag.putLong("Pos", this.pos.asLong());
        tag.putString("Name", this.name);
        tag.putDouble("Radius", this.radius);
        tag.putInt("Progress", this.progress);

        if (this.owner != null) {
            tag.putString("Owner", this.owner);
        }

        if (this.home != null) {
            tag.putString("Home", this.home);
        }

        if (this.taking != null) {
            tag.putString("Taking", this.taking);
        }

        return tag;
    }

    public static MatchPoint load(CompoundTag tag) {
        MatchPoint point = new MatchPoint(BlockPos.of(tag.getLong("Pos")), tag.getString("Name"),
                tag.getDouble("Radius"));

        point.progress = tag.getInt("Progress");
        point.owner = tag.contains("Owner") ? tag.getString("Owner") : null;
        point.home = tag.contains("Home") ? tag.getString("Home") : null;
        point.taking = tag.contains("Taking") ? tag.getString("Taking") : null;

        return point;
    }
}
