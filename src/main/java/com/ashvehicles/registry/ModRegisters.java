package com.ashvehicles.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.ashvehicles.AshVehicles;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * MOD 本体以外の名前空間で物を登録するための口。
 *
 * <p>{@link DeferredRegister} は作るときに名前空間を1つ決め、以後その1つでしか登録できない。MOD 本体の
 * 機体は全部 {@code ashvehicles} なのでそれで足りていたが、コンテンツパックは自分の名前で物を出す
 * ——{@code data/mypack/aircraft/foo.json} は {@code mypack:foo} になる——ので、名前空間の数だけ
 * DeferredRegister が要る。ここはその置き場で、初めてその名前空間を見たときに1つ作り、その場で MOD の
 * イベントバスへ繋ぐ。
 *
 * <p><b>{@link #bind} を最初に呼ぶこと。</b> 繋ぐ相手を知る前に作られた DeferredRegister は、誰にも
 * 呼ばれないまま登録の機会を逃す——そして「機体が1機も出てこない」という形でしか表に出ない。MOD の
 * コンストラクタの1行目がそれだ。
 */
public final class ModRegisters {
    private static final Map<String, DeferredRegister<EntityType<?>>> ENTITIES = new LinkedHashMap<>();
    private static final Map<String, DeferredRegister.Items> ITEMS = new LinkedHashMap<>();

    @Nullable
    private static IEventBus bus;

    private ModRegisters() {
    }

    /** 以後ここで作る物を繋ぐ先。MOD の構築時に一度だけ。 */
    public static void bind(IEventBus modEventBus) {
        bus = modEventBus;
    }

    /** その名前空間のエンティティ型レジスタ。{@code ashvehicles} には {@link ModEntities} 自身の物を使う。 */
    public static DeferredRegister<EntityType<?>> entities(String namespace) {
        return ENTITIES.computeIfAbsent(namespace,
                name -> attach(DeferredRegister.create(Registries.ENTITY_TYPE, name)));
    }

    /** その名前空間のアイテムレジスタ。{@code ashvehicles} には {@link ModItems} 自身の物を使う。 */
    public static DeferredRegister.Items items(String namespace) {
        return ITEMS.computeIfAbsent(namespace, name -> attach(DeferredRegister.createItems(name)));
    }

    private static <R extends DeferredRegister<?>> R attach(R register) {
        IEventBus attached = bus;

        if (attached == null) {
            AshVehicles.LOGGER.error("A content pack namespace was registered before the mod bus was known;"
                    + " its contents will not appear");
        } else {
            register.register(attached);
        }

        return register;
    }

    /** ログ用。MOD 本体以外にいくつの名前空間が現れたか。 */
    public static Supplier<Integer> extraNamespaces() {
        return ENTITIES::size;
    }
}
