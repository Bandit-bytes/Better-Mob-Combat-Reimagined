package bmc_re.better_mob_combat.mixin;

import bmc_re.better_mob_combat.config.BMCConfig;
import bmc_re.better_mob_combat.logic.MobCombatLogic;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

@Mixin(value = MeleeAttackGoal.class, priority = 2000)
public abstract class MeleeAttackGoalMixin extends Goal {
    @Shadow @Final protected PathfinderMob mob;

    @Shadow private int ticksUntilNextAttack;

    @Shadow protected abstract double getAttackReachSqr(LivingEntity target);

    @Shadow protected abstract void resetAttackCooldown();

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
     * Minecraft 1.20.1's MeleeAttackGoal checks its own squared reach instead of
     * Mob#isWithinMeleeAttackRange. Better Combat weapons replace that reach with the weapon's
     * attack shape; every other mob keeps the goal's (possibly subclass-overridden) reach plus the
     * configured additional range.
     */
    @Inject(method = "checkAndPerformAttack", at = @At("HEAD"), cancellable = true)
    private void bmc$useBetterCombatReach(LivingEntity target, double distanceSqr, CallbackInfo ci) {
        boolean eligible = MobCombatLogic.isEligible(this.mob);
        if (!eligible && (!MobCombatLogic.isFallbackMeleeCandidate(this.mob)
                || BMCConfig.ADDITIONAL_ATTACK_RANGE.get() <= 0.0D)) {
            // Vanilla reach is already correct; the doHurtTarget redirect below still applies.
            return;
        }
        ci.cancel();

        boolean inRange = eligible
                ? MobCombatLogic.isWithinWeaponRange(this.mob, target)
                : MobCombatLogic.isWithinFallbackRange(this.mob, target, this.getAttackReachSqr(target), distanceSqr);
        if (!inRange || this.ticksUntilNextAttack > 0) {
            return;
        }

        this.resetAttackCooldown();
        this.mob.swing(InteractionHand.MAIN_HAND);
        if (!MobCombatLogic.interceptGoalMeleeAttack(this.mob, target)) {
            this.mob.doHurtTarget(target);
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
