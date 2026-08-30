package com.ashvehicles.client.sound;

import com.ashvehicles.client.RadarReadout;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.sensor.Threat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 警戒受信機の警報音。脅威が続く限り鳴らし続ける。
 *
 * <p>短い音を繰り返し鳴らすのではなく、1本の音を流し続ける。受信機の警報とは実際には、既にリズムを内包した
 * 連続音であり、録音する者が録るのもそれだ——だから MOD の仕事は、鳴らし始め、値する間は続け、値しなくなった
 * 瞬間に止めることになる。
 *
 * <p>コックピットへ平坦に流す。位置も減衰も距離による低下も無い。パイロット席の後ろの箱であって、脅かしてくる
 * 機体ではないからだ。
 *
 * <p>指示を待たず自ら終わる。これが決してやってはならないのは、警告対象より長く鳴り続けることだ——ミサイルが
 * 通り過ぎた後も鳴り続ける警報は、警報が無いより悪い——ので、毎tick受信機がまだ同じことを言っているか確認し、
 * 答えが変わった瞬間にチャンネルを返す。
 */
public class WarningSoundInstance extends AbstractTickableSoundInstance {
    private final Threat.Kind kind;

    public WarningSoundInstance(SoundEvent sound, Threat.Kind kind, float pitch, float volume) {
        super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
        this.kind = kind;
        // 脅威が続いている間に録音が尽きたら頭から回す。
        this.looping = true;
        this.delay = 0;
        this.volume = volume;
        this.pitch = pitch;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    /** どの警報を鳴らしているか。より深刻な物が引き継げるように。 */
    public Threat.Kind kind() {
        return this.kind;
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || !(minecraft.player.getVehicle() instanceof AircraftEntity)
                || RadarReadout.worst() != this.kind) {
            this.stop();
        }
    }
}
