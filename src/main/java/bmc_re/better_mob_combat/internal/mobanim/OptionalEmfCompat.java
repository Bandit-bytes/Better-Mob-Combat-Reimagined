package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Optional Entity Model Features/Fresh Animations bridge.
 *
 * <p>This is the useful part of the original Mob Player Animator EMF integration adapted to the
 * Reimagined single-mod architecture. EMF remains enabled. Immediately before the base model is
 * drawn, this class snapshots every custom part it changes, exposes Fresh Animations' individual
 * attack arm, hides its crossed-arm node, pauses only the bones controlled by Player Animator and
 * reapplies the live arm keyframes. Immediately after the draw, EMF and every saved model value are
 * restored.</p>
 */
public final class OptionalEmfCompat {
    private static final String EMF_API = "traben.entity_model_features.EMFAnimationApi";
    private static final String EMF_MODEL_INTERFACE = "traben.entity_model_features.models.IEMFModel";

    private static final Set<UUID> PAUSED = new HashSet<>();
    private static final Map<UUID, List<PartState>> SAVED_PARTS = new HashMap<>();

    private static boolean initialized;
    private static boolean available;
    private static boolean warned;
    private static Class<?> emfModelInterface;
    private static Method isModelAnimated;
    private static Method emfEntityOf;
    private static Method pauseAnimations;
    private static Method resumeAnimations;
    private static Method getEmfRootModel;

    private OptionalEmfCompat() {
    }

    public static void pause(LivingEntity entity, EntityModel<?> model) {
        if (!EmbeddedPlayerAnimator.isAnimating(entity) || !initialize()) {
            return;
        }

        UUID uuid = entity.getUUID();
        try {
            // Recover safely if another renderer aborted between our BEFORE and AFTER injections.
            resumeIfPaused(entity, uuid);
            restoreSavedParts(SAVED_PARTS.remove(uuid));

            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                    EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);

            // Layer-tree inspection can briefly report no enabled channels during a transition.
            // The synchronized attack state still tells us which weapon arm must remain controlled.
            if (entity.getType() == EntityType.VINDICATOR
                    && EmbeddedPlayerAnimator.isAttackAnimating(entity)) {
                if (entity instanceof Mob mob && mob.isLeftHanded()) {
                    animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
                } else {
                    animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
                }
            }
            if (animated.isEmpty()) {
                return;
            }

            Set<ModelPart> partsToPause = Collections.newSetFromMap(new IdentityHashMap<>());
            collectControlledParts(model, animated, partsToPause);

            List<PartState> saved = applyFreshAnimationsVindicatorMapping(entity, model, animated, partsToPause);
            if (!saved.isEmpty()) {
                SAVED_PARTS.put(uuid, saved);
            }

            if (partsToPause.isEmpty()) {
                restoreSavedParts(SAVED_PARTS.remove(uuid));
                return;
            }

            Object emfEntity = emfEntityOf.invoke(null, entity);
            if (emfEntity == null) {
                restoreSavedParts(SAVED_PARTS.remove(uuid));
                return;
            }

            pauseAnimations.invoke(null, emfEntity, (Object) partsToPause.toArray(ModelPart[]::new));
            PAUSED.add(uuid);

            // Fresh Animations performs custom hierarchy updates after vanilla setupAnim. Reapply
            // only the attack arms at the last possible point. Body, head and legs remain entirely
            // controlled by Fresh Animations.
            if (entity.getType() == EntityType.VINDICATOR
                    && EmbeddedPlayerAnimator.isAttackAnimating(entity)
                    && model instanceof IllagerModelAccess illager) {
                EmbeddedPlayerAnimator.applyAttackArmsOnly(
                        illager,
                        EmbeddedPlayerAnimator.getAnimation(entity),
                        animated
                );
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            try {
                resumeIfPaused(entity, uuid);
            } catch (ReflectiveOperationException | RuntimeException resumeException) {
                exception.addSuppressed(resumeException);
            }
            restoreSavedParts(SAVED_PARTS.remove(uuid));
            warnOnce("Failed to coordinate Fresh Animations/EMF with a mob attack animation", exception);
        }
    }

    public static void resume(LivingEntity entity) {
        if (!initialized || !available) {
            return;
        }

        UUID uuid = entity.getUUID();
        try {
            resumeIfPaused(entity, uuid);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to resume Fresh Animations/EMF after an animated mob render", exception);
        } finally {
            restoreSavedParts(SAVED_PARTS.remove(uuid));
        }
    }

    private static void resumeIfPaused(LivingEntity entity, UUID uuid) throws ReflectiveOperationException {
        if (!PAUSED.remove(uuid)) {
            return;
        }
        Object emfEntity = emfEntityOf.invoke(null, entity);
        if (emfEntity != null) {
            resumeAnimations.invoke(null, emfEntity);
        }
    }

    /**
     * Fresh Animations' Vindicator uses hidden individual arms plus a visible crossed-arm hierarchy.
     * Player Animator and Minecraft's held-item layer both target the individual vanilla arm anchor.
     * During an authored arm swing, expose that exact arm and hide only the crossed-arm node.
     *
     * <p>No leg offsets, torso offsets, model locking or global EMF disabling are used.</p>
     */
    private static List<PartState> applyFreshAnimationsVindicatorMapping(
            LivingEntity entity,
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> partsToPause
    ) throws ReflectiveOperationException {
        if (entity.getType() != EntityType.VINDICATOR
                || emfModelInterface == null
                || !emfModelInterface.isInstance(model)) {
            return List.of();
        }

        Object rootObject = getEmfRootModel.invoke(model);
        if (!(rootObject instanceof ModelPart root)) {
            return List.of();
        }

        List<PartState> saved = new ArrayList<>();
        boolean left = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        boolean right = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);

        if (left) {
            setVisible(root, "left_arm", true, saved);
            addIfPresent(root, "left_arm#EMF_left_arm", partsToPause);
        }
        if (right) {
            setVisible(root, "right_arm", true, saved);
            addIfPresent(root, "right_arm#EMF_right_arm", partsToPause);
        }
        if (left || right) {
            setVisible(root, "body#EMF_body#EMF_arms_rotation", false, saved);
        }

        return saved;
    }

    private static void setVisible(ModelPart root, String path, boolean visible, List<PartState> saved) {
        ModelPart part = findPart(root, path);
        if (part != null && part.visible != visible) {
            saveOnce(part, saved);
            part.visible = visible;
        }
    }

    private static void addIfPresent(ModelPart root, String path, Set<ModelPart> partsToPause) {
        ModelPart part = findPart(root, path);
        if (part != null) {
            partsToPause.add(part);
        }
    }

    private static void saveOnce(ModelPart part, List<PartState> saved) {
        for (PartState state : saved) {
            if (state.part() == part) {
                return;
            }
        }
        saved.add(PartState.capture(part));
    }

    public static boolean isAnimatedEmfVindicator(LivingEntity entity, EntityModel<?> model) {
        if (entity.getType() != EntityType.VINDICATOR || !initialize()) {
            return false;
        }
        try {
            return emfModelInterface != null
                    && emfModelInterface.isInstance(model)
                    && Boolean.TRUE.equals(isModelAnimated.invoke(null, model));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to query the active EMF Vindicator model", exception);
            return false;
        }
    }

    private static ModelPart findPart(ModelPart root, String path) {
        ModelPart current = root;
        for (String child : path.split("#")) {
            try {
                current = current.getChild(child);
            } catch (RuntimeException exception) {
                return null;
            }
        }
        return current;
    }

    private static void restoreSavedParts(List<PartState> states) {
        if (states == null) {
            return;
        }
        for (PartState state : states) {
            state.restore();
        }
    }

    private static void collectControlledParts(
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> parts
    ) {
        if (model instanceof PlayerModel<?> player) {
            addHumanoidParts(player, animated, parts);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) parts.add(player.jacket);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) parts.add(player.leftSleeve);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) parts.add(player.rightSleeve);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) parts.add(player.leftPants);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) parts.add(player.rightPants);
            return;
        }

        if (model instanceof HumanoidModel<?> humanoid) {
            addHumanoidParts(humanoid, animated, parts);
            return;
        }

        if (model instanceof IllagerModel<?> && model instanceof IllagerModelAccess illager) {
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
                parts.add(illager.bmc$getHead());
                parts.add(illager.bmc$getHat());
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                parts.add(illager.bmc$getBody());
            }
            boolean left = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            boolean right = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            if (left) parts.add(illager.bmc$getLeftArm());
            if (right) parts.add(illager.bmc$getRightArm());
            if (left || right) parts.add(illager.bmc$getCrossedArms());
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) parts.add(illager.bmc$getLeftLeg());
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) parts.add(illager.bmc$getRightLeg());
        }
    }

    private static void addHumanoidParts(
            HumanoidModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> parts
    ) {
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
            parts.add(model.head);
            parts.add(model.hat);
        }
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) parts.add(model.body);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) parts.add(model.leftArm);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) parts.add(model.rightArm);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) parts.add(model.leftLeg);
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) parts.add(model.rightLeg);
    }

    private static boolean initialize() {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            Class<?> api = Class.forName(EMF_API);
            emfModelInterface = Class.forName(EMF_MODEL_INTERFACE);
            isModelAnimated = findStatic(api, "isModelAnimatedByEMF", 1);
            emfEntityOf = findStatic(api, "emfEntityOf", Entity.class);
            pauseAnimations = findStatic(api, "pauseCustomAnimationsForThesePartsOfEntity", 2);
            resumeAnimations = findStatic(api, "resumeAllCustomAnimationsForEntity", 1);
            getEmfRootModel = emfModelInterface.getMethod("emf$getEMFRootModel");
            available = true;
        } catch (ClassNotFoundException ignored) {
            available = false;
        } catch (ReflectiveOperationException exception) {
            available = false;
            warnOnce("Entity Model Features was found, but its animation API was not compatible", exception);
        }
        return available;
    }

    private static Method findStatic(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " is not static");
        }
        return method;
    }

    private static Method findStatic(Class<?> owner, String name, int parameterCount)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name + "/" + parameterCount);
    }

    private static void warnOnce(String message, Throwable throwable) {
        if (!warned) {
            warned = true;
            BetterMobCombatReimagined.LOGGER.warn(message, throwable);
        }
    }

    private record PartState(
            ModelPart part,
            float x,
            float y,
            float z,
            float xRot,
            float yRot,
            float zRot,
            float xScale,
            float yScale,
            float zScale,
            boolean visible,
            boolean skipDraw
    ) {
        static PartState capture(ModelPart part) {
            return new PartState(
                    part,
                    part.x, part.y, part.z,
                    part.xRot, part.yRot, part.zRot,
                    part.xScale, part.yScale, part.zScale,
                    part.visible, part.skipDraw
            );
        }

        void restore() {
            part.x = x;
            part.y = y;
            part.z = z;
            part.xRot = xRot;
            part.yRot = yRot;
            part.zRot = zRot;
            part.xScale = xScale;
            part.yScale = yScale;
            part.zScale = zScale;
            part.visible = visible;
            part.skipDraw = skipDraw;
        }
    }
}
