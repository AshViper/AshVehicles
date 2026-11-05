package com.ashvehicles.client;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.VehicleEntityBase;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * MFD の TGP 頁に出すポッドの映像。ワールドを描き直すのではなく、ポッドのレンズから地形へ光線を追って
 * 1画素ずつ組む、白黒のセンサー画像。
 *
 * <p><b>描き直さない理由。</b>最初はワールドをもう1回ポッドの目から {@code renderLevel} していた。Sodium は
 * 見えるセクションの一覧をカメラが動いた時にしか組み直さないので2回目の描画に地形が出ず、Iris はフレームの組み
 * 立てを丸ごと持っているので差し替えが届かない。どちらも同梱の環境にある。光線追跡ならゲームの描画経路に
 * 一切触れないので、どちらの下でも同じ物が出る。
 *
 * <p>地形はクライアントが持つチャンクのハイトマップと最上面のブロックの地図色から、陰影は隣の列の高さから
 * 組む。持っていないチャンクの先は {@link Terrain} と同じく最後に見た高さの平らな床で、格子を入れて推定と
 * 分かるようにする。機体・車両・生き物は地形を描いた後に、深度を比べて重ねる（IR では熱く、TV では暗く）。
 *
 * <p>重さは光線の数で決まるので、TGP 頁が直前に映っていた時だけ、1フレームに {@link #ROWS_PER_FRAME} 行ずつ
 * 走査する。全画面のポッド視界の間は本物の画面が同じ物を映しているので走査しない。
 */
@EventBusSubscriber(modid = AshVehicles.MODID, value = Dist.CLIENT)
public final class PodFeed {
    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(AshVehicles.MODID, "pod_feed");

    static final int WIDTH = 128;
    static final int HEIGHT = 96;
    /** 1フレームに走査する行。全面は {@code HEIGHT / ROWS_PER_FRAME} フレームで描き終わる。 */
    private static final int ROWS_PER_FRAME = 12;
    /** 光線を追う距離（ブロック）。 */
    private static final double REACH = 6000.0;
    /** 大気で霞む距離（ブロック）。この距離で約6割が空の明るさになる。 */
    private static final double HAZE = 2500.0;

    /** 視野（横、度）。広角と狭角。 */
    private static final float[] FIELDS = {8.0F, 2.5F};
    private static final String[] FIELD_NAMES = {"WFOV", "NFOV"};

    enum Mode {
        TV("TV"), WHITE_HOT("WHT"), BLACK_HOT("BHT");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private static int field;
    private static Mode mode = Mode.TV;

    @Nullable
    private static DynamicTexture texture;
    /** 地形の明るさ（0〜1）と、そこまでの距離。機体を重ねる前の物。 */
    private static final float[] shade = new float[WIDTH * HEIGHT];
    private static final float[] depth = new float[WIDTH * HEIGHT];
    private static int row;
    /** 一度でも全面を描き終えたか。 */
    private static boolean complete;
    @Nullable
    private static AircraftEntity shown;
    @Nullable
    private static AircraftEntity requested;
    private static int requestedAt;
    private static int frame;

    // 光線1本の間の、最後に引いたチャンク。列を順に歩くので同じチャンクを何度も引く。
    @Nullable
    private static ChunkAccess chunk;
    private static int chunkX;
    private static int chunkZ;
    private static boolean chunkValid;
    /** 読んだ列の地面高。全面を描き終えるたびに忘れる（地形は変わるし、チャンクは届く）。 */
    private static final Long2DoubleOpenHashMap columns = new Long2DoubleOpenHashMap();

    static {
        columns.defaultReturnValue(Double.NaN);
    }

    private PodFeed() {
    }

    /**
     * TGP 頁が映っている。次のフレームから走査する。描けた映像があれば true——呼んだ側は {@link #TEXTURE} を
     * 貼ってよい。
     */
    static boolean request(AircraftEntity aircraft) {
        requested = aircraft;
        requestedAt = frame;

        return complete && shown == aircraft && texture != null && usable(aircraft);
    }

    /** 映像の縦横比（横 / 縦）。 */
    static float aspect() {
        return (float) WIDTH / HEIGHT;
    }

    /** 今の横の視野（度）。 */
    static float field() {
        return FIELDS[field];
    }

    static String fieldName() {
        return FIELD_NAMES[field];
    }

    static String modeName() {
        return mode.label;
    }

    /** 広角と狭角を切り替える。MFD の FOV。 */
    static void cycleField() {
        field = (field + 1) % FIELDS.length;
    }

    /** TV・白熱・黒熱を順に回す。MFD の MODE。描き終わっている画像はそのまま次の走査で置き換わる。 */
    static void cycleMode() {
        mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        frame++;

        AircraftEntity aircraft = requested;

        if (aircraft == null || frame - requestedAt > 2) {
            requested = null;

            return;
        }

        if (!usable(aircraft) || PodCamera.isShowing()) {
            return;
        }

        if (shown != aircraft) {
            shown = aircraft;
            complete = false;
            row = 0;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        scan(aircraft, partialTick);
        compose(aircraft, partialTick);
    }

    private static boolean usable(AircraftEntity aircraft) {
        Minecraft minecraft = Minecraft.getInstance();

        return minecraft.level != null && minecraft.player != null
                && minecraft.player.getVehicle() == aircraft && PodCamera.carrier() == aircraft
                && !aircraft.isRemoved();
    }

    // ---- 走査 ----

    /** レンズの座標系。前・右・上の単位ベクトルと、画面の端までの tan。 */
    private record Lens(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up, double tanX, double tanY) {
        static Lens of(float partialTick) {
            Vec3 forward = PodCamera.direction(partialTick).normalize();
            Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0));

            // 真下を向いている時は水平線が無いので、機首の方を上にする。
            right = right.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();

            Vec3 up = right.cross(forward).normalize();
            double tanX = Math.tan(Math.toRadians(field() / 2.0));
            double tanY = tanX * HEIGHT / WIDTH;

            return new Lens(PodCamera.eye(partialTick), forward, right, up, tanX, tanY);
        }

        Vec3 ray(double px, double py) {
            double sx = (px / WIDTH * 2.0 - 1.0) * this.tanX;
            double sy = (1.0 - py / HEIGHT * 2.0) * this.tanY;

            return this.forward.add(this.right.scale(sx)).add(this.up.scale(sy)).normalize();
        }
    }

    private static void scan(AircraftEntity aircraft, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        Lens lens = Lens.of(partialTick);
        chunkValid = false;

        // 地上に駐機していると、胴体の下のレンズが地面の列の上面より下に来ることがある。そのままでは全部の光線が
        // 1歩目で「当たり」、映像は足元の色1色になる。
        double under = height(level, Mth.floor(lens.eye().x), Mth.floor(lens.eye().z));

        if (!Double.isNaN(under) && lens.eye().y < under + 0.2) {
            lens = new Lens(new Vec3(lens.eye().x, under + 0.2, lens.eye().z), lens.forward(), lens.right(),
                    lens.up(), lens.tanX(), lens.tanY());
        }

        for (int line = 0; line < ROWS_PER_FRAME; line++) {
            for (int x = 0; x < WIDTH; x++) {
                trace(level, lens.eye(), lens.ray(x + 0.5, row + 0.5), row * WIDTH + x);
            }

            row++;

            if (row >= HEIGHT) {
                row = 0;
                complete = true;
                columns.clear();
            }
        }

        chunk = null;
    }

    /** 光線1本。当たった所の明るさと距離を書く。 */
    private static void trace(ClientLevel level, Vec3 from, Vec3 along, int pixel) {
        double floor = Double.NaN;
        double travelled = 0.5;
        double previous = 0.0;
        int top = level.getMaxBuildHeight();

        while (travelled < REACH) {
            double x = from.x + along.x * travelled;
            double y = from.y + along.y * travelled;
            double z = from.z + along.z * travelled;

            if (y > top && along.y >= 0.0) {
                break;
            }

            double ground = height(level, Mth.floor(x), Mth.floor(z));
            boolean estimated = Double.isNaN(ground);

            if (estimated) {
                ground = Double.isNaN(floor) ? level.getSeaLevel() : floor;
            } else {
                floor = ground;
            }

            double gap = y - ground;

            if (gap <= 0.0) {
                double hit = refine(level, from, along, previous, travelled, ground, estimated);
                Vec3 at = from.add(along.scale(hit));
                shade[pixel] = haze(estimated ? estimated(at) : surface(level, at), hit);
                depth[pixel] = (float) hit;

                return;
            }

            previous = travelled;
            // 地面からの高さに比べて歩く。遠くでは1歩の下限を距離に比べて伸ばす（画素1つより細かく見ても意味が無い）。
            travelled += Math.max(Math.max(gap * 0.8, 0.6), travelled * 0.004);
        }

        shade[pixel] = sky(along);
        depth[pixel] = Float.MAX_VALUE;
    }

    /** 地面を跨いだ1歩を二分して、当たった距離を詰める。 */
    private static double refine(ClientLevel level, Vec3 from, Vec3 along, double above, double below,
            double floor, boolean estimated) {
        for (int i = 0; i < 5; i++) {
            double middle = (above + below) / 2.0;
            double x = from.x + along.x * middle;
            double z = from.z + along.z * middle;
            double ground = estimated ? floor : height(level, Mth.floor(x), Mth.floor(z));

            if (Double.isNaN(ground)) {
                ground = floor;
            }

            if (from.y + along.y * middle <= ground) {
                below = middle;
            } else {
                above = middle;
            }
        }

        return below;
    }

    /**
     * 列の地面高（上面）。チャンクが無ければ NaN。
     *
     * <p>ハイトマップをそのまま信じない。クライアントのチャンクのハイトマップは空のまま（世界の底を返す）の物が
     * あり、それを読むと光線は地面を素通りして遥か下で当たり、映像が霞の灰色1色になった。答えの下のブロックが
     * 空気なら、列をブロックで上から読み直す。列ごとに覚えておき、全面を描き終えるたびに忘れる。
     */
    private static double height(ClientLevel level, int x, int z) {
        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        double known = columns.get(key);

        if (!Double.isNaN(known) || columns.containsKey(key)) {
            return known;
        }

        int cx = x >> 4;
        int cz = z >> 4;

        if (!chunkValid || cx != chunkX || cz != chunkZ) {
            chunk = level.getChunkSource().getChunk(cx, cz, false);
            chunkX = cx;
            chunkZ = cz;
            chunkValid = true;
        }

        double ground = chunk == null ? Double.NaN : column(chunk, x, z);
        columns.put(key, ground);

        return ground;
    }

    private static double column(ChunkAccess chunk, int x, int z) {
        int bottom = chunk.getMinBuildHeight();
        int mapped = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, mapped, z);

        if (mapped > bottom && !chunk.getBlockState(pos).isAir()) {
            return mapped + 1.0;
        }

        int sectionTop = chunk.getHighestFilledSectionIndex();

        if (sectionTop < 0) {
            return bottom;
        }

        for (int y = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionTop)) + 15;
                y >= bottom; y--) {
            pos.setY(y);
            BlockState state = chunk.getBlockState(pos);

            if (!state.isAir() && (state.blocksMotion() || !state.getFluidState().isEmpty())) {
                return y + 1.0;
            }
        }

        return bottom;
    }

    /** 当たった列の明るさ。地図色・斜面の陰影・材質の温度から。 */
    private static float surface(ClientLevel level, Vec3 at) {
        int x = Mth.floor(at.x);
        int z = Mth.floor(at.z);
        double here = height(level, x, z);
        BlockPos pos = BlockPos.containing(at.x, (Double.isNaN(here) ? at.y : here) - 1.0, at.z);
        BlockState state = level.getBlockState(pos);
        boolean water = state.getFluidState().is(FluidTags.WATER);

        double east = height(level, x + 1, z);
        double west = height(level, x - 1, z);
        double south = height(level, x, z + 1);
        double north = height(level, x, z - 1);
        double dx = Double.isNaN(east) || Double.isNaN(west) ? 0.0 : (east - west) / 2.0;
        double dz = Double.isNaN(south) || Double.isNaN(north) ? 0.0 : (south - north) / 2.0;
        // 法線 (-dx, 1, -dz) と、北西の高い所から差す光。
        double length = Math.sqrt(dx * dx + 1.0 + dz * dz);
        float lit = (float) Math.max(0.0, (dx * 0.45 + 0.75 + dz * 0.45) / length / 1.06);

        if (mode == Mode.TV) {
            if (water) {
                return 0.22F + 0.08F * lit;
            }

            int colour = state.getMapColor(level, pos).col;
            float luminance = colour == 0 ? 0.5F
                    : (0.299F * (colour >> 16 & 0xFF) + 0.587F * (colour >> 8 & 0xFF) + 0.114F * (colour & 0xFF))
                            / 255.0F;

            return luminance * (0.4F + 0.6F * lit);
        }

        float heat = water ? 0.16F : warmth(state, level, pos) + 0.2F * lit;

        return mode == Mode.WHITE_HOT ? heat : 1.0F - heat;
    }

    /** 材質のおおよその温度（0〜1）。日なたの岩や砂は暖かく、植物と雪は冷たい。 */
    private static float warmth(BlockState state, ClientLevel level, BlockPos pos) {
        if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CAMPFIRE)) {
            return 1.0F;
        }

        MapColor colour = state.getMapColor(level, pos);

        if (colour == MapColor.SNOW || colour == MapColor.ICE) {
            return 0.08F;
        }

        if (colour == MapColor.PLANT || colour == MapColor.GRASS) {
            return 0.3F;
        }

        if (colour == MapColor.WOOD || colour == MapColor.COLOR_GRAY || colour == MapColor.STONE
                || colour == MapColor.METAL) {
            return 0.42F;
        }

        return 0.38F;
    }

    /** チャンクを持たない所の平らな床。32 ブロックの格子で、推定だと分かるように。 */
    private static float estimated(Vec3 at) {
        boolean line = Math.floorMod(Mth.floor(at.x), 32) < 1 || Math.floorMod(Mth.floor(at.z), 32) < 1;
        float base = mode == Mode.TV ? 0.32F : mode == Mode.WHITE_HOT ? 0.34F : 0.66F;

        return line ? base + (mode == Mode.BLACK_HOT ? -0.1F : 0.1F) : base;
    }

    private static float sky(Vec3 along) {
        return switch (mode) {
            case TV -> 0.7F + 0.2F * (float) Mth.clamp(along.y, 0.0, 1.0);
            case WHITE_HOT -> 0.06F;
            case BLACK_HOT -> 0.94F;
        };
    }

    /** 遠くほど空の明るさへ寄せる。 */
    private static float haze(float value, double distance) {
        float far = (float) (1.0 - Math.exp(-distance / HAZE));
        float sky = mode == Mode.TV ? 0.75F : mode == Mode.WHITE_HOT ? 0.2F : 0.8F;

        return Mth.lerp(far, value, sky);
    }

    // ---- 合成 ----

    /** 地形の上へ機体と生き物を重ねて、テクスチャへ送る。毎フレーム（動いている物は毎フレーム動くので）。 */
    private static void compose(AircraftEntity aircraft, float partialTick) {
        if (!complete) {
            return;
        }

        if (texture == null) {
            texture = new DynamicTexture(WIDTH, HEIGHT, false);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        }

        NativeImage image = texture.getPixels();

        if (image == null) {
            return;
        }

        float[] value = shade.clone();
        float[] near = depth.clone();
        Lens lens = Lens.of(partialTick);

        for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
            if (entity.getRootVehicle() == aircraft || !(entity instanceof VehicleEntityBase
                    || entity instanceof LivingEntity) || entity.getVehicle() instanceof VehicleEntityBase) {
                continue;
            }

            blob(entity, lens, partialTick, value, near);
        }

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int grey = Mth.clamp(Math.round(value[y * WIDTH + x] * 255.0F), 0, 255);
                image.setPixelRGBA(x, y, 0xFF000000 | grey << 16 | grey << 8 | grey);
            }
        }

        texture.upload();
    }

    /** 1つの物を、見えている大きさの円で重ねる。地形の向こうなら描かない。 */
    private static void blob(Entity entity, Lens lens, float partialTick, float[] value, float[] near) {
        Vec3 offset = entity.getPosition(partialTick).add(0.0, entity.getBbHeight() * 0.5, 0.0)
                .subtract(lens.eye());
        double ahead = offset.dot(lens.forward());

        if (ahead < 1.0 || ahead > REACH) {
            return;
        }

        double px = (offset.dot(lens.right()) / ahead / lens.tanX() * 0.5 + 0.5) * WIDTH;
        double py = (0.5 - offset.dot(lens.up()) / ahead / lens.tanY() * 0.5) * HEIGHT;
        double size = Math.max(entity.getBbWidth(), entity.getBbHeight());
        double radius = Math.max(0.7, size / ahead / lens.tanX() * WIDTH * 0.25);

        if (px < -radius || py < -radius || px > WIDTH + radius || py > HEIGHT + radius) {
            return;
        }

        boolean machine = entity instanceof VehicleEntityBase;
        float colour = switch (mode) {
            case TV -> machine ? 0.12F : 0.2F;
            case WHITE_HOT -> machine ? 1.0F : 0.85F;
            case BLACK_HOT -> machine ? 0.0F : 0.15F;
        };

        int x0 = Math.max(0, (int) Math.floor(px - radius));
        int x1 = Math.min(WIDTH - 1, (int) Math.ceil(px + radius));
        int y0 = Math.max(0, (int) Math.floor(py - radius));
        int y1 = Math.min(HEIGHT - 1, (int) Math.ceil(py + radius));

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double ex = x + 0.5 - px;
                double ey = y + 0.5 - py;
                int pixel = y * WIDTH + x;

                // 地面の手前にある時だけ。半分の大きさの猶予は、地面に立つ物の足元が地面に埋もれないため。
                if (ex * ex + ey * ey <= radius * radius && ahead - size < near[pixel]) {
                    value[pixel] = colour;
                    near[pixel] = (float) ahead;
                }
            }
        }
    }
}
