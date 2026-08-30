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
 * MOD の {@code ashvehicles:weapon.impact}、それも無ければゲーム本体の「金床を置く音」。重い物が金属に
 * 到達してそこに留まる音として最も近く、跳弾がフォールバックに使う「金床が落ちる音」より鈍い。選択と距離
 * 処理は {@link com.ashvehicles.client.sound.WeaponSounds} が行う。
 */
public final class Impact {
    /** 音イベント名の末尾。{@code weapon.<weapon>.impact} の形。 */
    public static final String SOUND_ROLE = "impact";

    /** 専用の命中音を持たない兵装のフォールバック。サーバーが指定する。 */
    public static final ResourceLocation SOUND = ResourceLocation.fromNamespaceAndPath(
            AshVehicles.MODID, WeaponMounts.SOUND_PREFIX + SOUND_ROLE);

    /**
     * 命中音が届く距離。兵装の発砲音と同じ尺度で、この数値は音量ではなく
     * {@link WeaponDefinition.SoundSetup#carry()} における到達距離。
     *
     * <p>跳弾より遠く、発砲音よりずっと近い。戦車戦は叫び声の届かない距離で行われ、引き金を引いた者に
     * 聞こえない命中は当たっていないのと同じ。ただし発砲そのものは到着音よりはるかに大きな音なので、
     * 両者が同じ重みで返ってきてはいけない。
     */
    public static final float VOLUME = 1.5F;

    /**
     * 低めのピッチ。耳で跳弾と区別する手がかりがこれ。
     *
     * <p>装甲板を滑る音は明るく硬い音なのでピッチを上げる。装甲で止まった弾は逆で、持っていた全部が一度に
     * 金属へ入る。返ってくるのは低く短い音になる。
     */
    public static final float PITCH = 0.85F;

    /**
     * 上の2つを、音の送受信両側が読む1つのオブジェクトにまとめた物。サーバーは「どこまで届くか」を、
     * クライアントは「聴き手の位置でどれだけの音量か」を訊く。同じ数値でなければ、音は間違った音量で届く
     * か、まったく届かない。
     */
    public static final WeaponDefinition.SoundSetup SOUND_SETUP =
            new WeaponDefinition.SoundSetup(Optional.empty(), VOLUME, PITCH);

    private Impact() {
    }

    /** 1兵装分の命中音イベント。パック側が独自に用意してもよい。 */
    public static ResourceLocation soundFor(ResourceLocation weapon) {
        return weapon.withPath(WeaponMounts.SOUND_PREFIX + weapon.getPath() + "." + SOUND_ROLE);
    }
}
