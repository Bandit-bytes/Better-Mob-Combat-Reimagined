package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.internal.mobanim.OptionalEmfCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = "traben.entity_model_features.models.parts.EMFModelPartWithState",
        priority = 500,
        remap = false
)
public abstract class EMFModelPartWithStateMixin {
    // EMF overrides ModelPart#render; production Forge names it by its SRG name. Both hooks are
    // optional (require = 0): EMF 3.2+ animates through EMFModelPartRoot#animate, while EMF 3.0.x
    // animates through EMFModelPart.Animator#run. Exactly one of them matches a given EMF version,
    // and a missing hook must never crash the game for an optional compatibility layer.

    @Inject(
            method = {
                    "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                    "m_104306_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Ltraben/entity_model_features/models/parts/EMFModelPartRoot;animate()V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            require = 0,
            remap = false
    )
    private void bmc$overlayAttackArmsAfterEmfAnimation(
            PoseStack poseStack,
            VertexConsumer vertexConsumer,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha,
            CallbackInfo ci
    ) {
        OptionalEmfCompat.reapplyArmsAfterEmfAnimation();
    }

    @Inject(
            method = {
                    "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                    "m_104306_(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Ltraben/entity_model_features/models/parts/EMFModelPart$Animator;run()V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            require = 0,
            remap = false
    )
    private void bmc$overlayAttackArmsAfterLegacyEmfAnimation(
            PoseStack poseStack,
            VertexConsumer vertexConsumer,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha,
            CallbackInfo ci
    ) {
        OptionalEmfCompat.reapplyArmsAfterEmfAnimation();
    }
}
