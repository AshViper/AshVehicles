package com.ashvehicles;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

// サンプルの設定クラス。必須ではないが、設定をまとめておく置き場としてあると良い。
// Neo の設定 API の使い方の見本。
// バスは指定しない。NeoForge 21.1 以降、行き先はイベントの型が決める——IModBusEvent を継ぐ物（ここでは
// ModConfigEvent）は MOD バスへ、それ以外はゲームバスへ自動で載る。bus() は残っているが値は無視される。
@EventBusSubscriber(modid = AshVehicles.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // アイテムのリソースロケーションとして扱う文字列のリスト
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    /**
     * プレイヤーを、そのプレイヤーの地面を誰もロードしていない距離まで送り続ける範囲（ブロック）。
     *
     * <p>これはクライアントの設定ではなくサーバーの設定だ。決めているのは「何を描くか」ではなく
     * 「何を送るか」であり、代償——プレイヤー1人につき、範囲内の相手1人あたり毎tick 1個の位置パケット
     * ——を払うのはサーバーだからだ。人数の多いサーバーでは人数の2乗で効いてくるので、0 にすれば
     * この扱いは丸ごと無くなり、プレイヤーはバニラ通り、描画距離の縁で消える。
     *
     * <p>既定の512はプレイヤー型が元から要求している追跡距離（32チャンク）と同じ。バニラがそこまで
     * 送らない理由は距離そのものではなく、相手の描画距離とチャンクのロード状況で頭打ちにするからで、
     * 外しているのはその2つだけである。{@code EntityTrackingMixin} 参照。
     */
    public static final ModConfigSpec.IntValue PLAYER_GHOST_RANGE = BUILDER
            .comment("How far, in blocks, players are sent to each other regardless of render distance or",
                    "which chunks are loaded. This is what lets another player still be drawn once the ground",
                    "they stand on is no longer being sent. 0 turns it off and players vanish at the edge of",
                    "the receiving player's world, as they do in vanilla.",
                    "The cost is one position packet per tick per pair of players within range, so this grows",
                    "with the square of how many players are near each other.")
            .defineInRange("playerGhostRange", 512, 0, 8192);

    static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * {@link #PLAYER_GHOST_RANGE} を読み込み時に写した物。
     *
     * <p>設定オブジェクトを直に読まないのは、これを問うのが毎tick、追跡中のエンティティとプレイヤーの
     * 組ごとだからだ。加えて設定が読み込まれる前に問われても答えられる——その時点で
     * {@code ConfigValue.get()} は例外を投げる。
     */
    private static int playerGhostRange = 512;

    /** プレイヤーを送り続ける距離（ブロック）。0 ならこの扱いは無効。 */
    public static int playerGhostRange() {
        return playerGhostRange;
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent.Loading event) {
        refresh(event);
    }

    @SubscribeEvent
    static void onReload(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    private static void refresh(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            playerGhostRange = PLAYER_GHOST_RANGE.getAsInt();
        }
    }

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
