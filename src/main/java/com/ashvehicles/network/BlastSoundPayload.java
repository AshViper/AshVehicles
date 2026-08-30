package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.sound.BlastSounds;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 爆発が起きたこと、その位置と規模。
 *
 * <p>通常の方法で音を鳴らす代わりに送る。通常の方法では届かないからだ。サーバーで要求した音は
 * {@code volume * 16} ブロックより先の誰にも届かず、音響エンジン側の減衰も同じ距離でゼロになる。つまり
 * どれだけ大音量と書いても爆発は64ブロック先で無音になる。チェストの開閉音には妥当で、現実なら数km先まで
 * 聞こえる500kg の炸薬には無意味な答え。
 *
 * <p>そこでサーバーは「爆発が起きた」とだけ告げ、残りはクライアントが計算する。音が届くまでの時間、着い
 * た時に残っている音量、道中でどれだけ籠もったか。{@link BlastSounds} 参照。
 */
public record BlastSoundPayload(double x, double y, double z, float power) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlastSoundPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "blast_sound"));

    public static final StreamCodec<FriendlyByteBuf, BlastSoundPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
                buf.writeFloat(payload.power());
            },
            buf -> new BlastSoundPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat()));

    /** 爆発を送る価値のある距離（ブロック）。これより遠い者には一切知らせない。 */
    private static final double CARRY = 220.0;
    private static final double CARRY_PER_POWER = 80.0;

    /**
     * この規模の爆発が聞こえる距離（ブロック）。
     *
     * <p>両端が使う。サーバーは誰に送るかの判断に、クライアントは着いた時点で音量がどれだけ残っているか
     * の判断に。両者が一致していないと、境界にいるプレイヤーは音を送られた上で「無音」と告げられる。
     */
    public static double carry(float power) {
        return CARRY + power * CARRY_PER_POWER;
    }

    public Vec3 at() {
        return new Vec3(this.x, this.y, this.z);
    }

    @Override
    public CustomPacketPayload.Type<BlastSoundPayload> type() {
        return TYPE;
    }

    /**
     * クライアント向けとしてのみ登録されているので、これはクライアントでしか走らない。専用サーバーが
     * {@link BlastSounds} を解決することはない。
     */
    public static void handle(BlastSoundPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> BlastSounds.hear(payload.at(), payload.power()));
    }
}
