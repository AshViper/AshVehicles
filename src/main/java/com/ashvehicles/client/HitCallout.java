package com.ashvehicles.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.ashvehicles.client.sound.KillSounds;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 着弾を1行の文にして読み上げる。当たった、弾かれた、倒した。
 *
 * <p>{@link HitReadout} の絵の下に積む。<b>絵と文は同じ事実の別の答え方だ。</b>絵が答えるのは「どこに」で、
 * 弾を当て続けている間ずっと見る価値がある。文が答えるのは「何が起きたか」で、起きた瞬間にしか価値が無い
 * 代わりに、目を上げなくても視野の端で読める。装甲を抜いたのか滑ったのかを、砲手はマークの中身が詰まって
 * いるかどうかを見に行かずに知れる。
 *
 * <p><b>同じ結末は積み上げず1行にまとめる。</b>機関砲の1連射は毎tick 数発当たるので、1発1行にすると
 * それだけで画面が流れ、しかも読む前に消える。同じ種類の答えが短時間に続いた場合は、行を増やさずその行の
 * 発数と累計損害を増やす——連射に対する正しい読み上げは「当たった当たった当たった」ではなく「6発、損害
 * 128」だ。撃破だけは決してまとめない。1機につき1度しか起きないし、それがこの表示で最も重い行だからだ。
 *
 * <p>撃破の行にだけ音が付く。{@link KillSounds} 参照。
 */
final class HitCallout {
    /** 1行を残す時間（ミリ秒）。{@link HitReadout} の絵より短い。文は事象であって状態ではない。 */
    private static final long LINGER = 3500L;
    /** そのうちフェードに使う割合。 */
    private static final long FADE = 800L;
    /** 同じ種類の答えを1行にまとめる猶予。これ以上空いたら別の交戦として新しい行にする。 */
    private static final long MERGE = 900L;
    /** 同時に出す行数。これより古い物から落とす。 */
    private static final int MOST = 4;
    /** 行間。 */
    private static final int STEP = 10;

    /** 貫通。損害を出した弾。 */
    private static final int STRUCK = AircraftHud.GREEN;
    /** 装甲が弾いた弾。{@link HitReadout} のマークと同じ琥珀色。 */
    private static final int BOUNCED = 0xFFFFD24A;
    /** 当たったが損害にならなかった弾。 */
    private static final int NOTHING = AircraftHud.DIM;
    /** 撃破。 */
    private static final int KILLED = AircraftHud.WARNING;

    /** 答えの種類。まとめてよいかどうかはこれが同じかどうかで決まる。 */
    private enum Kind {
        STRIKE, HELD, RICOCHET, NO_DAMAGE, KILL
    }

    /**
     * 表示中の1行。
     *
     * @param kind どの答えか。同じ種類の続きはこの行に足される
     * @param subject 何に対してか。相手が変われば行も変える
     * @param rounds この行がまとめている発数
     * @param damage この行がまとめている損害の合計
     * @param at 最後に足された時刻。ここから寿命を測る
     */
    private static final class Line {
        private final Kind kind;
        private final ResourceLocation subject;
        private int rounds;
        private float damage;
        private long at;

        private Line(Kind kind, ResourceLocation subject, float damage, long at) {
            this.kind = kind;
            this.subject = subject;
            this.rounds = 1;
            this.damage = damage;
            this.at = at;
        }
    }

    /** 新しい物が先頭。 */
    private static final List<Line> LINES = new ArrayList<>();

    private HitCallout() {
    }

    /**
     * 着弾1発。{@link HitReadout#report} から、サーバーが撃った本人にだけ送る報告ごとに呼ばれる。
     *
     * @param subject 当たった相手
     * @param damage 与えた損害。装甲が弾いたなら0
     * @param bounced 装甲が弾いたか
     * @param held 板に入ったが抜けなかったか。損害は半分で届いている
     * @param killed この1発で相手が終わったか
     */
    static void report(ResourceLocation subject, float damage, boolean bounced, boolean held, boolean killed) {
        long now = Util.getMillis();
        Kind kind = killed ? Kind.KILL
                : bounced ? Kind.RICOCHET
                : held ? Kind.HELD
                : damage > 0.0F ? Kind.STRIKE : Kind.NO_DAMAGE;

        if (kind == Kind.KILL) {
            // 撃破はまとめない。1機につき1度しか起きないし、その1行の上に次の連射が乗ってはいけない。
            push(new Line(kind, subject, damage, now));
            KillSounds.confirm();

            return;
        }

        Line newest = LINES.isEmpty() ? null : LINES.get(0);

        if (newest != null && newest.kind == kind && newest.subject.equals(subject)
                && now - newest.at <= MERGE) {
            newest.rounds++;
            newest.damage += damage;
            newest.at = now;

            return;
        }

        push(new Line(kind, subject, damage, now));
    }

    private static void push(Line line) {
        LINES.add(0, line);

        while (LINES.size() > MOST) {
            LINES.remove(LINES.size() - 1);
        }
    }

    /**
     * 右上、絵の下に積む。{@link HitReadout#draw} から呼ばれる——絵が出ていない間も文だけは出るので、
     * 呼び出しはあちらの早期リターンより前にある。
     *
     * @param right 行の右端。絵の枠の右端に合わせる
     * @param top 最初の行の上端
     */
    static void draw(GuiGraphics graphics, Font font, int right, int top) {
        if (LINES.isEmpty()) {
            return;
        }

        long now = Util.getMillis();
        int y = top;

        for (int i = 0; i < LINES.size(); i++) {
            Line line = LINES.get(i);
            long age = now - line.at;

            if (age > LINGER) {
                // これより後ろは全部これより古い。まとめて捨てる。
                LINES.subList(i, LINES.size()).clear();

                return;
            }

            float alpha = age > LINGER - FADE ? (float) (LINGER - age) / FADE : 1.0F;
            String text = text(line);

            graphics.drawString(font, text, right - font.width(text), y, fade(colour(line), alpha), true);
            y += STEP;
        }
    }

    /** その行が読み上げる文。 */
    private static String text(Line line) {
        String rounds = line.rounds > 1 ? " x" + line.rounds : "";

        return switch (line.kind) {
            case KILL -> Component.translatable("hud.ashvehicles.hit.killed",
                    HitReadout.nameOf(line.subject).toUpperCase(Locale.ROOT)).getString();
            case HELD -> Component.translatable("hud.ashvehicles.hit.held",
                    Math.round(line.damage)).getString() + rounds;
            case RICOCHET -> Component.translatable("hud.ashvehicles.hit.ricochet").getString() + rounds;
            case NO_DAMAGE -> Component.translatable("hud.ashvehicles.hit.nothing").getString() + rounds;
            case STRIKE -> Component.translatable("hud.ashvehicles.hit.struck",
                    Math.round(line.damage)).getString() + rounds;
        };
    }

    private static int colour(Line line) {
        return switch (line.kind) {
            case KILL -> KILLED;
            case HELD, RICOCHET -> BOUNCED;
            case NO_DAMAGE -> NOTHING;
            case STRIKE -> STRUCK;
        };
    }

    /** 同じ色を、その行の残り寿命に応じて薄くした物。 */
    private static int fade(int colour, float alpha) {
        int opacity = Math.round(((colour >>> 24) & 0xFF) * Mth.clamp(alpha, 0.0F, 1.0F));

        return (opacity << 24) | (colour & 0x00FFFFFF);
    }
}
