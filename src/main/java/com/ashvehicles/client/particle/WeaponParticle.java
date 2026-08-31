package com.ashvehicles.client.particle;

import com.ashvehicles.particle.TintedParticleOption;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * MOD が描く全パーティクルの共通点。色は生成元のオプションから来ること、ロード範囲外でも見えること、そして
 * 深度を残さないこと。
 *
 * <p>これらが存在する理由は照明だ。バニラはパーティクルが何に照らされているかを世界へ問うが、下のチャンクが
 * 未ロードなら世界の答えは0になる——つまりそこのパーティクルは単に暗いのではなく、真っ黒に描かれる。ロード範囲
 * の外の爆発は、見ている者には空中に現れた煤の染みに見える。よって下にチャンクが無い物は、実際の姿——開けた空、
 * あるいは自身の炎——として照らす。
 *
 * <p>それでも変更が必要だった理由は深度だ。{@link #NO_DEPTH_WRITE} 参照。
 */
public abstract class WeaponParticle extends TextureSheetParticle {
    /** 空の光が最大でブロック光は0。高高度の煙を照らしているのはそれだ。 */
    private static final int OPEN_AIR = LightTexture.pack(0, 15);

    /**
     * バニラの半透明パーティクルシートから、この MOD が共存できない1点を取り除いた物。深度を書き込まない。
     *
     * <p>{@link ParticleRenderType#PARTICLE_SHEET_TRANSLUCENT} は {@code RenderSystem.depthMask(true)} で始まる。
     * つまり煙の塊は背後の物にブレンドされる<em>と同時に</em>自分のクアッドを深度バッファへ刻むので、その後により
     * 深い位置に描かれる物は捨てられる——煙で暗くなるのでもブレンドされるのでもなく、単に描かれない。バニラは
     * 気付かない。バニラはパーティクルの後に見る価値のある物を描かないからだ。
     *
     * <p>この MOD は描く。ゴーストパスは {@code RenderLevelStageEvent.Stage.AFTER_PARTICLES} で走るし、そうする
     * ほかない。そこが Distant Horizons のバニラフェードより後の最初のステージであり、それ以前に描かれた物は
     * フェードに塗り潰されてしまう（{@link com.ashvehicles.client.ghost.GhostRenderDispatcher} 参照）。結果、
     * ゴーストパスが描く全機体が MOD 自身のパーティクルに深度で弾かれていた——しかも MOD のパーティクルは、
     * まさに機体に付いて回る物だ。{@code ghostStartDistance} より遠いミサイルは毎tick自分の尾部に飛行機雲と排気を
     * 置き、その背後に隠れていた。バーナーを点けた機体は自分のプルームに隠れ、排気管を覗く角度からは完全に見えな
     * かった。見ている者に見えたのは、発生源の無い煙の筋だけだ。
     *
     * <p>深度書き込みをやめても失う物は無い。これらのパーティクルは今も深度<em>テスト</em>はするので、地面も
     * ゲームが描いた物も従来通り隠してくれる。単に、ゴーストパスが弾かれる相手となる自前の深度を残さないだけだ。
     * パーティクル同士はクリップではなくブレンドするようになり、それは元々煙がそうすべきだった振る舞いだ。
     * {@code ParticleEngine.render} は終了時に {@code depthMask(true)} を戻すので、後で描かれる物がこれを引き継ぐ
     * ことはない。
     */
    private static final ParticleRenderType NO_DEPTH_WRITE = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textureManager) {
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "ashvehicles:particle_sheet_translucent_no_depth";
        }
    };

    /** 灼熱しない粒の {@code hot}。ほとんどの粒はこれで、生まれた時点で既に自分の色をしている。 */
    protected static final int NEVER_GLOWS = 0;
    /**
     * ブロック光の最大値。
     *
     * <p>{@code LightTexture.FULL_BLOCK} ではない。あちらは詰めた後の 240 で、{@code LightTexture.pack} が
     * 受け取るのは詰める前の 0〜15 の方だ。混ぜると16倍ずれる。
     */
    private static final int BRIGHTEST = 15;

    /** 生まれた時の色から本来の色へ移りきる、寿命に対する割合。0なら移らない。 */
    private float coolsBy;
    /** 冷めきったときの色。灼熱する粒だけ、生まれた時の色と別になる。 */
    private float coolRed;
    private float coolGreen;
    private float coolBlue;
    /** そして生まれた時の色。 */
    private float hotRed;
    private float hotGreen;
    private float hotBlue;

    protected WeaponParticle(ClientLevel level, double x, double y, double z, TintedParticleOption options) {
        super(level, x, y, z);
        this.rCol = options.red();
        this.gCol = options.green();
        this.bCol = options.blue();
    }

    /**
     * この粒を「生まれた時は灼熱していて、時間をかけてただの煙になる」物にする。コンストラクタから呼ぶ。
     *
     * <p>核の雲のためにある。あの雲は生まれた時点で内側が灼熱しており、冷めるまでの十数秒は煙ではなく光って
     * いる物で、夜の風景を自分で照らす。そこから煤へ落ちるのは急にではなく、色と明るさが同じ熱量から出て
     * じわじわ移っていく——ある tick で「煙になった」と分かる瞬間が無いことが狙いだ。
     *
     * @param hot 生まれた時の色。{@link #NEVER_GLOWS} なら何もしない
     * @param coolsBy 本来の色へ移りきる、寿命に対する割合
     */
    protected void glowsWhileHot(int hot, float coolsBy) {
        if (hot == NEVER_GLOWS || coolsBy <= 0.0F) {
            return;
        }

        // 今入っている色（＝生成元が指定した色）が冷めきった先。そこへ向かって戻っていく。
        this.coolRed = this.rCol;
        this.coolGreen = this.gCol;
        this.coolBlue = this.bCol;
        this.coolsBy = coolsBy;
        this.hotRed = ((hot >> 16) & 0xFF) / 255.0F;
        this.hotGreen = ((hot >> 8) & 0xFF) / 255.0F;
        this.hotBlue = (hot & 0xFF) / 255.0F;
        this.rCol = this.hotRed;
        this.gCol = this.hotGreen;
        this.bCol = this.hotBlue;
    }

    /** 灼熱して冷める粒かどうか。 */
    protected boolean glows() {
        return this.coolsBy > 0.0F;
    }

    /** まだどれだけ熱いか。1で灼熱、0で完全に冷えている。 */
    protected float heat(float lived) {
        return 1.0F - Mth.clamp(lived / this.coolsBy, 0.0F, 1.0F);
    }

    /**
     * 冷え具合に応じて色を移す。tick から、寿命の進み具合を渡して呼ぶ。
     *
     * <p>移す先は毎回同じなので、灼熱側の色は保持せずここで逆算している——今の色ではなく熱量から引き直すので、
     * 何度呼んでも同じ結果になる。
     */
    protected void coolTowards(float lived) {
        if (!this.glows()) {
            return;
        }

        float heat = this.heat(lived);

        this.rCol = Mth.lerp(heat, this.coolRed, this.hotRed);
        this.gCol = Mth.lerp(heat, this.coolGreen, this.hotGreen);
        this.bCol = Mth.lerp(heat, this.coolBlue, this.hotBlue);
    }

    /**
     * 冷めきるまでは自前の光源。灼熱する粒の {@code getLightColor} から、世界が返した明るさを渡して呼ぶ。
     *
     * <p>切り替えではなく混ぜる。ブロック光の成分だけを、周囲の値から最大まで熱量ぶん持ち上げる——空の光は
     * 触らないので、昼の雲は昼のまま内側だけが明るくなる。閾値で切り替えると、その tick で雲が一斉に暗く
     * なるのが見えてしまう。
     */
    protected int litWhileHot(int lit, float lived) {
        if (!this.glows()) {
            return lit;
        }

        float heat = this.heat(lived);

        if (heat <= 0.0F) {
            return lit;
        }

        int block = Math.round(Mth.lerp(heat, LightTexture.block(lit), BRIGHTEST));

        return LightTexture.pack(block, LightTexture.sky(lit));
    }

    @Override
    protected int getLightColor(float partialTick) {
        return this.level.hasChunkAt(BlockPos.containing(this.x, this.y, this.z))
                ? super.getLightColor(partialTick)
                : this.lightBeyondTheWorld();
    }

    /** 問い合わせる世界が下に無いときの照らされ方。 */
    protected int lightBeyondTheWorld() {
        return OPEN_AIR;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return NO_DEPTH_WRITE;
    }
}
