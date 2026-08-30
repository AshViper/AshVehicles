package com.ashvehicles.entity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.ashvehicles.AshVehicles;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 機体が乗っている人間を殺さないようにする。
 *
 * <p>ここで防ぐ物に「誰かが誰かを撃った」結果は1つも無い。全部、機体が「動く大きな物体」であるというだけ
 * で自分の乗員に与えてしまうダメージだ。Minecraft にはその概念が無いので、知っている唯一の方法で計上する
 * ——空中に立っている人間として、壁の中にいる人間として、たった今とても長く落ちた人間として。
 *
 * <p><b>3種類ある。</b>
 *
 * <p><em>乗っている場合。</em> 座席とは固体の中の場所であり、「何かの中にある位置」に対してゲームがする
 * ことの半分は、そこにいる者を傷つけることだ。通り過ぎざまに斜面を貫いたコックピットはパイロットを窒息
 * させ、変な場所に置かれた機体は乗員を圧殺する。機体に括り付けられた者に起きることは何一つ当人のせいでは
 * ないので、何一つ当人に請求しない。乗っている間、ダメージを受けるのは機体であり、機体の数百ポイントの
 * 耐久はそのためにある。
 *
 * <p><em>上に立っている場合。</em> 甲板とはゲームに見えない床だ。その上にいる者は {@link Hitboxes#carry}
 * が運ぶが、下方向へ運ばれることは、どれだけ丁寧に運んでも「落下」として記帳される——あちらの注記参照。
 * 距離は支払われるのではなくそこで捨てられている。ここは第2の防衛線で、機体に接している者に届いてしまった
 * 落下は機体のせいとして帳消しにする。動く壁がもたれかかっている者を傷つける他の3つの経路も同じ。それ以外
 * の落下は今も着地する。翼の上に立つことは鎧ではない。
 *
 * <p><em>降りた直後の場合。</em> この MOD で最も確実な死因は、高高度でスニークキーに触れることと、撃墜
 * された物に乗っていたことだった。座席が手を離し、続く落下は誰も選んでいない落下になる。機体が誰かを放り
 * 出してから数秒間、最初の着地は無料。
 */
@EventBusSubscriber(modid = AshVehicles.MODID)
public final class CrewSafety {
    /**
     * 機体が誰かを手放してから、続く落下をまだ機体のせいと見なす時間（tick）。
     *
     * <p>10秒。落下距離にして約400ブロックなので、この機体群が飛ぶどの高度からの脱出も覆う。継続時間では
     * なく上限であり、猶予は最初の着地で消費される。だから「10秒間崖から飛び降り放題」にはならない。
     */
    private static final long BAILOUT_GRACE = 200L;

    /**
     * 各自が最後に機体から放り出された時刻。
     *
     * <p>弱参照にしてあるので、ログアウトや死亡した者は自分のエントリを連れて消える。同期しているのは、
     * エンティティが機体を降りるのが両側で起き、各側が自分のスレッドで tick するから。
     */
    private static final Map<Entity, Long> BAILED = Collections.synchronizedMap(new WeakHashMap<>());

    private CrewSafety() {
    }

    /**
     * 機体に乗っている者、あるいは機体が接している者に対してこれから行われること全部。
     *
     * <p>乗っている場合は全部を止める。乗員は降りるまでゲームの手の届かない場所におり、機体がどうなるかは
     * 機体の問題だ。接しているだけの場合は「動く障害物が接触相手を傷つける数通り」だけを止める。翼は立つ
     * 場所であって免罪符ではないから。
     *
     * <p>そもそもダメージではない2つの経路——奈落と {@code /kill}——は一切妨げない。世界の底を抜けて飛んだ
     * パイロットは他の誰とも同じくそれで死ぬし、誰にも消せない機体は「乗員を殺す機体」より良くはならない。
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity crew = event.getEntity();
        DamageSource source = event.getSource();

        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }

        if (aboard(crew) || (isContact(source) && Hitboxes.touching(crew))) {
            event.setCanceled(true);
        }
    }

    /**
     * 機体の助けを借りて到達した着地。
     *
     * <p>減らすのではなく取り消す。背後にある距離は誰かが落ちた距離ではないからだ。降下した甲板であり、
     * 手を離した座席であり、誰かを縁から押し出した船体であって、そこに「正直に請求できる何分の一か」は無い。
     *
     * <p>脱出の猶予が消費されるのもここ。着地は機体が始めた落下の終わりであり、その後に起きることは再び
     * プレイヤー自身の物になる。
     */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        LivingEntity faller = event.getEntity();

        if (aboard(faller) || Hitboxes.touching(faller) || bailedOut(faller)) {
            BAILED.remove(faller);
            event.setCanceled(true);
        }
    }

    /**
     * 機体が誰かを手放した瞬間を記録し、再び乗せた瞬間に忘れる。
     *
     * <p>乗り込みも降車もここを通り、対象の機体は乗り降りされている物そのもの。それ以外——馬、ボート、
     * トロッコ——はここと無関係。
     */
    @SubscribeEvent
    public static void onMountChange(EntityMountEvent event) {
        if (!(event.getEntityBeingMounted() instanceof VehicleEntityBase) || event.getLevel().isClientSide) {
            return;
        }

        Entity crew = event.getEntityMounting();

        if (event.isDismounting()) {
            BAILED.put(crew, event.getLevel().getGameTime());
        } else {
            BAILED.remove(crew);
        }
    }

    /** この MOD の機体に乗っているか。連結している物のどの席でも該当する。 */
    private static boolean aboard(Entity crew) {
        return crew.isPassenger() && crew.getRootVehicle() instanceof VehicleEntityBase;
    }

    /** 最近機体に放り出され、まだ着地していないか。 */
    private static boolean bailedOut(Entity crew) {
        Long left = BAILED.get(crew);

        if (left == null) {
            return false;
        }

        long since = crew.level().getGameTime() - left;

        if (since >= 0L && since <= BAILOUT_GRACE) {
            return true;
        }

        BAILED.remove(crew);

        return false;
    }

    /**
     * 動く物に接していることで傷つく経路。その上に着地する／それに下へ運ばれる、それに壁へ押し付けられる、
     * それに突っ込まれる、それに押し詰められる。
     *
     * <p>翼の上に立っている者から見れば4つとも同じ出来事——機体が動き、自分がその進路にいた——であり、
     * そこからプレイヤーにできることは1つも無い。
     */
    private static boolean isContact(DamageSource source) {
        return source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypes.IN_WALL)
                || source.is(DamageTypes.FLY_INTO_WALL)
                || source.is(DamageTypes.CRAMMING);
    }
}
