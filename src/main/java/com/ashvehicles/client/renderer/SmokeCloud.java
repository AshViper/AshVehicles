package com.ashvehicles.client.renderer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.client.MotorLight;
import com.ashvehicles.client.ghost.GhostRenderDispatcher;
import com.ashvehicles.client.ghost.dh.DHFog;
import com.ashvehicles.client.particle.ParticleReach;
import com.ashvehicles.client.particle.SmokeParticle;
import com.ashvehicles.particle.TintedParticleOption;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * パーティクルを使わない煙と火。クライアントだけの飾りで、同期も保存もしない。
 *
 * <p>バニラのパーティクルでは煙の塊が作れなかった。総数に上限があり、テクスチャの板は重なると「点の集まり」に
 * 見える。ここでは玉を自分で持ち、毎 tick 動かして、カメラを向いた柔らかい円として描く。円は中心が濃く縁が
 * 透明な扇でテクスチャを持たないので、重なった玉は1つの塊に溶ける。
 *
 * <p>玉は3種類。煙（{@link Kind#SMOKE}）は奥から順に通常の半透明で描き、炎（{@link Kind#FLAME}）と火球
 * （{@link Kind#BLAST}）は自ら光るので加算で、煙の後に描く。煙の中の火が煙越しに光って見える。
 *
 * <p><b>サーバーは何も変わらない。</b> 爆発・残骸・排気・航跡は今まで通り粒子を送ってくる。クライアントの受け口
 * （{@link #smokeProvider} など）がパーティクルを作らずにここへ玉を足し、null を返す。挙動の数値も元の
 * パーティクル（{@link SmokeParticle.Shape} など）のまま。
 *
 * <p>遠方面より遠い玉の引き寄せと霧は {@link ParticleReach} の取り決めをそのまま使う。機体の煙が機体と同じ
 * 比率で引き寄せられないと、遠くの機体から煙が離れて漂う。
 *
 * <p>深度は読むが書かない。煙同士も、後から描く半透明の物も互いを消さない。
 *
 * <p>重なりの見え方で4つ手を入れてある（2026-11-03）。円は玉の手前へ寄せて地面や機体に切られにくくし、
 * 並べ替えは視線方向の奥行きで行い明るさは時間で均してちらつきを抑え、円の断面は尖らない釣鐘形にし、
 * 混んだ所は薄めて塊が硬くならないようにした。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class SmokeCloud {
    /** 同時に持つ玉の上限。超えたら古い物から捨てる。 */
    private static final int MAX = 8000;
    /** 円の縁の分割数。 */
    private static final int SIDES = 14;
    /**
     * 円の濃さの断面。中心からの距離（半径に対する割合）と、そこでの濃さ。{@code (1 - t²)²} を4点で写したもの。
     *
     * <p>以前は中心から縁へ直線で薄めていた。直線の円錐は頂点が尖っているので、重なった玉は溶けずに白い点や
     * 多角形の模様になった。この形は中心で平らで、縁へ滑らかに落ちる。
     */
    private static final float[] RING = {0.0F, 0.4F, 0.7F, 1.0F};
    private static final float[] RING_ALPHA = {1.0F, 0.7056F, 0.2601F, 0.0F};
    /**
     * 円をカメラへ寄せる量。半径に対する割合。円は玉の中心を通る平面なので、地面や機体の近くでは半分が
     * 埋まり、そこに真っ直ぐな切り口が出る。手前へ寄せて同じ画面上の大きさに縮めれば、玉の奥にある物は
     * 切り口を作らない。
     */
    private static final float FORWARD = 1.0F;
    /** 混み具合を数える格子の一辺（ブロック）。 */
    private static final double CROWD_CELL = 2.0;
    /**
     * 混んだ所の薄め方。同じ格子に n 個の玉があれば濃さを {@code 1 / (1 + (n - 1) × この値)} にする。
     * 半透明を何十枚も重ねると不透明の塊になり、その縁が硬い線になる。
     */
    private static final float CROWDING = 0.12F;
    /** 明るさが新しい値へ寄る割合（1 tick あたり）。ブロックの境を越えた玉が瞬時に明滅しないように。 */
    private static final float LIGHT_EASE = 0.25F;
    /** 太陽へ向かって数える格子のマス数。 */
    private static final int SHADOW_STEPS = 6;
    /** 太陽との間の1マスにある玉1つの光学的厚み。 */
    private static final float SHADOW_PER_PUFF = 0.35F;
    /** 炎1つが同じマスの煙に与える光の強さと、その上限。 */
    private static final float FIRE_GAIN = 0.35F;
    private static final float FIRE_MOST = 2.5F;
    /** 炎と火球の光る強さ。積算の画面は浮動小数なので 1 を超えてよく、厚い所は白く飛ぶ。 */
    private static final float FLAME_GLOW = 2.4F;
    /** 太陽への向き（ワールド）。tick ごとに引き直す。 */
    private static final Vector3f SUN = new Vector3f(0.0F, 1.0F, 0.0F);
    /** 板の半幅に対する円の半径。柔らかい縁の分だけ大きく描いて、元の粒と同じ濃さの広がりにする。 */
    private static final float DISC = 1.15F;
    /** 世界が答えないところ（ロード済みの外）の明るさ。空の光が最大でブロック光は0。 */
    private static final int OPEN_AIR = LightTexture.pack(0, 15);

    /** 炎の寿命と、根元の白から炎の色へ移る割合。{@code FlameParticle} の値。 */
    private static final int FLAME_LIFE = 14;
    private static final int FLAME_JITTER = 12;
    private static final float FLAME_TAPER = 0.2F;
    private static final float FLAME_HOT_FOR = 0.35F;
    /** 火球。{@code BlastParticle} の値。 */
    private static final int BLAST_LIFE = 9;
    private static final int BLAST_JITTER = 6;
    private static final float BLAST_OPENS_AT = 0.35F;

    private static final ArrayDeque<Puff> PUFFS = new ArrayDeque<>();

    // onTick の作業表。中身は毎 tick 捨てるが、器は使い回す。
    private static final Long2IntOpenHashMap CROWD = new Long2IntOpenHashMap();
    private static final Long2FloatOpenHashMap FLAMES = new Long2FloatOpenHashMap();
    /** ブロックごとの「中に埋まるか」。0 は未問、1 は否、2 は是。 */
    private static final Long2ByteOpenHashMap BURIED = new Long2ByteOpenHashMap();
    /** ブロックごとの光。-1 は未問（詰めた光の値は負にならない）。 */
    private static final Long2IntOpenHashMap LIGHT = new Long2IntOpenHashMap();
    /** マスごとの火の照り返し。NaN は未計算。 */
    private static final Long2FloatOpenHashMap FIRE = new Long2FloatOpenHashMap();

    static {
        LIGHT.defaultReturnValue(-1);
        FIRE.defaultReturnValue(Float.NaN);
    }

    /** 煙の円盤の扇の向き。毎フレーム玉ごとに 28 回引いていた三角関数を1度だけ。 */
    private static final float[] SIDE_COS = new float[SIDES + 1];
    private static final float[] SIDE_SIN = new float[SIDES + 1];

    static {
        for (int i = 0; i <= SIDES; i++) {
            float a = Mth.TWO_PI * i / SIDES;
            SIDE_COS[i] = Mth.cos(a);
            SIDE_SIN[i] = Mth.sin(a);
        }
    }
    private static ClientLevel seenLevel;

    public enum Kind {
        SMOKE, FLAME, BLAST
    }

    private static final class Puff {
        final Kind kind;
        double x, y, z, xo, yo, zo;
        double vx, vy, vz;
        final double windX, windZ;
        final float size;
        final float growth;
        final float friction;
        final float gravity;
        final float opacity;
        final float red, green, blue;
        /** 生まれた時の色。0なら光らない（煙だけが使う）。 */
        final int hot;
        final float cools;
        final int life;
        int age;
        /** 世界の明るさ。0〜1。tick ごとに引き直す。 */
        float light = -1.0F;
        /** 太陽との間に他の玉が置いた光学的厚み。tick ごとに格子から数え、時間で均す。 */
        float shadow;
        /** 近くの火から受ける光の強さ。 */
        float fire;
        /** 空が見えている割合（0〜1）。屋根の下の煙に日は当たらない。 */
        float open = 1.0F;
        /** 縁の模様の種。 */
        final float seed;
        /** 混み具合による濃さの係数。tick ごとに数え直す。 */
        float thin = 1.0F;
        /** 並べ替えの鍵。視線方向の奥行き。 */
        double depth;
        /** カメラからの距離の2乗。遠方の引き寄せに使う。 */
        double distance;

        Puff(Kind kind, double x, double y, double z, double vx, double vy, double vz, double windX, double windZ,
                float size, float growth, float friction, float gravity, float opacity, int colour, int hot,
                float cools, int life) {
            this.kind = kind;
            this.x = this.xo = x;
            this.y = this.yo = y;
            this.z = this.zo = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.windX = windX;
            this.windZ = windZ;
            this.size = size;
            this.growth = growth;
            this.friction = friction;
            this.gravity = gravity;
            this.opacity = opacity;
            this.red = ((colour >> 16) & 0xFF) / 255.0F;
            this.green = ((colour >> 8) & 0xFF) / 255.0F;
            this.blue = (colour & 0xFF) / 255.0F;
            this.hot = hot;
            this.cools = cools;
            this.life = Math.max(life, 1);
            this.seed = (float) Math.floorMod((long) (x * 7919.0 + y * 104729.0 + z * 1299709.0), 1000L) * 0.37F;
        }

        float lived(float partial) {
            return Mth.clamp((this.age + partial) / this.life, 0.0F, 1.0F);
        }

        float radius(float lived) {
            return DISC * this.size * switch (this.kind) {
                case SMOKE -> 1.0F + (this.growth - 1.0F) * lived;
                case FLAME -> 1.0F - (1.0F - FLAME_TAPER) * lived;
                // 速く開いてから保つ。平方根が成長のほぼ全てを最初の2〜3tickへ寄せる。
                case BLAST -> BLAST_OPENS_AT + (1.0F - BLAST_OPENS_AT) * Mth.sqrt(lived);
            };
        }
    }

    private SmokeCloud() {
    }

    /**
     * 煙の玉を1つ足す。数値の意味は {@link SmokeParticle.Shape} と同じ。
     *
     * @param velocity 出た時の速度（ブロック/tick）
     * @param size 出た時の半幅（ブロック）
     * @param growth 寿命の終わりで出た時の何倍になるか
     * @param friction 毎 tick 残る速度の割合
     * @param gravity 落ちる速さ。昇る煙では負
     * @param life 寿命（tick）
     * @param opacity 一番濃い時の不透明度
     */
    public static void smoke(Vec3 at, Vec3 velocity, float size, float growth, float friction, float gravity,
            int life, float opacity, int colour) {
        add(new Puff(Kind.SMOKE, at.x, at.y, at.z, velocity.x, velocity.y, velocity.z, 0.0, 0.0, size, growth,
                friction, gravity, opacity, colour, 0, 0.0F, life));
    }

    private static void add(Puff puff) {
        if (PUFFS.size() >= MAX) {
            PUFFS.pollFirst();
        }

        PUFFS.addLast(puff);
    }

    /** 煙のパーティクルの受け口。パーティクルを作らずに玉を足す。 */
    public static ParticleProvider<TintedParticleOption> smokeProvider(SmokeParticle.Shape shape) {
        return (options, level, x, y, z, xd, yd, zd) -> {
            RandomSource random = level.random;
            boolean wind = shape.windborne();

            add(new Puff(Kind.SMOKE, x, y, z, wind ? 0.0 : xd, yd, wind ? 0.0 : zd, wind ? xd : 0.0,
                    wind ? zd : 0.0, shape.size() * options.scale() * (0.8F + random.nextFloat() * 0.4F),
                    shape.growth(), shape.friction(), shape.gravity(), shape.opacity(), options.colour(),
                    shape.hot(), shape.cools(), shape.life() + random.nextInt(Math.max(shape.lifeJitter(), 1))));

            return null;
        };
    }

    /** 炎のパーティクルの受け口。 */
    public static ParticleProvider<TintedParticleOption> flameProvider() {
        return (options, level, x, y, z, xd, yd, zd) -> {
            RandomSource random = level.random;

            // 負の重力なので昇る。炎は熱気であり、熱気の行く所へ行く。
            add(new Puff(Kind.FLAME, x, y, z, xd, yd, zd, 0.0, 0.0,
                    options.scale() * (0.7F + random.nextFloat() * 0.6F), 1.0F, 0.91F, -0.055F, 1.0F,
                    options.colour(), 0, 0.0F, FLAME_LIFE + random.nextInt(FLAME_JITTER)));

            return null;
        };
    }

    /** 火球のパーティクルの受け口。 */
    public static ParticleProvider<TintedParticleOption> blastProvider() {
        return (options, level, x, y, z, xd, yd, zd) -> {
            RandomSource random = level.random;

            add(new Puff(Kind.BLAST, x, y, z, xd, yd, zd, 0.0, 0.0,
                    options.scale() * (0.85F + random.nextFloat() * 0.3F), 1.0F, 0.82F, -0.05F, 1.0F,
                    options.colour(), 0, 0.0F, BLAST_LIFE + random.nextInt(BLAST_JITTER)));

            return null;
        };
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;

        if (level != seenLevel) {
            PUFFS.clear();
            seenLevel = level;
        }

        if (level == null || minecraft.isPaused() || PUFFS.isEmpty()) {
            return;
        }

        float sky = level.getSkyDarken(1.0F);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        // 表は tick をまたいで使い回す。玉は数千あり、毎 tick 作り直すと配列の確保だけで数百KB になる。
        Long2IntOpenHashMap crowd = CROWD;
        Long2FloatOpenHashMap flames = FLAMES;
        crowd.clear();
        flames.clear();
        BURIED.clear();
        LIGHT.clear();
        FIRE.clear();
        float angle = level.getSunAngle(1.0F);
        SUN.set(-Mth.sin(angle), Mth.cos(angle), 0.0F);

        if (SUN.y < 0.0F) {
            // 夜は月。向きは反対側で、明るさは end の側で落とす。
            SUN.negate();
        }

        PUFFS.removeIf(puff -> {
            if (++puff.age >= puff.life) {
                return true;
            }

            puff.xo = puff.x;
            puff.yo = puff.y;
            puff.zo = puff.z;

            // バニラの Particle.tick と同じ順序。重力、移動、摩擦。
            puff.vy -= 0.04 * puff.gravity;
            puff.x += puff.vx + puff.windX;
            puff.y += puff.vy;
            puff.z += puff.vz + puff.windZ;
            puff.vx *= puff.friction;
            puff.vy *= puff.friction;
            puff.vz *= puff.friction;

            // 水や地面の中の玉は表面で真っ直ぐ切られる（水に落ちた残骸の煙と炎が半円になっていた）。
            // 中に入った玉は上の面まで浮かせ、沈む速さを捨てる。ロード済みの外は訊かない。
            at.set(puff.x, puff.y, puff.z);

            if (buriedAt(level, at)) {
                puff.y = puff.yo = at.getY() + 1.0 + puff.size * 0.5;
                puff.vy = Math.max(puff.vy, 0.0);
            }

            if (puff.kind == Kind.SMOKE) {
                at.set(puff.x, puff.y, puff.z);
                int packed = MotorLight.lit(lightAt(level, at), puff.x, puff.y, puff.z);
                float block = LightTexture.block(packed) / 15.0F;
                float daylight = LightTexture.sky(packed) / 15.0F * sky;
                float light = 0.12F + 0.88F * Math.max(block, daylight);
                puff.light = puff.light < 0.0F ? light : Mth.lerp(LIGHT_EASE, puff.light, light);
                puff.open = Mth.lerp(LIGHT_EASE, puff.open, LightTexture.sky(packed) / 15.0F);
                crowd.addTo(cell(puff), 1);
            } else {
                flames.addTo(cell(puff), 1.0F - (float) puff.age / puff.life);
            }

            return false;
        });

        for (Puff puff : PUFFS) {
            if (puff.kind == Kind.SMOKE) {
                puff.thin = 1.0F / (1.0F + (crowd.get(cell(puff)) - 1) * CROWDING);
                puff.shadow = Mth.lerp(LIGHT_EASE, puff.shadow, shadowAbove(puff, crowd));
                puff.fire = Mth.lerp(0.5F, puff.fire, fireAround(puff, flames));
            }
        }
    }

    /**
     * 同じ tick の中ではワールドは変わらないので、同じブロックへの問いは1回で済む。航跡の玉は1ブロックに
     * 何個も重なって置かれるので、ミサイルが数発飛ぶと玉の数千回の問い合わせが数百回まで縮む。答えは
     * 毎回訊いた時と同じ。
     */
    private static boolean buriedAt(ClientLevel level, BlockPos.MutableBlockPos at) {
        long key = at.asLong();
        byte known = BURIED.get(key);

        if (known != 0) {
            return known == 2;
        }

        boolean answer = level.hasChunkAt(at) && buried(level, at);
        BURIED.put(key, answer ? (byte) 2 : (byte) 1);

        return answer;
    }

    /** そのブロックの光（ロード済みの外は空の下）。{@link #buriedAt} と同じく tick の中で1回だけ訊く。 */
    private static int lightAt(ClientLevel level, BlockPos.MutableBlockPos at) {
        long key = at.asLong();
        int known = LIGHT.get(key);

        if (known != -1) {
            return known;
        }

        int packed = level.hasChunkAt(at) ? LevelRenderer.getLightColor(level, at) : OPEN_AIR;
        LIGHT.put(key, packed);

        return packed;
    }

    private static boolean buried(ClientLevel level, BlockPos at) {
        return !level.getFluidState(at).isEmpty() || !level.getBlockState(at).getCollisionShape(level, at).isEmpty();
    }

    /**
     * 太陽との間にある煙の光学的厚み。太陽へ向かって格子を {@link #SHADOW_STEPS} マス進み、通ったマスの玉の
     * 数を足す。1マスに1つの玉で {@link #SHADOW_PER_PUFF}。煙の雲の下側と奥が暗くなるのはこれ。
     */
    private static float shadowAbove(Puff puff, Long2IntOpenHashMap crowd) {
        float tau = 0.0F;

        for (int k = 1; k <= SHADOW_STEPS; k++) {
            double step = k * CROWD_CELL;
            tau += crowd.get(cell(puff.x + SUN.x * step, puff.y + SUN.y * step, puff.z + SUN.z * step));
        }

        return tau * SHADOW_PER_PUFF;
    }

    /** 周りのマス（3×3×3）の炎と火球の数。火の中の煙が照らされる。 */
    private static float fireAround(Puff puff, Long2FloatOpenHashMap flames) {
        if (flames.isEmpty()) {
            return 0.0F;
        }

        // 答えは玉のいるマスだけで決まるので、マスごとに1回数える。同じマスの玉は 27 回の検索を繰り返さない。
        int cx = Mth.floor(puff.x / CROWD_CELL);
        int cy = Mth.floor(puff.y / CROWD_CELL);
        int cz = Mth.floor(puff.z / CROWD_CELL);
        long home = BlockPos.asLong(cx, cy, cz);
        float known = FIRE.get(home);

        if (!Float.isNaN(known)) {
            return known;
        }

        float sum = 0.0F;

        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (int k = -1; k <= 1; k++) {
                    float near = flames.get(BlockPos.asLong(cx + i, cy + j, cz + k));
                    sum += (i == 0 && j == 0 && k == 0) ? near : near * 0.4F;
                }
            }
        }

        float fire = Math.min(sum * FIRE_GAIN, FIRE_MOST);
        FIRE.put(home, fire);

        return fire;
    }

    private static long cell(double x, double y, double z) {
        return BlockPos.asLong(Mth.floor(x / CROWD_CELL), Mth.floor(y / CROWD_CELL), Mth.floor(z / CROWD_CELL));
    }

    private static long cell(Puff puff) {
        return BlockPos.asLong(Mth.floor(puff.x / CROWD_CELL), Mth.floor(puff.y / CROWD_CELL),
                Mth.floor(puff.z / CROWD_CELL));
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();

        // ゴーストと同じステージ。Iris のシェーダーパックの下では AFTER_PARTICLES に描いた物はパックの合成で
        // 塗り潰されるので、AFTER_LEVEL で行列を立て直して描く（GhostRenderDispatcher の注記参照）。
        if (event.getStage() != GhostRenderDispatcher.passStage() || minecraft.level == null || PUFFS.isEmpty()) {
            return;
        }

        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            pass(event, minecraft, event.getPoseStack().last().pose(), true);

            return;
        }

        Matrix4f projection = RenderSystem.getProjectionMatrix();
        VertexSorting sorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();

        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();

        try {
            // ここでの霧の帯はパックの残りかすで、本当の霧ではない。読まない。
            pass(event, minecraft, new Matrix4f(event.getModelViewMatrix()), false);
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
        }
    }

    private static void pass(RenderLevelStageEvent event, Minecraft minecraft, Matrix4f matrix, boolean fogged) {

        Camera camera = event.getCamera();
        Vec3 eye = camera.getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        List<Puff> smoke = new ArrayList<>(PUFFS.size());
        List<Puff> glow = new ArrayList<>();
        Vector3f look = camera.getLookVector();

        for (Puff puff : PUFFS) {
            double dx = Mth.lerp(partial, puff.xo, puff.x) - eye.x;
            double dy = Mth.lerp(partial, puff.yo, puff.y) - eye.y;
            double dz = Mth.lerp(partial, puff.zo, puff.z) - eye.z;
            puff.distance = dx * dx + dy * dy + dz * dz;
            // 円はどれも画面に平行な板なので、正しい前後は視線方向の奥行きで決まる。距離で並べると、斜めに
            // 見た2枚の前後が実際の重なりと食い違う所で入れ替わり、ちらついた。
            puff.depth = dx * look.x() + dy * look.y() + dz * look.z();
            (puff.kind == Kind.SMOKE ? smoke : glow).add(puff);
        }

        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        boolean volumetric = SmokeVolume.available();

        if (volumetric) {
            // 光の通り道として演算する（SmokeVolume）。シェーダーパックの下でも同じ: Iris は主画面の深度テクスチャを
            // depthtex0 として使い続けるので AFTER_LEVEL でも場面の深度が残っている。霧はパックの物なので足さない。
            // 炎も同じ演算に入れる。光る媒質として積むので、手前の煙が火を隠し、火は周りの煙を照らす。
            SmokeVolume.begin();

            for (Puff puff : smoke) {
                volume(puff, eye, left, up, partial, fogged);
            }

            for (Puff puff : glow) {
                volume(puff, eye, left, up, partial, fogged);
            }

            ClientLevel level = minecraft.level;
            float rain = level.getRainLevel(partial);
            // 日の高さ。地平線の少し上で全量に達する。負なら夜。
            float day = Mth.clamp(Mth.cos(level.getSunAngle(partial)) * 3.0F, -1.0F, 1.0F);
            float sunUp = Math.abs(day);
            // 昼は日、夜は月（暗く青い）。低い日は赤い。雨は日を遮り空を灰にする。
            float strength = (day >= 0.0F ? 1.25F : 0.08F) * Mth.clamp(sunUp, 0.0F, 1.0F) * (1.0F - rain * 0.85F);
            float warm = Mth.clamp(sunUp, 0.0F, 1.0F);
            Vector3f sunColour = day >= 0.0F
                    ? new Vector3f(1.0F, Mth.lerp(warm, 0.55F, 0.95F), Mth.lerp(warm, 0.3F, 0.86F)).mul(strength)
                    : new Vector3f(0.6F, 0.7F, 1.0F).mul(strength);
            Vec3 skyTint = level.getSkyColor(eye, partial);
            Vector3f skyColour = new Vector3f(
                    Mth.lerp(0.55F, 1.0F, (float) skyTint.x),
                    Mth.lerp(0.55F, 1.0F, (float) skyTint.y),
                    Mth.lerp(0.55F, 1.0F, (float) skyTint.z)).mul(0.85F);
            SmokeVolume.end(event.getModelViewMatrix(), event.getProjectionMatrix(), SUN, sunColour, skyColour,
                    new Vector3f(1.0F, 0.5F, 0.18F));

            return;
        }

        // 演算できない環境だけ板の円で描く。
        if (!smoke.isEmpty()) {
            // 奥から。手前の煙が奥の煙を正しく覆う。
            smoke.sort(Comparator.comparingDouble((Puff puff) -> puff.depth).reversed());
            VertexConsumer buffer = buffers.getBuffer(SmokeRenderType.SMOKE);

            for (Puff puff : smoke) {
                draw(buffer, matrix, puff, eye, left, up, partial, fogged);
            }

            buffers.endBatch(SmokeRenderType.SMOKE);
        }

        if (!glow.isEmpty()) {
            VertexConsumer buffer = buffers.getBuffer(SmokeRenderType.GLOW);

            for (Puff puff : glow) {
                draw(buffer, matrix, puff, eye, left, up, partial, fogged);
            }

            buffers.endBatch(SmokeRenderType.GLOW);
        }
    }

    private static void draw(VertexConsumer buffer, Matrix4f matrix, Puff puff, Vec3 eye, Vector3f left,
            Vector3f up, float partial, boolean fogged) {
        float lived = puff.lived(partial);
        float red;
        float green;
        float blue;
        float alpha;

        switch (puff.kind) {
            case FLAME -> {
                // 根元は白、少し上は炎の色、昇るにつれてオレンジから赤へ落ちる。青を最初に、赤を最後に手放す。
                float hot = 1.0F - Mth.clamp(lived / FLAME_HOT_FOR, 0.0F, 1.0F);
                float cool = 1.0F - lived * 0.55F;
                red = Mth.lerp(hot, puff.red, 1.0F) * cool;
                green = Mth.lerp(hot, puff.green, 1.0F) * cool * (1.0F - lived * 0.45F);
                blue = Mth.lerp(hot, puff.blue, 1.0F) * cool * (1.0F - lived * 0.85F);
                alpha = 1.0F - lived * lived * lived;
            }
            case BLAST -> {
                // 起爆の瞬間は白、直後に兵器の色、消える前は暗く。最後に残るのが煤に見えるように。
                float white = (1.0F - lived) * (1.0F - lived);
                float cool = 1.0F - lived * 0.65F;
                red = Mth.lerp(white, puff.red * cool, 1.0F);
                green = Mth.lerp(white, puff.green * cool, 1.0F);
                blue = Mth.lerp(white, puff.blue * cool, 1.0F);
                alpha = 1.0F - lived * lived;
            }
            default -> {
                // 塊である間は濃さを保ち、薄く広がるにつれて手放す。
                alpha = puff.opacity * puff.thin * (1.0F - lived * lived * lived)
                        * Math.min((puff.age + partial) / 2.0F, 1.0F);
                float heat = puff.hot == 0 || puff.cools <= 0.0F ? 0.0F
                        : 1.0F - Mth.clamp(lived / puff.cools, 0.0F, 1.0F);
                float light = Mth.lerp(heat, Math.max(puff.light, 0.0F), 1.0F);
                red = Mth.lerp(heat, puff.red, ((puff.hot >> 16) & 0xFF) / 255.0F) * light;
                green = Mth.lerp(heat, puff.green, ((puff.hot >> 8) & 0xFF) / 255.0F) * light;
                blue = Mth.lerp(heat, puff.blue, (puff.hot & 0xFF) / 255.0F) * light;
            }
        }

        if (alpha <= 0.003F) {
            return;
        }

        double dx = Mth.lerp(partial, puff.xo, puff.x) - eye.x;
        double dy = Mth.lerp(partial, puff.yo, puff.y) - eye.y;
        double dz = Mth.lerp(partial, puff.zo, puff.z) - eye.z;
        double away = Math.sqrt(puff.distance);
        float fog = fogged ? ParticleReach.fog(dx, dz, away) : 0.0F;

        if (fog >= DHFog.OPAQUE || away < 1.0E-3) {
            return;
        }

        // 遠方面より遠ければ視線に沿って手前へ寄せ、同じだけ縮める。同じ画素を覆う。
        double pull = ParticleReach.pull(away);
        float r = puff.radius(lived);

        if (puff.kind != Kind.SMOKE) {
            // 炎と火球は板ではなく立体の殻（AfterburnerPlume と同じ作り）。地面に切られても切り口は球の
            // 曲線で、加算なので並べ替えも要らない。
            alpha *= 1.0F - fog;
            glowBall(buffer, matrix, (float) (dx * pull), (float) (dy * pull), (float) (dz * pull),
                    r * (float) pull, red, green, blue, alpha);

            return;
        }

        // 円を玉の手前の面へ寄せ、画面上の大きさが変わらないよう縮める。カメラを追い越さない。
        double forward = Math.min(r * FORWARD, away * 0.5);
        double near = (away - forward) / away;
        pull *= near;
        float cx = (float) (dx * pull);
        float cy = (float) (dy * pull);
        float cz = (float) (dz * pull);
        r *= (float) pull;
        alpha *= 1.0F - fog;

        for (int i = 0; i < SIDES; i++) {
            float c0 = SIDE_COS[i];
            float s0 = SIDE_SIN[i];
            float c1 = SIDE_COS[i + 1];
            float s1 = SIDE_SIN[i + 1];

            buffer.addVertex(matrix, cx, cy, cz).setColor(red, green, blue, alpha);
            ring(buffer, matrix, cx, cy, cz, left, up, c0, s0, r * RING[1], red, green, blue, alpha * RING_ALPHA[1]);
            ring(buffer, matrix, cx, cy, cz, left, up, c1, s1, r * RING[1], red, green, blue, alpha * RING_ALPHA[1]);

            for (int k = 1; k < RING.length - 1; k++) {
                float ri = r * RING[k];
                float ro = r * RING[k + 1];
                float ai = alpha * RING_ALPHA[k];
                float ao = alpha * RING_ALPHA[k + 1];
                ring(buffer, matrix, cx, cy, cz, left, up, c0, s0, ri, red, green, blue, ai);
                ring(buffer, matrix, cx, cy, cz, left, up, c0, s0, ro, red, green, blue, ao);
                ring(buffer, matrix, cx, cy, cz, left, up, c1, s1, ro, red, green, blue, ao);
                ring(buffer, matrix, cx, cy, cz, left, up, c0, s0, ri, red, green, blue, ai);
                ring(buffer, matrix, cx, cy, cz, left, up, c1, s1, ro, red, green, blue, ao);
                ring(buffer, matrix, cx, cy, cz, left, up, c1, s1, ri, red, green, blue, ai);
            }
        }
    }

    /**
     * 煙の玉1つを {@link SmokeVolume} へ渡す。色と濃さは板の円と同じ式で、濃さを中心を通る視線の不透明度と
     * 読んで減衰係数へ直す。中心を通る視線の密度の積分は {@code 4r/3}。
     */
    private static void volume(Puff puff, Vec3 eye, Vector3f left, Vector3f up, float partial,
            boolean fogged) {
        float lived = puff.lived(partial);
        float alpha;
        float red;
        float green;
        float blue;
        float heat;

        switch (puff.kind) {
            case FLAME -> {
                float hot = 1.0F - Mth.clamp(lived / FLAME_HOT_FOR, 0.0F, 1.0F);
                float cool = 1.0F - lived * 0.55F;
                red = Mth.lerp(hot, puff.red, 1.0F) * cool * FLAME_GLOW;
                green = Mth.lerp(hot, puff.green, 1.0F) * cool * (1.0F - lived * 0.45F) * FLAME_GLOW;
                blue = Mth.lerp(hot, puff.blue, 1.0F) * cool * (1.0F - lived * 0.85F) * FLAME_GLOW;
                alpha = 0.85F * (1.0F - lived * lived * lived);
                heat = 1.0F;
            }
            case BLAST -> {
                float white = (1.0F - lived) * (1.0F - lived);
                float cool = 1.0F - lived * 0.65F;
                red = Mth.lerp(white, puff.red * cool, 1.0F) * FLAME_GLOW;
                green = Mth.lerp(white, puff.green * cool, 1.0F) * FLAME_GLOW;
                blue = Mth.lerp(white, puff.blue * cool, 1.0F) * FLAME_GLOW;
                alpha = 0.9F * (1.0F - lived * lived);
                heat = 1.0F;
            }
            default -> {
                alpha = puff.opacity * (1.0F - lived * lived * lived) * Math.min((puff.age + partial) / 2.0F, 1.0F);
                heat = puff.hot == 0 || puff.cools <= 0.0F ? 0.0F
                        : 1.0F - Mth.clamp(lived / puff.cools, 0.0F, 1.0F);
                red = Mth.lerp(heat, puff.red, ((puff.hot >> 16) & 0xFF) / 255.0F);
                green = Mth.lerp(heat, puff.green, ((puff.hot >> 8) & 0xFF) / 255.0F);
                blue = Mth.lerp(heat, puff.blue, (puff.hot & 0xFF) / 255.0F);
            }
        }

        double dx = Mth.lerp(partial, puff.xo, puff.x) - eye.x;
        double dy = Mth.lerp(partial, puff.yo, puff.y) - eye.y;
        double dz = Mth.lerp(partial, puff.zo, puff.z) - eye.z;
        double away = Math.sqrt(puff.distance);
        float fog = fogged ? ParticleReach.fog(dx, dz, away) : 0.0F;

        if (fog >= DHFog.OPAQUE || alpha <= 0.003F) {
            return;
        }

        alpha = Math.min(alpha * (1.0F - fog), 0.995F);
        double pull = ParticleReach.pull(away);
        // 板の円は柔らかい縁の分だけ DISC 倍に描いていた。球は縁で密度が 0 になるので、その大きさのままでよい。
        float r = puff.radius(lived) * (float) pull;
        float sigma = (float) (-Math.log(1.0F - alpha) / (4.0 * r / 3.0));

        // 空の光の強さは、その場所の明るさ（空とブロックの光）。日は空が見えている所にだけ当たる。
        float ambient = Math.max(puff.light, 0.0F);
        SmokeVolume.puff((float) (dx * pull), (float) (dy * pull), (float) (dz * pull), r, puff.seed, puff.fire,
                red, green, blue, sigma, ambient, puff.shadow, puff.open, heat,
                left.x(), left.y(), left.z(), up.x(), up.y(), up.z());
    }

    /** 緯度方向と経度方向の分割数。 */
    private static final int LATITUDES = 7;
    private static final int LONGITUDES = 12;

    /**
     * 光る玉。外側の包絡と、白に寄った芯の2枚の球殻を加算で重ねる（{@link AfterburnerPlume} の入れ子の殻）。
     *
     * <p>頂点の濃さは面がカメラを向いている度合いに比例させ、輪郭で 0 にする。殻の厚みを視線が通る長さで
     * 近似したもので、球の縁は板の円と同じく柔らかく溶ける。両面を描くので、表と裏で半分ずつ。
     */
    private static void glowBall(VertexConsumer buffer, Matrix4f matrix, float cx, float cy, float cz, float r,
            float red, float green, float blue, float alpha) {
        float length = Mth.sqrt(cx * cx + cy * cy + cz * cz);

        if (length < 1.0E-4F) {
            return;
        }

        float vx = -cx / length;
        float vy = -cy / length;
        float vz = -cz / length;
        shell(buffer, matrix, cx, cy, cz, vx, vy, vz, r, red, green, blue, alpha * 0.5F);
        shell(buffer, matrix, cx, cy, cz, vx, vy, vz, r * 0.55F, Math.min(1.0F, red + 0.25F),
                Math.min(1.0F, green + 0.3F), Math.min(1.0F, blue + 0.3F), alpha * 0.45F);
    }

    private static void shell(VertexConsumer buffer, Matrix4f matrix, float cx, float cy, float cz, float vx,
            float vy, float vz, float r, float red, float green, float blue, float alpha) {
        for (int i = 0; i < LATITUDES; i++) {
            for (int j = 0; j < LONGITUDES; j++) {
                sphere(buffer, matrix, cx, cy, cz, vx, vy, vz, r, i, j, red, green, blue, alpha);
                sphere(buffer, matrix, cx, cy, cz, vx, vy, vz, r, i + 1, j, red, green, blue, alpha);
                sphere(buffer, matrix, cx, cy, cz, vx, vy, vz, r, i + 1, j + 1, red, green, blue, alpha);
                sphere(buffer, matrix, cx, cy, cz, vx, vy, vz, r, i, j, red, green, blue, alpha);
                sphere(buffer, matrix, cx, cy, cz, vx, vy, vz, r, i + 1, j + 1, red, green, blue, alpha);
                sphere(buffer, matrix, cx, cy, cz, vx, vy, vz, r, i, j + 1, red, green, blue, alpha);
            }
        }
    }

    /** 球殻の格子点の法線。緯度 i・経度 j を [i][j][xyz] で。毎フレーム頂点ごとに引いていた三角関数を1度だけ。 */
    private static final float[][][] SPHERE_NORMALS = new float[LATITUDES + 1][LONGITUDES + 1][3];

    static {
        for (int i = 0; i <= LATITUDES; i++) {
            float theta = Mth.PI * i / LATITUDES;
            float sin = Mth.sin(theta);

            for (int j = 0; j <= LONGITUDES; j++) {
                float phi = Mth.TWO_PI * j / LONGITUDES;
                SPHERE_NORMALS[i][j][0] = sin * Mth.cos(phi);
                SPHERE_NORMALS[i][j][1] = Mth.cos(theta);
                SPHERE_NORMALS[i][j][2] = sin * Mth.sin(phi);
            }
        }
    }

    /** 球の上の1点。濃さは法線とカメラへの向きの内積で、輪郭ほど薄い。 */
    private static void sphere(VertexConsumer buffer, Matrix4f matrix, float cx, float cy, float cz, float vx,
            float vy, float vz, float r, int latitude, int longitude, float red, float green, float blue,
            float alpha) {
        float[] normal = SPHERE_NORMALS[latitude][longitude];
        float nx = normal[0];
        float ny = normal[1];
        float nz = normal[2];
        float facing = Math.abs(nx * vx + ny * vy + nz * vz);

        buffer.addVertex(matrix, cx + nx * r, cy + ny * r, cz + nz * r)
                .setColor(red, green, blue, alpha * facing * facing);
    }

    /** 円の上の1点。{@code (c, s)} が方位、{@code radius} が中心からの距離。 */
    private static void ring(VertexConsumer buffer, Matrix4f matrix, float cx, float cy, float cz, Vector3f left,
            Vector3f up, float c, float s, float radius, float red, float green, float blue, float alpha) {
        buffer.addVertex(matrix, cx + (left.x() * c + up.x() * s) * radius, cy + (left.y() * c + up.y() * s) * radius,
                cz + (left.z() * c + up.z() * s) * radius).setColor(red, green, blue, alpha);
    }

    private static final class SmokeRenderType extends RenderType {
        static final RenderType SMOKE = create("ashvehicles_smoke", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.TRIANGLES, 1 << 18, false, false, CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setOutputState(TRANSLUCENT_TARGET)
                        .createCompositeState(false));
        /** 炎と火球。加算なので並べ替えが要らない。 */
        static final RenderType GLOW = create("ashvehicles_smoke_glow", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.TRIANGLES, 1 << 18, false, false, CompositeState.builder()
                        .setShaderState(RENDERTYPE_LIGHTNING_SHADER)
                        .setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setOutputState(TRANSLUCENT_TARGET)
                        .createCompositeState(false));

        private SmokeRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
            super(name, format, mode, size, crumbling, sort, setup, clear);
        }
    }
}
