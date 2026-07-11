package bmc_re.better_mob_combat.mixin;

import bmc_re.better_mob_combat.logic.MobCombatLogic;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MeleeAttackGoal.class)
public abstract class MeleeAttackGoalMixin extends Goal {
    @Shadow @Final protected PathfinderMob mob;
    @Shadow private int ticksUntilNextAttack;

    @Inject(method = "resetAttackCooldown", at = @At("HEAD"), cancellable = true)
    private void bmc$useWeaponAttackInterval(CallbackInfo ci) {
        if (MobCombatLogic.isEligible(this.mob)) {
            this.ticksUntilNextAttack = this.adjustedTickDelay(MobCombatLogic.attackIntervalForCurrentWeapon(this.mob));
            ci.cancel();
        }
    }

}
