package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Optional Entity Model Features/Fresh Animations bridge embedded from Mob Player Animator's
 * 1.21.1 behavior.
 *
 * <p>There is intentionally no compile-time EMF dependency. When EMF is installed, reflection is
 * used to pause its custom keyframes for the model parts currently controlled by Player Animator.
 * This prevents Fresh Animations from overwriting Better Combat's pose immediately before the base
 * model is drawn.</p>
 */
public final class OptionalEmfCompat {
    private static final String EMF_API = "traben.entity_model_features.EMFAnimationApi";
    private static final String EMF_MODEL = "traben.entity_model_features.models.IEMFModel";

    private static final Set<UUID> PAUSED = new HashSet<>();
    private static final Map<UUID, List<SavedPart>> SAVED_PARTS = new java.util.HashMap<>();

    private static boolean initialized;
    private static boolean available;
    private static boolean warned;
    private static Method isModelAnimated;
    private static Method emfEntityOf;
    private static Method pauseAnimations;
    private static Method resumeAnimations;
    private static Method getEmfRoot;

    private OptionalEmfCompat() {
    }

    public static void pause(LivingEntity entity, EntityModel<?> model) {
        if (!EmbeddedPlayerAnimator.isAnimating(entity) || !initialize()) {
            return;
        }

        try {
            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return;
            }

            Object emfEntity = emfEntityOf.invoke(null, entity);
            if (emfEntity == null) {
                return;
            }

            boolean attackAnimation = EmbeddedPlayerAnimator.isAttackAnimating(entity);
            Set<ModelPart> parts = Collections.newSetFromMap(new IdentityHashMap<>());
            collectControlledParts(model, attackAnimation, parts);

            List<SavedPart> saved = new ArrayList<>();
            if (entity.getType() == EntityType.VINDICATOR && getEmfRoot.getDeclaringClass().isInstance(model)) {
                Object rootObject = getEmfRoot.invoke(model);
                if (rootObject instanceof ModelPart root) {
                    applyFreshAnimationsVindicatorFix(root, attackAnimation, parts, saved);
                }
            }

            if (parts.isEmpty()) {
                restore(saved);
                return;
            }

            // Store modified custom-part state before invoking EMF so it can still be restored if
            // an optional API call fails midway through the render.
            if (!saved.isEmpty()) {
                SAVED_PARTS.put(entity.getUUID(), saved);
            }
            pauseAnimations.invoke(null, emfEntity, (Object) parts.toArray(ModelPart[]::new));
            PAUSED.add(entity.getUUID());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            restore(SAVED_PARTS.remove(entity.getUUID()));
            warnOnce("Failed to pause Fresh Animations/EMF for animated mob", exception);
        }
    }

    public static void resume(LivingEntity entity, EntityModel<?> model) {
        if (!initialized || !available) {
            return;
        }

        List<SavedPart> saved = SAVED_PARTS.remove(entity.getUUID());
        try {
            if (PAUSED.remove(entity.getUUID())) {
                Object emfEntity = emfEntityOf.invoke(null, entity);
                if (emfEntity != null) {
                    resumeAnimations.invoke(null, emfEntity);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to resume Fresh Animations/EMF for animated mob", exception);
        } finally {
            restore(saved);
        }
    }

    private static void collectControlledParts(EntityModel<?> model, boolean attack, Set<ModelPart> parts) {
        if (model instanceof PlayerModel<?> player) {
            parts.add(player.body);
            parts.add(player.leftArm);
            parts.add(player.rightArm);
            parts.add(player.leftSleeve);
            parts.add(player.rightSleeve);
            parts.add(player.jacket);
            if (attack) {
                parts.add(player.head);
                parts.add(player.hat);
                parts.add(player.leftLeg);
                parts.add(player.rightLeg);
                parts.add(player.leftPants);
                parts.add(player.rightPants);
            }
            return;
        }

        if (model instanceof HumanoidModel<?> humanoid) {
            parts.add(humanoid.body);
            parts.add(humanoid.leftArm);
            parts.add(humanoid.rightArm);
            if (attack) {
                parts.add(humanoid.head);
                parts.add(humanoid.hat);
                parts.add(humanoid.leftLeg);
                parts.add(humanoid.rightLeg);
            }
            return;
        }

        if (model instanceof IllagerModel<?> && model instanceof IllagerModelAccess illager) {
            parts.add(illager.bmc$getBody());
            parts.add(illager.bmc$getCrossedArms());
            parts.add(illager.bmc$getLeftArm());
            parts.add(illager.bmc$getRightArm());
            if (attack) {
                parts.add(illager.bmc$getHead());
                parts.add(illager.bmc$getHat());
                parts.add(illager.bmc$getLeftLeg());
                parts.add(illager.bmc$getRightLeg());
            }
        }
    }

    /**
     * Mob Player Animator ships these exact default corrections for Fresh Animations' vindicator
     * hierarchy. Its custom leg children use a different origin, while the resource pack keeps a
     * separate crossed-arm rotation group visible unless explicitly hidden.
     */
    private static void applyFreshAnimationsVindicatorFix(
            ModelPart root,
            boolean attack,
            Set<ModelPart> parts,
            List<SavedPart> saved
    ) {
        Set<ModelPart> alreadySaved = Collections.newSetFromMap(new IdentityHashMap<>());

        if (attack) {
            modify(root, "left_leg#EMF_left_leg", parts, saved, alreadySaved, part ->
                    part.offsetPos(new Vector3f(0.0F, -12.0F, 0.0F)));
            modify(root, "right_leg#EMF_right_leg", parts, saved, alreadySaved, part ->
                    part.offsetPos(new Vector3f(0.0F, -12.0F, 0.0F)));
        }

        modify(root, "left_arm", parts, saved, alreadySaved, part -> part.visible = true);
        modify(root, "right_arm", parts, saved, alreadySaved, part -> part.visible = true);
        modify(root, "body#EMF_body#EMF_arms_rotation", parts, saved, alreadySaved, part -> part.visible = false);
    }

    private static void modify(
            ModelPart root,
            String path,
            Set<ModelPart> parts,
            List<SavedPart> saved,
            Set<ModelPart> alreadySaved,
            java.util.function.Consumer<ModelPart> modifier
    ) {
        ModelPart part = findPart(root, path);
        if (part == null) {
            return;
        }
        if (alreadySaved.add(part)) {
            saved.add(new SavedPart(part));
        }
        modifier.accept(part);
        parts.add(part);
    }

    private static ModelPart findPart(ModelPart root, String path) {
        ModelPart current = root;
        try {
            for (String segment : path.split("#")) {
                current = current.getChild(segment);
            }
            return current;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean initialize() {
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            Class<?> api = Class.forName(EMF_API);
            Class<?> emfModel = Class.forName(EMF_MODEL);
            isModelAnimated = findStatic(api, "isModelAnimatedByEMF", 1);
            emfEntityOf = findStatic(api, "emfEntityOf", 1);
            pauseAnimations = findStatic(api, "pauseCustomAnimationsForThesePartsOfEntity", 2);
            resumeAnimations = findStatic(api, "resumeAllCustomAnimationsForEntity", 1);
            getEmfRoot = emfModel.getMethod("emf$getEMFRootModel");
            available = true;
        } catch (ClassNotFoundException ignored) {
            available = false;
        } catch (ReflectiveOperationException exception) {
            available = false;
            warnOnce("Entity Model Features was found, but its animation API did not match the expected 1.21.1 API", exception);
        }
        return available;
    }

    private static Method findStatic(Class<?> owner, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "#" + name + "/" + parameterCount);
    }

    private static void restore(List<SavedPart> saved) {
        if (saved == null) {
            return;
        }
        for (SavedPart part : saved) {
            part.restore();
        }
    }

    private static void warnOnce(String message, Throwable throwable) {
        if (!warned) {
            warned = true;
            BetterMobCombatReimagined.LOGGER.warn(message, throwable);
        }
    }

    private record SavedPart(
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
            boolean visible
    ) {
        SavedPart(ModelPart part) {
            this(
                    part,
                    part.x,
                    part.y,
                    part.z,
                    part.xRot,
                    part.yRot,
                    part.zRot,
                    part.xScale,
                    part.yScale,
                    part.zScale,
                    part.visible
            );
        }

        void restore() {
            this.part.setPos(this.x, this.y, this.z);
            this.part.setRotation(this.xRot, this.yRot, this.zRot);
            this.part.xScale = this.xScale;
            this.part.yScale = this.yScale;
            this.part.zScale = this.zScale;
            this.part.visible = this.visible;
        }
    }
}
