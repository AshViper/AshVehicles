package com.ashvehicles.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * 1tick分のパイロットの操縦入力のスナップショット。
 *
 * <p>各軸は [-1, 1] に正規化されている。{@code throttle} は絶対値ではなく変化率で、機体側が積分するので、
 * キーを押し続けるとエンジンが上下する。{@code fire} は引き金の押下状態で、何を撃つかは機体の選択次第。
 * {@code flare} と {@code chaff} は2つの対抗手段レバー。別々なのは応じる脅威が違うから
 * （{@link com.ashvehicles.weapon.Dispenser} 参照）。{@code lock} はシーカー専用の引き金で、押している間
 * だけ新しい目標を取ってよいという意味。既に捉えている目標の保持とは無関係。
 * {@link com.ashvehicles.weapon.TargetLock#tick} 参照。
 */
public record AircraftInput(float pitch, float roll, float yaw, float throttle, boolean brake, boolean fire,
        boolean flare, boolean chaff, boolean lock) {

    /** 操縦桿中立、エンジンはそのまま。無人のコックピットもこれを出す。 */
    public static final AircraftInput NONE =
            new AircraftInput(0.0F, 0.0F, 0.0F, 0.0F, false, false, false, false, false);

    public AircraftInput {
        pitch = Mth.clamp(pitch, -1.0F, 1.0F);
        roll = Mth.clamp(roll, -1.0F, 1.0F);
        yaw = Mth.clamp(yaw, -1.0F, 1.0F);
        throttle = Mth.clamp(throttle, -1.0F, 1.0F);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeFloat(this.pitch);
        buf.writeFloat(this.roll);
        buf.writeFloat(this.yaw);
        buf.writeFloat(this.throttle);
        buf.writeBoolean(this.brake);
        buf.writeBoolean(this.fire);
        buf.writeBoolean(this.flare);
        buf.writeBoolean(this.chaff);
        buf.writeBoolean(this.lock);
    }

    public static AircraftInput read(FriendlyByteBuf buf) {
        return new AircraftInput(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }
}
