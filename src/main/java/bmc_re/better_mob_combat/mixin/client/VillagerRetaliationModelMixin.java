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


@Pseudo
@Mixin(
        targets = "com.jvn.villagerretaliation.client.model.VillagerRetaliationVillagerModel",
        priority = 500,
        remap = false
)
public abstract class VillagerRetaliationModelMixin {

    @Inject(
            // Third-party class: match both the development name and the production SRG name.
            method = {
                    "setupAnim(Lnet/minecraft/world/entity/npc/AbstractVillager;FFFFF)V",
                    "m_6973_(Lnet/minecraft/world/entity/npc/AbstractVillager;FFFFF)V"
            },
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