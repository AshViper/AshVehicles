package com.ashvehicles.client.renderer;

import java.nio.FloatBuffer;

import com.ashvehicles.AshVehicles;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * 煙を光の通り道として演算する。玉を板として重ねるのではなく、画素ごとに視線と煙の球の交わりを解き、
 * 視線が通る煙の量を積分して、透過率を Beer–Lambert の法則 {@code T = exp(-τ)} で求める。
 *
 * <p>板を重ねる描き方（{@link SmokeCloud} の円）には、本物の煙に無い3つの性質があった。前後の順で色が変わる
 * （並べ替えが入れ替わるとちらつく）、地面や水の面で真っ直ぐ切れる、重ねた枚数だけ濃くなって硬い塊になる。
 * 光学的厚み τ は足し算なので順序を持たず、透過率は指数なので重なっても滑らかに不透明へ近づくだけで、
 * 視線は場面の深度で止まるので地面に埋まった煙は「そこまでの厚み」になる。3つとも、近似を外したら消える。
 *
 * <p>2段で描く。
 * <ol>
 * <li>積算：浮動小数の画面大のテクスチャへ、玉ごとに τ と τ×色を加算する。玉の密度は中心で最大、表面で 0 の
 *     {@code ρ = 1 - |x - c|² / r²}。視線に沿った積分は解析的に解ける（断片シェーダーの式）。中心を通る視線で
 *     {@code 4r/3} なので、元の玉の「一番濃い時の不透明度」がそのまま中心の不透明度になるよう σ を決める。
 * <li>合成：画面へ {@code 色 = 背景 × T + 平均色 × (1 - T)}。平均色は τ で重み付けた散乱光の色。
 * </ol>
 *
 * <p>シェーダーパックの下でも同じに描く。パックの合成が済んだ後（AFTER_LEVEL）に画面へ直接重ねるので、
 * パックに塗り潰されない。場面の深度は主画面の深度テクスチャで、Iris はこれを depthtex0 として使い続ける。
 * TAA のあるパックでは深度が画素の数分の一だけ揺れるが、煙の縁が地面で僅かに揺らぐだけ。
 * セットアップに失敗した環境だけ {@link SmokeCloud} の板の円へ戻る。
 */
final class SmokeVolume {
    /** 1頂点の浮動小数の数。角(3) 中心(3) 半径・模様の種・火の光・予備(4) 色と σ(4) 陰(4)。 */
    private static final int STRIDE = 18;
    private static final int MAX_PUFFS = 8000;

    private static final String PUFF_VERTEX = """
            #version 150
            in vec3 Corner;
            in vec3 Centre;
            in vec4 Radius;
            in vec4 Medium;
            in vec4 Shade;
            uniform mat4 ModelView;
            uniform mat4 Projection;
            out vec3 vPos;
            flat out vec3 vCentre;
            flat out vec4 vRadius;
            flat out vec4 vMedium;
            flat out vec4 vShade;
            void main() {
                vec4 p = ModelView * vec4(Corner, 1.0);
                vPos = p.xyz;
                vCentre = (ModelView * vec4(Centre, 1.0)).xyz;
                vRadius = Radius;
                vMedium = Medium;
                vShade = Shade;
                gl_Position = Projection * p;
            }
            """;

    /*
     * 断片ごとに演算する物。
     *
     * 1. 視線が球を通る区間（場面の深度で打ち切る）と、その区間の密度の積分 τ。密度は ρ = 1 - |x-c|²/r²。
     * 2. 縁の侵食。球の表面に3次元の雑音を貼り、縁ほど雑音が高い所しか残さない。カリフラワー状の盛り上がり
     *    になり、中心近くは侵されないので塊の芯は残る。
     * 3. その区間の色。
     *    - 太陽光の一次散乱。玉の中の点から太陽へ同じ玉を抜けるまでの τ（自分の影）と、他の玉が太陽との間に
     *      置いた τ（Shade.y、CPU が格子で数える）で減衰する。日の当たる上面が明るく、下と奥が暗い。
     *    - 位相関数は Henyey–Greenstein（g = 0.35）。逆光で縁が光る。
     *    - 空の光は上から来るので玉の上側ほど多く、他の玉に覆われると減る。
     *    - 近くの火の光（Radius.z）。火の中の煙が橙に照らされる。
     *    - 炎と火球（Shade.w = 1）は散乱ではなく自ら光る媒質。同じ τ で積むので、手前の煙に隠される。
     */
    private static final String PUFF_FRAGMENT = """
            #version 150
            uniform sampler2D SceneDepth;
            uniform mat4 InverseProjection;
            uniform vec2 ScreenSize;
            uniform vec3 SunDir;
            uniform vec3 UpDir;
            uniform vec3 SunColour;
            uniform vec3 SkyColour;
            uniform vec3 FireColour;
            in vec3 vPos;
            flat in vec3 vCentre;
            flat in vec4 vRadius;
            flat in vec4 vMedium;
            flat in vec4 vShade;
            out vec4 fragColor;

            float hash(vec3 p) {
                p = fract(p * 0.3183099 + 0.1);
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }

            float noise(vec3 x) {
                vec3 i = floor(x);
                vec3 f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x),
                               mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
                           mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x),
                               mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
            }

            float fbm(vec3 p) {
                return 0.55 * noise(p) + 0.3 * noise(p * 2.03 + 7.1) + 0.15 * noise(p * 4.11 + 3.7);
            }

            void main() {
                float r = vRadius.x;
                vec3 dir = normalize(vPos);
                float tc = dot(vCentre, dir);
                vec3 off = dir * tc - vCentre;
                float b2 = dot(off, off);
                float r2 = r * r;
                if (b2 >= r2) discard;
                float h = sqrt(r2 - b2);
                vec2 uv = gl_FragCoord.xy / ScreenSize;
                float depth = texture(SceneDepth, uv).r;
                vec4 scene = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                float tScene = depth >= 1.0 ? 1.0e9 : length(scene.xyz / scene.w);
                float t0 = max(tc - h, 0.0);
                float t1 = min(tc + h, tScene);
                if (t1 <= t0) discard;
                float s0 = t0 - tc;
                float s1 = t1 - tc;
                float column = (s1 - s0) * (1.0 - b2 / r2) - (s1 * s1 * s1 - s0 * s0 * s0) / (3.0 * r2);

                vec3 surface = normalize(dir * (tc - h) - vCentre);
                float rim = sqrt(b2 / r2);
                float lump = fbm(surface * 2.2 + vRadius.y);
                column *= smoothstep(rim - 0.45, rim + 0.05, lump);
                float sigma = vMedium.a;
                float tau = sigma * column;
                if (tau <= 1.0e-5) discard;

                vec3 p = dir * (0.5 * (t0 + t1)) - vCentre;
                float pr2 = dot(p, p) / r2;
                float ld = dot(p, SunDir);
                float exitSun = -ld + sqrt(max(ld * ld - (dot(p, p) - r2), 0.0));
                float selfTau = sigma * exitSun * (1.0 - 0.5 * pr2) * 0.8;
                float g = 0.35;
                float cosT = dot(SunDir, dir);
                float phase = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosT, 1.5);
                float sun = vShade.z * exp(-(selfTau + vShade.y)) * phase;
                float upness = dot(p, UpDir) / r;
                float ambient = vShade.x * (0.5 + 0.3 * upness) * exp(-0.25 * vShade.y);
                vec3 lit = vMedium.rgb * (SunColour * sun + SkyColour * ambient + FireColour * vRadius.z);
                vec3 colour = mix(lit, vMedium.rgb, vShade.w);
                fragColor = vec4(colour * tau, tau);
            }
            """;

    private static final String COMPOSITE_VERTEX = """
            #version 150
            out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT = """
            #version 150
            uniform sampler2D Accum;
            in vec2 uv;
            out vec4 fragColor;
            void main() {
                vec4 a = texture(Accum, uv);
                if (a.a <= 1.0e-5) discard;
                float t = exp(-a.a);
                fragColor = vec4(a.rgb / a.a * (1.0 - t), t);
            }
            """;

    private static boolean tried;
    private static boolean broken;
    private static int puffProgram;
    private static int compositeProgram;
    private static int vao;
    private static int vbo;
    private static int emptyVao;
    private static int framebuffer;
    private static int texture;
    private static int width;
    private static int height;
    private static FloatBuffer data;
    private static int count;

    private SmokeVolume() {
    }

    /** 使えるか。シェーダーが組めなかった環境では二度と試さない。 */
    static boolean available() {
        if (!tried) {
            tried = true;

            try {
                setUp();
            } catch (RuntimeException e) {
                AshVehicles.LOGGER.warn("Volumetric smoke is unavailable, falling back to discs", e);
                broken = true;
            }
        }

        return !broken;
    }

    static void begin() {
        data.clear();
        count = 0;
    }

    /**
     * 玉1つ。座標はカメラからの相対位置。
     *
     * @param left カメラの左
     * @param up カメラの上
     * @param sigma 中心の密度 1 あたりの減衰（1/ブロック）
     */
    static void puff(float cx, float cy, float cz, float r, float seed, float fire, float red, float green,
            float blue, float sigma, float ambient, float shadow, float sunlit, float heat,
            float lx, float ly, float lz, float ux, float uy, float uz) {
        Medium m = MEDIUM;
        m.r = r;
        m.seed = seed;
        m.fire = fire;
        m.red = red;
        m.green = green;
        m.blue = blue;
        m.sigma = sigma;
        m.ambient = ambient;
        m.shadow = shadow;
        m.sunlit = sunlit;
        m.heat = heat;
        m.cx = cx;
        m.cy = cy;
        m.cz = cz;

        if (count >= MAX_PUFFS) {
            return;
        }

        float d = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);

        if (d < 1.0E-4F) {
            return;
        }

        // 球の輪郭を覆う板。球の手前の面に置き、見かけの半径（視線と接線の角）だけ広げる。カメラが球の
        // 中にいるときはカメラのすぐ前に大きく置く。深度テストはしないので、板が何かに埋まっても欠けない。
        float q = Math.max(d - r, 0.1F);
        float sin = Math.min(r / d, 0.985F);
        float half = q * sin / (float) Math.sqrt(1.0F - sin * sin) * 1.05F;
        float k = q / d;
        // 板は視線（カメラから玉の中心）に垂直に立てる。画面に平行な板では、画面の端の玉ほど輪郭が板の外へ
        // はみ出し、四角い切り口が出た。視線に垂直なら、輪郭の円錐との交わりはちょうど半径 half の円になる。
        float nx = cx / d;
        float ny = cy / d;
        float nz = cz / d;
        // 左 = 上 × 視線、上 = 視線 × 左。カメラの上を手掛かりにし、真上・真下を見る玉ではカメラの左を使う。
        float ax = uy * nz - uz * ny;
        float ay = uz * nx - ux * nz;
        float az = ux * ny - uy * nx;
        float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);

        if (al < 1.0E-3F) {
            ax = lx;
            ay = ly;
            az = lz;
            al = 1.0F;
        }

        lx = ax / al;
        ly = ay / al;
        lz = az / al;
        ux = ny * lz - nz * ly;
        uy = nz * lx - nx * lz;
        uz = nx * ly - ny * lx;
        float px = cx * k;
        float py = cy * k;
        float pz = cz * k;

        corner(px, py, pz, -half, -half, lx, ly, lz, ux, uy, uz, m);
        corner(px, py, pz, half, -half, lx, ly, lz, ux, uy, uz, m);
        corner(px, py, pz, half, half, lx, ly, lz, ux, uy, uz, m);
        corner(px, py, pz, -half, -half, lx, ly, lz, ux, uy, uz, m);
        corner(px, py, pz, half, half, lx, ly, lz, ux, uy, uz, m);
        corner(px, py, pz, -half, half, lx, ly, lz, ux, uy, uz, m);
        count++;
    }

    private static void corner(float px, float py, float pz, float a, float b, float lx, float ly, float lz,
            float ux, float uy, float uz, Medium m) {
        data.put(px + lx * a + ux * b).put(py + ly * a + uy * b).put(pz + lz * a + uz * b)
                .put(m.cx).put(m.cy).put(m.cz).put(m.r).put(m.seed).put(m.fire).put(0.0F)
                .put(m.red).put(m.green).put(m.blue).put(m.sigma)
                .put(m.ambient).put(m.shadow).put(m.sunlit).put(m.heat);
    }

    /** 積んだ玉を演算して画面へ重ねる。 */
    /** 1つの玉の、板の4隅に共通する値。毎回作らずに使い回す。 */
    private static final class Medium {
        float cx, cy, cz, r, seed, fire, red, green, blue, sigma, ambient, shadow, sunlit, heat;
    }

    private static final Medium MEDIUM = new Medium();

    /**
     * @param sun 太陽への向き（ワールド座標、単位長）
     * @param sunColour 太陽光の色と強さ
     * @param skyColour 空の光の色と強さ
     * @param fireColour 火の光の色。玉ごとの強さに掛ける
     */
    static void end(Matrix4f modelView, Matrix4f projection, Vector3f sun, Vector3f sunColour, Vector3f skyColour,
            Vector3f fireColour) {
        if (count == 0) {
            return;
        }

        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        resize(main.width, main.height);

        // 積算。
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._viewport(0, 0, width, height);
        GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);

        GlStateManager._glUseProgram(puffProgram);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrix = stack.mallocFloat(16);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(puffProgram, "ModelView"), false,
                    modelView.get(matrix));
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(puffProgram, "Projection"), false,
                    projection.get(matrix));
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(puffProgram, "InverseProjection"), false,
                    new Matrix4f(projection).invert().get(matrix));
        }
        GL20.glUniform2f(GL20.glGetUniformLocation(puffProgram, "ScreenSize"), width, height);
        Vector3f sunView = modelView.transformDirection(new Vector3f(sun)).normalize();
        Vector3f upView = modelView.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
        GL20.glUniform3f(GL20.glGetUniformLocation(puffProgram, "SunDir"), sunView.x, sunView.y, sunView.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(puffProgram, "UpDir"), upView.x, upView.y, upView.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(puffProgram, "SunColour"), sunColour.x, sunColour.y,
                sunColour.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(puffProgram, "SkyColour"), skyColour.x, skyColour.y,
                skyColour.z);
        GL20.glUniform3f(GL20.glGetUniformLocation(puffProgram, "FireColour"), fireColour.x, fireColour.y,
                fireColour.z);
        GL20.glUniform1i(GL20.glGetUniformLocation(puffProgram, "SceneDepth"), 0);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(main.getDepthTextureId());

        data.flip();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count * 6);

        // 合成。背景 × T + 散乱光。
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        GlStateManager._viewport(0, 0, main.width, main.height);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        GlStateManager._glUseProgram(compositeProgram);
        GL20.glUniform1i(GL20.glGetUniformLocation(compositeProgram, "Accum"), 0);
        GlStateManager._bindTexture(texture);
        GL30.glBindVertexArray(emptyVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        BufferUploader.reset();
        GlStateManager._glUseProgram(0);
        GlStateManager._bindTexture(0);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private static void resize(int w, int h) {
        if (w == width && h == height) {
            return;
        }

        width = w;
        height = h;
        GlStateManager._bindTexture(texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT,
                (FloatBuffer) null);
        GlStateManager._bindTexture(0);
    }

    private static void setUp() {
        puffProgram = program(PUFF_VERTEX, PUFF_FRAGMENT, "Corner", "Centre", "Radius", "Medium", "Shade");
        compositeProgram = program(COMPOSITE_VERTEX, COMPOSITE_FRAGMENT);

        data = MemoryUtil.memAllocFloat(MAX_PUFFS * 6 * STRIDE);
        vao = GL30.glGenVertexArrays();
        emptyVao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        int bytes = STRIDE * Float.BYTES;
        attribute(0, 3, bytes, 0);
        attribute(1, 3, bytes, 3);
        attribute(2, 4, bytes, 6);
        attribute(3, 4, bytes, 10);
        attribute(4, 4, bytes, 14);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        BufferUploader.reset();

        texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, 1, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT,
                (FloatBuffer) null);
        width = 1;
        height = 1;
        framebuffer = GlStateManager.glGenFramebuffers();
        int previous = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
        GlStateManager._bindTexture(0);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Smoke framebuffer incomplete: " + status);
        }
    }

    private static void attribute(int index, int size, int stride, int offset) {
        GL20.glEnableVertexAttribArray(index);
        GL20.glVertexAttribPointer(index, size, GL11.GL_FLOAT, false, stride, (long) offset * Float.BYTES);
    }

    private static int program(String vertex, String fragment, String... attributes) {
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader(GL20.GL_VERTEX_SHADER, vertex));
        GL20.glAttachShader(program, shader(GL20.GL_FRAGMENT_SHADER, fragment));

        for (int i = 0; i < attributes.length; i++) {
            GL20.glBindAttribLocation(program, i, attributes[i]);
        }

        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Smoke shader link failed: " + GL20.glGetProgramInfoLog(program));
        }

        return program;
    }

    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Smoke shader compile failed: " + GL20.glGetShaderInfoLog(shader));
        }

        return shader;
    }
}
