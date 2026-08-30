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
 * <p>コストは1頂点につき仮想呼び出し1段。ゴーストは1フレーム最大256体で、通常は十数体だ。
 */
public final class PlainVertices implements VertexConsumer {
    private final VertexConsumer target;

    public PlainVertices(VertexConsumer target) {
        this.target = target;
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
