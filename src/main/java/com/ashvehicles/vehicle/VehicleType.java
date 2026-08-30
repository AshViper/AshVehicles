package com.ashvehicles.vehicle;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;

/**
 * ファイルが記述しているのがどの種類の機体か。推測させず、ファイルに明記する。
 *
 * <p>以前は種類が2つの事実から暗に決まっていて、どこにも書かれていなかった。ファイルの置き場
 * （{@code aircraft/} か {@code vehicle/} か）が飛ぶか走るかを示し、{@code rotor} ブロックの有無が
 * ヘリコプターかを示す。ゲームを動かすには足りるが、ファイルを読む人が一目で分かるには足りず、そして
 * 「飛びも陸を走りもしない第三の物」の居場所がまったく無い。そこで種類をフィールドにした。飛行機と
 * ヘリコプターは機体側の仕組みを丸ごと共有する2つの値、戦車と軍艦は地上車両側を共有する2つの値、そして
 * 新しい艦は「地面ではなく水に支えられる地上車両」。
 *
 * <p>この値が支配するのは物理だけで、ファイルの読み方には関与しない。{@code aircraft} でも
 * {@code helicopter} でも機体ファイルは {@link com.ashvehicles.aircraft.AircraftDefinition} のままだ
 * し、{@code ground_vehicle} でも {@code ship} でも {@code vehicle/} のファイルは
 * {@link GroundVehicleDefinition} のまま。値が決めるのはワールドに出た後の挙動——翼で浮くかローターで
 * 浮くか、地面に押し付けられるか海に浮かべられるか。
 */
public enum VehicleType implements StringRepresentable {
    /** 固定翼機。翼で浮くので、翼に空気を当て続けるために飛び続ける必要がある。 */
    AIRCRAFT("aircraft"),
    /** 回転翼機。静止したままでも自分で揚力を作るローターで浮く。 */
    HELICOPTER("helicopter"),
    /** 地上の車両。重力で地面に押し付けられ、走る地形に沿って寝る。 */
    GROUND_VEHICLE("ground_vehicle"),
    /** 水上の艦艇。地面ではなく下の海に、喫水線で浮かべられる。 */
    SHIP("ship");

    public static final Codec<VehicleType> CODEC = StringRepresentable.fromEnum(VehicleType::values);

    private final String name;

    VehicleType(String name) {
        this.name = name;
    }

    /** 翼かローターで飛ぶ種類か（機体かヘリコプターか）。 */
    public boolean flies() {
        return this == AIRCRAFT || this == HELICOPTER;
    }

    /** 翼ではなくローターで浮くヘリコプターか。 */
    public boolean isHelicopter() {
        return this == HELICOPTER;
    }

    /** 地面に乗るのではなく下の水に浮く種類か。 */
    public boolean floats() {
        return this == SHIP;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}
