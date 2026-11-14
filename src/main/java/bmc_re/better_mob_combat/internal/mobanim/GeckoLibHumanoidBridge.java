package bmc_re.better_mob_combat.internal.mobanim;

import bmc_re.better_mob_combat.BetterMobCombatReimagined;
import bmc_re.better_mob_combat.api.MobAnimationAccess;
import bmc_re.better_mob_combat.client.RangedMobAnimator;
import bmc_re.better_mob_combat.config.BMCConfig;
import bmc_re.better_mob_combat.logic.IronsSpellsCompat;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Drives Better Combat poses, attack animations and Better Mob Combat's ranged motion on GeckoLib
 * humanoid skeletons, currently Iron's Spells casting mobs and addons built on them (Dark
 * Doppelganger and its minions).
 *
 * <p>Those mobs never pass through {@code HumanoidModel}, so none of the vanilla-model hooks reach
 * them. Each frame, after the mob's own GeckoLib animation and Iron's procedural head/walk motion
 * have been applied, the six humanoid bones are copied into a proxy {@link HumanoidModel}, animated
 * there by exactly the same code paths vanilla mobs use, and copied back. GeckoLib recomputes every
 * bone from its keyframes or rest pose on the next frame, so nothing written here persists.</p>
 *
 * <p>Bone/part conversion: GeckoLib renders without vanilla's {@code scale(-1, -1, 1)} flip, so a
 * vanilla part's X/Y rotations and Y offset are negated on the bone. Positions are bone offsets
 * from the rest pose, which is the vanilla part's initial pose.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GeckoLibHumanoidBridge {
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

    @Nullable
    private static HumanoidModel<LivingEntity> proxy;

    private GeckoLibHumanoidBridge() {
    }

    /**
     * @param model the casting mob's GeckoLib model, after its animations were processed this frame
     */
    public static void apply(GeoModel<?> model, LivingEntity entity, float partialTick) {
        if (!(entity instanceof MobAnimationAccess access)
                || !BMCConfig.ENABLED.get()
                || BMCConfig.isBlacklisted(entity.getType())) {
            return;
        }

        AnimationApplier animation = EmbeddedPlayerAnimator.getAnimation(entity);
        boolean attacking = access.bmc$isAttackAnimationActive();
        // Idle weapon poses yield to the mob's own spellcasting/melee animations; Better Combat
        // attacks always win, since they are the attack that actually deals damage.
        boolean posed = access.bmc$isArmAnimationActive()
                && (attacking || !IronsSpellsCompat.isPlayingNativeAnimation(entity));
        boolean playerAnimator = animation != null && posed;
        boolean ranged = RangedMobAnimator.hasRangedWork(entity);
        if (!playerAnimator && !ranged) {
            return;
        }

        AnimationProcessor<?> processor = model.getAnimationProcessor();
        GeoBone head = processor.getBone("head");
        GeoBone torso = processor.getBone("torso");
        GeoBone rightArm = processor.getBone("right_arm");
        GeoBone leftArm = processor.getBone("left_arm");
        GeoBone rightLeg = processor.getBone("right_leg");
        GeoBone leftLeg = processor.getBone("left_leg");
        if (head == null || torso == null || rightArm == null || leftArm == null
                || rightLeg == null || leftLeg == null) {
            logOnce("missing|" + entity.getType() + "|" + model.getClass().getName(),
                    "[BMC GeckoLib] {} ({}) has no humanoid skeleton; Better Combat animations are skipped",
                    entity.getType(), model.getClass().getName());
            return;
        }

        HumanoidModel<LivingEntity> parts = proxy();
        copyIn(head, parts.head);
        copyIn(torso, parts.body);
        copyIn(rightArm, parts.rightArm);
        copyIn(leftArm, parts.leftArm);
        copyIn(rightLeg, parts.rightLeg);
        copyIn(leftLeg, parts.leftLeg);
        parts.hat.copyFrom(parts.head);
        parts.young = entity.isBaby();
        parts.riding = entity.isPassenger();
        parts.crouching = false;

        // Same order as a vanilla humanoid: base aim pose (setupAnim), then the Better Combat layer
        // (setupAnim TAIL), then the ranged overlay (render).
        if (ranged) {
            RangedMobAnimator.poseVanillaRangedArms(entity, parts);
        }

        if (playerAnimator) {
            animation.setTickDelta(partialTick);
            if (animation.isActive()) {
                EmbeddedPlayerAnimator.updatePartSafely(animation, "head", parts.head);
                EmbeddedPlayerAnimator.updatePartSafely(animation, "torso", parts.body);
                EmbeddedPlayerAnimator.updatePartSafely(animation, "rightArm", parts.rightArm);
                EmbeddedPlayerAnimator.updatePartSafely(animation, "leftArm", parts.leftArm);
                EmbeddedPlayerAnimator.updatePartSafely(animation, "rightLeg", parts.rightLeg);
                EmbeddedPlayerAnimator.updatePartSafely(animation, "leftLeg", parts.leftLeg);
                applyBodyChannel(animation, processor.getBone("body"));
            } else {
                playerAnimator = false;
            }
        }

        if (ranged) {
            RangedMobAnimator.apply(entity, parts, partialTick);
        }

        copyOut(parts.head, head);
        copyOut(parts.body, torso);
        copyOut(parts.rightArm, rightArm);
        copyOut(parts.leftArm, leftArm);
        copyOut(parts.rightLeg, rightLeg);
        copyOut(parts.leftLeg, leftLeg);

        // GeckoLib's setters mark a bone as changed, and GeckoLib skips its rest-pose reset next
        // frame for marked bones. Unmarked, every bone written here starts from its own pose again
        // next frame. Left marked, the ranged overlay and the body channel, which add to the pose they
        // find, would build on this frame's result and spin the torso round.
        for (GeoBone bone : new GeoBone[]{head, torso, rightArm, leftArm, rightLeg, leftLeg, processor.getBone("body")}) {
            if (bone != null) {
                bone.resetStateChanges();
            }
        }

        logOnce("applied|" + entity.getType() + "|" + model.getClass().getName(),
                "[BMC GeckoLib] Animating {} ({}) betterCombat={} ranged={}",
                entity.getType(), model.getClass().getName(), playerAnimator, ranged);
    }

    /**
     * Player Animator's "body" channel moves the whole model (vanilla applies it to the pose stack
     * in {@code setupRotations}). Iron's root {@code body} bone parents every limb and pivots at the
     * waist, so the same transform is added to it. GeckoLib applies Z, Y, then X rotation in the
     * same un-flipped space as that pose stack, and translates by {@code -posX}.
     */
    private static void applyBodyChannel(AnimationApplier animation, @Nullable GeoBone root) {
        if (root == null) {
            return;
        }
        Vec3f rotation = animation.get3DTransform("body", TransformType.ROTATION, Vec3f.ZERO);
        Vec3f position = animation.get3DTransform("body", TransformType.POSITION, Vec3f.ZERO);
        root.setRotX(root.getRotX() + rotation.getX());
        root.setRotY(root.getRotY() + rotation.getY());
        root.setRotZ(root.getRotZ() + rotation.getZ());
        root.setPosX(root.getPosX() - position.getX() * 16.0F);
        root.setPosY(root.getPosY() + position.getY() * 16.0F);
        root.setPosZ(root.getPosZ() + position.getZ() * 16.0F);
    }

    private static void copyIn(GeoBone bone, ModelPart part) {
        PartPose rest = part.getInitialPose();
        part.x = rest.x + bone.getPosX();
        part.y = rest.y - bone.getPosY();
        part.z = rest.z + bone.getPosZ();
        part.xRot = -bone.getRotX();
        part.yRot = -bone.getRotY();
        part.zRot = bone.getRotZ();
        part.xScale = bone.getScaleX();
        part.yScale = bone.getScaleY();
        part.zScale = bone.getScaleZ();
    }

    private static void copyOut(ModelPart part, GeoBone bone) {
        PartPose rest = part.getInitialPose();
        bone.setPosX(part.x - rest.x);
        bone.setPosY(rest.y - part.y);
        bone.setPosZ(part.z - rest.z);
        bone.setRotX(-part.xRot);
        bone.setRotY(-part.yRot);
        bone.setRotZ(part.zRot);
        bone.setScaleX(part.xScale);
        bone.setScaleY(part.yScale);
        bone.setScaleZ(part.zScale);
    }

    /** A plain player-shaped model, used only as a pose scratchpad and never rendered. */
    private static HumanoidModel<LivingEntity> proxy() {
        if (proxy == null) {
            proxy = new HumanoidModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER));
        }
        return proxy;
    }

    private static void logOnce(String key, String message, Object... args) {
        if (LOGGED.add(key)) {
            BetterMobCombatReimagined.LOGGER.info(message, args);
        }
    }
}
