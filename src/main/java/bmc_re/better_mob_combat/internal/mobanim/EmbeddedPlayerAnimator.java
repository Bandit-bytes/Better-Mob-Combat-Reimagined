package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.api.MobAnimationAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.MathHelper;
import dev.kosmx.playerAnim.core.util.Pair;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;


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

    public static boolean isAttackAnimating(LivingEntity entity) {
        return entity instanceof MobAnimationAccess access && access.bmc$isAttackAnimationActive();
    }

    public static boolean isArmAnimating(LivingEntity entity) {
        if (!isAnimating(entity)) {
            return false;
        }
        EnumSet<AnimatedPart> parts = getCurrentlyAnimatedParts(entity);
        return parts.contains(AnimatedPart.LEFT_ARM) || parts.contains(AnimatedPart.RIGHT_ARM);
    }

    public enum AnimatedPart {
        HEAD,
        TORSO,
        LEFT_ARM,
        RIGHT_ARM,
        LEFT_LEG,
        RIGHT_LEG
    }

    public static EnumSet<AnimatedPart> getCurrentlyAnimatedParts(LivingEntity entity) {
        EnumSet<AnimatedPart> parts = EnumSet.noneOf(AnimatedPart.class);
        if (!(entity instanceof MobAnimationAccess access)) {
            return parts;
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectAnimatedParts(access.bmc$getAnimationStack(), parts, visited);
        return parts;
    }

    private static void collectAnimatedParts(
            Object animation,
            EnumSet<AnimatedPart> parts,
            Set<Object> visited
    ) {
        if (animation == null || !visited.add(animation)) {
            return;
        }
        if (animation instanceof IAnimation layeredAnimation && !layeredAnimation.isActive()) {
            return;
        }

        Object bodyParts = readField(animation, "bodyParts");
        if (bodyParts instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String name && bodyPartEnabled(entry.getValue())) {
                    mapAnimatedPart(name, parts);
                }
            }
            return;
        }

        Object layers = readField(animation, "layers");
        if (layers instanceof Iterable<?> iterable) {
            for (Object layer : iterable) {
                Object child = pairValue(layer);
                if (child != null) {
                    collectAnimatedParts(child, parts, visited);
                }
            }
            return;
        }

        Object child = invokeNoArg(animation, "getAnimation");
        if (child == null) {
            child = invokeNoArg(animation, "getAnim");
        }
        if (child == null) {
            child = readField(animation, "animation");
        }
        if (child == null) {
            child = readField(animation, "anim");
        }
        if (child != null && child != animation) {
            collectAnimatedParts(child, parts, visited);
        }
    }

    private static boolean bodyPartEnabled(Object bodyPart) {
        if (bodyPart == null) {
            return false;
        }
        Object state = readField(bodyPart, "part");
        if (state == null) {
            state = invokeNoArg(bodyPart, "part");
        }
        Object enabled = state == null ? null : invokeNoArg(state, "isEnabled");
        return Boolean.TRUE.equals(enabled);
    }

    private static void mapAnimatedPart(String name, EnumSet<AnimatedPart> parts) {
        String normalized = name.replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "head" -> parts.add(AnimatedPart.HEAD);
            case "torso" -> parts.add(AnimatedPart.TORSO);
            case "leftarm" -> parts.add(AnimatedPart.LEFT_ARM);
            case "rightarm" -> parts.add(AnimatedPart.RIGHT_ARM);
            case "leftleg" -> parts.add(AnimatedPart.LEFT_LEG);
            case "rightleg" -> parts.add(AnimatedPart.RIGHT_LEG);
            default -> {
            }
        }
    }

    private static Object pairValue(Object pair) {
        if (pair == null) {
            return null;
        }
        if (pair instanceof Map.Entry<?, ?> entry) {
            return entry.getValue();
        }
        for (String method : new String[]{"getSecond", "getRight", "getValue"}) {
            Object value = invokeNoArg(pair, method);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object readField(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object owner, String name) {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(owner);
            } catch (NoSuchMethodException ignored) {
                // Continue through the hierarchy.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
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
        poseStack.translate(position.getX().doubleValue(), position.getY().doubleValue() + 0.7D, position.getZ().doubleValue());

        Vec3f rotation = animation.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
        poseStack.mulPose(Axis.ZP.rotation(rotation.getZ().floatValue()));
        poseStack.mulPose(Axis.YP.rotation(rotation.getY().floatValue()));
        poseStack.mulPose(Axis.XP.rotation(rotation.getX().floatValue()));
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
            updatePartSafely(animation, "head", model.bmc$getHead());
            model.bmc$getHat().copyFrom(model.bmc$getHead());
            updatePartSafely(animation, "torso", model.bmc$getBody());
            updatePartSafely(animation, "leftArm", model.bmc$getLeftArm());
            updatePartSafely(animation, "rightArm", model.bmc$getRightArm());
            updatePartSafely(animation, "leftLeg", model.bmc$getLeftLeg());
            updatePartSafely(animation, "rightLeg", model.bmc$getRightLeg());
        } else {
            model.bmc$setFirstPersonNext(false);
            supplier.set(null);
            resetBends(model);
        }
    }

    /**
     * Arms-only variant of {@link #applyToModel}. Keeps the Player Animator supplier active so the
     * held-item layer still reads the rightItem/leftItem transforms, but never writes the head,
     * torso or leg channels. Used while another system (vanilla swim rotation or Fresh Animations)
     * owns the rest of the body - e.g. a swimming Vindicator - where writing player-shaped torso
     * and leg keyframes visibly detaches the torso from the legs.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends HumanoidModelAccess & FirstPersonTracker & IMutableModel> void applyArmsOnlyKeepingSupplier(
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
            updatePartSafely(animation, "leftArm", model.bmc$getLeftArm());
            updatePartSafely(animation, "rightArm", model.bmc$getRightArm());
        } else {
            model.bmc$setFirstPersonNext(false);
            supplier.set(null);
            resetBends(model);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends HumanoidModelAccess & FirstPersonTracker & IMutableModel> void applyToBabyModel(
            T model,
            @Nullable AnimationApplier animation,
            boolean armsOnly
    ) {
        SetableSupplier supplier = model.getEmoteSupplier();

        // Keep Player Animator's bend-aware custom renderer disabled for babies. Vanilla's
        // AgeableListModel renderer must remain responsible for the baby head/body scaling.
        supplier.set(null);
        resetBends(model);

        if (animation == null || model.bmc$isFirstPersonNext() || !animation.isActive()) {
            model.bmc$setFirstPersonNext(false);
            return;
        }

        if (armsOnly) {
            // EMF/Fresh Animations owns this baby's visible model tree and keeps animating the
            // head, torso and legs (that is how FA shapes its babies). EMF may reuse the exact
            // same ModelPart instances as this facade, so writing head/torso/leg keyframes here
            // would wipe FA's baby pose and leave a small adult. Only the weapon arms belong to
            // Better Combat; OptionalEmfCompat mirrors them into EMF's arm branches.
            updatePartSafely(animation, "leftArm", model.bmc$getLeftArm());
            updatePartSafely(animation, "rightArm", model.bmc$getRightArm());
            return;
        }

        // The normal model parts still receive the keyframes. OptionalEmfCompat mirrors these
        // channels into EMF's visible model tree immediately before it is rendered.
        updatePartSafely(animation, "head", model.bmc$getHead());
        model.bmc$getHat().copyFrom(model.bmc$getHead());
        updatePartSafely(animation, "torso", model.bmc$getBody());
        updatePartSafely(animation, "leftArm", model.bmc$getLeftArm());
        updatePartSafely(animation, "rightArm", model.bmc$getRightArm());
        updatePartSafely(animation, "leftLeg", model.bmc$getLeftLeg());
        updatePartSafely(animation, "rightLeg", model.bmc$getRightLeg());
    }

    public static void applyArmsOnlyToModel(
            IllagerModelAccess model,
            @Nullable AnimationApplier animation
    ) {
        if (animation == null || !animation.isActive()) {
            return;
        }
        updatePartSafely(animation, "leftArm", model.bmc$getLeftArm());
        updatePartSafely(animation, "rightArm", model.bmc$getRightArm());
    }

    /**
     * Parts whose first cuboid has no bendy-lib "bend" mutator. Identity-keyed (ModelPart does not
     * override equals/hashCode) and weak, so EMF variant swaps and resource reloads never leak.
     */
    private static final Set<ModelPart> UNBENDABLE_PARTS = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * {@link AnimationApplier#updatePart} for parts this mod did not prepare with
     * {@link IBendHelper#initBend}, such as EMF/Fresh Animations' visible model tree or custom
     * modded models.
     *
     * <p>Player Animator 1.0.x (Minecraft 1.20.1) bends through bendy-lib, whose cuboids return a
     * null mutator unless {@code initBend} registered one, and Player Animator then calls
     * {@code applyBend} on that null. The NullPointerException aborted every overlay that touched
     * such a part. For Fresh Animations Vindicators that meant the left arm was rotated, the right
     * arm never was, and FA's own idle swing showed through. {@code updatePart} writes position and
     * rotation before bending, so the first failure still leaves a correct pose; afterwards the part
     * is driven without bending.</p>
     */
    public static void updatePartSafely(AnimationApplier animation, String channel, ModelPart part) {
        if (UNBENDABLE_PARTS.contains(part)) {
            updatePartWithoutBend(animation, channel, part);
            return;
        }
        try {
            animation.updatePart(channel, part);
        } catch (NullPointerException exception) {
            UNBENDABLE_PARTS.add(part);
        }
    }

    /** {@link IBendHelper#bend(ModelPart, Pair)} that tolerates parts without a bend mutator. */
    public static void bendSafely(ModelPart part, @Nullable Pair<Float, Float> bend) {
        if (bend != null && UNBENDABLE_PARTS.contains(part)) {
            return;
        }
        try {
            IBendHelper.INSTANCE.bend(part, bend);
        } catch (NullPointerException exception) {
            UNBENDABLE_PARTS.add(part);
        }
    }

    /** Mirrors Player Animator 1.0.x's {@code updatePart} without the bend step. */
    private static void updatePartWithoutBend(AnimationApplier animation, String channel, ModelPart part) {
        Vec3f position = animation.get3DTransform(channel, TransformType.POSITION, new Vec3f(part.x, part.y, part.z));
        part.x = position.getX();
        part.y = position.getY();
        part.z = position.getZ();
        Vec3f rotation = animation.get3DTransform(
                channel,
                TransformType.ROTATION,
                new Vec3f(
                        MathHelper.clampToRadian(part.xRot),
                        MathHelper.clampToRadian(part.yRot),
                        MathHelper.clampToRadian(part.zRot)
                )
        );
        part.setRotation(rotation.getX(), rotation.getY(), rotation.getZ());
    }

    public static void applyAttackArmsOnly(
            HumanoidModelAccess model,
            @Nullable AnimationApplier animation,
            EnumSet<AnimatedPart> animatedParts
    ) {
        if (animation == null || !animation.isActive()) {
            return;
        }
        if (animatedParts.contains(AnimatedPart.LEFT_ARM)) {
            updatePartSafely(animation, "leftArm", model.bmc$getLeftArm());
        }
        if (animatedParts.contains(AnimatedPart.RIGHT_ARM)) {
            updatePartSafely(animation, "rightArm", model.bmc$getRightArm());
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
            float red,
            float green,
            float blue,
            float alpha,
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
                part.render(poseStack, vertices, packedLight, packedOverlay, red, green, blue, alpha);
            }
        }
        for (ModelPart part : bodyParts) {
            if (!((IUpperPartHelper) (Object) part).isUpperPart()) {
                part.render(poseStack, vertices, packedLight, packedOverlay, red, green, blue, alpha);
            }
        }

        poseStack.pushPose();
        IBendHelper.rotateMatrixStack(poseStack, processor.getBend("body"));
        for (ModelPart part : headParts) {
            if (((IUpperPartHelper) (Object) part).isUpperPart()) {
                part.render(poseStack, vertices, packedLight, packedOverlay, red, green, blue, alpha);
            }
        }
        for (ModelPart part : bodyParts) {
            if (((IUpperPartHelper) (Object) part).isUpperPart()) {
                part.render(poseStack, vertices, packedLight, packedOverlay, red, green, blue, alpha);
            }
        }
        poseStack.popPose();
        return true;
    }
}