package com.ashvehicles.weapon;

import java.util.Optional;

import com.ashvehicles.AshVehicles;

import net.minecraft.resources.ResourceLocation;

/**
 * 弾がこの MOD の箱を滑り落ちるのではなく、中へ入った時の音。
 *
 * <p>{@link Ricochet} のもう半分で、何も言うことが無かった方の半分。装甲に弾かれた弾には弾かれる装甲が
 * できた時から金属音があったが、<em>入った</em>弾は火花を散らすだけで無音だった。
 * {@code WeaponEffects.detonation} が音を鳴らすのは、弾が鳴らす爆発を持っている時だけだからだ。つまり
 * 戦車が実際に撃つ2種類——徹甲弾と機銃の連射、どちらも炸裂物を持たない——は無音で着弾し、砲手が最も欲しい
 * フィードバックだけをこの MOD は返していなかった。
 *
 * <p>そこでこれは命中そのものの音になる。装甲板で発生し、撃った側から聞こえ、意図的に跳弾とは別の音。
 * その違いこそが存在理由だ——硬く平坦な金属音は「抜けずに出ていった」、重い鈍い音は「入った」を意味し、
 * 耳で聞き分けられる砲手は、何かが燃え出すのを待たずに同じ場所をもう一度撃つべきか判断できる。
 *
 * <p>自前の炸薬を持たない弾専用。着弾点で炸裂する物は既にその音で聞こえている（{@code Effects.boom}
 * 参照）ので、その上に金属音を重ねても第2の情報ではなく同じ情報の二重奏になる。
 *
 * <p><b>どの音声を使うか。</b> 専用の音を持つ兵装は {@code <namespace>:weapon.<name>.impact}、無ければ
 * MOD 同梱の {@code ashvehicles:weapon.impact}（合成した装甲板の打撃音。{@code tool/make_effect_sounds.py}
 * 参照）、それも無ければゲーム本体の金属音——機体を素手で叩いた時に鳴るのと同じ物
 * （{@code VehicleEntityBase.clank}）。選択と距離処理は
 * {@link com.ashvehicles.client.sound.WeaponSounds} が行う。
 */
public final class Impact {
    /** 音イベント名の末尾。{@code weapon.<weapon>.impact} の形。 */
    public static final String SOUND_ROLE = "impact";

    /** 専用の命中音を持たない兵装のフォールバック。サーバーが指定する。 */
    public static final ResourceLocation SOUND = ResourceLocation.fromNamespaceAndPath(
            AshVehicles.MODID, WeaponMounts.SOUND_PREFIX + SOUND_ROLE);

    /**
     * 命中音が届く距離（ブロック）。
     *
     * <p><b>これは近所の音だ。</b>装甲板を叩く音が谷を越えて聞こえることはない。かつては発砲音と同じ
     * 尺度で数百ブロック届かせていたが、それは「射線の遠端にいる砲手へ命中を伝える」ためであって、
     * 音でそれをやる必要は無かった——伝えるのは {@code HitReadout} と {@code HitCallout} の仕事で、
     * あちらは距離を持たない。音は、当たった場所の近くに居る者のためだけに鳴る。
     */
    public static final float RANGE = 10.0F;

    /** 上を {@code volume} 欄の値へ直した物。この欄は音量ではなく距離だ。 */
    public static final float VOLUME = WeaponDefinition.SoundSetup.volumeForCarry(RANGE);

    /**
     * 低めのピッチ。耳で跳弾と区別する手がかりがこれ。
     *
     * <p>装甲板を滑る音は明るく硬い音なのでピッチを上げる。装甲で止まった弾は逆で、持っていた全部が一度に
     * 金属へ入る。返ってくるのは低く短い音になる。同梱の録音は実測周波数のまま切ってあり、この値と
     * {@link Ricochet#PITCH} が両者の差を意図的に広げる。
     */
    public static final float PITCH = 0.85F;

    /**
     * 録音を鳴らす大きさ。到達距離とは別に持つ（{@link WeaponDefinition.SoundSetup#gain()} 参照）。
     *
     * <p>叩いた拳（{@code VehicleEntityBase} の 0.45）と同じ。当たっている物は同じ装甲板なので、
     * 手元で聞いた時の大きさも揃える。録音は峰を 0.86 に正規化してあるので、ここが実際の大きさを決める
     * 唯一の値だ。
     */
    public static final float GAIN = 0.45F;

    /**
     * 上の3つを、音の送受信両側が読む1つのオブジェクトにまとめた物。サーバーは「どこまで届くか」を、
     * クライアントは「聴き手の位置でどれだけの音量か」を訊く。同じ数値でなければ、音は間違った音量で届く
     * か、まったく届かない。
     */
    public static final WeaponDefinition.SoundSetup SOUND_SETUP =
            new WeaponDefinition.SoundSetup(Optional.empty(), VOLUME, PITCH, GAIN,
                    WeaponDefinition.SoundSetup.DEFAULT.interval());

    private Impact() {
    }

    /** 1兵装分の命中音イベント。パック側が独自に用意してもよい。 */
    public static ResourceLocation soundFor(ResourceLocation weapon) {
        return weapon.withPath(WeaponMounts.SOUND_PREFIX + weapon.getPath() + "." + SOUND_ROLE);
    }
}
