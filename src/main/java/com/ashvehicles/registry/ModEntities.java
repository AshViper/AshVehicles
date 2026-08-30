package com.ashvehicles.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ashvehicles.vehicle.VehicleChassis;
import com.ashvehicles.data.Definitions;
import com.ashvehicles.AshVehicles;
import com.ashvehicles.aircraft.AircraftDefinition;
import com.ashvehicles.entity.AircraftEntity;
import com.ashvehicles.entity.BulletEntity;
import com.ashvehicles.entity.CountermeasureEntity;
import com.ashvehicles.entity.DesignationEntity;
import com.ashvehicles.entity.GroundVehicleEntity;
import com.ashvehicles.entity.RocketEntity;
import com.ashvehicles.entity.TargetDroneEntity;
import com.ashvehicles.vehicle.GroundVehicleDefinition;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 機体ファイル1つにつきエンティティ型1つ。ここに機体名は一切書かれておらず、一覧は
 * {@link Definitions} が MOD のリソースから見つけたものがそのまま並ぶ。
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, AshVehicles.MODID);

    private static final Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> AIRCRAFT =
            registerAll();

    private static final Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>>
            VEHICLES = registerVehicles();

    /**
     * 機銃の弾1発。小さく速く短命なので遠くまで追跡し毎tick更新する。3tickに1度しか動かない曳光弾は
     * ただの点線になる。
     *
     * <p>追跡距離は兵装の射程に合わせる。機銃の射程は800〜1400ブロックあり、既定の16 chunk（256ブロック）
     * では弾が飛び切るはるか手前——撃った本人からたった256ブロック——でサーバーが報告をやめる。曳光は空中
     * で消え、遠くの目標へ吸い込まれていく様子は誰にも見えない。当たり判定はサーバーが持っているので、
     * これは見え方だけの話であり、そして遠距離射撃で見えていなければ困る物はまさに曳光だ。40 chunk
     * （640ブロック）は {@code VehicleProjectile.RENDER_RANGE} の内側で、そこから先は
     * {@code BulletGhostAdapter} のゴーストが引き継ぐ。代償は1発あたりパケット数個
     * （{@code updateInterval} 参照）。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BulletEntity>> BULLET =
            ENTITY_TYPES.register("bullet",
                    () -> EntityType.Builder.<BulletEntity>of(BulletEntity::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(40)
                            // 更新は稀に、速度は送らない。どちらも送る方が害になる。速度パケットは
                            // これほど速い物を表現できず真値の1/10に丸めるし、1tick古い位置は40ブロック
                            // の飛びになる。クライアントには本当の速度を同期データで一度だけ伝え、
                            // あとは自前で飛ばす。たまに来る位置は補正ではなく答え合わせ。
                            // VehicleProjectile 参照。
                            .updateInterval(20)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("bullet"));

    /**
     * ロケットまたはミサイル。弾より大きく遅く、着弾まで見る価値があるので追跡距離は弾より長い。
     * {@link com.ashvehicles.mixin.EntityTrackingMixin} が描画距離による頭打ちを外すため、この値が
     * そのまま実際の追跡距離になる。
     *
     * <p>そしてこの値が、煙がどこで途切れるかを決めている。航跡を置くのはクライアント側の実体だけ
     * （{@code VehicleProjectile.spawnTrail}）で、受信が止まればゴーストが機体を描き続けても煙は止まる。
     * 32 chunk は512ブロック——1tickに68ブロック出すミサイルなら発射から8tickだ。撃った本人が、自分の
     * ミサイルの煙が目の前で途切れるのを見ることになる。128 chunk（2048ブロック）はその種の交戦がまるごと
     * 収まる距離で、代償は飛翔中のミサイル1発につき5tickに1個の位置パケットしかない。空にいるミサイルは
     * 元々数発だ。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<RocketEntity>> ROCKET =
            ENTITY_TYPES.register("rocket",
                    () -> EntityType.Builder.<RocketEntity>of(RocketEntity::new, MobCategory.MISC)
                            .sized(0.4F, 0.4F)
                            .clientTrackingRange(128)
                            // 弾より高頻度。理由は弾の側に書いた通りで、ミサイルは誘導するため、
                            // 両者が「何を狙っているか」で一致している間しか結果も一致しない。
                            .updateInterval(5)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("rocket"));

    /**
     * フレアまたはチャフ。小さく短命だが、追ってくるミサイルと同じ距離から見えるべきもの。それが
     * 見せ場そのものなので。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<CountermeasureEntity>> COUNTERMEASURE =
            ENTITY_TYPES.register("countermeasure",
                    () -> EntityType.Builder.<CountermeasureEntity>of(CountermeasureEntity::new, MobCategory.MISC)
                            .sized(0.3F, 0.3F)
                            .clientTrackingRange(32)
                            // 一度投げたら落ちるに任せる。機体の速度を分けてもらって射出されるのに
                            // 生成パケットは1tick 3.9ブロックまでしか運べないので補正は頻繁。ただし
                            // 十分遅いので、補正は高速弾のようなテレポートではなく普通の相対移動で済む。
                            .updateInterval(2)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("countermeasure"));

    /**
     * 照準ポッドが地上に置いている光点。動かず、何も描かれず、レーザー誘導兵器が追う相手となる
     * エンティティが要るというだけの存在。{@link DesignationEntity} 参照。
     *
     * <p>地平線まで追跡し、位置補正はしない。置いたらそこに留まるだけだから。高高度から落とした爆弾は
     * 着弾する頃には通常の追跡距離のはるか外におり、マークを忘れたクライアントは「何も無い所へ落ちて
     * いく爆弾」を見ることになる。
     *
     * <p>距離はポッドの射程＋α。マークが置き手の機体から離れうる最大がそこだから。そしてその機体こそ
     * マークを見失ってはいけない唯一のクライアントで、コックピットで「今何を掴んでいるか」を示すもの
     * は全部、世界にマークの位置を訊きに行く。この数字を意味あるものにしているのが追跡側の細工で、
     * マークの下の地面はクライアントで決してロードされないため、バニラは距離をいくつにしようが送信を
     * 拒む。{@link com.ashvehicles.mixin.EntityTrackingMixin} 参照。何も描かれず、地面に降りる間しか
     * 動かないエンティティ1個なら送っても安い。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<DesignationEntity>> DESIGNATION =
            ENTITY_TYPES.register("designation",
                    () -> EntityType.Builder.<DesignationEntity>of(DesignationEntity::new, MobCategory.MISC)
                            .sized(0.2F, 0.2F)
                            .clientTrackingRange(144)
                            .updateInterval(20)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("designation"));

    /**
     * 標的ドローン。ソロでシーカーや近接信管を試すための、周回し続ける的。
     *
     * <p>追跡距離はミサイルと同じ理由で同じ値。的はミサイルより遠くからロックされ、飛んでいくミサイルと
     * 同じ画面に映り続ける必要がある。描画距離による頭打ちは {@code EntityTrackingMixin} が外す。
     *
     * <p>更新はミサイルと同じ間隔で、速度は送らない。円は両側が同じ式で計算するので
     * （{@code TargetDroneEntity} 参照）、たまに届く位置は補正ではなく答え合わせ。
     */
    public static final DeferredHolder<EntityType<?>, EntityType<TargetDroneEntity>> TARGET_DRONE =
            ENTITY_TYPES.register("target_drone",
                    () -> EntityType.Builder.<TargetDroneEntity>of(TargetDroneEntity::new, MobCategory.MISC)
                            .sized(2.2F, 1.0F)
                            .clientTrackingRange(128)
                            .updateInterval(5)
                            .setShouldReceiveVelocityUpdates(false)
                            .build("target_drone"));

    private static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> registerAll() {
        Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> types = new LinkedHashMap<>();

        Definitions.AIRCRAFT.builtIn().forEach((id, definition) -> types.put(id, register(id, definition)));

        return Collections.unmodifiableMap(types);
    }

    private static DeferredHolder<EntityType<?>, EntityType<AircraftEntity>> register(
            ResourceLocation id, AircraftDefinition definition) {
        VehicleChassis.Hitbox hitbox = definition.hitbox();

        // Minecraft の当たり判定は底面が正方形の箱なので、機体のシルエットには決して合わない。
        // ファイルの値は胴体と翼根を覆い、外翼ははみ出させる寸法。大きさはここで固定でデータパックから
        // は変えられない。エンティティ型は登録された瞬間からこの値を持ち歩くため。
        return typesFor(id).register(id.getPath(),
                () -> EntityType.Builder.<AircraftEntity>of(AircraftEntity::new, MobCategory.MISC)
                        .sized(hitbox.width(), hitbox.height())
                        .clientTrackingRange(hitbox.trackingRange())
                        .updateInterval(1)
                        .setShouldReceiveVelocityUpdates(true)
                        .build(id.getPath()));
    }

    private static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>>
            registerVehicles() {
        Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>> types =
                new LinkedHashMap<>();

        Definitions.VEHICLES.builtIn().forEach((id, definition) -> {
            if (AIRCRAFT.containsKey(id)) {
                AshVehicles.LOGGER.error("Ground vehicle {} shares its name with an aircraft; it is skipped", id);

                return;
            }

            types.put(id, registerVehicle(id, definition));
        });

        return Collections.unmodifiableMap(types);
    }

    /**
     * 地上車両ファイル1つにつきエンティティ型1つ。条件は機体と同じで、大きさはここで固定、データ
     * パックからは変えられない（登録時から型が持つ値なので）。
     *
     * <p>毎tick更新し速度も載せる。理由は機体と同じで、車体を計算しているのは運転している者であり、
     * それ以外の全員は車体をカクつきではなく滑らかに動くものとして描けなければならない。
     */
    private static DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>> registerVehicle(
            ResourceLocation id, GroundVehicleDefinition definition) {
        VehicleChassis.Hitbox hitbox = definition.hitbox();

        return typesFor(id).register(id.getPath(),
                () -> EntityType.Builder.<GroundVehicleEntity>of(GroundVehicleEntity::new, MobCategory.MISC)
                        .sized(hitbox.width(), hitbox.height())
                        .clientTrackingRange(hitbox.trackingRange())
                        .updateInterval(1)
                        .setShouldReceiveVelocityUpdates(true)
                        .build(id.getPath()));
    }

    /**
     * その ID を登録すべきレジスタ。MOD 本体の物はここ自身の物、コンテンツパックの物はその名前空間の物。
     *
     * <p>{@link DeferredRegister} は名前空間を1つしか持てないので、{@code mypack:foo} という機体は
     * {@code mypack} のレジスタからしか登録できない。{@link ModRegisters} 参照。
     */
    private static DeferredRegister<EntityType<?>> typesFor(ResourceLocation id) {
        return AshVehicles.MODID.equals(id.getNamespace())
                ? ENTITY_TYPES
                : ModRegisters.entities(id.getNamespace());
    }

    /** 登録済みの全機体（ID順）。 */
    public static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<AircraftEntity>>> aircraft() {
        return AIRCRAFT;
    }

    /** 登録済みの全地上車両（ID順）。 */
    public static Map<ResourceLocation, DeferredHolder<EntityType<?>, EntityType<GroundVehicleEntity>>> vehicles() {
        return VEHICLES;
    }

    private ModEntities() {
    }
}
