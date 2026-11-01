package com.ashvehicles.entity;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 地上車両が他の機体にぶつかったときの押し合い。サーバーで、地上車両の tick から。
 *
 * <p><b>ぶつかった瞬間はもう止まっている。</b> 車両は他の機体の箱を登れない段差として読み（{@code limitToShape}）、
 * 触れる前の tick で速度を失う。だから衝突は「触れている2台の閉じる速さ」からではなく、「前の tick に走って
 * いた車両が、相手に触れた所で急に止まった」ことから読む。止まった分の運動量を、反発係数を掛けて質量比で
 * 2台に分け直す——軽い車両に突っ込んだ戦車はほとんど止まらずに相手を弾き、戦車に突っ込んだ軽車両は跳ね返る。
 *
 * <p>止まった後も運転手が踏み続けていれば、それは押しだ。駆動力を2台の質量で割った分だけ毎 tick 相手を押す。
 * 重い車両は軽い車両を押しのけ、同じ重さ同士はほとんど動かない。
 *
 * <p>速度はサーバーが知っている値（運転側の報告、{@code DATA_SPEED}）なので、運転しているクライアントの
 * tick とは1つずれる。押しは {@link VehicleEntityBase#shove} で運転側へ届く。
 */
final class VehicleCollisions {
    /** 反発係数。鉄の塊同士の、ほぼ塑性の衝突。 */
    private static final double RESTITUTION = 0.2;
    /** 箱がこれだけ離れていても触れていると読む。止まった車両は相手の手前で止まっているので。 */
    private static final double CONTACT_MARGIN = 0.6;
    /** これより遅い急停止は衝突と読まない（ブロック/tick）。 */
    private static final double IMPACT_SPEED = 0.05;

    private VehicleCollisions() {
    }

    /**
     * @param before 前の tick の車首方向の速度
     */
    static void tick(GroundVehicleEntity self, float before) {
        if (!(self.level() instanceof ServerLevel level) || self.isCargo()) {
            return;
        }

        float now = self.getSpeed();
        double stopped = Math.abs(before) - Math.abs(now);
        boolean impact = stopped > IMPACT_SPEED;
        double drive = self.isWrecked() || self.overturned() ? 0.0 : self.getInput().drive();
        boolean pushing = drive != 0.0 && Math.abs(now) < IMPACT_SPEED;

        if (!impact && !pushing) {
            return;
        }

        Vec3 heading = Vec3.directionFromRotation(0.0F, self.getYRot());
        // 押す向き。前進なら車首、後進なら車尾。
        double sense = impact ? Math.signum(before) : Math.signum(drive);
        Vec3 along = heading.scale(sense);

        VehicleEntityBase other = touching(level, self, along);

        if (other == null) {
            return;
        }

        double m1 = self.mass();
        double m2 = other.mass();
        Vec3 contact = self.position().add(other.position()).scale(0.5)
                .add(0.0, Math.min(self.getBbHeight(), other.getBbHeight()) * 0.4, 0.0);

        if (impact) {
            // 走っていた速さのうち、相手の方へ向かっていた分が閉じる速さ。相手が同じ向きへ逃げていれば小さい。
            double closing = Math.abs(before) - other.getVelocity().dot(along);

            if (closing <= 0.0) {
                return;
            }

            double share = (1.0 + RESTITUTION) * closing / (m1 + m2);
            other.shove(along.scale(share * m1), contact);
            // こちらは既に 0 まで止められている。衝突後の速さ（運動量の残り）へ戻す——軽い相手なら前へ、
            // 重い相手なら跳ね返って後ろへ。
            double after = Math.abs(before) - share * m2;
            self.shove(along.scale(after), contact);
        } else {
            double push = self.getStats().powertrain().acceleration() * Math.abs(drive) * m1 / (m1 + m2);
            other.shove(along.scale(push), contact);
        }
    }

    /** 進む向きの前で触れている機体。積んでいる・積まれている相手は数えない。 */
    private static VehicleEntityBase touching(ServerLevel level, GroundVehicleEntity self, Vec3 along) {
        VehiclePart[] mine = self.getParts();
        AABB reach = self.getBoundingBox().inflate(self.reach() + CONTACT_MARGIN);
        Set<VehicleEntityBase> seen = new HashSet<>();
        VehicleEntityBase best = null;
        double bestAhead = 0.0;

        for (Entity entity : level.getEntities(self, reach)) {
            Entity subject = entity instanceof VehiclePart part ? part.getParent() : entity;

            if (!(subject instanceof VehicleEntityBase other) || other == self || other.isRemoved()
                    || !seen.add(other) || other.getVehicle() == self || self.getVehicle() == other) {
                continue;
            }

            Vec3 toward = other.position().subtract(self.position());
            double ahead = new Vec3(toward.x, 0.0, toward.z).normalize().dot(along);

            // 真横や後ろの相手は、今前へ押している相手ではない。
            if (ahead < 0.3 || !overlaps(mine, self, other)) {
                continue;
            }

            if (best == null || ahead > bestAhead) {
                best = other;
                bestAhead = ahead;
            }
        }

        return best;
    }

    private static boolean overlaps(VehiclePart[] mine, GroundVehicleEntity self, VehicleEntityBase other) {
        VehiclePart[] theirs = other.getParts();

        if (mine.length == 0 || theirs.length == 0) {
            return self.getBoundingBox().inflate(CONTACT_MARGIN).intersects(other.getBoundingBox());
        }

        for (VehiclePart a : mine) {
            AABB box = a.getBoundingBox().inflate(CONTACT_MARGIN);

            for (VehiclePart b : theirs) {
                if (box.intersects(b.getBoundingBox())) {
                    return true;
                }
            }
        }

        return false;
    }
}
