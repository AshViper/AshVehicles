package com.ashvehicles.client.sound;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.entity.Entity;

/**
 * エンティティ1つにつき1本の継続音。瞬間ではなく物に属する音のための仕組み。
 *
 * <p>エンジン、モーター、降着装置はいずれも同じ問題だ。発生源がその動作を続ける限り鳴り続け、しかも誰もパケット
 * を送らない音。答えは毎回同じで、このクライアントから見えるエンティティのリストを保持し、鳴っているべき物すべて
 * に音が走っていることを保証する。
 *
 * <p>各エンティティへ音を生涯結び付けるのではなくエンティティのリストとして書いてあるのは、音がチャンネルであり
 * チャンネルは少ないからだ。可聴範囲外の物や動作していない物はチャンネルを返し、状況が変われば {@link #tick}
 * が空きに気付いて別の音を開始する。リソースリロードや音量スライダーで失われた音も自ずと戻ってくる。
 *
 * <p>開始は毎tick試みない。開始できなかった音——音量が0、空きチャンネルが無い——は、そうしないとエンティティの
 * 生存中ずっと毎秒20回試されることになる。間隔は各エンティティ自身の齢に対して測るので、空一杯のエンティティが
 * あっても試行は一斉ではなく分散する。
 */
public final class LiveSounds<T extends Entity> {
    /** このエンティティに今鳴らすべき音。まだ鳴らすべきでなければ null。 */
    @FunctionalInterface
    public interface Starter<T> {
        @Nullable
        AbstractTickableSoundInstance start(T entity);
    }

    private final Class<T> kind;
    private final int retryTicks;
    private final Starter<T> starter;
    /** 現在のレベルにいるエンティティと、それぞれが今持っている音（あれば）。 */
    private final Map<T, AbstractTickableSoundInstance> sounds = new HashMap<>();

    public LiveSounds(Class<T> kind, int retryTicks, Starter<T> starter) {
        this.kind = kind;
        this.retryTicks = Math.max(1, retryTicks);
        this.starter = starter;
    }

    /** レベルへ入ってきたエンティティを、MOD の物なら記録する。 */
    public void offer(Entity entity) {
        if (this.kind.isInstance(entity)) {
            this.sounds.putIfAbsent(this.kind.cast(entity), null);
        }
    }

    /** ワールドを離れるときリストごと捨てる。次のワールドは別の空だ。 */
    public void forget() {
        this.sounds.clear();
    }

    public void tick(Minecraft minecraft) {
        if (this.sounds.isEmpty()) {
            return;
        }

        SoundManager manager = minecraft.getSoundManager();
        Iterator<Map.Entry<T, AbstractTickableSoundInstance>> entries = this.sounds.entrySet().iterator();

        while (entries.hasNext()) {
            Map.Entry<T, AbstractTickableSoundInstance> entry = entries.next();
            T entity = entry.getKey();

            if (entity.isRemoved() || entity.level() != minecraft.level) {
                entries.remove();

                continue;
            }

            AbstractTickableSoundInstance sound = entry.getValue();

            if (sound != null && !sound.isStopped() && manager.isActive(sound)) {
                continue;
            }

            if (entity.tickCount % this.retryTicks != 0) {
                continue;
            }

            AbstractTickableSoundInstance started = this.starter.start(entity);
            entry.setValue(started);

            if (started != null) {
                manager.play(started);
            }
        }
    }
}
