package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobAnimationAccess;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Vindicator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

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

    private static final String[] HEAD_NAMES = {"head", "head_root", "headroot", "skull"};
    private static final String[] BODY_NAMES = {"body", "torso", "chest", "upper_body", "upperbody"};
    private static final String[] LEFT_ARM_NAMES = {
            "left_arm", "leftarm", "arm_left", "armleft", "left_upper_arm", "leftupperarm", "larm"
    };
    private static final String[] RIGHT_ARM_NAMES = {
            "right_arm", "rightarm", "arm_right", "armright", "right_upper_arm", "rightupperarm", "rarm"
    };
    private static final String[] LEFT_LEG_NAMES = {
            "left_leg", "leftleg", "leg_left", "legleft", "left_upper_leg", "leftupperleg", "lleg"
    };
    private static final String[] RIGHT_LEG_NAMES = {
            "right_leg", "rightleg", "leg_right", "legright", "right_upper_leg", "rightupperleg", "rleg"
    };
    private static final String[] CROSSED_ARM_NAMES = {
            "arms", "crossed_arms", "crossedarms", "arms_rotation", "armsrotation"
    };

    private static final Set<UUID> PAUSED = new HashSet<>();
    private static final Map<UUID, List<PartState>> SAVED_PARTS = new HashMap<>();
    /**
     * Fresh Animations evaluates both visibility and translated leg pivots every frame. EMF's
     * whole-model pause is required so its Vindicator attack expressions cannot erase BMC's arm
     * swing, but the same pause leaves the leg branches at their raw exported CEM pivots (inside
     * the torso) and can hide nested geometry. Preserve the most recent fully evaluated non-attack
     * visibility state plus the complete left/right leg trees, then reassert them for the short
     * synchronized attack.
     *
     * <p>A weak entity key avoids retaining unloaded Vindicators.</p>
     */
    private static final Map<LivingEntity, VindicatorNonArmSnapshot>
            VINDICATOR_NON_ARM_STATE = new WeakHashMap<>();

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
        // Better Mob Combat Reimagined only owns animation stacks attached to Mob instances.
        // Never participate in EMF arbitration for players: FA Player Extension is itself an EMF
        // player model, and pausing it here freezes its complete idle/locomotion animation graph.
        boolean vindicatorCombatOverride = IllagerArmOwnership.suppressesVanillaAttack(entity);
        if (!(entity instanceof MobAnimationAccess) || !initialize()) {
            return;
        }

        // This hook runs before setupAnim. At the first attack frame the EMF tree still contains
        // the previous, fully evaluated Fresh Animations visibility state, which is exactly what we
        // need to preserve for its non-arm geometry while the whole animation graph is paused.
        if (entity instanceof Vindicator) {
            captureVindicatorNonArmState(entity, model);
        }

        if (!EmbeddedPlayerAnimator.isAnimating(entity) && !vindicatorCombatOverride) {
            return;
        }

        UUID uuid = entity.getUUID();

        // With EMF's pause condition registered, EMF already stops animating this entity for us, so
        // the per-part animation pausing below is obsolete. The Fresh Animations VISIBILITY mapping
        // is NOT obsolete though: FA's crossed-arm node is real geometry in its .jem whose
        // visibility was being driven by FA's own animations. Now that those animations are paused,
        // that node would sit permanently visible on top of the individual arms - which renders as
        // a second pair of hands crossed over the chest. Keep exposing/hiding the right parts.
        if (pauseConditionRegistered && shouldPauseWholeEmfModel(entity)) {
            try {
                restoreSavedParts(SAVED_PARTS.remove(uuid));

                if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                    return;
                }

                EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated =
                        EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
                bmc$ensureWeaponArms(entity, animated);
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
            if (isEmfHumanoid) {
                bmc$ensureWeaponArms(entity, animated);
            }
            // Fresh Animations' illager lower-leg hierarchy must keep running during attacks.
            // The whole-model pause condition collapses those child bones into their base CEM pose,
            // so illagers selectively yield only the authored upper-body branches to Better Combat.
            if (model instanceof IllagerModel<?> && entity instanceof AbstractIllager) {
                animated.retainAll(EnumSet.of(
                        EmbeddedPlayerAnimator.AnimatedPart.HEAD,
                        EmbeddedPlayerAnimator.AnimatedPart.TORSO,
                        EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM,
                        EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM
                ));
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
        boolean vindicatorCombatOverride = IllagerArmOwnership.suppressesVanillaAttack(entity);
        if (!(entity instanceof MobAnimationAccess)
                || (!EmbeddedPlayerAnimator.isAnimating(entity) && !vindicatorCombatOverride)
                || !initialized
                || !available) {
            return;
        }
        // A registered whole-model pause prevents EMF from overwriting the attack, but some Fresh
        // Animations models render a rebuilt ModelPart tree rather than the vanilla arm objects that
        // HumanoidModelMixin updated during setupAnim. Reapply the active channels directly to EMF's
        // visible root so skeleton-family and other replaced meshes receive the authored swing.
        if (pauseConditionRegistered && shouldPauseWholeEmfModel(entity)) {
            reapplyWholePausedEmfAnimation(entity, model);
            return;
        }
        if (!PAUSED.contains(entity.getUUID())) {
            return;
        }

        // Illagers under Fresh Animations need a dedicated final pass. EMF can render rebuilt arm
        // branches that are not the same ModelPart objects stored in IllagerModel.leftArm/rightArm.
        // Applying the animation only to those facade fields leaves FA's visible left arm raised and
        // can make the complete attack appear absent. Pose the facade from its baked anchors, then
        // copy the result into EMF's actual rendered arm roots while hiding the real crossed-arms
        // branch for this frame.
        if (entity instanceof AbstractIllager
                && model instanceof IllagerModel<?>
                && reapplyIllagerEmfUpperBody(entity, model)) {
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
        bmc$ensureWeaponArms(entity, animated);
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

    private static boolean reapplyIllagerEmfUpperBody(LivingEntity entity, EntityModel<?> model) {
        if (emfModelInterface == null
                || !emfModelInterface.isInstance(model)
                || !(model instanceof HumanoidModelAccess humanoidAccess)) {
            return false;
        }

        try {
            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return false;
            }
            Object rootObject = getEmfRootModel.invoke(model);
            if (!(rootObject instanceof ModelPart root)) {
                return false;
            }

            var animation = EmbeddedPlayerAnimator.getAnimation(entity);
            boolean animationActive = animation != null && animation.isActive();
            if (animationActive && entity instanceof MobAnimationAccess access) {
                animation.setTickDelta(access.bmc$getRenderPartialTick());
                animationActive = animation.isActive();
            }
            if (!animationActive && !IllagerArmOwnership.suppressesVanillaAttack(entity)) {
                return false;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> authored = animationActive
                    ? EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity)
                    : EnumSet.noneOf(EmbeddedPlayerAnimator.AnimatedPart.class);
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> owned = authored.clone();
            bmc$ensureWeaponArms(entity, owned);
            if (!owned.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)
                    && !owned.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) {
                return false;
            }

            ModelPart emfLeftArm = findAliasedPart(root, LEFT_ARM_NAMES);
            ModelPart emfRightArm = findAliasedPart(root, RIGHT_ARM_NAMES);
            if (emfLeftArm == null && emfRightArm == null) {
                return false;
            }

            List<PartState> saved = SAVED_PARTS.computeIfAbsent(
                    entity.getUUID(), ignored -> new ArrayList<>()
            );
            // FA's current Vindicator model exposes crossed arms as a top-level `arms` branch, not
            // under body/EMF_body. Reassert visibility here because setup/render expressions can
            // change it after the earlier pause hook.
            setIllagerCrossedArmsVisible(model, root, false, saved);
            setVisible(emfLeftArm, true, saved);
            setVisible(emfRightArm, true, saved);

            // IllagerModelMixin has already applied the live Player Animator processor to the
            // facade head/body/arms. Transfer those resulting *deltas* into FA's visible CEM roots
            // instead of applying player-authored absolute pivots directly to CEM parts. This keeps
            // Fresh Animations' own shoulder locations while making the visible arm and Minecraft's
            // held-item anchor use the exact same swing.
            if (authored.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                ModelPart emfBody = findAliasedPart(root, BODY_NAMES);
                resetPart(emfBody);
                copyPoseDelta(humanoidAccess.bmc$getBody(), emfBody);
            }
            if (authored.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
                ModelPart emfHead = findAliasedPart(root, HEAD_NAMES);
                resetPartTree(emfHead);
                copyPoseDelta(humanoidAccess.bmc$getHead(), emfHead);
            }

            // Always reset both FA arm branches while a BMC-weapon Vindicator is aggressive. During
            // the ten-tick commitment window there may be no active keyframe player yet; copying the
            // facade's neutral/walking arm deltas prevents FA's independent raised-left-arm axe pose
            // from masquerading as an early attack. Once the packet starts, these same facade arms
            // already contain the authored one- or two-handed Better Combat animation.
            resetPartTree(emfLeftArm);
            resetPartTree(emfRightArm);
            copyPoseDelta(humanoidAccess.bmc$getLeftArm(), emfLeftArm);
            copyPoseDelta(humanoidAccess.bmc$getRightArm(), emfRightArm);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to apply Better Combat to Fresh Animations' visible illager arms", exception);
            return false;
        }
    }

    private static void resetPart(ModelPart part) {
        if (part != null) {
            part.resetPose();
        }
    }

    private static void resetPartTree(ModelPart part) {
        if (part == null) {
            return;
        }
        part.getAllParts().forEach(ModelPart::resetPose);
    }

    private static void copyTransform(ModelPart source, ModelPart target) {
        if (source == null || target == null || source == target) {
            return;
        }
        target.x = source.x;
        target.y = source.y;
        target.z = source.z;
        target.xRot = source.xRot;
        target.yRot = source.yRot;
        target.zRot = source.zRot;
        target.xScale = source.xScale;
        target.yScale = source.yScale;
        target.zScale = source.zScale;
    }

    /** Copies animation offsets while preserving the destination CEM part's authored pivot. */
    private static void copyPoseDelta(ModelPart source, ModelPart target) {
        if (source == null || target == null || source == target) {
            return;
        }
        var sourceBase = source.getInitialPose();
        var targetBase = target.getInitialPose();
        target.x = targetBase.x + (source.x - sourceBase.x);
        target.y = targetBase.y + (source.y - sourceBase.y);
        target.z = targetBase.z + (source.z - sourceBase.z);
        target.xRot = targetBase.xRot + (source.xRot - sourceBase.xRot);
        target.yRot = targetBase.yRot + (source.yRot - sourceBase.yRot);
        target.zRot = targetBase.zRot + (source.zRot - sourceBase.zRot);
        target.xScale = source.xScale;
        target.yScale = source.yScale;
        target.zScale = source.zScale;
    }

    private static void reapplyWholePausedEmfAnimation(LivingEntity entity, EntityModel<?> model) {
        if (emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return;
        }

        try {
            Object rootObject = getEmfRootModel.invoke(model);
            if (!(rootObject instanceof ModelPart root)) {
                return;
            }

            var animation = EmbeddedPlayerAnimator.getAnimation(entity);
            if (animation == null || !animation.isActive()) {
                return;
            }

            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> authored =
                    EmbeddedPlayerAnimator.getCurrentlyAnimatedParts(entity);
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> owned = authored.clone();
            bmc$ensureWeaponArms(entity, owned);
            if (owned.isEmpty()) {
                return;
            }

            if (entity instanceof MobAnimationAccess access) {
                animation.setTickDelta(access.bmc$getRenderPartialTick());
            }
            if (!animation.isActive()) {
                return;
            }

            ModelPart emfLeftArm = findAliasedPart(root, LEFT_ARM_NAMES);
            ModelPart emfRightArm = findAliasedPart(root, RIGHT_ARM_NAMES);

            if (entity instanceof Vindicator) {
                // Fresh Animations' Vindicator legs are multi-part CEM chains whose usable pivots are
                // produced by EMF expressions every frame. Resetting the head/body/leg trees while the
                // whole model is paused collapses those child bones into their raw exported pose: the
                // feet jump into the torso and the head/body separate. The last evaluated FA pose is
                // already valid and EMF's pause condition preserves it, so leave every non-arm branch
                // completely untouched. Only the two arm trees are reset and replaced by BMC.
                //
                // EMF's whole-model pause also skips FA's visibility expressions. Newer FA revisions
                // use those expressions to expose nested feet/lower-leg pieces, so merely leaving their
                // transforms untouched is not enough: the geometry can become hidden for the attack.
                // Restore the last fully evaluated leg pose and all non-arm visibility before
                // drawing. This keeps FA's translated leg pivots out of the torso while the graph
                // is paused for BMC's real weapon swing.
                restoreVindicatorNonArmState(entity, root);
                resetPartTree(emfLeftArm);
                resetPartTree(emfRightArm);

                List<PartState> saved = SAVED_PARTS.computeIfAbsent(
                        entity.getUUID(), ignored -> new ArrayList<>()
                );
                setIllagerCrossedArmsVisible(model, root, false, saved);
                setVisible(emfLeftArm, true, saved);
                setVisible(emfRightArm, true, saved);

                updateEmfPart(animation, "leftArm", emfLeftArm,
                        authored.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM));
                updateEmfPart(animation, "rightArm", emfRightArm,
                        authored.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM));
                return;
            }

            ModelPart emfHead = findAliasedPart(root, HEAD_NAMES);
            ModelPart emfBody = findAliasedPart(root, BODY_NAMES);
            ModelPart emfLeftLeg = findAliasedPart(root, LEFT_LEG_NAMES);
            ModelPart emfRightLeg = findAliasedPart(root, RIGHT_LEG_NAMES);

            // Non-illager whole-paused models use vanilla-compatible humanoid pivots, so rebuilding
            // their un-authored channels remains safe and keeps locomotion alive during attacks.
            resetPartTree(emfHead);
            resetPartTree(emfBody);
            resetPartTree(emfLeftArm);
            resetPartTree(emfRightArm);
            resetPartTree(emfLeftLeg);
            resetPartTree(emfRightLeg);

            if (model instanceof HumanoidModelAccess facade) {
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD)) {
                    copyPoseDelta(facade.bmc$getHead(), emfHead);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO)) {
                    copyPoseDelta(facade.bmc$getBody(), emfBody);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM)) {
                    copyPoseDelta(facade.bmc$getLeftArm(), emfLeftArm);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM)) {
                    copyPoseDelta(facade.bmc$getRightArm(), emfRightArm);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG)) {
                    copyPoseDelta(facade.bmc$getLeftLeg(), emfLeftLeg);
                }
                if (!authored.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG)) {
                    copyPoseDelta(facade.bmc$getRightLeg(), emfRightLeg);
                }
            }

            updateEmfPart(animation, "head", emfHead,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD));
            updateEmfPart(animation, "torso", emfBody,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO));
            updateEmfPart(animation, "leftArm", emfLeftArm,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM));
            updateEmfPart(animation, "rightArm", emfRightArm,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM));
            updateEmfPart(animation, "leftLeg", emfLeftLeg,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_LEG));
            updateEmfPart(animation, "rightLeg", emfRightLeg,
                    authored.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_LEG));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to apply Better Combat keyframes to EMF's visible model tree", exception);
        }
    }

    private static void updateEmfPart(
            dev.kosmx.playerAnim.impl.animation.AnimationApplier animation,
            String channel,
            ModelPart part,
            boolean active
    ) {
        if (active && part != null) {
            animation.updatePart(channel, part);
        }
    }

    private static int ROT_SAMPLES;

    /**
     * Makes attack-arm ownership explicit. Layer inspection can briefly miss a channel during a
     * transition, which is especially visible on two-handed attacks. The synchronized mob state is
     * authoritative: two-handed poses/attacks always own both arms; one-handed attacks own the
     * mob's dominant weapon arm.
     */
    private static void bmc$ensureWeaponArms(
            LivingEntity entity,
            EnumSet<EmbeddedPlayerAnimator.AnimatedPart> animated
    ) {
        // Fresh Animations drives illager arms as a coupled rig. Even a one-handed BMC attack must
        // claim/reset both visible arms; otherwise FA leaves the off hand in its aggressive raised
        // pose while the weapon arm is controlled by Better Combat.
        if (entity instanceof AbstractIllager
                && IllagerArmOwnership.suppressesVanillaAttack(entity)) {
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            return;
        }

        if (entity instanceof MobAnimationAccess access
                && access.bmc$isTwoHandedArmAnimationActive()) {
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            return;
        }

        if (EmbeddedPlayerAnimator.isAttackAnimating(entity)) {
            if (entity instanceof Mob mob && mob.isLeftHanded()) {
                animated.add(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
            } else {
                animated.add(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
            }
        }
    }

    public static void resume(LivingEntity entity) {
        if (!(entity instanceof MobAnimationAccess) || !initialized || !available) {
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
        boolean head = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.HEAD);
        boolean torso = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.TORSO);
        boolean left = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.LEFT_ARM);
        boolean right = animated.contains(EmbeddedPlayerAnimator.AnimatedPart.RIGHT_ARM);
        boolean foundAny = false;

        // Resolve the real visible EMF branches rather than assuming the vanilla top-level names.
        // Fresh Animations' skeleton and third-party CEM models may nest or rename those branches
        // even though the renderer still exposes a vanilla HumanoidModel facade.
        ModelPart emfHead = findAliasedPart(root, HEAD_NAMES);
        ModelPart emfBody = findAliasedPart(root, BODY_NAMES);
        ModelPart emfLeftArm = findAliasedPart(root, LEFT_ARM_NAMES);
        ModelPart emfRightArm = findAliasedPart(root, RIGHT_ARM_NAMES);

        // Two-handed and heavy attacks frequently animate the torso (and sometimes the head) in
        // addition to both arms. Pause those complete EMF branches too, while deliberately leaving
        // leg branches under Fresh Animations control unless the authored attack actually keys them.
        if (head && addPartTree(emfHead, partsToPause)) {
            foundAny = true;
        }
        if (torso && addPartTree(emfBody, partsToPause)) {
            foundAny = true;
        }
        if (left) {
            setVisible(emfLeftArm, true, saved);
            if (addPartTree(emfLeftArm, partsToPause)) {
                foundAny = true;
            }
        }
        if (right) {
            setVisible(emfRightArm, true, saved);
            if (addPartTree(emfRightArm, partsToPause)) {
                foundAny = true;
            }
        }
        // The crossed-arm hierarchy that needs hiding only exists on illager-family models
        // (Vindicator, Pillager, Evoker, etc.) - plain humanoid mobs like zombies and skeletons
        // never have this node, so skip it for them rather than guess at a path that isn't there.
        if (isIllager && (left || right)) {
            setIllagerCrossedArmsVisible(model, root, false, saved);
        }

        if ((head || torso || left || right) && !foundAny) {
            debugOnce("EMF upper-body part lookup found no matching head/body/arm branches on the "
                    + "EMF root for " + entity.getType() + " (model "
                    + model.getClass().getName() + "). Use EMF's config > Tools > Export models "
                    + "to log this model's real part names.");
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
        ModelPart emfLeft = findAliasedPart(root, LEFT_ARM_NAMES);
        ModelPart emfRight = findAliasedPart(root, RIGHT_ARM_NAMES);

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
    private static boolean addPartTree(ModelPart part, Set<ModelPart> partsToPause) {
        if (part == null) {
            return false;
        }
        part.getAllParts().forEach(partsToPause::add);
        return true;
    }

    private static void setVisible(ModelPart part, boolean visible, List<PartState> saved) {
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
     * Returns true when EMF owns this model instance, even while EMF's custom animations are
     * temporarily paused by Better Mob Combat. This must be used for render-path decisions: a
     * paused EMF model still has EMF's custom hierarchy and must not be rendered part-by-part as a
     * vanilla model.
     */
    public static boolean isEmfModel(EntityModel<?> model) {
        return initialize()
                && emfModelInterface != null
                && emfModelInterface.isInstance(model);
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

    /**
     * Hides every crossed-arm copy that EMF or the vanilla IllagerModel facade may render.
     *
     * <p>Fresh Animations revisions do not all expose this geometry through the same object. Some
     * keep the vanilla {@code IllagerModel.arms} facade and a rebuilt EMF {@code arms} branch at the
     * same time; hiding only the first alias leaves one close/crossed hand flashing over BMC's
     * two-handed grip. Descendants are hidden as well because EMF may flatten a child submodel into
     * a separately rendered wrapper.</p>
     */
    private static void setIllagerCrossedArmsVisible(
            EntityModel<?> model,
            ModelPart root,
            boolean visible,
            List<PartState> saved
    ) {
        Set<ModelPart> crossed = Collections.newSetFromMap(new IdentityHashMap<>());
        crossed.addAll(findAllAliasedParts(root, CROSSED_ARM_NAMES));
        crossed.addAll(findLikelyCrossedArmParts(root));
        if (model instanceof IllagerModelAccess access) {
            crossed.add(access.bmc$getCrossedArms());
        }
        for (ModelPart part : crossed) {
            setVisibleTree(part, visible, saved);
        }
    }

    /**
     * EMF commonly prefixes rebuilt CEM parts with {@code EMF_}. Exact alias matching therefore
     * misses branches such as {@code EMF_arms} or revision-specific names like
     * {@code arms_rotation2}. Those branches are the source of the rare crossed-hand flash seen
     * during an otherwise-correct two-handed swing. Match only unsided arm-group names so normal
     * left/right arm and forearm trees can never be hidden by this fallback.
     */
    private static List<ModelPart> findLikelyCrossedArmParts(ModelPart root) {
        if (root == null) {
            return List.of();
        }

        Set<ModelPart> matches = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<ModelPart> queue = new ArrayDeque<>();
            queue.add(root);
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                ModelPart current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                Object raw = childrenField.get(current);
                if (!(raw instanceof Map<?, ?> children)) {
                    continue;
                }
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }
                    String normalized = normalizePartName(name);
                    while (normalized.startsWith("emf") && normalized.length() > 3) {
                        normalized = normalized.substring(3);
                    }
                    boolean sided = normalized.contains("left") || normalized.contains("right");
                    boolean groupedArms = normalized.equals("arms")
                            || normalized.startsWith("armsrotation")
                            || normalized.startsWith("crossedarm")
                            || normalized.startsWith("crossedhand")
                            || normalized.startsWith("foldedarm")
                            || normalized.startsWith("foldedhand")
                            || normalized.startsWith("joinedarm")
                            || normalized.startsWith("joinedhand")
                            || normalized.startsWith("armscrossed")
                            || normalized.startsWith("armsfolded");
                    if (!sided && groupedArms) {
                        matches.add(part);
                    }
                    queue.addLast(part);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Exact aliases and the vanilla IllagerModel facade are still handled by the caller.
        }
        return List.copyOf(matches);
    }

    private static void setVisibleTree(ModelPart part, boolean visible, List<PartState> saved) {
        if (part == null) {
            return;
        }
        part.getAllParts().forEach(child -> setVisible(child, visible, saved));
    }

    /**
     * Captures only Vindicator geometry that BMC does not own. The snapshot is taken before
     * setupAnim, so on the first attack frame it still represents FA's last fully evaluated model.
     */
    private static void captureVindicatorNonArmState(
            LivingEntity entity,
            EntityModel<?> model
    ) {
        if (emfModelInterface == null || !emfModelInterface.isInstance(model)) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(isModelAnimated.invoke(null, model))) {
                return;
            }
            Object rootObject = getEmfRootModel.invoke(model);
            if (!(rootObject instanceof ModelPart root)) {
                return;
            }

            VindicatorNonArmSnapshot previous = VINDICATOR_NON_ARM_STATE.get(entity);
            boolean attackActive = entity instanceof MobAnimationAccess access
                    && access.bmc$isAttackAnimationActive();
            if (attackActive && previous != null && previous.root() == root) {
                // Do not replace the good pre-attack snapshot with values from a frame whose EMF
                // graph is already paused and whose arm/crossed-arm visibility BMC has modified.
                return;
            }

            Set<ModelPart> excluded = Collections.newSetFromMap(new IdentityHashMap<>());
            addAliasedPartTrees(root, LEFT_ARM_NAMES, excluded);
            addAliasedPartTrees(root, RIGHT_ARM_NAMES, excluded);
            addAliasedPartTrees(root, CROSSED_ARM_NAMES, excluded);
            if (model instanceof IllagerModelAccess illager) {
                addPartTree(illager.bmc$getLeftArm(), excluded);
                addPartTree(illager.bmc$getRightArm(), excluded);
                addPartTree(illager.bmc$getCrossedArms(), excluded);
            }

            IdentityHashMap<ModelPart, VisibilityState> visibilityStates = new IdentityHashMap<>();
            root.getAllParts().forEach(part -> {
                if (!excluded.contains(part)) {
                    visibilityStates.put(part, VisibilityState.capture(part));
                }
            });

            // The FA Vindicator animates leg translations as well as rotations. When EMF pauses the
            // whole model, setupAnim can leave these branches at the raw CEM export pivot instead
            // of their evaluated ty/tx/tz values, which visually pulls the feet into the chest.
            // Snapshot every descendant of both visible leg roots so custom lower-leg/foot nodes
            // are preserved without making assumptions about their names.
            Set<ModelPart> legParts = Collections.newSetFromMap(new IdentityHashMap<>());
            addAliasedPartTrees(root, LEFT_LEG_NAMES, legParts);
            addAliasedPartTrees(root, RIGHT_LEG_NAMES, legParts);
            List<PartState> legStates = new ArrayList<>(legParts.size());
            for (ModelPart part : legParts) {
                legStates.add(PartState.capture(part));
            }

            ModelPart leftLegRoot = findAliasedPart(root, LEFT_LEG_NAMES);
            ModelPart rightLegRoot = findAliasedPart(root, RIGHT_LEG_NAMES);
            float partialTick = entity instanceof MobAnimationAccess access
                    ? access.bmc$getRenderPartialTick()
                    : 1.0F;
            float walkPosition = entity.walkAnimation.position(partialTick);
            float walkSpeed = Mth.clamp(entity.walkAnimation.speed(partialTick), 0.0F, 1.0F);

            VINDICATOR_NON_ARM_STATE.put(
                    entity,
                    new VindicatorNonArmSnapshot(
                            root,
                            visibilityStates,
                            List.copyOf(legStates),
                            leftLegRoot,
                            rightLegRoot,
                            walkPosition,
                            walkSpeed,
                            entity.tickCount + partialTick
                    )
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to preserve Fresh Animations Vindicator non-arm state", exception);
        }
    }

    private static void restoreVindicatorNonArmState(LivingEntity entity, ModelPart root) {
        VindicatorNonArmSnapshot snapshot = VINDICATOR_NON_ARM_STATE.get(entity);
        if (snapshot == null || snapshot.root() != root) {
            return;
        }

        // Restore pose first, then visibility. PartState includes visibility too, but the broader
        // visibility snapshot is authoritative for non-leg branches and safely reasserts the final
        // evaluated state after all leg transforms are back in place.
        for (PartState state : snapshot.legStates()) {
            state.restore();
        }
        applyVindicatorLegContinuation(entity, snapshot);
        snapshot.visibilityStates().forEach((part, state) -> state.restore(part));
    }

    /**
     * Whole-model EMF pausing is still necessary for the visible Vindicator arms, but it also stops
     * FA's locomotion clock. Continue only the two top-level leg roots from the last fully evaluated
     * FA pose. Pivots, lower-leg geometry and foot visibility remain exactly as FA authored them; a
     * small phase-correct walk delta prevents the conspicuous frozen-leg slide during the swing.
     */
    private static void applyVindicatorLegContinuation(
            LivingEntity entity,
            VindicatorNonArmSnapshot snapshot
    ) {
        ModelPart leftLeg = snapshot.leftLegRoot();
        ModelPart rightLeg = snapshot.rightLegRoot();
        if (leftLeg == null && rightLeg == null) {
            return;
        }

        float partialTick = entity instanceof MobAnimationAccess access
                ? access.bmc$getRenderPartialTick()
                : 1.0F;
        float walkPosition = entity.walkAnimation.position(partialTick);
        float walkSpeed = Mth.clamp(entity.walkAnimation.speed(partialTick), 0.0F, 1.0F);

        // Use a deliberately restrained stride. The saved FA pose already contains its own knee and
        // foot shaping; this only advances the upper-leg phase instead of replacing that animation.
        float capturedRight = Mth.cos(snapshot.walkPosition() * 0.6662F)
                * 0.55F * snapshot.walkSpeed();
        float capturedLeft = Mth.cos(snapshot.walkPosition() * 0.6662F + Mth.PI)
                * 0.55F * snapshot.walkSpeed();
        float currentRight = Mth.cos(walkPosition * 0.6662F) * 0.55F * walkSpeed;
        float currentLeft = Mth.cos(walkPosition * 0.6662F + Mth.PI) * 0.55F * walkSpeed;

        // Continue a tiny idle shift as well, so a stationary Vindicator does not become perfectly
        // rigid for the duration of a long custom swing. Subtract the captured phase to avoid a pop
        // on the first attack frame.
        float currentAge = entity.tickCount + partialTick;
        float idleDelta = (Mth.sin(currentAge * 0.12F)
                - Mth.sin(snapshot.capturedAge() * 0.12F)) * 0.012F;

        if (rightLeg != null) {
            rightLeg.xRot += currentRight - capturedRight + idleDelta;
        }
        if (leftLeg != null) {
            leftLeg.xRot += currentLeft - capturedLeft - idleDelta;
        }
    }

    private static void addAliasedPartTrees(
            ModelPart root,
            String[] aliases,
            Set<ModelPart> output
    ) {
        for (ModelPart part : findAllAliasedParts(root, aliases)) {
            addPartTree(part, output);
        }
    }

    private static ModelPart findIllagerCrossedArms(ModelPart root) {
        if (root == null) {
            return null;
        }
        // Prefer the actual top-level `arms` branch used by Fresh Animations' illager models.
        try {
            if (root.hasChild("arms")) {
                return root.getChild("arms");
            }
        } catch (RuntimeException ignored) {
            // Fall through to normalized recursive aliases for alternate FA/CEM revisions.
        }
        return findAliasedPart(root, CROSSED_ARM_NAMES);
    }

    /** Finds every exact normalized alias rather than stopping at the first rebuilt EMF branch. */
    private static List<ModelPart> findAllAliasedParts(ModelPart root, String... aliases) {
        if (root == null) {
            return List.of();
        }

        Set<String> normalizedAliases = new HashSet<>();
        for (String alias : aliases) {
            normalizedAliases.add(normalizePartName(alias));
        }

        Set<ModelPart> matches = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<ModelPart> queue = new ArrayDeque<>();
            queue.add(root);
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                ModelPart current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                Object raw = childrenField.get(current);
                if (!(raw instanceof Map<?, ?> children)) {
                    continue;
                }
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }
                    if (normalizedAliases.contains(normalizePartName(name))) {
                        matches.add(part);
                    }
                    queue.addLast(part);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return List.copyOf(matches);
        }
        return List.copyOf(matches);
    }

    private static ModelPart findAliasedPart(ModelPart root, String... aliases) {
        if (root == null) {
            return null;
        }

        // Fast path for vanilla/standard CEM names.
        for (String alias : aliases) {
            try {
                if (root.hasChild(alias)) {
                    return root.getChild(alias);
                }
            } catch (RuntimeException ignored) {
                // Continue with the recursive normalized lookup.
            }
        }

        Set<String> normalizedAliases = new HashSet<>();
        for (String alias : aliases) {
            normalizedAliases.add(normalizePartName(alias));
        }

        try {
            Field childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
            ArrayDeque<ModelPart> queue = new ArrayDeque<>();
            queue.add(root);
            Set<ModelPart> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!queue.isEmpty()) {
                ModelPart current = queue.removeFirst();
                if (!visited.add(current)) {
                    continue;
                }
                Object raw = childrenField.get(current);
                if (!(raw instanceof Map<?, ?> children)) {
                    continue;
                }
                for (Map.Entry<?, ?> child : children.entrySet()) {
                    if (!(child.getKey() instanceof String name)
                            || !(child.getValue() instanceof ModelPart part)) {
                        continue;
                    }
                    if (normalizedAliases.contains(normalizePartName(name))) {
                        return part;
                    }
                    queue.addLast(part);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static String normalizePartName(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
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
                    // Whole-model pausing is reserved for an actual Better Combat mob attack.
                    // Weapon idle poses must not freeze Fresh Animations locomotion, and players
                    // must never be claimed here because FA Player Extension is an EMF player model.
                    // Illagers still use selective upper-body pausing so their lower-leg CEM bones
                    // remain animated and do not collapse into the torso.
                    return shouldPauseWholeEmfModel(living);
                } catch (RuntimeException exception) {
                    return Boolean.FALSE;
                }
            };
            register.invoke(null, condition);
            pauseConditionRegistered = true;
            BetterMobCombatReimagined.LOGGER.info(
                    "[BMC-EMF] Registered pause condition with EMF - Fresh Animations will yield the "
                            + "visible model tree during synchronized Better Combat mob attacks."
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnOnce("Failed to register a pause condition with Entity Model Features", exception);
        }
    }


    /**
     * True only while a non-illager mob is playing a synchronized Better Combat attack.
     *
     * <p>Do not key this off {@link EmbeddedPlayerAnimator#isAnimating(LivingEntity)}: that also
     * includes persistent weapon idle/body poses. Registering those poses as a whole-entity EMF
     * pause freezes Fresh Animations walking, idling and (for FA Player Extension) the complete
     * player model. Illagers deliberately stay on the selective branch because their custom lower
     * legs require EMF to continue evaluating every frame.</p>
     */
    private static boolean shouldPauseWholeEmfModel(LivingEntity entity) {
        if (!(entity instanceof MobAnimationAccess access) || !access.bmc$isAttackAnimationActive()) {
            return false;
        }

        // Most illagers remain on selective upper-body pausing because their CEM leg hierarchy needs
        // continuous evaluation. Vindicator is the exception: Fresh Animations reasserts its
        // ATTACKING arm expressions after the selective pass, completely erasing BMC's visible swing.
        // Pause its complete FA animation graph only for the short, synchronized BMC attack packet;
        // the final pass replaces only the arm branches and preserves FA's last valid head/body/leg
        // hierarchy instead of rebuilding player-shaped pivots onto the CEM skeleton.
        return !(entity instanceof AbstractIllager) || entity instanceof Vindicator;
    }

    /** Resolves the vanilla Entity behind EMF's EMFEntity wrapper, whatever it names its accessor. */
    private static Entity entityFromEmfEntity(Object emfEntity) {
        if (emfEntity instanceof Entity direct) {
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

    private record VindicatorNonArmSnapshot(
            ModelPart root,
            IdentityHashMap<ModelPart, VisibilityState> visibilityStates,
            List<PartState> legStates,
            ModelPart leftLegRoot,
            ModelPart rightLegRoot,
            float walkPosition,
            float walkSpeed,
            float capturedAge
    ) {
    }

    private record VisibilityState(boolean visible, boolean skipDraw) {
        static VisibilityState capture(ModelPart part) {
            return new VisibilityState(part.visible, part.skipDraw);
        }

        void restore(ModelPart part) {
            part.visible = visible;
            part.skipDraw = skipDraw;
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