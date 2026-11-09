package bmc_re.better_mob_combat.mixin;

import bmc_re.better_mob_combat.logic.MobCombatLogic;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MeleeAttackGoal.class, priority = 2000)
public abstract class MeleeAttackGoalMixin extends Goal {
    @Shadow @Final protected PathfinderMob mob;

    @ModifyArg(
            method = "resetAttackCooldown",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/ai/goal/MeleeAttackGoal;adjustedTickDelay(I)I"
            ),
            index = 0
    )
    private int bmc$useWeaponAttackInterval(int vanillaInterval) {
        return MobCombatLogic.isEligible(this.mob)
                ? MobCombatLogic.attackIntervalForCurrentWeapon(this.mob)
                : vanillaInterval;
    }

    /**
     * Goals created with {@code followingTargetEvenIfNotSeen = false} (Vindicators, among others)
     * stop as soon as their path is done, which happens the moment they reach the target. Vanilla's
     * instant swing hides that; a Better Combat swing does not. The goal stopped mid-attack, cleared
     * {@code aggressive}, stopped tracking the target and could not restart for 20 ticks, so the
     * mob finished every swing facing wherever it had been walking, and Fresh Animations dropped to
     * its non-aggressive pose. Keep the goal alive while a Better Combat attack is in progress or the
     * target is still inside weapon range; every other vanilla stop condition still applies.
     */
    @Inject(method = "canContinueToUse", at = @At("RETURN"), cancellable = true)
    private void bmc$keepGoalDuringBetterCombatAttack(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && MobCombatLogic.shouldKeepMeleeGoal(this.mob)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Better Combat attacks telegraph for {@code attackStartDelayTicks} plus the upswing before the
     * hit. MeleeAttackGoal kept pathing into the target during that window, so fast mobs
     * (Vindicators run at 0.35) covered the whole gap, ended up inside or behind the target and
     * swung at nothing. Plant the mob while its attack winds up and the target is still inside
     * weapon range; it chases normally again as soon as the target leaves that range.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void bmc$holdPositionDuringWindup(CallbackInfo ci) {
        if (MobCombatLogic.shouldHoldPositionForAttack(this.mob)) {
            this.mob.getNavigation().stop();
        }
    }

    @Redirect(
            method = "checkAndPerformAttack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/PathfinderMob;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"
            )
    )
    private boolean bmc$delayEntireDynamicMeleeAttack(PathfinderMob mob, Entity target) {
        if (MobCombatLogic.interceptGoalMeleeAttack(mob, target)) {
            return false;
        }
        return mob.doHurtTarget(target);
    }
}
