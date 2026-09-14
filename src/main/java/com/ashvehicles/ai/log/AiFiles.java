package com.ashvehicles.ai.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.ai.AiConfig;

import net.neoforged.fml.loading.FMLPaths;

/**
 * 戦闘 AI のファイル。記録・統計・版の置き場所と、書き出し。
 *
 * <pre>
 * &lt;ゲームフォルダ&gt;/ashvehicles_ai/
 *   versions/&lt;版&gt;.json          AI の版（方針の種類とパラメータ）
 *   champion.txt                 採用版の名前
 *   battles/&lt;戦闘&gt;/meta.json     会場・陣営と版・特徴量の並び
 *   battles/&lt;戦闘&gt;/events.jsonl  出来事（報酬を含む）
 *   battles/&lt;戦闘&gt;/decisions.jsonl 判断（状態・行動・次の判断までの報酬）
 *   battles/&lt;戦闘&gt;/flights.jsonl   航空機の AI の飛び方（1秒ごと）
 *   battles/&lt;戦闘&gt;/summary.json  結果
 *   training/battles.jsonl       戦闘1回1行
 *   training/lives.jsonl         車両1両の一生1行
 *   stats/&lt;版&gt;.json             版ごとの累計
 * </pre>
 *
 * <p><b>書き出しはサーバーのスレッドでやらない。</b> ディスクは遅いときに遅い——試合の最中に1行の書き込みが
 * 数十 ms 止まれば、それは AI がサーバーを止めたのと同じだ。書く物は文字列にしてから、ここの1本の書き手スレッドへ
 * 渡す。書き手はワールドにもエンティティにも触らない（渡されるのは出来上がった文字列だけ）。
 *
 * <p>読み込み（版と統計）はサーバーの起動時とコマンドからだけで、小さなファイルなのでその場で読む。
 */
public final class AiFiles {
    /** これだけ書いたらまとめてディスクへ流す（行）。 */
    private static final int FLUSH_EVERY = 64;

    @FunctionalInterface
    private interface Job {
        void run() throws IOException;
    }

    @Nullable
    private static ExecutorService writer;

    /** 開いているファイル。書き手スレッドだけが触る。 */
    private static final Map<Path, BufferedWriter> OPEN = new HashMap<>();
    private static int pending;

    private AiFiles() {
    }

    /** 置き場所の根。 */
    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve(AiConfig.get().logging().directory());
    }

    public static Path battles() {
        return root().resolve("battles");
    }

    public static Path versions() {
        return root().resolve("versions");
    }

    public static Path stats() {
        return root().resolve("stats");
    }

    public static Path training() {
        return root().resolve("training");
    }

    /** 1行足す。書き手スレッドで。 */
    public static void append(Path file, String line) {
        submit(() -> {
            BufferedWriter out = OPEN.get(file);

            if (out == null) {
                Files.createDirectories(file.getParent());
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                OPEN.put(file, out);
            }

            out.write(line);
            out.newLine();

            if (++pending >= FLUSH_EVERY) {
                flushOpen();
            }
        });
    }

    /** ファイルを丸ごと置き換える。書き手スレッドで、途中の姿を読まれないように一時ファイルから移す。 */
    public static void replace(Path file, String content) {
        submit(() -> writeAtomically(file, content));
    }

    /**
     * ファイルを丸ごと置き換える。<b>中身の文字列も書き手スレッドで作る</b>——大きな物の文字列化でサーバーを
     * 止めないように。渡す物は世界にもエンティティにも触れない写しであること。
     */
    public static void replace(Path file, Supplier<String> content) {
        submit(() -> writeAtomically(file, content.get()));
    }

    /** そのファイルを閉じる。戦闘が終わったとき。 */
    public static void closeFile(Path file) {
        submit(() -> {
            BufferedWriter out = OPEN.remove(file);

            if (out != null) {
                out.close();
            }
        });
    }

    /** 溜まっている行をディスクへ流す。 */
    public static void flush() {
        submit(AiFiles::flushOpen);
    }

    /** 全部書き終えて、書き手を止める。サーバーが止まるとき。次に書くときにまた作られる。 */
    public static synchronized void close() {
        ExecutorService stopping = writer;

        writer = null;

        if (stopping == null) {
            return;
        }

        stopping.execute(() -> {
            for (BufferedWriter out : OPEN.values()) {
                try {
                    out.close();
                } catch (IOException exception) {
                    AshVehicles.LOGGER.warn("[ai] could not close a log file", exception);
                }
            }

            OPEN.clear();
            pending = 0;
        });
        stopping.shutdown();

        try {
            if (!stopping.awaitTermination(5, TimeUnit.SECONDS)) {
                AshVehicles.LOGGER.warn("[ai] log writer did not finish within 5 seconds");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 小さなファイルをその場で読む。無ければ null。 */
    @Nullable
    public static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException exception) {
            AshVehicles.LOGGER.warn("[ai] could not read {}", file, exception);

            return null;
        }
    }

    /** 小さなファイルをその場で書く。コマンドから、結果をその場で答えたいとき。 */
    public static boolean writeNow(Path file, String content) {
        try {
            writeAtomically(file, content);

            return true;
        } catch (IOException exception) {
            AshVehicles.LOGGER.warn("[ai] could not write {}", file, exception);

            return false;
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void flushOpen() throws IOException {
        for (BufferedWriter out : OPEN.values()) {
            out.flush();
        }

        pending = 0;
    }

    private static synchronized void submit(Job job) {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "AshVehicles AI log writer");

                thread.setDaemon(true);

                return thread;
            });
        }

        writer.execute(() -> {
            try {
                job.run();
            } catch (IOException exception) {
                AshVehicles.LOGGER.warn("[ai] could not write AI log", exception);
            }
        });
    }
}
