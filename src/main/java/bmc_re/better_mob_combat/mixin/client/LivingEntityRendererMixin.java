package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobAnimationAccess;
import bmc_re.better_mob_combat.client.RangedMobAnimator;
import bmc_re.better_mob_combat.internal.mobanim.EmbeddedPlayerAnimator;
import bmc_re.better_mob_combat.internal.mobanim.OptionalEmfCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
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
 *
 * <p>Fresh Animations/EMF stays fully enabled for every mob at all times here. Compatibility with
 * it is handled entirely by {@link OptionalEmfCompat#pause}, which briefly pauses only the two
 * specific arm bones Better Combat needs to drive during a swing/two-handed grip, and hands control
 * straight back afterward. Nothing here ever forces a vanilla model or vanilla texture fallback.</p>
 */
@Mixin(value = LivingEntityRenderer.class, priority = 2000)
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
        // IllagerModel is a HierarchicalModel, not a HumanoidModel: its root part already carries
        // vanilla's own flip/offset. Applying Player Animator's root transform on top of that
        // double-applies the inversion and renders the legs upside down, while the limbs - which are
        // posed in local space - still look correct. Illagers get their pose entirely from
        // applyToModel in IllagerModelMixin; they must not also take the root transform here.
        if (entity instanceof Mob && !(this.model instanceof IllagerModel<?>)) {
            EmbeddedPlayerAnimator.applyBodyTransform(
                    EmbeddedPlayerAnimator.getAnimation(entity),
                    poseStack,
                    partialTick
            );
        }
    }

    /**
     * Pause the EMF arm bones BEFORE the model's setupAnim runs.
     *
     * <p>EMF applies its custom CEM animations during setupAnim. Pausing afterwards (e.g. just
     * before renderToBuffer) is too late: EMF has already animated the arm for this frame, and
     * since pausing only stops <em>future</em> updates, it re-animates from scratch on every
     * subsequent frame too. The result is that EMF's own arm motion keeps winning and Better
     * Combat's pose never becomes visible - the weapon is held, sound and damage fire, but the arm
     * plays EMF's animation instead of the authored swing.</p>
     *
     * <p>Pausing here means EMF simply skips those specific arm bones this frame, leaving head,
     * legs, torso, idle sway and textures fully under Fresh Animations' control as before.</p>
     */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD")
    )
    private void bmc$pauseFreshAnimationsBeforeSetupAnim(
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

    /**
     * Re-assert Better Combat's arm pose immediately before the base model draws, after every
     * setupAnim/feature pass has finished touching the limbs.
     */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void bmc$reapplyArmsBeforeBaseModel(
            T entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        OptionalEmfCompat.reapplyArms(entity, this.model);
        bmc$logLegsOnce(entity);
    }

    /**
     * One-time per mob type: dump the leg pivot AND rotation right before the draw. Legs sitting
     * inside the torso is a pivot (position) problem, not a rotation problem - so comparing a
     * vindicator against a zombie (which renders correctly) will show immediately whether the baked
     * pivots we re-apply every frame match what this model actually expects.
     */
    @Unique
    private void bmc$logLegsOnce(T entity) {
        if (!(this.model instanceof bmc_re.better_mob_combat.internal.mobanim.HumanoidModelAccess access)) {
            return;
        }
        if (!EmbeddedPlayerAnimator.isAnimating(entity)) {
            return;
        }
        String key = "legs|" + entity.getType();
        if (!BMC$RENDER_DIAGNOSTICS.add(key)) {
            return;
        }
        var leftLeg = access.bmc$getLeftLeg();
        var rightLeg = access.bmc$getRightLeg();
        var body = access.bmc$getBody();
        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-LEGS] {} model={} isIllagerModel={} | leftLeg pivot=({},{},{}) rot=({},{},{})"
                        + " | rightLeg pivot=({},{},{}) rot=({},{},{})"
                        + " | body pivot=({},{},{})",
                entity.getType(),
                this.model.getClass().getSimpleName(),
                this.model instanceof IllagerModel<?>,
                leftLeg.x, leftLeg.y, leftLeg.z, leftLeg.xRot, leftLeg.yRot, leftLeg.zRot,
                rightLeg.x, rightLeg.y, rightLeg.z, rightLeg.xRot, rightLeg.yRot, rightLeg.zRot,
                body.x, body.y, body.z
        );
    }

    /**
     * Restore any selective pauses only after the complete living-entity render, including
     * held-item and armor layers. Fresh Animations remains enabled for every other mob and resumes
     * for this entity on its next non-BMC render.
     */
    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL")
    )
    private void bmc$resumeFreshAnimationsAfterAllRenderLayers(
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