package com.ashvehicles.client;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.match.MatchState;
import com.ashvehicles.network.MatchStatePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 試合の掲示板。サーバーから配られた写しを持ち、画面上端の中央に出す。
 *
 * <p><b>形は2本のチケットバー。</b> 中央で向かい合い、残りチケットの割合だけ外へ伸びる。読むのは長さで
 * あって数字ではない——試合中に知りたいのは「押しているか押されているか」で、それは2本の長さの差が
 * 一目で答える。数字は外端に小さく添えるだけだ。
 *
 * <p><b>左右は作られた順に固定する。</b> 残り順に並べ替えると、1枚減るたびに赤と青が入れ替わり、どちらが
 * 自分か毎回読み直すことになる（順序を決めているのは {@code MatchStatePayload.of}）。自分の陣営の数字は
 * 太字にする——色だけでは、同じ色覚でない人に読めない。
 *
 * <p><b>拠点はバーの下のダイヤ。</b> 持ち主の色で塗り、取られかけている拠点は下から相手の色が上がって
 * くる。拮抗——2つの陣営が同時に中にいる——は上下の白い印で示す。拠点を置いていない試合ではこの行ごと
 * 無い。
 *
 * <p><b>判断には使わない。</b> ここにあるのは1秒ごとに届く写しであり、出撃できるかどうかも、誰が味方か
 * も、決めるのはサーバーだ。この写しの役目は「今どうなっているか」を人に見せることだけである。
 *
 * <p>出さないのは、試合が始まっていないとき。設営中の運営の画面に空のバーを出し続ける理由が無い。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class MatchView {
    private static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID,
            "match_board");

    /** バー1本の長さ（片側、ピクセル）。 */
    private static final int HALF = 120;
    private static final int BAR_H = 6;

    /** バーの上端。画面の一番上には置かない——数字が上に少しはみ出る。 */
    private static final int TOP = 6;

    /** 空のバーの地。減ったぶんここが見える。 */
    private static final int TRACK = 0xC0121212;
    private static final int TRACK_EDGE = 0xFF000000;

    /** 拠点のダイヤ。 */
    private static final int DIAMOND = 5;
    private static final int DIAMOND_GAP = 14;
    private static final int NEUTRAL = 0xFFB0B0B0;
    private static final int CONTESTED = 0xFFFFFFFF;

    private static final int TEXT = 0xFFE8E8E8;
    private static final int POINTS = 0xFFA6D93B;
    private static final int WAIT = 0xFFD9553B;

    @Nullable
    private static MatchStatePayload board;

    /**
     * 出撃できるようになるゲーム時刻。
     *
     * <p><b>残り tick を持って毎フレーム引かない。</b> 描画はフレームごとに走るので、120fps の画面では
     * 秒読みが6倍速く進む。持つのは時刻で、引き算は描くたびにワールドの時計に対して行う。
     */
    private static long readyAt;

    private MatchView() {
    }

    public static void accept(MatchStatePayload payload) {
        board = payload;
        readyAt = now() + payload.waitTicks();
    }

    /** クライアント側のワールドの時計。ワールドに居ないなら0。 */
    private static long now() {
        return Minecraft.getInstance().level == null ? 0L : Minecraft.getInstance().level.getGameTime();
    }

    /** 自分の陣営 ID。無所属なら null。 */
    @Nullable
    public static String ownTeam() {
        return board == null || board.own().isEmpty() ? null : board.own();
    }

    /** 今の手持ち出撃ポイント。試合に参加していなければ0。 */
    public static int purse() {
        return board == null ? 0 : board.purse();
    }

    /** 今その者が出撃を待たされている tick。0 なら出られる。 */
    public static int waitTicks() {
        return (int) Math.max(readyAt - now(), 0L);
    }

    /** 試合が動いているか。 */
    public static boolean running() {
        return board != null && board.phase() == MatchState.Phase.RUNNING.ordinal();
    }

    /** 今の拠点。試合をしていないか拠点を置いていなければ空。 */
    public static List<MatchStatePayload.Point> points() {
        return board == null ? List.of() : board.points();
    }

    /**
     * その者の陣営 ID。無所属なら null。
     *
     * <p>名簿はサーバーが毎秒配っている（{@code MatchStatePayload.Team.members}）。人数ぶんの線形探索
     * だが、この問いが走るのはワールド描画1フレームにつき見えている人の数だけで、試合の人数はその
     * どちらも小さい。
     */
    @Nullable
    public static String teamIdOf(UUID player) {
        if (board == null) {
            return null;
        }

        for (MatchStatePayload.Team team : board.teams()) {
            if (team.members().contains(player)) {
                return team.id();
            }
        }

        return null;
    }

    /**
     * その車両を出している陣営の ID。AI の車両でなければ null。
     *
     * <p>人の機体は乗っている者から陣営が決まるが、AI には乗っている者がいない。サーバーは AI の車両の
     * UUID を陣営ごとに配っている（{@code MatchStatePayload.Team.machines}）ので、味方かどうかの判定は
     * そちらを引く。
     */
    @Nullable
    public static String teamOfMachine(UUID machine) {
        if (board == null) {
            return null;
        }

        for (MatchStatePayload.Team team : board.teams()) {
            if (team.machines().contains(machine)) {
                return team.id();
            }
        }

        return null;
    }

    /** その陣営の色（ARGB）。知らない陣営なら白。 */
    public static int colourOfTeam(String team) {
        return colourOf(colourNameOf(board, team));
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, LAYER, MatchView::draw);
    }

    private static void draw(GuiGraphics graphics, DeltaTracker delta) {
        MatchStatePayload shown = board;
        Minecraft client = Minecraft.getInstance();

        if (shown == null || client.options.hideGui || shown.teams().isEmpty()
                || shown.phase() == MatchState.Phase.SETUP.ordinal()) {
            return;
        }

        int centre = graphics.guiWidth() / 2;

        drawBar(graphics, shown, shown.teams().get(0), centre, true);

        if (shown.teams().size() > 1) {
            drawBar(graphics, shown, shown.teams().get(1), centre, false);
        }

        int under = drawPoints(graphics, shown, centre, TOP + BAR_H + 3);
        Component clock = clock(shown);

        graphics.drawString(client.font, clock, centre - client.font.width(clock) / 2, under, TEXT, true);

        // 手持ちのポイント。次に何を出せるかはこの数字1つで決まるので、時計のすぐ下に置く。
        Component purse = Component.translatable("hud.ashvehicles.match.purse", shown.purse());

        graphics.drawString(client.font, purse, centre - client.font.width(purse) / 2, under + 10,
                POINTS, true);
        drawExtras(graphics, shown, centre, under + 21);

        int held = waitTicks();

        if (held > 0) {
            Component hold = Component.translatable("hud.ashvehicles.match.respawn",
                    (held + 19) / 20).withStyle(ChatFormatting.BOLD);
            int at = (graphics.guiWidth() - client.font.width(hold)) / 2;

            graphics.drawString(client.font, hold, at, graphics.guiHeight() / 2 + 20, WAIT, true);
        }
    }

    /**
     * 片側のチケットバー1本。中央を起点に外へ伸びる。
     *
     * <p>減ったぶんは<b>外端から消える</b>。中央は常に両者が接していて、そこが基準線になる——そうで
     * ないと2本の長さを比べられない。
     */
    private static void drawBar(GuiGraphics graphics, MatchStatePayload shown, MatchStatePayload.Team team,
            int centre, boolean onLeft) {
        Minecraft client = Minecraft.getInstance();
        int colour = colourOf(team.color());
        float fraction = Mth.clamp((float) team.tickets() / Math.max(shown.startTickets(), 1), 0.0F, 1.0F);
        int filled = Math.round(HALF * fraction);
        int inner = onLeft ? centre - 1 : centre + 1;
        int outer = onLeft ? inner - HALF : inner + HALF;
        int end = onLeft ? inner - filled : inner + filled;

        graphics.fill(Math.min(inner, outer), TOP, Math.max(inner, outer), TOP + BAR_H, TRACK);
        graphics.renderOutline(Math.min(inner, outer), TOP, HALF, BAR_H, TRACK_EDGE);
        graphics.fill(Math.min(inner, end), TOP, Math.max(inner, end), TOP + BAR_H, colour);

        Component count = Component.literal(String.valueOf(team.tickets()));

        if (team.id().equals(shown.own())) {
            count = count.copy().withStyle(ChatFormatting.BOLD);
        }

        int width = client.font.width(count);
        int at = onLeft ? outer - 4 - width : outer + 4;

        graphics.drawString(client.font, count, at, TOP - 1, colour, true);
    }

    /**
     * 拠点のダイヤを1列。
     *
     * @return 次に描いてよい y。拠点を置いていない試合では渡された y がそのまま返る
     */
    private static int drawPoints(GuiGraphics graphics, MatchStatePayload shown, int centre, int y) {
        List<MatchStatePayload.Point> points = shown.points();

        if (points.isEmpty()) {
            return y;
        }

        int x = centre - (points.size() - 1) * DIAMOND_GAP / 2;

        for (MatchStatePayload.Point point : points) {
            drawDiamond(graphics, shown, point, x, y + DIAMOND);
            x += DIAMOND_GAP;
        }

        return y + DIAMOND * 2 + 4;
    }

    /**
     * 拠点1つ。持ち主の色で塗り、取られかけている分だけ下から相手の色が上がる。
     *
     * <p>下から上がるのは進みに向きを持たせるため。割合を数字で出しても、取られている最中なのか取り
     * 返している最中なのかは2回見ないと分からない。
     */
    private static void drawDiamond(GuiGraphics graphics, MatchStatePayload shown,
            MatchStatePayload.Point point, int centreX, int centreY) {
        int owner = point.owner().isEmpty() ? NEUTRAL : colourOf(colourNameOf(shown, point.owner()));
        int taking = point.taking().isEmpty() ? owner : colourOf(colourNameOf(shown, point.taking()));
        int rising = Math.round(point.progress() * (DIAMOND * 2 + 1));

        for (int dy = -DIAMOND; dy <= DIAMOND; dy++) {
            int span = DIAMOND - Math.abs(dy);
            int row = centreY + dy;
            boolean taken = dy > DIAMOND - rising;

            graphics.fill(centreX - span, row, centreX + span + 1, row + 1, taken ? taking : owner);
        }

        if (point.contested()) {
            // 拮抗は印で言う。塗りを変えると「今誰の物か」が読めなくなる。
            graphics.fill(centreX - 1, centreY - DIAMOND - 2, centreX + 2, centreY - DIAMOND - 1, CONTESTED);
            graphics.fill(centreX - 1, centreY + DIAMOND + 2, centreX + 2, centreY + DIAMOND + 3, CONTESTED);
        }
    }

    /** 3つめ以降の陣営。2陣営の試合では何も描かない。 */
    private static void drawExtras(GuiGraphics graphics, MatchStatePayload shown, int centre, int y) {
        if (shown.teams().size() < 3) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        Component line = Component.empty();

        for (int at = 2; at < shown.teams().size(); at++) {
            MatchStatePayload.Team team = shown.teams().get(at);
            ChatFormatting colour = ChatFormatting.getByName(team.color());
            Component one = Component.literal(team.name() + " " + team.tickets())
                    .withStyle(colour == null ? ChatFormatting.WHITE : colour);

            line = Component.empty().append(line)
                    .append(at > 2 ? Component.literal("   ") : Component.empty()).append(one);
        }

        graphics.drawString(client.font, line, centre - client.font.width(line) / 2, y, TEXT, true);
    }

    /** その陣営 ID の色名。掲示板に載っていない陣営なら白。 */
    private static String colourNameOf(@Nullable MatchStatePayload shown, String team) {
        if (shown == null) {
            return ChatFormatting.WHITE.getName();
        }

        for (MatchStatePayload.Team candidate : shown.teams()) {
            if (candidate.id().equals(team)) {
                return candidate.color();
            }
        }

        return ChatFormatting.WHITE.getName();
    }

    private static int colourOf(String name) {
        ChatFormatting colour = ChatFormatting.getByName(name);
        Integer rgb = colour == null ? null : colour.getColor();

        return rgb == null ? 0xFFFFFFFF : 0xFF000000 | rgb;
    }

    /** 中央の1行。残り時間、無ければ配ったチケット枚数。決着後はその旨。 */
    private static Component clock(MatchStatePayload shown) {
        if (shown.phase() == MatchState.Phase.ENDED.ordinal()) {
            return Component.translatable("hud.ashvehicles.match.ended").withStyle(ChatFormatting.GOLD);
        }

        if (shown.ticksLeft() > 0) {
            int seconds = shown.ticksLeft() / 20;

            return Component.translatable("hud.ashvehicles.match.time", seconds / 60,
                    String.format("%02d", seconds % 60));
        }

        return Component.translatable("hud.ashvehicles.match.tickets", shown.startTickets());
    }
}
