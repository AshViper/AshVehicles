package com.ashvehicles.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 飛翔中の弾を、どの距離から描かれる場合でもどう描くか。
 *
 * <p>2つではなく1つのクラスにしてあるのは、弾を描く物が2つある——近距離では弾自身のレンダラー、引き継ぎを過ぎたら
 * ゴーストパス——うえ、その間を跨いでも見た目が変わってはならないからだ。曳光の形状に関する全てがここにあり、両者が
 * ここへ問い合わせる。
 *
 * <h2>曳光に詳細度が必要な理由</h2>
 *
 * <p>省ける物は何も無い。頂点4つであり、線をこれより安く描く方法は無い。距離とともに変わるのは「見えるかどうか」
 * だ。弾は差し渡し数センチで、1/100ブロックは数百ブロック離れれば1ピクセル未満になる——だから筋は消えるほど細くなり、
 * 残った分もクアッドがサンプリング格子に掛かったり外れたりして明滅する。それはゴーストパスが引き継ぐまさにその距離
 * であり、砲手が弾道を最も読みたい距離でもある。
 *
 * <p>そこで筋の幅は、ワールド上の幅ではなく<em>画面上</em>の幅で保つ。近距離ではワールド側の値の方が大きいので何も
 * 変わらない。1.5ピクセルを下回る距離を超えると、描画幅はそれ以上縮まなくなる。それは曳光が実際にやっていることでも
 * ある。その距離で目が追っているのは弾ではなく弾が出している光であり、光は描かれる媒体より細くはならない。
 *
 * <p>{@link #dot} は筋を完全に諦め、そこで既になっている光点にした物だ。パスが {@code BILLBOARD} に置いたゴースト
 * 用の最遠階層——弾が実際にクライアントへ送られる16チャンクの範囲では到達しない——だが、画面上で線として読めないほど
 * 短い筋も、距離を問わずこれで描く。そしてそれは距離の問題ですらない。筋は弾自身の飛翔に沿うので、視線方向へ遠ざかる
 * 弾はどれだけ近くても端から見た線であり、端から見た線は点だ。それは砲手自身の座席から、撃っている砲の砲腔を覗く
 * 視点そのものである。
 */
public final class Tracer {
    /** 1tick分の移動距離のうち、筋が覆う割合。 */
    public static final float LENGTH = 0.9F;
    /**
     * 筋を描く最大長（ブロック）。機関砲弾は1tickで40ブロック進むが、40ブロックの筋は曳光ではなくビームに見える。
     */
    public static final double MAX_LENGTH = 8.0;
    /** ワールド上での筋の半幅（ブロック）。近距離で描かれる幅。 */
    public static final float HALF_WIDTH = 0.05F;

    /** どれだけ遠くても筋を描く最小幅（画面ピクセル）。 */
    private static final double MIN_PIXELS = 1.5;
    /** 最遠階層の光点を描く幅（画面ピクセル）。 */
    private static final double DOT_PIXELS = 2.0;

    /**
     * 筋として描く価値のある最短長（画面ピクセル）。
     *
     * <p>距離の話ではなく、「どこから何が描いているか」の話だ。筋は弾自身の飛翔に沿うので、視線方向へ遠ざかる弾は
     * 端から見た線であり、端から見た線はどんな長さでも点になる。それはまさに砲手の視点——自分の砲の砲腔越しであり、
     * 曳光が最も重要になる場所だ——で、幅で救うこともできない。幅を持たせる対象がそこに無いからだ。これを下回ると、
     * 代わりにそこで既になっている光点として描く。おかげで弾は銃口の先で消えるのではなく目標まで追える。
     * {@link #dot} 参照。
     */
    private static final double MIN_STREAK_PIXELS = 3.0;

    /** これを下回ると2つの方向は同一方向であり、その間に平面は存在しない。 */
    private static final double PARALLEL = 1.0E-6;

    private Tracer() {
    }

    /** 弾に対する筋の尾の位置。経路に沿って後方だが、程度をわきまえた範囲で。 */
    public static Vec3 tail(Vec3 travel) {
        Vec3 tail = travel.scale(-LENGTH);

        return tail.lengthSqr() > MAX_LENGTH * MAX_LENGTH ? tail.normalize().scale(MAX_LENGTH) : tail;
    }

    /**
     * 弾から経路を遡るクアッド。先端が明るく尾で消える。pose stack の現在の原点に描くが、両方の呼び出し元が既に
     * そこを弾の位置に置いている。
     *
     * <p><b>見ている者の方へ向ける。</b>クアッドは、その面が視点に対して正対するまで筋自身の軸周りに回す必要がある。
     * ここでカメラが要る理由はそれが全てだ。代わりにワールドの鉛直を基準に組むと——以前はそうだった——クアッドは1つの
     * 固定平面に寝ることになり、同時に2つの不具合が起きる。その平面に対して浅い角度から見ると短縮されて消えるし、幅の
     * 下限では救えない。下限はワールド上の幅であり、潰れるのは投影後に残る分だからだ。さらに悪いことに、クアッドには
     * 表と裏があり描画タイプは裏を切る。ワールドの鉛直で寝かせると、水平射撃の筋は<em>上</em>を向くので、飛行経路の
     * 上のカメラからは見えても経路上のカメラからはまったく見えない。合わせると、三人称カメラからは完璧に読めるのに
     * コックピットからは見えない曳光になる——砲手が撃つ唯一の視点なのに、だ。視点へ向ければ、幅はどの角度でも書いて
     * ある通りの意味を持ち、面は常に表になる。
     *
     * @param fromCamera 視点から見た弾。弾の位置からカメラ位置を引いた値
     * @param travel このtickのステップ。筋が沿う線
     * @param distanceSq 弾とカメラの距離の2乗。幅を決める値
     */
    public static void streak(PoseStack poseStack, VertexConsumer buffer, Camera camera, Vec3 fromCamera,
            Vec3 travel, double distanceSq, int colour) {
        Vec3 tail = tail(travel);
        Vec3 view = fromCamera.lengthSqr() < PARALLEL ? travel.normalize() : fromCamera.normalize();
        // 投影を通した後に残る筋の分。視線を横切る成分だ。視点から真っ直ぐ遠ざかる弾にはそれが無い。
        // MIN_STREAK_PIXELS 参照。
        Vec3 sideways = tail.subtract(view.scale(tail.dot(view)));

        if (sideways.length() < blocksPerPixel(Math.sqrt(distanceSq)) * MIN_STREAK_PIXELS) {
            dot(poseStack, buffer, camera, distanceSq, colour);

            return;
        }

        // 視点が先、飛翔方向が後。この順序がクアッドの表面をカメラ側へ向け、裏面カリングから外す。
        Vec3 across = across(view, travel).scale(halfWidth(distanceSq));
        Matrix4f pose = poseStack.last().pose();

        vertex(buffer, pose, across, colour);
        vertex(buffer, pose, across.scale(-1.0), colour);
        vertex(buffer, pose, tail.add(across.scale(-1.0)), colour & 0x00FFFFFF);
        vertex(buffer, pose, tail.add(across), colour & 0x00FFFFFF);
    }

    /**
     * 筋にも視線にも直交する単位ベクトル。クアッドはこの方向へ幅を持つ。
     *
     * <p>フォールバックは弾をちょうど端から見た場合のためだが、ここでは起こりえない——視線にそこまで近い筋は既に点
     * として描かれている——ので、2つのどんな配置でも長さ0のベクトルを正規化に渡さないための保険として残してある。
     */
    private static Vec3 across(Vec3 view, Vec3 travel) {
        Vec3 across = view.cross(travel.normalize());

        if (across.lengthSqr() < PARALLEL) {
            across = travel.normalize().cross(new Vec3(0.0, 1.0, 0.0));
        }

        return across.lengthSqr() < PARALLEL ? new Vec3(1.0, 0.0, 0.0) : across.normalize();
    }

    /**
     * 最遠階層。カメラを向いた光の正方形を弾の先端に置く。長さも方向も残さない。
     */
    public static void dot(PoseStack poseStack, VertexConsumer buffer, Camera camera, double distanceSq,
            int colour) {
        float half = (float) (blocksPerPixel(Math.sqrt(distanceSq)) * DOT_PIXELS * 0.5);

        if (half <= 0.0F) {
            return;
        }

        poseStack.pushPose();
        // パーティクルとまったく同じようにカメラを向く。
        poseStack.mulPose(camera.rotation());
        Matrix4f pose = poseStack.last().pose();

        buffer.addVertex(pose, -half, -half, 0.0F).setColor(colour);
        buffer.addVertex(pose, half, -half, 0.0F).setColor(colour);
        buffer.addVertex(pose, half, half, 0.0F).setColor(colour);
        buffer.addVertex(pose, -half, half, 0.0F).setColor(colour);
        poseStack.popPose();
    }

    /**
     * 筋を描く半幅（ブロック）。ワールド上の値と、この距離で1.5ピクセルに相当する値の、広い方。
     */
    public static float halfWidth(double distanceSq) {
        double floor = blocksPerPixel(Math.sqrt(distanceSq)) * MIN_PIXELS * 0.5;

        return (float) Math.max(HALF_WIDTH, floor);
    }

    /**
     * その距離で1画面ピクセルが覆うブロック数。
     *
     * <p>ゲームが実際に使っている投影から求める。視錐台の上端は視線から {@code distance × tan(fov / 2)} 上にあり、
     * 画面中央からウィンドウのピクセル数の半分だけ離れている。UI スケールではなくフレームバッファを基準に測る。
     * クアッドがラスタライズされる先は実ピクセルだからだ。
     *
     * <p>ゴーストパスが適用する遠方面への引き寄せは関与しない。あれはゴーストを手前へ滑らせ、まったく同じ分だけ縮める
     * ので、ゴーストが覆うピクセル数はどちらでも同じ——それがあの処理の要点だ——であり、真の距離から求めた幅は要求
     * された通りの幅で描かれる。
     */
    private static double blocksPerPixel(double distance) {
        Minecraft minecraft = Minecraft.getInstance();
        int height = minecraft.getWindow().getHeight();

        if (height <= 0) {
            return 0.0;
        }

        double fov = minecraft.options.fov().get();

        return 2.0 * distance * Math.tan(Math.toRadians(fov) * 0.5) / height;
    }

    private static void vertex(VertexConsumer buffer, Matrix4f pose, Vec3 at, int colour) {
        buffer.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(colour);
    }
}
