package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.api.MobAnimationAccess;
import bmc_re.better_mob_combat.internal.mobanim.GenericHumanoidModelCompat;
import net.minecraft.client.model.EntityModel;
import net.minecraft.world.entity.npc.AbstractVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


/**
 * Applies Better Combat's attack arms after Villager Retaliation has posed its villager models.
 *
 * <p>{@code HumanoidCompatVillagerModel} extends {@code VillagerRetaliationVillagerModel} and
 * overrides {@code setupAnim}: it calls super (where the base-model hook runs), then its Fresh
 * Animations profile re-poses the arms from Villager Retaliation's own pose provider. Targeting the
 * subclass as well re-applies the attack arms after that, so they are not overwritten.</p>
 */
@Pseudo
@Mixin(
        targets = {
                "com.jvn.villagerretaliation.client.model.VillagerRetaliationVillagerModel",
                "com.jvn.villagerretaliation.client.model.HumanoidCompatVillagerModel"
        },
        priority = 500
)
public abstract class VillagerRetaliationModelMixin {

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/npc/AbstractVillager;FFFFF)V",
            at = @At("RETURN"),
            require = 0
    )
    private void bmc$applyBetterCombatArmsAfterVillagerRetaliationPose(
            AbstractVillager villager,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        if (!(villager instanceof MobAnimationAccess access)
                || !access.bmc$isAttackAnimationActive()) {
            return;
        }

        GenericHumanoidModelCompat.applyAttackArmsDirect(
                villager,
                (EntityModel<?>) (Object) this,
                access.bmc$getRenderPartialTick()
        );
    }
}