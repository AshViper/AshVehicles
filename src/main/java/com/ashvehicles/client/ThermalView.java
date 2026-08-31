package com.ashvehicles.client;

import com.ashvehicles.AshVehicles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 砲手が覗いているのは窓ではなく画面だ。ガンカメラの映像を、そこに実際に付いている装置——赤外線センサー——が
 * 出す物に変える。
 *
 * <p><b>なぜ計器だけでは足りないか。</b>{@link GunnerDisplay} が描く十字線と数値は、砲手席の画面の<em>枠</em>
 * でしかない。枠の中身が晴れた昼の色付きの風景のままなら、それは航空機のセンサーではなくキャノピーであり、
 * 砲手が実際にしていること——地面を舐めて熱い物を探すこと——を何一つ助けない。夜になれば真っ暗になる窓で
 * 対地射撃をするなら、そもそも AC-130 の砲手席は成立しない。
 *
 * <p><b>やり方はポストエフェクトの差し込み。</b>ワールドが描き上がり計器が描かれる前——{@code GameRenderer}
 * が {@code postEffect} を処理する所——に1本チェーンを挟む。だから熱くなるのは映像だけで、その上に載る十字線
 * も数値も従来通りの色で読める。バニラがクリーパー視点で使っている仕組みそのものなので、こちらは席を立ったら
 * 掛けた物を外し、外した後にバニラの分を掛け直す義務を負う（{@code checkEntityPostEffect}）。
 *
 * <p><b>温度は持っていないので、色から推し量る。</b>ポストエフェクトが読めるのは画面の色だけで、ブロックが
 * 何度かは誰も知らない。それでも実用になるのは、この世界の色が驚くほど素直に熱と対応するからだ——植生の緑は
 * 冷たく、水と空の青はもっと冷たく、燃えている物の赤橙はどんな白より熱い。輝度を土台にその3つを補正した物が
 * センサーの読みで、式は {@code thermal.fsh} にある。結果として木立は沈み、道と建物と装甲は浮き、燃えている
 * 物は滲みを伴って画面から抜ける。狙う相手が最も明るく光るという、赤外線の一番の効能はそのまま残る。
 *
 * <p><b>白熱と黒熱をキーで入れ替える。</b>実機の極性切り替えであり、計器の {@code WHOT}/{@code BHOT} 表示は
 * これを読んでいる。飾りにしないのは、雪原と砂漠では逆の方が見えるという理由が本当にあるからだ。
 *
 * <p><b>シェーダー本体のコメントは英語で書いてある。</b>GLSL の原文は ASCII であることを求める処理系があり、
 * コメントの中であっても非 ASCII を含むソースをそのまま撥ねる。なぜそう書いたかはこのクラスにあり、あちらには
 * 何をしているかだけを置いてある。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class ThermalView {
    private static final ResourceLocation CHAIN =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "shaders/post/thermal.json");

    /** 白熱表示か。false なら黒熱。 */
    private static boolean whiteHot = true;
    /**
     * チェーンの読み込みに失敗したか。
     *
     * <p>1度でも失敗したら二度と試さない。{@code loadEffect} は例外を飲んで警告を1行吐くだけなので、毎tick
     * 呼び直せば、砲手席に座っている間ずっと毎秒20行のログが出続ける。映像は色付きのままになるが、計器は
     * 従来通り描かれるし、砲は従来通り撃てる。
     */
    private static boolean failed;

    private ThermalView() {
    }

    /** 白熱表示中か。計器の極性表示はここを読む。 */
    public static boolean isWhiteHot() {
        return whiteHot;
    }

    /** 今この画面にセンサー映像が掛かっているか。掛けられなかった場合は false。 */
    public static boolean isShowing() {
        return !failed && chain(Minecraft.getInstance()) != null;
    }

    /**
     * 極性を入れ替える。掛かっている最中なら、掛け直さずにユニフォームだけ差し替える。
     *
     * <p>ユニフォームの値はエフェクト側に残るので、1回書けば以降のフレームもその値で処理される。押すたびに
     * チェーンを組み直せば、シェーダーの再リンクを1フレーム分挟むことになる。
     */
    public static void togglePolarity() {
        whiteHot = !whiteHot;

        PostChain chain = chain(Minecraft.getInstance());

        if (chain != null) {
            chain.setUniform("Polarity", whiteHot ? 1.0F : 0.0F);
        }
    }

    /**
     * 毎tick、映像が今あるべき状態かを確かめる。
     *
     * <p>フレームごとではなくtickごとで足りる。切り替わるのは砲手席に着いた時と離れた時だけで、どちらも
     * 1tick遅れて色が変わることに気付ける速さではない。
     *
     * <p>掛かっているのが自分の物かは名前で見る。バニラは視点エンティティが変わるたび、そして資源の再読み込み
     * のたびに {@code postEffect} を自分の物へ差し替えるので、「1度掛けた」という記憶は当てにならない。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean wanted = GunnerDisplay.manned(minecraft) != com.ashvehicles.weapon.GunStations.NONE;
        boolean mine = chain(minecraft) != null;

        if (wanted == mine) {
            return;
        }

        if (wanted) {
            if (failed) {
                return;
            }

            minecraft.gameRenderer.loadEffect(CHAIN);

            PostChain loaded = chain(minecraft);

            if (loaded == null) {
                failed = true;

                return;
            }

            loaded.setUniform("Polarity", whiteHot ? 1.0F : 0.0F);

            return;
        }

        minecraft.gameRenderer.shutdownEffect();
        // 外しただけでは足りない。ここへ来る前に掛かっていたのがバニラの物だった可能性があり——クリーパーや
        // クモの視点——それを黙って消したまま席を立たせるわけにはいかない。掛け直す判断はバニラ自身に任せる。
        minecraft.gameRenderer.checkEntityPostEffect(minecraft.getCameraEntity());
    }

    /** 今掛かっているのがこのチェーンなら、それ。違う物か何も掛かっていなければ null。 */
    private static PostChain chain(Minecraft minecraft) {
        PostChain current = minecraft.gameRenderer.currentEffect();

        return current != null && current.getName().equals(CHAIN.toString()) ? current : null;
    }
}
