package com.ashvehicles.client.ghost.geo;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 頂点を1つずつ素直に書かせるための覆い。
 *
 * <p>これ自体は何もしない——全呼び出しを包んだバッファへそのまま渡す。存在理由は「何であるか」ではなく
 * 「何で<em>ない</em>か」だ。これは Sodium の {@code BufferBuilder} ではないので、GeckoLib の描画を
 * 乗っ取る最適化 MOD（gbf）が頂点を生バイトで直接流し込む高速経路——{@code VertexBufferWriter.tryOf} と
 * {@code instanceof BufferBuilder} の二重の門——に入れず、必ず1頂点ずつのフォールバックへ落ちる。
 *
 * <p>なぜ落とすか。あの生書き込みはバッファの頂点フォーマット（Sodium の NEW_ENTITY、Iris の拡張
 * エンティティ形式）を見て、通常のエンティティ描画フェーズの状態を前提にバイト列を組む。ゴーストパスは
 * GeckoLib モデルがそのフェーズの<em>外</em>（AFTER_PARTICLES / AFTER_LEVEL）で描かれる唯一の場所で、
 * そこでは前提が成り立たず、機体はそれぞれ別の機体のテクスチャ対応で塗られて出てくる。ライブの機体は
 * 通常フェーズで描かれるので高速経路のままでよく、被害も出ない——だからこの覆いはゴーストパスだけが使う。
 *
 * <p><b>1頂点まるごとの書き込みは、まるごと渡す。</b>{@link VertexConsumer} の11引数の
 * {@code addVertex} は既定では位置・色・UV・オーバーレイ・光・法線の6回の呼び出しに割られ、覆いを挟むと
 * その6回がそれぞれもう1段の仮想呼び出しになる。包んだ {@code BufferBuilder} はその11引数版を自前で
 * 持っていて、フォーマットが許せば1回で詰める——バニラの {@code fastFormat}、Sodium・Iris の書き換えも
 * そこに乗る。遠くの機体はほぼ全部ゴーストパスを通るので、AI を並べた試合では1フレームに数十万頂点が
 * ここを通る。まるごと渡してもバッファ自身が自分のフォーマットで書くので、gbf の門は閉じたままであり、
 * この覆いの目的は損なわれない。
 */
public final class PlainVertices implements VertexConsumer {
    private final VertexConsumer target;

    public PlainVertices(VertexConsumer target) {
        this.target = target;
    }

    /** GeckoLib（と gbf のフォールバック・MeshBone）が1頂点ごとに呼ぶ入口。包んだ側の一括版へそのまま。 */
    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int packedOverlay,
            int packedLight, float normalX, float normalY, float normalZ) {
        this.target.addVertex(x, y, z, color, u, v, packedOverlay, packedLight, normalX, normalY, normalZ);
    }

    /** 包んだ側ではなく自分を返す。連鎖の途中で生のバッファが呼び出し元の手に渡らないように。 */
    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        this.target.addVertex(x, y, z);

        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        this.target.setColor(red, green, blue, alpha);

        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.target.setUv(u, v);

        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        this.target.setUv1(u, v);

        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.target.setUv2(u, v);

        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.target.setNormal(x, y, z);

        return this;
    }
}
