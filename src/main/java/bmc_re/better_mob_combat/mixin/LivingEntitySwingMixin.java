package bmc_re.better_mob_combat.mixin;

import bmc_re.better_mob_combat.logic.ExternalAttackCompat;
import bmc_re.better_mob_combat.logic.IronsSpellsCompat;
import bmc_re.better_mob_combat.logic.MobCombatLogic;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"), cancellable = true)
    private void bmc$suppressVanillaMobSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
        if (!((Object) this instanceof Mob mob)) {
            return;
        }

        // Vanilla mobs enter BMC through Mob#doHurtTarget immediately after this call. A custom
        // mob can bypass that injection completely, so its visual bridge is allowed to claim the
        // swing even when its held item did not resolve through the normal eligibility check.
        boolean eligible = MobCombatLogic.isEligible(mob);
        if (eligible && IronsSpellsCompat.isSpellCastingMob(mob) && mob.getTarget() != null) {
            // Iron's melee goals swing when a move starts but only call doHurtTarget on the move's
            // hit frames, so starting there would land the Better Combat swing a whole move late.
            // Start it with the move instead; the later hit-frame calls are absorbed by beginAttack.
            MobCombatLogic.beginAttack(mob, mob.getTarget());
        }
        boolean externallyAnimated = ExternalAttackCompat.handleSwing(mob, hand);
        if (eligible || externallyAnimated) {
            ci.cancel();
        }
    }
}
