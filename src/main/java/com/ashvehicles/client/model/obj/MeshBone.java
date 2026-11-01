package com.ashvehicles.client.model.obj;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

/**
 * OBJ の1グループから焼いたボーン。形は普通のボーンと同じくキューブ1つの面として持ち、それとは別に頂点ごとの
 * 法線を持つ。
 *
 * <p><b>法線を別に持つ理由。</b>GeckoLib の面（{@link GeoQuad}）が持てる法線は1枚に1本で、立方体の面ならそれで
 * 正しい。曲面を三角形で近似したメッシュを面ごとの法線で照らすと、三角形が1枚ずつ別の明るさになり、胴体が多面体
 * に見える。OBJ の {@code vn} は頂点ごとの向きで、隣り合う面が同じ向きを共有するから曲面が曲面に見える。その差を
 * 捨てないため。
 *
 * <p><b>面は GeckoLib の形のまま持つ。</b>ジオメトリを測る側——{@code BakedGeometry}・履帯・アイコンの構図・
 * 搭載物の寸法——はキューブ→面→頂点を歩くので、OBJ を知らないまま同じ答えを出す。このクラスを知らない
 * レンダラーが描いても、面ごとの法線で平らに照らされるだけで形は正しい。
 */
public final class MeshBone extends GeoBone {
    private final GeoCube mesh;

    /** 面ごとに4頂点×3。{@link #mesh} の面と同じ並び。 */
    private final float[] normals;

    MeshBone(@Nullable GeoBone parent, String name, GeoCube mesh, float[] normals) {
        super(parent, name, null, null, null, null);
        this.mesh = mesh;
        this.normals = normals;
        this.getCubes().add(mesh);
    }

    /**
     * OBJ のボーンなら頂点ごとの法線で描いて true。そうでなければ何もせず false で、呼び出し元は GeckoLib の
     * 描き方へ戻る。各レンダラーの {@code renderCubesOfBone} から呼ぶ。
     *
     * <p>中身は GeckoLib の {@code renderCube} と同じで、違うのは法線の出所だけ。OBJ のキューブは支点も回転も
     * 持たないので、そこは掛けない。
     */
    public static boolean draw(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight,
            int packedOverlay, int colour) {
        if (!(bone instanceof MeshBone mesh)) {
            return false;
        }

        if (bone.isHidden()) {
            return true;
        }

        Matrix4f pose = poseStack.last().pose();
        Matrix3f turn = poseStack.last().normal();
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        float[] normals = mesh.normals;
        int next = 0;

        for (GeoQuad quad : mesh.mesh.quads()) {
            for (GeoVertex vertex : quad.vertices()) {
                Vector3f at = vertex.position();

                pose.transformPosition(at.x(), at.y(), at.z(), position);
                turn.transform(normals[next], normals[next + 1], normals[next + 2], normal);
                next += 3;

                buffer.addVertex(position.x(), position.y(), position.z(), colour, vertex.texU(), vertex.texV(),
                        packedOverlay, packedLight, normal.x(), normal.y(), normal.z());
            }
        }

        return true;
    }
}
