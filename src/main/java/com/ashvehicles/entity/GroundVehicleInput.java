package com.ashvehicles.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * 1tick分の運転手の操作のスナップショット。
 *
 * <p>2軸、どちらも [-1, 1] に正規化。{@code drive} はスロットルと前後進の選択を兼ねる——正で前進、負で
 * 後進——のであって、上げて放置する設定値ではない。戦車はペダルを踏んで運転する物であって、スロットル
 * レバーに手を置いて飛ばす物ではなく、離せば減速する。{@code steer} は右が正。
 *
 * <p>{@code brake} はサービスブレーキであると同時に駐車ブレーキでもある。掛け続ければ車両は止まり、斜面
 * でも保持される。
 *
 * <p><b>引き金は1つではなく2つ。</b> {@code fire} は乗員が選択している兵装——主砲か、両方積む車両なら
 * ミサイル。{@code coax} は砲の脇に固定された機銃で、選択されることは無く常にそこにある。目標を捉えている
 * 砲手は主砲をしまわずに機銃を撃ち込めるし、主砲と機銃を選ばねばならない戦車は「兵装1つの戦車」になる。
 */
public record GroundVehicleInput(float drive, float steer, boolean brake, boolean fire, boolean coax) {

    /** 操作中立、何も押していない状態。 */
    public static final GroundVehicleInput NONE = new GroundVehicleInput(0.0F, 0.0F, false, false, false);

    /**
     * 無人の運転席が出す入力。ブレーキ ON。
     *
     * <p>単なる中立ではない。何も押されていない車両は惰性で進み、戦車の転がり抵抗は小さいので、走行中に
     * 放棄されると15秒近く走り続ける——乗員が野原に立って自分の戦車が走り去るのを眺めるには十分な長さだ。
     * 降りる者はブレーキを掛けていく。これも同じ。
     */
    public static final GroundVehicleInput PARKED = new GroundVehicleInput(0.0F, 0.0F, true, false, false);

    public GroundVehicleInput {
        drive = Mth.clamp(drive, -1.0F, 1.0F);
        steer = Mth.clamp(steer, -1.0F, 1.0F);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(this.drive);
        buf.writeFloat(this.steer);
        buf.writeBoolean(this.brake);
        buf.writeBoolean(this.fire);
        buf.writeBoolean(this.coax);
    }

    public static GroundVehicleInput read(FriendlyByteBuf buf) {
        return new GroundVehicleInput(buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean());
    }
}
