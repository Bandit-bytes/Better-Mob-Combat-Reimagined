package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.api.MobAnimationAccess;
import bmc_re.better_mob_combat.logic.MobAttackSelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vindicator;

public final class IllagerArmOwnership {
    private IllagerArmOwnership() {
    }

    public static boolean owns(LivingEntity entity) {
        return entity instanceof MobAnimationAccess access && access.bmc$isArmAnimationActive();
    }

    public static boolean suppressesVanillaAttack(LivingEntity entity) {
        return owns(entity)
                || entity instanceof Vindicator vindicator
                && vindicator.isAggressive()
                && MobAttackSelector.hasCombatWeapon(vindicator);
    }
}
