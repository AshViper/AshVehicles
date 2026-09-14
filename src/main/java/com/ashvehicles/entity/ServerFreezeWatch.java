package com.ashvehicles.entity;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

import com.ashvehicles.AshVehicles;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 一時的。サーバースレッドが 1 秒を超えて止まったとき、その瞬間に何をしていたかをスタックで記録する。
 *
 * <p><b>これが在る理由。</b> 「Can't keep up! 8304ms」は何が止めたかを言わない。{@link ChunkStalls} は
 * {@code ServerChunkCache.getChunk} を通った待ちしか数えられず、2026-09-04 の 8 秒の停止はそこを
 * 通っていなかった——同じ秒の {@code [stall]} 行は無い。つまり候補は getChunk の外にある: 別経路の
 * {@code managedBlock}、GC の確保停止、他 MOD のフック。どれかを決めるには、止まっている最中の
 * サーバースレッドのスタックを誰かが外から覗くしかない。専用サーバーの watchdog がやっていることで、
 * 内蔵サーバーには無い。
 *
 * <p>監視スレッドは 100 ms ごとに「最後の tick 開始から何 ms 経ったか」を見る。閾値を超えていれば
 * サーバースレッドのスタックの上位を 1 行にして出し、同じ停止が続く間は 2 秒ごとに出し直す。
 * 一時停止中（ESC メニュー）は tick が来ないので {@code isPaused} で黙る。GC の合計時間も添える——
 * 停止の間に GC の時計が大きく進んでいれば、犯人はスタックではなく確保レートである。
 *
 * <p>コストは監視スレッド 1 本と、tick ごとの nanoTime 1 回と GC カウンタの読み取り 1 回。
 * 調査が終わったらこのクラスを消すこと。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class ServerFreezeWatch {
    /** 調査中だけ真にすること。 */
    private static final boolean ENABLED = true;

    /** これを超えて tick が始まらなければ止まっていると見なす（ミリ秒）。 */
    private static final long FREEZE_MILLIS = 1000L;
    /** 同じ停止が続く間、次に出し直すまでの間隔（ミリ秒）。 */
    private static final long AGAIN_MILLIS = 2000L;
    /** 監視の周期（ミリ秒）。 */
    private static final long POLL_MILLIS = 100L;
    /** 1 行に出すフレーム数。 */
    private static final int FRAMES = 14;

    private static final long MILLIS = 1_000_000L;

    private static volatile MinecraftServer server;
    private static volatile Thread serverThread;
    /** 最後の tick が始まった時刻（nanoTime）。 */
    private static volatile long tickBegan;
    /** その時点の GC 累計時間（ミリ秒）。 */
    private static volatile long gcAtTickBegin;
    private static Thread watcher;

    private ServerFreezeWatch() {
    }

    @SubscribeEvent
    public static void onTickBegins(ServerTickEvent.Pre event) {
        if (!ENABLED) {
            return;
        }

        server = event.getServer();
        serverThread = Thread.currentThread();
        gcAtTickBegin = gcMillis();
        tickBegan = System.nanoTime();

        if (watcher == null) {
            watcher = new Thread(ServerFreezeWatch::watch, "ashvehicles-freeze-watch");
            watcher.setDaemon(true);
            watcher.start();
        }
    }

    private static void watch() {
        long reportedBegan = 0L;
        long reportedAt = 0L;

        while (true) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                return;
            }

            Thread thread = serverThread;
            MinecraftServer running = server;
            long began = tickBegan;

            if (thread == null || running == null || began == 0L || !thread.isAlive()) {
                continue;
            }

            long stuck = (System.nanoTime() - began) / MILLIS;

            if (stuck < FREEZE_MILLIS || running.isPaused() || running.isStopped()) {
                continue;
            }

            // 同じ停止（tick の開始時刻が同じ）は間隔を空けて出し直す。
            if (began == reportedBegan && stuck - reportedAt < AGAIN_MILLIS) {
                continue;
            }

            reportedBegan = began;
            reportedAt = stuck;
            report(thread, stuck, gcMillis() - gcAtTickBegin);
        }
    }

    private static void report(Thread thread, long stuck, long gc) {
        StackTraceElement[] stack = thread.getStackTrace();
        StringBuilder line = new StringBuilder();
        int shown = 0;

        for (StackTraceElement frame : stack) {
            if (plumbing(frame)) {
                continue;
            }

            if (shown++ >= FRAMES) {
                break;
            }

            if (shown > 1) {
                line.append(" < ");
            }

            String owner = frame.getClassName();

            line.append(owner.substring(owner.lastIndexOf('.') + 1)).append('.')
                    .append(frame.getMethodName()).append(':').append(frame.getLineNumber());
        }

        AshVehicles.LOGGER.warn("[freeze] サーバースレッドが {} ms 止まっている（その間の GC {} ms、状態 {}）: {}",
                stuck, gc, thread.getState(), line);
    }

    /**
     * 待ちの配管か。名前にしても「chunk を待っている」以上のことが分からないフレームは飛ばす。
     *
     * <p><b>これが無いと肝心の答えが落ちる。</b> 2026-09-06 の最初の実測では、14 フレームが
     * {@code Unsafe.park} から {@code Level.getChunk} までの停止機構と、c2me と mixinextras が
     * 挟んだブリッジ（{@code mixinextras$bridge$...}、{@code wrapOperation$cdh000$c2me_base$...}）で
     * 使い切られ、<em>誰が地面を訊いたのか</em>が 1 行にも残らなかった。知りたいのはその外側だけである。
     * {@code ChunkStalls.plumbing} と同じ考え方で、あちらは責任者を 1 人選び、こちらは連鎖を残す。
     */
    private static boolean plumbing(StackTraceElement frame) {
        String owner = frame.getClassName();
        String method = frame.getMethodName();

        return owner.startsWith("java.")
                || owner.startsWith("jdk.")
                || owner.startsWith("sun.")
                || owner.startsWith("com.ishland.c2me.")
                || owner.equals("net.minecraft.util.thread.BlockableEventLoop")
                || owner.startsWith("net.minecraft.server.level.ServerChunkCache")
                || owner.equals("net.minecraft.world.level.Level")
                // 他 MOD の mixin が挟むブリッジ。クラス名は素のままなので、メソッド名で見分ける。
                || method.contains("mixinextras$")
                || method.contains("$cdh")
                || method.startsWith("wrapOperation$")
                || method.startsWith("wrapMethod$")
                || method.startsWith("redirect$");
    }

    /** 全コレクタの累計 GC 時間（ミリ秒）。 */
    private static long gcMillis() {
        long total = 0L;

        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = collector.getCollectionTime();

            if (time > 0L) {
                total += time;
            }
        }

        return total;
    }
}
