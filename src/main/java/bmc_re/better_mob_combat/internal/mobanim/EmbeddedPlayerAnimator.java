package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.api.MobAnimationAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.SetableSupplier;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.Helper;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.IMutableModel;
import dev.kosmx.playerAnim.impl.IUpperPartHelper;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import dev.kosmx.playerAnim.impl.animation.IBendHelper;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * The small render bridge embedded from Mob Player Animator's 1.21.1 implementation.
 *
 * <p>Player Animator already mixes bend support and supplier propagation into every
 * {@code HumanoidModel}. What it does not normally do is attach an animation stack to mobs or feed
 * that stack into non-player models. These helpers perform that missing handoff using Player
 * Animator's own {@link AnimationApplier}, {@link IMutableModel}, and bend APIs.</p>
 */
public final class EmbeddedPlayerAnimator {
    private EmbeddedPlayerAnimator() {
    }

    @Nullable
    public static AnimationApplier getAnimation(LivingEntity entity) {
        return entity instanceof IAnimatedPlayer animated ? animated.playerAnimator_getAnimation() : null;
    }

    public static boolean isAnimating(LivingEntity entity) {
        AnimationApplier animation = getAnimation(entity);
        return animation != null && animation.isActive();
    }

    /** Returns true only while Better Mob Combat's high-priority attack layer is playing. */
    public static boolean isAttackAnimating(LivingEntity entity) {
        return entity instanceof MobAnimationAccess access && access.bmc$isAttackAnimationActive();
    }

    public static void applyBodyTransform(@Nullable AnimationApplier animation, PoseStack poseStack, float partialTick) {
        if (animation == null) {
            return;
        }

        animation.setTickDelta(partialTick);
        if (!animation.isActive()) {
            return;
        }

        Vec3f position = animation.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
        poseStack.translate(position.getX(), position.getY() + 0.7D, position.getZ());

        Vec3f rotation = animation.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
        poseStack.mulPose(Axis.ZP.rotation(rotation.getZ()));
        poseStack.mulPose(Axis.YP.rotation(rotation.getY()));
        poseStack.mulPose(Axis.XP.rotation(rotation.getX()));
        poseStack.translate(0.0D, -0.7D, 0.0D);
    }

    public static void initializeIllagerBends(ModelPart root, HumanoidModelAccess model) {
        IBendHelper.INSTANCE.initBend(root.getChild("body"), Direction.DOWN);
        IBendHelper.INSTANCE.initBend(root.getChild("right_arm"), Direction.UP);
        IBendHelper.INSTANCE.initBend(root.getChild("left_arm"), Direction.UP);
        IBendHelper.INSTANCE.initBend(root.getChild("right_leg"), Direction.UP);
        IBendHelper.INSTANCE.initBend(root.getChild("left_leg"), Direction.UP);

        ((IUpperPartHelper) (Object) model.bmc$getRightArm()).setUpperPart(true);
        ((IUpperPartHelper) (Object) model.bmc$getLeftArm()).setUpperPart(true);
        ((IUpperPartHelper) (Object) model.bmc$getHead()).setUpperPart(true);
        ((IUpperPartHelper) (Object) model.bmc$getHat()).setUpperPart(true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void initializeSupplier(IMutableModel model, SetableSupplier<AnimationProcessor> supplier) {
        supplier.set(null);
        model.setEmoteSupplier(supplier);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void copySupplier(IMutableModel target, SetableSupplier<AnimationProcessor> supplier) {
        if (supplier != null) {
            target.setEmoteSupplier(supplier);
        }
    }

    public static void resetToBakedPose(HumanoidModelAccess model) {
        HumanoidBodyPose pose = model.bmc$getInitialBodyPose();
        if (pose != null) {
            pose.apply(model);
        }
    }

    /**
     * Applies the exact same live processor to the model that Player Animator uses for players.
     * The supplier is subsequently copied by Player Animator's HumanoidModel mixin to inner/outer
     * armor models, which keeps rotations, pivots, scale and bend deformation identical.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends HumanoidModelAccess & FirstPersonTracker & IMutableModel> void applyToModel(
            T model,
            @Nullable AnimationApplier animation
    ) {
        SetableSupplier supplier = model.getEmoteSupplier();
        if (animation == null) {
            supplier.set(null);
            resetBends(model);
            return;
        }

        if (!model.bmc$isFirstPersonNext() && animation.isActive()) {
            supplier.set(animation);
            animation.updatePart("head", model.bmc$getHead());
            model.bmc$getHat().copyFrom(model.bmc$getHead());
            animation.updatePart("torso", model.bmc$getBody());
            animation.updatePart("leftArm", model.bmc$getLeftArm());
            animation.updatePart("rightArm", model.bmc$getRightArm());
            animation.updatePart("leftLeg", model.bmc$getLeftLeg());
            animation.updatePart("rightLeg", model.bmc$getRightLeg());
        } else {
            model.bmc$setFirstPersonNext(false);
            supplier.set(null);
            resetBends(model);
        }
    }

    private static void resetBends(HumanoidModelAccess model) {
        IBendHelper.INSTANCE.bend(model.bmc$getBody(), null);
        IBendHelper.INSTANCE.bend(model.bmc$getLeftArm(), null);
        IBendHelper.INSTANCE.bend(model.bmc$getRightArm(), null);
        IBendHelper.INSTANCE.bend(model.bmc$getLeftLeg(), null);
        IBendHelper.INSTANCE.bend(model.bmc$getRightLeg(), null);
    }

    /** Player Animator's bend-aware renderer for IllagerModel, which is not a HumanoidModel. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean renderIllagerWithBends(
            PoseStack poseStack,
            VertexConsumer vertices,
            int packedLight,
            int packedOverlay,
            int color,
            SetableSupplier<AnimationProcessor> supplier,
            Iterable<ModelPart> headParts,
            Iterable<ModelPart> bodyParts
    ) {
        AnimationProcessor processor = supplier == null ? null : supplier.get();
        if (!Helper.isBendEnabled() || processor == null || !processor.isActive()) {
            return false;
        }

        for (ModelPart part : headParts) {
            if (!((IUpperPartHelper) (Object) part).isUpperPart()) {
                part.render(poseStack, vertices, packedLight, packedOverlay, color);
            }
        }
        for (ModelPart part : bodyParts) {
            if (!((IUpperPartHelper) (Object) part).isUpperPart()) {
                part.render(poseStack, vertices, packedLight, packedOverlay, color);
            }
        }

        poseStack.pushPose();
        IBendHelper.rotateMatrixStack(poseStack, processor.getBend("body"));
        for (ModelPart part : headParts) {
            if (((IUpperPartHelper) (Object) part).isUpperPart()) {
                part.render(poseStack, vertices, packedLight, packedOverlay, color);
            }
        }
        for (ModelPart part : bodyParts) {
            if (((IUpperPartHelper) (Object) part).isUpperPart()) {
                part.render(poseStack, vertices, packedLight, packedOverlay, color);
            }
        }
        poseStack.popPose();
        return true;
    }
}
