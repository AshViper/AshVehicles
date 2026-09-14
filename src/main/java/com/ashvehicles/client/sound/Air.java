package com.ashvehicles.client.sound;

import net.minecraft.util.Mth;

/**
 * 音が旅する空気。
 *
 * <p>ゲームの音は空気を持たない。要求された瞬間に、要求された音量で、64ブロック先で無音になる。この MOD が
 * 鳴らす物のほとんど——数百ブロック先の砲声、上空を横切るジェット、着弾の轟音——は、その3つがどれも成り立たない
 * 距離で聞かれる。だから距離に対して何が起きるかを、ここに1か所だけ持つ。
 *
 * <ul>
 * <li><b>遅れて着く。</b>音速は約17ブロック/tick なので、300ブロック先は1秒近く遅れる。閃光と轟音が同時に来ない
 *     ことが「遠い」の最も強い手掛かりであり、逆に同時に来ることは「近い」の手掛かりだ。
 * <li><b>鈍って着く。</b>空気は高い周波数から先に吸う。近くでの破裂音は、谷を越えれば低い轟きになる。
 * <li><b>粘って落ちる。</b>音量は線形には落ちない。最初に速く落ちてから遠方までしぶとく残る。
 * <li><b>動く物は音程が変わる。</b>近づく音は詰まって高く、去る音は伸びて低い。
 * </ul>
 *
 * <p>ここに数値を集めたのは、同じ空気を別々の場所が別々に書いていたからだ。爆発は自分の音速定数を持ち、
 * エンジン音はもう1つ持ち、発砲音は減衰の指数だけを写していた。空気は1つしかない。
 */
public final class Air {
    /**
     * 音速（ブロック/tick）。343 m/s を 20 tps で割った値。
     *
     * <p>1ブロック＝1mとして扱う。この MOD の機体は実寸で描かれ実諸元で飛ぶので、音もその世界の音速で進む。
     */
    public static final double SPEED = 17.15;

    /**
     * 距離に対する減音の指数。1未満。
     *
     * <p><b>線形にしない。</b>{@code 1 - d/range} は到達距離の遠い半分を捨てる——射程512の機体は256ブロックで
     * 既に半分、400で2割強まで落ちる。実際のジェットはその距離でまだはっきり聞こえるので、線形の遠端は
     * 「そこまで届く」と書いてあるだけで何も聞こえない区間になる。1未満の指数は最初に速く落ちてから粘るので、
     * 到達距離の遠い端に意味が生まれる。
     */
    public static final double FALLOFF = 0.85;

    /**
     * 鋭さを失いきる距離（ブロック）。
     *
     * <p><b>到達距離の割合ではなく実距離で測る。</b>高い周波数から先に吸うのは空気であって、その音がどこまで
     * 届く予定かは関係しない。割合で測っていた頃、10ブロックしか届かない音は5歩下がっただけで半音下がっていた
     * ——同じ曲線で数百ブロック先の砲声を鈍らせるために書かれた値だったからだ。
     */
    public static final double DULLING_RANGE = 400.0;

    /**
     * ドップラー効果を許す範囲。
     *
     * <p>物理はここで止まらない——音速の半分で近づく物は音程が2倍になり、音速に達すれば発散する（それが
     * ソニックブームだ）。だが2つの理由で頭打ちにする。ゲームの音響エンジンはピッチを 0.5〜2.0 に丸めるので
     * その先は無いし、ループ音を大きく引き伸ばすと、エンジンの音がエンジンの音に聞こえなくなる。ここでの
     * 上下は「通り過ぎた」と分かる幅であり、音を壊さない幅でもある。
     */
    private static final float FASTEST = 1.35F;
    private static final float SLOWEST = 0.75F;

    /** その距離を音が渡るのにかかる tick 数。 */
    public static int travel(double away) {
        return (int) (away / SPEED);
    }

    /** 到達距離 {@code range} の音が、距離 {@code away} でどれだけ残るか（0〜1）。 */
    public static float carried(double away, double range) {
        double fade = Mth.clamp(away / Math.max(range, 1.0E-3), 0.0, 1.0);

        return (float) Math.pow(1.0 - fade, FALLOFF);
    }

    /**
     * その距離で残る鋭さ。掛けるとピッチが下がる。
     *
     * @param most 全部吸われた所で失う割合。破裂音のように高い成分でできている音ほど大きい
     */
    public static float dulled(double away, float most) {
        return 1.0F - most * (float) Mth.clamp(away / DULLING_RANGE, 0.0, 1.0);
    }

    /**
     * 近づく・遠ざかる音の音程。掛けて使う。
     *
     * @param closing 音源までの距離が1tickにどれだけ<em>増えた</em>か（ブロック）。近づいていれば負
     */
    public static float doppler(double closing) {
        // 分母に床を置く。音速で近づく物の音程は発散し（それがソニックブームだ）、それより速い物では
        // 符号が裏返る——ミサイルは音速の5倍で飛ぶので、床が無ければ超音速の接近が「最も低い音」になる。
        return (float) Mth.clamp(SPEED / Math.max(SPEED + closing, SPEED * 0.1), SLOWEST, FASTEST);
    }

    private Air() {
    }
}
