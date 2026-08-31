package com.ashvehicles.network;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.BlastFlash;
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
 * <p>元は音のためだけに存在した。通常の方法では届かないからだ——サーバーで要求した音は
 * {@code volume * 16} ブロックより先の誰にも届かず、音響エンジン側の減衰も同じ距離でゼロになる。つまり
 * どれだけ大音量と書いても爆発は64ブロック先で無音になる。チェストの開閉音には妥当で、現実なら数km先まで
 * 聞こえる500kg の炸薬には無意味な答え。
 *
 * <p>そこでサーバーは「爆発が起きた」とだけ告げ、残りはクライアントが計算する。そしてそれが分かった時点で、
 * 同じ通知から出る物が3つになった。届く順に、<b>閃光</b>（{@link com.ashvehicles.client.BlastFlash}、
 * 光は待たないので即座）、<b>轟音</b>（{@link BlastSounds}、音速で遅れ、道中で籠もる）、<b>揺れ</b>
 * （{@link com.ashvehicles.client.BlastShake}、轟音と同じ空気の壁なので同着）。三つが別々に着くこと自体が
 * 距離の表現になっている。
 *
 * <p>どれもサーバーの意見を必要としない。位置と規模さえ分かれば、見ている側が自分の位置から全部を導ける。
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
     * どれだけ大きくても、ここより遠くへは届かない（ブロック）。
     *
     * <p>兵装は {@link com.ashvehicles.particle.Effects#BIGGEST} までなので、そこでは1180ブロックとなり
     * この天井には触れない。効くのは試験棒を振り切った時だけで、そこでも3kmで止まる。比例のままだと
     * 「ワールドの全員へ1パケット」になり、しかも全員にほぼ最大音量で聞こえる。
     */
    private static final double FURTHEST = 3000.0;

    /**
     * この規模の爆発が聞こえる距離（ブロック）。
     *
     * <p>両端が使う。サーバーは誰に送るかの判断に、クライアントは着いた時点で音量がどれだけ残っているか
     * の判断に。両者が一致していないと、境界にいるプレイヤーは音を送られた上で「無音」と告げられる。
     */
    public static double carry(float power) {
        return Math.min(CARRY + power * CARRY_PER_POWER, FURTHEST);
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
        context.enqueueWork(() -> {
            // 閃光が先。光に飛行時間は無いので、パケットが着いた時点がそのまま見えた時点になる。轟音と、
            // 轟音と同じ空気の壁である揺れは、そこから音速で這ってくる。
            BlastFlash.seen(payload.at(), payload.power());
            BlastSounds.hear(payload.at(), payload.power());
        });
    }
}
