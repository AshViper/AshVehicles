package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.ashvehicles.data.Definitions;
import com.ashvehicles.entity.VehicleEntityBase;
import com.ashvehicles.vehicle.GroundVehicleDefinition;
import com.ashvehicles.vehicle.VehicleShape;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 直近数発が目標のどこに当たったかを、目標の絵の上に描く。
 *
 * <p><b>そもそも必要な理由。</b>800m で撃つ戦車砲手に見えるのは煙の塊だけだ。弾が砲塔前面に入ったのか、履帯に入った
 * のか、屋根の30cm 上を抜けたのかはここからは見えないし、答えが無ければ修正すべき物も無い。次弾は同じ場所へ同じ期待で
 * 撃たれる。この画面の他の全ては「弾を撃ち出す」ことについての物だが、これだけが「着弾したとき何が起きたか」を伝える。
 *
 * <p><b>目標は砲手が見た姿で描く</b>——機体自身のモデルを、弾が飛来した方位へ回す。方位は射手の現在位置ではなく弾自身
 * の飛行線から取る。だから車体側面への命中は側面図に、車体下部のマーク付きで描かれ、正面から撃った物はその正面に描か
 * れる。誰がどちらを向いていたかを算出する必要は無い。弾が既に知っている。
 *
 * <p><b>マークは、車両近くの空中の点ではなく、食い込んだ箱に対する割合として保持する。</b>だから弾と弾の間に旋回した
 * 砲塔は自分への命中痕を一緒に回して運ぶし、防盾のマークは防盾に留まる。
 *
 * <p>塗り潰したマークは貫通、中空のマークは装甲が弾いたことを意味する——この計器が伝える中で単独で最も有用な情報だ。
 * 「別の場所を狙う」か「同じ場所へもう一度撃つ」かの分かれ目だからである。
 *
 * <p>全体が1回の交戦のスナップショットだ。別の物に当てれば消えてやり直しになるし、最後の着弾から数秒でフェードアウト
 * する。戦闘の残りずっと画面隅に居座ったりはしない。
 */
public final class HitReadout {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 70;
    private static final int INSET = 8;
    /** 枠の内側で絵を描かない余白。 */
    private static final int MARGIN = 6;
    /** 上端に目標名、下端に集計を置くために確保する領域。 */
    private static final int HEADER = 12;
    private static final int FOOTER = 11;

    /** 最後の着弾後、表示を残す時間（ミリ秒）。 */
    private static final long LINGER = 6000L;
    /** そのうちフェードに使う割合。ぱっと消えるのではなく消えていくようにする。 */
    private static final long FADE = 900L;
    /** 保持するマーク数。機関砲の長い連射があると、さもないと絵が埋まってしまう。 */
    private static final int MOST = 24;
    /**
     * 機体を画面のどれだけ奥に描くか、そしてマークを機体表面からどれだけ手前へ浮かせるか（ブロック）。
     *
     * <p>後者が、マークが乗っている板の内側へ消えるのを防ぐ。着弾位置は当たり判定の箱に対して求めるが、モデル自身の
     * 表面はその箱の上にぴったり乗っているわけではない——だから計算通りの位置に置いたマークは装甲の数cm 内側に入りうる
     * し、中身の詰まったモデルではそれは描かないのと同じだ。代わりに視聴者側へ浮かせる。誤って透けて見えうるどの物の
     * 厚みよりも、はるかに小さい量で。
     */
    private static final float DEPTH = 90.0F;
    private static final float LIFT = 0.35F;

    /** 全マークの背面に敷く。緑の甲板の上のマークもマークとして見えるように。 */
    private static final int BACKING = 0xC0000000;
    /** 貫通した弾。 */
    private static final int STRIKE = AircraftHud.WARNING;
    /** 装甲が弾いた弾。別の答えなので別の色を与える。 */
    private static final int BOUNCE = 0xFFFFD24A;

    /**
     * 弾1発の着弾。どの箱に入ったか、そしてその箱の中のどこか——箱の各半長に対する割合で。
     *
     * <p>距離ではなく割合で保持するので、マークは箱自体を配置するのと同じ計算で配置され——{@link Silhouette} 参照——
     * 砲塔と共に回る。{@code box} が -1 の場合は箱を持たない機体で、{@code within} は中心からのブロック数で測る。
     */
    private record Mark(int box, Vec3 within, boolean bounced) {
    }

    private static final List<Mark> MARKS = new ArrayList<>();

    /** どの機体について報告しているか。2台目に当てたら新しい絵で始めるため。 */
    private static int target = -1;
    @Nullable
    private static ResourceLocation machine;
    private static Vec3 line = new Vec3(0.0, 0.0, 1.0);
    private static float traverse;
    private static float gunPitch;
    private static float damage;
    private static long arrived;

    /** 描画用に保持している機体と、その種類。{@link #copyOf} 参照。 */
    @Nullable
    private static VehicleEntityBase drawn;
    @Nullable
    private static ResourceLocation drawnId;

    private HitReadout() {
    }

    /**
     * 弾が着弾した。サーバーが射手にだけ送るパケットから呼ばれる。
     *
     * @param struck 目標のエンティティID。比較にしか使わない。別の物に当てれば表示中の内容を消す
     * @param id どの機体か。形状と名前のため
     * @param box その形状のどの箱に当たったか。箱を持たない機体では -1
     * @param within その箱の中のどこか。各半長に対する割合
     * @param approach 弾の進行方向。機体座標系
     * @param damage 与えたダメージ。装甲が弾いたなら0
     */
    public static void report(int struck, ResourceLocation id, int box, Vec3 within, Vec3 approach,
            float traverse, float gunPitch, float damage, boolean bounced) {
        long now = Util.getMillis();

        if (struck != target || !id.equals(machine) || now - arrived > LINGER) {
            MARKS.clear();
            HitReadout.damage = 0.0F;
        }

        target = struck;
        machine = id;
        line = approach;
        HitReadout.traverse = traverse;
        HitReadout.gunPitch = gunPitch;
        HitReadout.damage += damage;
        arrived = now;

        if (MARKS.size() >= MOST) {
            MARKS.remove(0);
        }

        MARKS.add(new Mark(box, within, bounced));
    }

    /** 右上隅に描く。最近何にも当てていなければ何も描かない。 */
    static void draw(GuiGraphics graphics, Font font) {
        ResourceLocation id = machine;

        if (id == null || MARKS.isEmpty()) {
            return;
        }

        long age = Util.getMillis() - arrived;

        if (age > LINGER) {
            return;
        }

        float alpha = age > LINGER - FADE ? (float) (LINGER - age) / FADE : 1.0F;
        // 計器なので1段小さく。{@link HudScale} 参照。ここで包むのは、この表示が機体からも車両からも
        // 砲手席からも呼ばれるからで、呼ぶ側に覚えさせれば、いつか1箇所だけ忘れられる。
        HudScale.push(graphics);

        int left = HudScale.width(graphics) - INSET - WIDTH;
        int top = INSET;

        panel(graphics, left, top, alpha);
        name(graphics, font, id, left, top, alpha);
        tally(graphics, font, left, top, alpha);
        picture(graphics, id, left, top, alpha);

        HudScale.pop(graphics);
    }

    private static void panel(GuiGraphics graphics, int left, int top, float alpha) {
        int right = left + WIDTH;
        int bottom = top + HEIGHT;
        int edge = fade(AircraftHud.DIM, alpha);

        graphics.fill(left, top, right, bottom, fade(AircraftHud.SHADOW, alpha));
        graphics.fill(left, top, right, top + 1, edge);
        graphics.fill(left, bottom - 1, right, bottom, edge);
        graphics.fill(left, top, left + 1, bottom, edge);
        graphics.fill(right - 1, top, right, bottom, edge);
    }

    /** 何に当てたか。ファイルのIDではなくゲームが与える名前で。 */
    private static void name(GuiGraphics graphics, Font font, ResourceLocation id, int left, int top,
            float alpha) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        String name = type == null ? id.getPath() : type.getDescription().getString();
        String text = font.plainSubstrByWidth(name.toUpperCase(Locale.ROOT), WIDTH - 8);

        graphics.drawString(font, text, left + 4, top + 3, fade(AircraftHud.GREEN, alpha), true);
    }

    /**
     * 連射の結果。削ったダメージと、要した弾数。
     *
     * <p>弾数はダメージと同じだけの価値がある。弾数だけが増えてダメージが増えない集計は、撃っている相手を貫通できない
     * 砲を意味する。射撃をやめて別の場所へ移る潮時だ。
     */
    private static void tally(GuiGraphics graphics, Font font, int left, int top, float alpha) {
        int bounced = 0;

        for (Mark mark : MARKS) {
            if (mark.bounced()) {
                bounced++;
            }
        }

        String hurt = damage > 0.0F ? "DMG " + Math.round(damage)
                : bounced > 0 ? "RICOCHET" : "NO DAMAGE";
        String count = MARKS.size() + (bounced > 0 ? " HIT " + bounced + "R" : " HIT");
        int y = top + HEIGHT - FOOTER + 1;

        graphics.drawString(font, hurt, left + 4, y,
                fade(damage > 0.0F ? AircraftHud.GREEN : BOUNCE, alpha), true);
        graphics.drawString(font, count, left + WIDTH - 4 - font.width(count), y,
                fade(AircraftHud.DIM, alpha), true);
    }

    /**
     * 機体そのものと、その上のマーク。
     *
     * <p>輪郭ではなくモデルとして描く。ワールドで持つのと同じジオメトリを同じレンダラーに通すので、防盾のマークは砲手が
     * 見て分かる防盾の上に乗る。ただし描いているのは目標そのものではない——{@link #copyOf} 参照。
     *
     * <p><b>マークは絵の「上」ではなく絵の「中」に置かれる。</b>各マークは機体上のあるべき位置で求め、モデルを描いた
     * まさにその行列に通し、返ってきた深度で置く。だから車体の向こう側への命中は車体に隠れ、手前側の命中は隠れない。
     * ここで何かが「どちら側か」を知る必要は無い。
     *
     * <p>スケールは今も当たり判定の箱から取る。先に描かずに読める機体寸法の記述はそれだけだからだ。
     */
    private static void picture(GuiGraphics graphics, ResourceLocation id, int left, int top, float alpha) {
        Minecraft minecraft = Minecraft.getInstance();
        VehicleEntityBase machine = copyOf(id, minecraft.level);

        if (machine == null) {
            return;
        }

        VehicleShape shape = Definitions.shape(id);
        GroundVehicleDefinition stats = Definitions.VEHICLES.has(id) ? Definitions.VEHICLES.get(id) : null;
        Silhouette.View view = Silhouette.View.along(line);
        double[] extent = extent(machine, shape, stats, view);
        int width = WIDTH - 2 * MARGIN;
        int height = HEIGHT - HEADER - FOOTER;
        float scale = (float) Math.min(width / Math.max(extent[1] - extent[0], 0.5),
                height / Math.max(extent[3] - extent[2], 0.5));
        float middleAcross = (float) ((extent[0] + extent[1]) * 0.5);
        float middleAloft = (float) ((extent[2] + extent[3]) * 0.5);

        machine.poseForDrawing(new Quaternionf(), traverse, gunPitch);

        // ここから先は全てパネルで切り取る。砲身はパネルの外へはみ出すし、車体後方へ流れていく長い連射は、下の数値
        // 表示の途中ではなく枠で止まるべきだ。
        HudScale.scissor(graphics, left + 1, top + HEADER, left + WIDTH - 1, top + HEADER + height);

        PoseStack pose = graphics.pose();

        pose.pushPose();
        pose.translate(left + WIDTH / 2.0, top + HEADER + height / 2.0, DEPTH);
        pose.scale(scale, scale, -scale);
        // スケールの内側なのでピクセルではなくブロック単位。描かれる物の中心がパネル中央に来るよう機体をずらす。
        // 原点ではだめだ。戦車では履帯の間の下の方にあるからだ。
        pose.translate(-middleAcross, middleAloft, 0.0F);
        turn(pose, view);
        model(graphics, machine, pose);

        // モデルを描いた行列である間に保持しておく。それがマークを金属の近くではなく金属の上に着地させる。
        Matrix4f drawnWith = new Matrix4f(pose.last().pose());

        pose.popPose();

        // その後、画面自身の行列の分を差し引く。HUD のレイヤーには綺麗な行列が渡されない——これより前に描かれた各
        // レイヤーがさらに手前へ押し出している——ので、上の行列からそのまま読んだ深度にはそのオフセットが二重に入り、
        // 車体の向こう側に埋まるはずのマークが手前側の前に浮いてしまう。
        Matrix4f onScreen = new Matrix4f(pose.last().pose()).invert().mul(drawnWith);

        for (Mark spot : MARKS) {
            Vec3 on = where(spot, shape, stats);
            // x を反転する。機体自身の軸は右を正とし、ワールドは左を正とするからだ。Silhouette 参照。
            Vector3f at = onScreen.transformPosition(
                    new Vector3f((float) -on.x, (float) on.y, (float) on.z));

            mark(graphics, Math.round(at.x), Math.round(at.y), Math.round(at.z + scale * LIFT),
                    spot.bounced(), alpha);
        }

        graphics.disableScissor();
    }

    /**
     * 弾が飛来した方向から機体が見えるよう絵を回す。
     *
     * <p>3回の回転で、考えることは何も無い。弾の飛来線が画面奥へ向くまで機体を自身の鉛直軸周りに回し、その線が水平から
     * どれだけ上下していたかだけ倒し、最後に上下を正す——最後の1つがそのためにある。画面は y を下向きに数え、ワールドは
     * 上向きに数えるからだ。
     */
    private static void turn(PoseStack pose, Silhouette.View view) {
        Vec3 look = view.look();
        // ワールド軸へ。そこでは機体の右舷が −x 方向に沿う。
        float bearing = (float) Math.toDegrees(Mth.atan2(-look.x, look.z));
        float climb = (float) Math.toDegrees(Math.asin(Mth.clamp(look.y, -1.0, 1.0)));

        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        pose.mulPose(Axis.XP.rotationDegrees(climb));
        pose.mulPose(Axis.YP.rotationDegrees(-bearing));
    }

    /**
     * 機体を、インベントリモデルとして照らし最大輝度で描く。
     *
     * <p>意図的に明るくしている。当てた相手は大抵遠方にあり半分は影の中だし、目標の形ではなく目標に当たっている光を報告
     * する計器は役に立たない。
     */
    private static void model(GuiGraphics graphics, VehicleEntityBase machine, PoseStack pose) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();

        Lighting.setupForEntityInInventory();
        dispatcher.setRenderShadow(false);
        dispatcher.render(machine, 0.0, 0.0, 0.0, 0.0F, 1.0F, pose, graphics.bufferSource(),
                LightTexture.FULL_BRIGHT);
        // バッチに残さず今描き出す。マークがその上に来るようにするためだ。
        graphics.flush();
        dispatcher.setRenderShadow(true);
        Lighting.setupFor3DItems();
    }

    /**
     * マーク1つを、機体上でそれが位置する深度に描く。
     *
     * <p>貫通なら塗り潰し、装甲が弾いたなら中空。色だけでなく形も変える。どちらが起きたかがこの計器の要点であり、赤と
     * 琥珀の区別に頼るべきではないからだ。
     */
    private static void mark(GuiGraphics graphics, int x, int y, int z, boolean bounced, float alpha) {
        int colour = fade(bounced ? BOUNCE : STRIKE, alpha);

        graphics.fill(RenderType.gui(), x - 3, y - 3, x + 3, y + 3, z, fade(BACKING, alpha));

        if (!bounced) {
            graphics.fill(RenderType.gui(), x - 2, y - 2, x + 2, y + 2, z, colour);

            return;
        }

        graphics.fill(RenderType.gui(), x - 2, y - 2, x + 2, y - 1, z, colour);
        graphics.fill(RenderType.gui(), x - 2, y + 1, x + 2, y + 2, z, colour);
        graphics.fill(RenderType.gui(), x - 2, y - 1, x - 1, y + 1, z, colour);
        graphics.fill(RenderType.gui(), x + 1, y - 1, x + 2, y + 1, z, colour);
    }

    /**
     * 描かれるためだけに存在する、同種の機体。
     *
     * <p><b>目標そのものではない。</b>この計器に値する相手へ撃つ弾は遠方の物へ撃たれるし、遠方は大抵クライアントが
     * 通知される範囲の外だ——だから表示が出る頃には、レンダラーを向ける対象のエンティティがここに存在しないことが非常に
     * 多い。代わりに描くのは、エンティティタイプから作った同種の新しい個体だ。どのワールドにも追加されず、tickもされ
     * ない。サーバーが本物について語った内容でポーズを付けたマネキンであり、次の報告が別の物についてになれば捨てられる。
     *
     * <p>フレーム間で保持するのは、作るのが無料ではなく、表示が数秒間続くからだ。種類が変わったとき、あるいはレベルが
     * 変わったときに捨てる——プレイヤーが去ったレベルを掴んだままのマネキンは、そのレベル全体を掴んだままにしてしまう。
     */
    @Nullable
    private static VehicleEntityBase copyOf(ResourceLocation id, @Nullable ClientLevel level) {
        if (level == null) {
            return null;
        }

        VehicleEntityBase kept = drawn;

        if (kept != null && id.equals(drawnId) && kept.level() == level) {
            return kept;
        }

        Entity made = BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .map(type -> type.create(level))
                .orElse(null);

        drawn = made instanceof VehicleEntityBase machine ? machine : null;
        drawnId = id;

        return drawn;
    }

    /**
     * 機体が絵の中で占める範囲を、箱が覆う広がり——左・右・下・上（ブロック）——として求める。
     *
     * <p>当たり判定の箱から測る。描かずに読める機体寸法の記述はそれだけだし、スケールは何かを描く前に決めねばならない。
     * 箱を1つも列挙していない機体は、ファイルが与える素の直方体へフォールバックする。
     */
    private static double[] extent(VehicleEntityBase machine, VehicleShape shape,
            @Nullable GroundVehicleDefinition stats, Silhouette.View view) {
        double[] extent = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};

        for (VehicleShape.Box box : shape.boxes()) {
            // 砲身は収める対象から外し、代わりに枠の外へはみ出させて描く。戦車砲は戦車の1.5倍の長さがあり、真横から
            // 見れば放っておくとスケール全体を決めてしまう。マークが乗る機体はパネルの1/3へ押し込められ、残りは空の
            // 筒の長さが取ることになる。
            if (box.mount() == VehicleShape.Mount.GUN) {
                continue;
            }

            double[] flat = flatten(box, stats, view);

            extent[0] = Math.min(extent[0], flat[0]);
            extent[1] = Math.max(extent[1], flat[1]);
            extent[2] = Math.min(extent[2], flat[2]);
            extent[3] = Math.max(extent[3], flat[3]);
        }

        if (extent[0] <= extent[1]) {
            return extent;
        }

        double half = machine.hitbox().width() * 0.5;

        return new double[]{-half, half, 0.0, machine.hitbox().height()};
    }

    /** 機体座標系でのマークの位置。砲塔は着弾時の向きで。 */
    private static Vec3 where(Mark mark, VehicleShape shape, @Nullable GroundVehicleDefinition stats) {
        if (mark.box() < 0 || mark.box() >= shape.boxes().size()) {
            return mark.within();
        }

        VehicleShape.Box box = shape.boxes().get(mark.box());
        Quaternionf rotation = Silhouette.rotation(box, stats, traverse, gunPitch);
        Vec3 inside = new Vec3(
                -mark.within().x * box.size().x * 0.5,
                mark.within().y * box.size().y * 0.5,
                mark.within().z * box.size().z * 0.5);

        return Silhouette.centre(box, stats, traverse, gunPitch).add(Silhouette.turn(rotation, inside));
    }

    /**
     * 箱1つを絵へ平面化し、その隅が覆う広がり——左・右・下・上（ブロック）——として返す。
     */
    private static double[] flatten(VehicleShape.Box box, @Nullable GroundVehicleDefinition stats,
            Silhouette.View view) {
        Vec3 centre = Silhouette.centre(box, stats, traverse, gunPitch);
        Quaternionf rotation = Silhouette.rotation(box, stats, traverse, gunPitch);
        double halfX = box.size().x * 0.5;
        double halfY = box.size().y * 0.5;
        double halfZ = box.size().z * 0.5;
        double[] flat = {Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE};

        for (int corner = 0; corner < 8; corner++) {
            Vec3 at = centre.add(Silhouette.turn(rotation, new Vec3(
                    (corner & 1) == 0 ? -halfX : halfX,
                    (corner & 2) == 0 ? -halfY : halfY,
                    (corner & 4) == 0 ? -halfZ : halfZ)));
            double across = view.across(at);
            double aloft = view.aloft(at);

            flat[0] = Math.min(flat[0], across);
            flat[1] = Math.max(flat[1], across);
            flat[2] = Math.min(flat[2], aloft);
            flat[3] = Math.max(flat[3], aloft);
        }

        return flat;
    }

    /** 同じ色を、表示の残り寿命に応じて暗くした物。 */
    private static int fade(int colour, float alpha) {
        int opacity = Math.round(((colour >>> 24) & 0xFF) * Mth.clamp(alpha, 0.0F, 1.0F));

        return (opacity << 24) | (colour & 0x00FFFFFF);
    }
}
