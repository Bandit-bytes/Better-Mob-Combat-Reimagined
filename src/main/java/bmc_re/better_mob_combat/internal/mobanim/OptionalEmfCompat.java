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
 * <p>EMF is never disabled and the entity is never forced onto its vanilla model. This follows
 * Mob Player Animator's compatibility path: pause only the EMF parts whose Player Animator
 * channels are active, temporarily adapt Fresh Animations' custom Vindicator hierarchy, render,
 * then restore every changed model value immediately.</p>
 */
public final class OptionalEmfCompat {
    private static final String EMF_API = "traben.entity_model_features.EMFAnimationApi";
    private static final String EMF_MODEL_INTERFACE = "traben.entity_model_features.models.IEMFModel";

    private static final Set<UUID> PAUSED = new HashSet<>();
    private static final Map<UUID, List<PartState>> MODIFIED_PARTS = new HashMap<>();

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
            // A renderer should always call resume. If a render was interrupted, explicitly resume
            // EMF before discarding our bookkeeping; simply forgetting PAUSED would leave custom
            // animation channels frozen on the entity.
            resumeIfPaused(entity, uuid);
            restoreModifiedParts(MODIFIED_PARTS.remove(uuid));

            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                debugOnce("EMF reports model NOT animated for " + entity.getType() + " - nothing to pause");
                return;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animatedParts =
                    EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
            if (animatedParts.isEmpty()) {
                debugOnce("Player Animator reports no animated parts for " + entity.getType()
                        + " - EMF pause skipped, EMF keeps full control");
                return;
            }

            Set<ModelPart> modelParts = Collections.newSetFromMap(new IdentityHashMap<>());
            collectControlledParts(model, animatedParts, modelParts);

            List<PartState> modified = applyEmfModelModifiers(entity, model, animatedParts, modelParts);
            if (!modified.isEmpty()) {
                MODIFIED_PARTS.put(uuid, modified);
            }

            if (modelParts.isEmpty()) {
                debugOnce("No matching ModelParts found to pause for " + entity.getType()
                        + " (animated channels: " + animatedParts + ") - check EMF bone names");
                restoreModifiedParts(MODIFIED_PARTS.remove(uuid));
                return;
            }

            Object emfEntity = emfEntityOf.invoke(null, entity);
            if (emfEntity == null) {
                debugOnce("EMF returned no entity wrapper for " + entity.getType() + " - pause skipped");
                restoreModifiedParts(MODIFIED_PARTS.remove(uuid));
                return;
            }

            debugOnce("Pausing EMF animation on " + modelParts.size() + " parts for "
                    + entity.getType() + " (channels: " + animatedParts + ")");
            pauseAnimations.invoke(null, emfEntity, (Object) modelParts.toArray(ModelPart[]::new));
            PAUSED.add(uuid);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            try {
                resumeIfPaused(entity, uuid);
            } catch (ReflectiveOperationException | RuntimeException resumeException) {
                exception.addSuppressed(resumeException);
            }
            restoreModifiedParts(MODIFIED_PARTS.remove(uuid));
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
            restoreModifiedParts(MODIFIED_PARTS.remove(uuid));
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
     * Fresh Animations renders the visible illager arm as a child of the vanilla arm anchor.
     * Pause that visible child while Better Mob Combat rotates the parent. The held-item layer
     * already uses the parent anchor, so the arm and axe now receive one shared transform.
     *
     * <p>No legs, torso positions, crossed-arm visibility, or body movement are changed here.</p>
     */
    private static List<PartState> applyEmfModelModifiers(
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

        int before = partsToPause.size();
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) {
            addIfPresent(root, "left_arm#EMF_left_arm", partsToPause);
        }
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) {
            addIfPresent(root, "right_arm#EMF_right_arm", partsToPause);
        }
        if (partsToPause.size() == before) {
            debugOnce("Vindicator EMF arm bones 'left_arm#EMF_left_arm'/'right_arm#EMF_right_arm' "
                    + "not found on this model - this Fresh Animations pack likely uses different "
                    + "bone names, so its custom arm bones are never paused");
        }
        return List.of();
    }

    private static void addIfPresent(ModelPart root, String path, Set<ModelPart> partsToPause) {
        ModelPart part = findPart(root, path);
        if (part != null) {
            partsToPause.add(part);
        }
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

    private static void restoreModifiedParts(List<PartState> states) {
        if (states == null) {
            return;
        }
        for (PartState state : states) {
            state.part().y = state.y();
            state.part().visible = state.visible();
        }
    }

    private static void collectControlledParts(
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> parts
    ) {
        if (model instanceof PlayerModel<?> player) {
            addHumanoidParts(player, animated, parts);
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                parts.add(player.jacket);
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) {
                parts.add(player.leftSleeve);
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) {
                parts.add(player.rightSleeve);
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) {
                parts.add(player.leftPants);
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) {
                parts.add(player.rightPants);
            }
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
            boolean leftArm = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            boolean rightArm = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            if (leftArm) {
                parts.add(illager.bmc$getLeftArm());
            }
            if (rightArm) {
                parts.add(illager.bmc$getRightArm());
            }
            if (leftArm || rightArm) {
                parts.add(illager.bmc$getCrossedArms());
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) {
                parts.add(illager.bmc$getLeftLeg());
            }
            if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) {
                parts.add(illager.bmc$getRightLeg());
            }
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
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
            parts.add(model.body);
        }
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) {
            parts.add(model.leftArm);
        }
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) {
            parts.add(model.rightArm);
        }
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) {
            parts.add(model.leftLeg);
        }
        if (animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) {
            parts.add(model.rightLeg);
        }
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
            // EMF overloads emfEntityOf for Entity and BlockEntity. Bind the normal entity overload.
            emfEntityOf = findStatic(api, "emfEntityOf", Entity.class);
            pauseAnimations = findStatic(api, "pauseCustomAnimationsForThesePartsOfEntity", 2);
            resumeAnimations = findStatic(api, "resumeAllCustomAnimationsForEntity", 1);
            getEmfRootModel = emfModelInterface.getMethod("emf$getEMFRootModel");
            available = true;
            debugOnce("EMF reflection bridge initialized successfully against " + EMF_API);
        } catch (ClassNotFoundException ignored) {
            available = false;
            debugOnce("EMF/Fresh Animations not present - bridge disabled");
        } catch (ReflectiveOperationException exception) {
            available = false;
            warnOnce("Entity Model Features was found, but its 1.21.1 animation API was not compatible", exception);
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

    // Temporary diagnostic aid: logs each distinct message once so we can see exactly where the
    // EMF/Fresh Animations bridge stops working without flooding the log every render frame.
    private static final Set<String> DEBUG_LOGGED = new HashSet<>();

    private static void debugOnce(String message) {
        if (DEBUG_LOGGED.add(message)) {
            BetterMobCombatReimagined.LOGGER.warn("[BMC-EMF-DEBUG] {}", message);
        }
    }

    private record PartState(ModelPart part, float y, boolean visible) {
    }
}