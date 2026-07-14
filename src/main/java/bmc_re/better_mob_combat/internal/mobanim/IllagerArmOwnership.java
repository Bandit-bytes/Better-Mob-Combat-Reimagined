package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.api.MobAnimationAccess;
import bmc_re.better_mob_combat.logic.MobAttackSelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vindicator;

/** Keeps the Vindicator arm model, Fresh Animations and held-item layer on one ownership clock. */
public final class IllagerArmOwnership {
    private IllagerArmOwnership() {
    }

    /** True while Player Animator has an authored Better Combat attack or weapon-body pose. */
    public static boolean owns(LivingEntity entity) {
        return entity instanceof MobAnimationAccess access && access.bmc$isArmAnimationActive();
    }

    /**
     * Vindicators report the vanilla ATTACKING arm pose for the entire time their melee goal is
     * aggressive, not just for the real attack frame. With an attributed Better Combat weapon that
     * produces an unrelated early axe swing (and, under Fresh Animations, a permanently raised
     * off-hand) during BMC's commitment delay. Suppress that Vindicator-only pose and let BMC's
     * synchronized packet be the sole visible attack.
     */
    public static boolean suppressesVanillaAttack(LivingEntity entity) {
        return owns(entity)
                || entity instanceof Vindicator vindicator
                && vindicator.isAggressive()
                && MobAttackSelector.hasCombatWeapon(vindicator);
    }
}
