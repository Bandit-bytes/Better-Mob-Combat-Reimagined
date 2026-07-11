package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobAnimationAccess;
import bmc_re.better_mob_combat.client.RangedMobAnimator;
import bmc_re.better_mob_combat.internal.mobanim.EmbeddedPlayerAnimator;
import bmc_re.better_mob_combat.internal.mobanim.OptionalEmfCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Adds the lightweight ranged aiming/recoil adjustments after the concrete mob model completes
 * setup. The embedded Mob Player Animator bridge handles keyframe application, root transforms,
 * bends, subclass overrides, model copying and armor rendering.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {
    @Unique
    private static final Set<String> BMC$RENDER_DIAGNOSTICS = new HashSet<>();

    @Shadow
    protected M model;

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER
            )
    )
    private void bmc$applyRangedMobPoseAfterModelSetup(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        if (!(entity instanceof MobAnimationAccess animatedMob)
                || !(this.model instanceof HumanoidModel<?> humanoidModel)) {
            return;
        }

        animatedMob.bmc$setRenderPartialTick(partialTick);

        if (animatedMob.bmc$isArmAnimationActive()) {
            String diagnosticKey = entity.getType() + "|" + this.model.getClass().getName()
                    + "|" + animatedMob.bmc$isTwoHandedArmAnimationActive();
            if (BMC$RENDER_DIAGNOSTICS.add(diagnosticKey)) {
                BetterMobCombatReimagined.LOGGER.info(
                        "[BMC render diagnostic] mob={} model={} stackActive={} armOwned={} twoHandedArmOwned={} detectedParts={}",
                        entity.getType(),
                        this.model.getClass().getName(),
                        EmbeddedPlayerAnimator.isAnimating(entity),
                        animatedMob.bmc$isArmAnimationActive(),
                        animatedMob.bmc$isTwoHandedArmAnimationActive(),
                        EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity)
                );
            }
        }

        // HumanoidModel's own TAIL injection is not late enough for every concrete mob model.
        // Reapply after the virtual setupAnim call has completely returned, before any render layer
        // or EMF pre-render hook can consume the final limb transforms.
        EmbeddedPlayerAnimator.reapplyAfterConcreteModelSetup(this.model, entity);

        RangedMobAnimator.apply(entity, humanoidModel, partialTick);
        humanoidModel.hat.copyFrom(humanoidModel.head);
    }
    /**
     * Player Animator applies root/body translation and rotation from the live mob processor during
     * setupRotations. This also sets the processor's render partial tick before model setup runs.
     */
    @Inject(
            method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At("RETURN")
    )
    private void bmc$applyEmbeddedMobRootTransform(
            T entity,
            PoseStack poseStack,
            float ageInTicks,
            float bodyYaw,
            float partialTick,
            float scale,
            CallbackInfo ci
    ) {
        if (entity instanceof Mob
                && !OptionalEmfCompat.isAnimatedEmfVindicator(entity, this.model)) {
            EmbeddedPlayerAnimator.applyBodyTransform(
                    EmbeddedPlayerAnimator.getAnimation(entity),
                    poseStack,
                    partialTick
            );
        }
    }

    /** Pause Fresh Animations/EMF on the body parts controlled by the embedded Player Animator. */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void bmc$pauseFreshAnimationsBeforeBaseModel(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        OptionalEmfCompat.pause(entity, this.model);
    }

    /** Resume paused EMF keyframes immediately after the base model render. */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                    shift = At.Shift.AFTER
            )
    )
    private void bmc$resumeFreshAnimationsAfterBaseModel(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        OptionalEmfCompat.resume(entity);
    }

}
