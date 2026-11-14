package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.internal.mobanim.GeckoLibHumanoidBridge;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

/**
 * Iron's Spells casting mobs (and Dark Doppelganger, which extends them) render through GeckoLib.
 * {@code handleAnimations} runs the GeckoLib controllers and then pops Iron's procedural transform
 * stack (head look, walk cycle, idle arm bob), so its tail is the last point before the bones are
 * drawn, armor layers included. The early return for same-frame re-renders is intentionally not
 * hooked: the bones still hold this frame's overlay then.
 */
@Pseudo
@Mixin(
        targets = "io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMobModel",
        remap = false
)
public abstract class IronsSpellCastingMobModelMixin {
    @Inject(
            method = "handleAnimations(Lio/redspace/ironsspellbooks/entity/mobs/abstract_spell_casting_mob/AbstractSpellCastingMob;JLsoftware/bernie/geckolib/animation/AnimationState;F)V",
            at = @At("TAIL"),
            require = 0,
            remap = false
    )
    private void bmc$applyBetterCombatToBones(
            @Coerce LivingEntity entity,
            long instanceId,
            AnimationState<?> animationState,
            float partialTick,
            CallbackInfo ci
    ) {
        GeckoLibHumanoidBridge.apply((GeoModel<?>) (Object) this, entity, partialTick);
    }
}
