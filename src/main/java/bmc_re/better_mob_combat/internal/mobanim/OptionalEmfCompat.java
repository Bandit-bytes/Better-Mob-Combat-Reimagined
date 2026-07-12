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
    private static boolean pauseConditionRegistered;
    private static Method emfEntityAccessor;
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

        // With EMF's pause condition registered, EMF already stops animating this entity for us, so
        // the per-part animation pausing below is obsolete. The Fresh Animations VISIBILITY mapping
        // is NOT obsolete though: FA's crossed-arm node is real geometry in its .jem whose
        // visibility was being driven by FA's own animations. Now that those animations are paused,
        // that node would sit permanently visible on top of the individual arms - which renders as
        // a second pair of hands crossed over the chest. Keep exposing/hiding the right parts.
        if (pauseConditionRegistered) {
            try {
                restoreSavedParts(SAVED_PARTS.remove(uuid));

                if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                    return;
                }

                EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                        EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
                if (EmbeddedPlayerAnimator.isAttackAnimating(entity)) {
                    if (entity instanceof Mob mob && mob.isLeftHanded()) {
                        animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
                    } else {
                        animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
                    }
                }
                if (animated.isEmpty()) {
                    return;
                }

                // Throwaway - we are not pausing individual parts on this path.
                Set<ModelPart> unused = Collections.newSetFromMap(new IdentityHashMap<>());
                List<PartState> saved =
                        applyFreshAnimationsArmMapping(entity, model, animated, unused);
                if (!saved.isEmpty()) {
                    SAVED_PARTS.put(uuid, saved);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                restoreSavedParts(SAVED_PARTS.remove(uuid));
                warnOnce("Failed to apply Fresh Animations visibility mapping", exception);
            }
            return;
        }
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
            // This applies to any humanoid-family mob EMF augments (illagers, zombies, skeletons,
            // piglins, etc.), not just Vindicator - any of them can hold a two-handed weapon.
            boolean isEmfHumanoid = model instanceof HumanoidModel<?> || model instanceof IllagerModel<?>;
            if (isEmfHumanoid && EmbeddedPlayerAnimator.isAttackAnimating(entity)) {
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

            List<PartState> saved = applyFreshAnimationsArmMapping(entity, model, animated, partsToPause);
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

    /**
     * Applies Better Combat's live arm keyframes after every setupAnim/feature pass has run, so the
     * authored swing is the last thing to touch the arms before the model draws. Called separately
     * from {@link #pause}, which must run before setupAnim in order to stop EMF animating the arms
     * in the first place.
     */
    public static void reapplyArms(LivingEntity entity, EntityModel<?> model) {
        if (!EmbeddedPlayerAnimator.isAnimating(entity) || !initialized || !available) {
            return;
        }
        // With EMF's pause condition registered, EMF no longer overwrites our pose at all, so the
        // full-body animation applied during setupAnim already stands. Re-asserting just the arms
        // here would be redundant at best, and at worst fights that full-body pose.
        if (pauseConditionRegistered) {
            return;
        }
        if (!PAUSED.contains(entity.getUUID())) {
            return;
        }
        if (!(model instanceof HumanoidModelAccess humanoidAccess)) {
            return;
        }
        if (!(model instanceof HumanoidModel<?> || model instanceof IllagerModel<?>)) {
            return;
        }

        EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
        if (EmbeddedPlayerAnimator.isAttackAnimating(entity)) {
            if (entity instanceof Mob mob && mob.isLeftHanded()) {
                animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            } else {
                animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            }
        }
        if (animated.isEmpty()) {
            return;
        }

        EmbeddedPlayerAnimator.applyAttackArmsOnly(
                humanoidAccess,
                EmbeddedPlayerAnimator.getAnimation(entity),
                animated
        );

        // Sample the arms across the frames of an actual attack, not one arbitrary frame. A single
        // one-shot sample can easily land on an idle frame and show a neutral pose that tells us
        // nothing. Log only while the attack layer is genuinely active, and cap the output.
        boolean attacking = EmbeddedPlayerAnimator.isAttackAnimating(entity);
        if (attacking && ROT_SAMPLES < 24) {
            ROT_SAMPLES++;
            ModelPart right = humanoidAccess.bmc$getRightArm();
            ModelPart left = humanoidAccess.bmc$getLeftArm();
            var applier = EmbeddedPlayerAnimator.getAnimation(entity);
            BetterMobCombatReimagined.LOGGER.warn(
                    "[BMC-EMF-ROT] {} #{} attacking={} applier={} applierActive={} channels={} "
                            + "| R x={} y={} z={} | L x={} y={} z={}",
                    entity.getType(),
                    ROT_SAMPLES,
                    attacking,
                    applier == null ? "null" : "present",
                    applier != null && applier.isActive(),
                    animated,
                    right.xRot, right.yRot, right.zRot,
                    left.xRot, left.yRot, left.zRot
            );
        }
    }

    private static int ROT_SAMPLES;

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
     * Fresh Animations' humanoid-family mobs (illagers, zombies, skeletons, piglins, etc.) use
     * hidden individual arms plus, for illagers specifically, a visible crossed-arm hierarchy.
     * Player Animator and Minecraft's held-item layer both target the individual vanilla arm
     * anchor. During an authored arm swing/two-handed grip, expose that exact arm - and, only for
     * illagers, hide the crossed-arm node that would otherwise cover it.
     *
     * <p>No leg offsets, torso offsets, model locking or global EMF disabling are used.</p>
     */
    private static List<PartState> applyFreshAnimationsArmMapping(
            LivingEntity entity,
            EntityModel<?> model,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated,
            Set<ModelPart> partsToPause
    ) throws ReflectiveOperationException {
        boolean isIllager = model instanceof IllagerModel<?>;
        boolean isHumanoidFamily = isIllager || model instanceof HumanoidModel<?>;
        if (!isHumanoidFamily || emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return List.of();
        }

        Object rootObject = getEmfRootModel.invoke(model);
        if (!(rootObject instanceof ModelPart root)) {
            return List.of();
        }

        dumpEmfTreeOnce(entity, root);
        dumpIdentityOnce(entity, model, root);

        List<PartState> saved = new ArrayList<>();
        boolean left = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        boolean right = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        boolean foundAny = false;

        // Do NOT hardcode child bone names here. EMF's own docs are explicit that CEM part names
        // differ per model - the "EMF_left_arm" child that exists on Fresh Animations' Vindicator
        // is not guaranteed to exist (or be named that) on its zombie/skeleton/piglin models. If we
        // look up a name that isn't there we pause nothing, Fresh Animations keeps driving the real
        // visible arm mesh, and the weapon appears frozen even though our pose is being applied
        // correctly to the invisible vanilla arm underneath.
        //
        // Instead, find the top-level arm part and pause it plus EVERY descendant, whatever they
        // happen to be called in this particular pack.
        if (left) {
            setVisible(root, "left_arm", true, saved);
            if (addPartTree(root, "left_arm", partsToPause)) {
                foundAny = true;
            }
        }
        if (right) {
            setVisible(root, "right_arm", true, saved);
            if (addPartTree(root, "right_arm", partsToPause)) {
                foundAny = true;
            }
        }
        // The crossed-arm hierarchy that needs hiding only exists on illager-family models
        // (Vindicator, Pillager, Evoker, etc.) - plain humanoid mobs like zombies and skeletons
        // never have this node, so skip it for them rather than guess at a path that isn't there.
        if (isIllager && (left || right)) {
            setVisible(root, "body#EMF_body#EMF_arms_rotation", false, saved);
        }

        if ((left || right) && !foundAny) {
            debugOnce("EMF arm part lookup found no 'left_arm'/'right_arm' on the EMF root for "
                    + entity.getType() + " (model " + model.getClass().getName() + "). Use EMF's "
                    + "config > Tools > Export models to log this model's real part names.");
        }

        return saved;
    }

    private static final Set<String> DEBUG_LOGGED = new HashSet<>();

    private static void debugOnce(String message) {
        if (DEBUG_LOGGED.add(message)) {
            BetterMobCombatReimagined.LOGGER.warn("[BMC-EMF-DEBUG] {}", message);
        }
    }

    /**
     * The decisive check: is the ModelPart our animator writes rotations into the SAME OBJECT that
     * EMF actually renders? EMF rebuilds the model hierarchy, so if the model's own leftArm/rightArm
     * fields are different instances from the left_arm/right_arm inside EMF's root tree, then every
     * pose we apply lands on a part that never gets drawn - which looks exactly like "nothing is
     * animating" while all our state diagnostics still report success.
     */
    private static void dumpIdentityOnce(LivingEntity entity, EntityModel<?> model, ModelPart root) {
        String key = "identity|" + entity.getType();
        if (!DEBUG_LOGGED.add(key)) {
            return;
        }
        if (!(model instanceof HumanoidModelAccess access)) {
            BetterMobCombatReimagined.LOGGER.warn(
                    "[BMC-EMF-IDENTITY] {} model {} is NOT a HumanoidModelAccess - our arm pose never reaches it",
                    entity.getType(),
                    model.getClass().getName()
            );
            return;
        }
        ModelPart posedLeft = access.bmc$getLeftArm();
        ModelPart posedRight = access.bmc$getRightArm();
        ModelPart emfLeft = findPart(root, "left_arm");
        ModelPart emfRight = findPart(root, "right_arm");

        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-EMF-IDENTITY] {} | posedLeft={} emfLeft={} SAME_LEFT={} | posedRight={} emfRight={} SAME_RIGHT={}",
                entity.getType(),
                System.identityHashCode(posedLeft),
                emfLeft == null ? "null" : String.valueOf(System.identityHashCode(emfLeft)),
                posedLeft == emfLeft,
                System.identityHashCode(posedRight),
                emfRight == null ? "null" : String.valueOf(System.identityHashCode(emfRight)),
                posedRight == emfRight
        );
    }

    /**
     * Logs this mob's real EMF part hierarchy once per entity type. EMF's CEM part names differ per
     * model and per pack, so this is the only reliable way to see what a given Fresh Animations
     * model actually calls its bones instead of assuming.
     */
    @SuppressWarnings("unchecked")
    private static void dumpEmfTreeOnce(LivingEntity entity, ModelPart root) {
        String key = "tree|" + entity.getType();
        if (!DEBUG_LOGGED.add(key)) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        try {
            java.lang.reflect.Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            appendTree(root, (Map<String, ModelPart>) childrenField.get(root), childrenField, builder, 0);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            builder.append(" <could not read children: ").append(exception).append('>');
        }
        BetterMobCombatReimagined.LOGGER.warn(
                "[BMC-EMF-TREE] real EMF part hierarchy for {}:\n{}",
                entity.getType(),
                builder
        );
    }

    @SuppressWarnings("unchecked")
    private static void appendTree(
            ModelPart part,
            Map<String, ModelPart> children,
            java.lang.reflect.Field childrenField,
            StringBuilder builder,
            int depth
    ) throws ReflectiveOperationException {
        if (depth > 6 || children == null) {
            return;
        }
        for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
            builder.append("  ".repeat(depth + 1)).append(entry.getKey()).append('\n');
            appendTree(
                    entry.getValue(),
                    (Map<String, ModelPart>) childrenField.get(entry.getValue()),
                    childrenField,
                    builder,
                    depth + 1
            );
        }
    }

    /**
     * Pauses the named part and every part beneath it, without assuming anything about how this
     * pack names its child bones. {@link ModelPart#getAllParts()} yields the part itself plus all
     * of its descendants.
     */
    private static boolean addPartTree(ModelPart root, String path, Set<ModelPart> partsToPause) {
        ModelPart part = findPart(root, path);
        if (part == null) {
            return false;
        }
        part.getAllParts().forEach(partsToPause::add);
        return true;
    }

    private static void setVisible(ModelPart root, String path, boolean visible, List<PartState> saved) {
        ModelPart part = findPart(root, path);
        if (part != null && part.visible != visible) {
            saveOnce(part, saved);
            part.visible = visible;
        }
    }

    private static boolean addIfPresent(ModelPart root, String path, Set<ModelPart> partsToPause) {
        ModelPart part = findPart(root, path);
        if (part != null) {
            partsToPause.add(part);
            return true;
        }
        return false;
    }

    private static void saveOnce(ModelPart part, List<PartState> saved) {
        for (PartState state : saved) {
            if (state.part() == part) {
                return;
            }
        }
        saved.add(PartState.capture(part));
    }

    /**
     * True when EMF has an animated custom model for this EntityModel, i.e. EMF owns the render
     * hierarchy and any manual part-by-part rendering we do would fight it.
     */
    public static boolean isEmfAnimatedModel(EntityModel<?> model) {
        if (!initialize()) {
            return false;
        }
        try {
            return emfModelInterface != null
                    && emfModelInterface.isInstance(model)
                    && Boolean.TRUE.equals(isModelAnimated.invoke(null, model));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
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
            registerPauseCondition(api);
        } catch (ClassNotFoundException ignored) {
            available = false;
        } catch (ReflectiveOperationException exception) {
            available = false;
            warnOnce("Entity Model Features was found, but its animation API was not compatible", exception);
        }
        return available;
    }

    /**
     * Registers a pause condition with EMF.
     *
     * <p>This is the API EMF added explicitly for animation/emote mods (its changelog cites
     * EmoteCraft, which is built on the same Player Animator library this mod embeds). It is the
     * correct mechanism for our case.</p>
     *
     * <p>Pausing individual model parts cannot work here: EMF re-asserts its own transforms during
     * its render pass, after every hook we have. Measurements confirmed Better Combat writes a
     * correct, fully-formed swing into the exact ModelPart EMF renders, immediately before the draw
     * call - and EMF still discarded it. A registered pause condition instead makes EMF skip
     * <em>animating</em> the entity for that frame, while its custom model geometry and textures
     * (i.e. everything Fresh Animations looks like) continue to render normally.</p>
     */
    private static void registerPauseCondition(Class<?> api) {
        Method register = null;
        for (Method method : api.getMethods()) {
            if (method.getName().equals("registerPauseCondition")
                    && method.getParameterCount() == 1
                    && Modifier.isStatic(method.getModifiers())) {
                register = method;
                break;
            }
        }
        if (register == null) {
            debugOnce("EMF has no registerPauseCondition(Function) - this EMF version predates the "
                    + "animation-mod pause API, falling back to per-part pausing (which EMF may "
                    + "override during its own render pass)");
            return;
        }

        try {
            java.util.function.Function<Object, Boolean> condition = emfEntity -> {
                try {
                    Entity entity = entityFromEmfEntity(emfEntity);
                    if (!(entity instanceof LivingEntity living)) {
                        return Boolean.FALSE;
                    }
                    // Pause EMF's animations for exactly as long as Better Combat is driving this
                    // mob. Everything else about the entity - model, texture, all other mobs -
                    // is untouched.
                    return EmbeddedPlayerAnimator.isAnimating(living);
                } catch (RuntimeException exception) {
                    return Boolean.FALSE;
                }
            };
            register.invoke(null, condition);
            pauseConditionRegistered = true;
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC-EMF] Registered pause condition with EMF - Fresh Animations will yield the "
                            + "model to Better Combat while a mob attack/weapon pose is playing."
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to register a pause condition with Entity Model Features", exception);
        }
    }

    /** Resolves the vanilla Entity behind EMF's EMFEntity wrapper, whatever it names its accessor. */
    private static Entity entityFromEmfEntity(Object emfEntity) {        if (emfEntity instanceof Entity direct) {
        return direct;
    }
        if (emfEntityAccessor == null) {
            for (Method method : emfEntity.getClass().getMethods()) {
                if (method.getParameterCount() == 0
                        && Entity.class.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    emfEntityAccessor = method;
                    debugOnce("Resolved EMFEntity -> Entity accessor: " + method.getName());
                    break;
                }
            }
        }
        if (emfEntityAccessor == null) {
            debugOnce("Could not resolve an Entity accessor on EMFEntity ("
                    + emfEntity.getClass().getName() + ")");
            return null;
        }
        try {
            return (Entity) emfEntityAccessor.invoke(emfEntity);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
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