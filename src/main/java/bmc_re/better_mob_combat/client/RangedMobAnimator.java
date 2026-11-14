package bmc_re.better_mob_combat.client;

import bmc_re.better_mob_combat.api.RangedWeaponKind;
import bmc_re.better_mob_combat.config.BMCConfig;
import net.bettercombat.api.WeaponAttributes;
import net.bettercombat.logic.WeaponRegistry;
import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Adds full-body ranged weapon motion on top of vanilla humanoid arm poses.
 *
 * <p>Better Combat attributes ranged weapons with two-handed idle poses, but it does not provide
 * the same attack-combo animation pipeline used by melee weapons. Vanilla mob models already tell
 * us when they are aiming a bow, charging/holding a crossbow, or preparing a spear. This class
 * enhances those poses and adds a short recoil animation synchronized from the server when the
 * projectile is actually spawned.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RangedMobAnimator {
    private static final int RELEASE_DURATION_TICKS = 7;
    private static final int SPEAR_RELEASE_DURATION_TICKS = 11;
    private static final Map<LivingEntity, ReleaseState> RELEASE_STATES = new WeakHashMap<>();

    private RangedMobAnimator() {
    }

    /**
     * ENABLED and the entity blacklist come from the synced SERVER config, so a server-side blacklist
     * now actually suppresses these client-side animations. The old code checked neither the blacklist
     * (so a blacklisted skeleton kept its enhanced bow draw) nor a synced value (so on a dedicated
     * server the client was reading its own unrelated local COMMON file).
     */
    private static boolean isAnimated(LivingEntity entity) {
        return BMCConfig.ENABLED.get()
                && BMCConfig.ENABLE_RANGED_ANIMATIONS.get()
                && !BMCConfig.isBlacklisted(entity.getType());
    }

    public static void triggerRelease(LivingEntity entity, RangedWeaponKind kind, boolean offHand) {
        if (!isAnimated(entity)) {
            return;
        }
        boolean rightArm = armForHand(entity, offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND)
                == HumanoidArm.RIGHT;
        RELEASE_STATES.put(entity, new ReleaseState(kind, entity.tickCount, rightArm));
    }

    /** Whether {@link #apply} could change anything for this entity right now. */
    public static boolean hasRangedWork(LivingEntity entity) {
        return isAnimated(entity) && (findRangedWeapon(entity) != null || RELEASE_STATES.containsKey(entity));
    }

    /**
     * Sets the arm pose and base aiming rotation vanilla's {@code HumanoidModel#setupAnim} would
     * have produced. Needed for models that are only posed through a {@link HumanoidModel} proxy
     * (GeckoLib casting mobs), since {@link #apply} adds its motion on top of that vanilla aim.
     * Expects the proxy's head rotation to already hold the mob's look direction.
     */
    public static void poseVanillaRangedArms(LivingEntity entity, HumanoidModel<?> model) {
        model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        if (!isAnimated(entity)) {
            return;
        }

        WeaponContext weapon = findRangedWeapon(entity);
        if (weapon == null) {
            return;
        }

        Item item = weapon.stack().getItem();
        boolean using = entity.isUsingItem() && entity.getUsedItemHand() == weapon.hand();
        HumanoidModel.ArmPose pose;
        if (item instanceof CrossbowItem) {
            if (using) {
                pose = HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            } else if (CrossbowItem.isCharged(weapon.stack())) {
                pose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
            } else {
                return;
            }
        } else if (!using) {
            return;
        } else if (item instanceof TridentItem) {
            pose = HumanoidModel.ArmPose.THROW_SPEAR;
        } else {
            pose = HumanoidModel.ArmPose.BOW_AND_ARROW;
        }

        boolean rightHanded = armForHand(entity, weapon.hand()) == HumanoidArm.RIGHT;
        if (rightHanded) {
            model.rightArmPose = pose;
        } else {
            model.leftArmPose = pose;
        }

        ModelPart head = model.head;
        switch (pose) {
            case BOW_AND_ARROW -> {
                model.rightArm.yRot = -0.1F + head.yRot - (rightHanded ? 0.0F : 0.4F);
                model.leftArm.yRot = 0.1F + head.yRot + (rightHanded ? 0.4F : 0.0F);
                model.rightArm.xRot = -Mth.HALF_PI + head.xRot;
                model.leftArm.xRot = -Mth.HALF_PI + head.xRot;
                model.rightArm.zRot = 0.0F;
                model.leftArm.zRot = 0.0F;
            }
            case CROSSBOW_CHARGE -> {
                model.rightArm.zRot = 0.0F;
                model.leftArm.zRot = 0.0F;
                AnimationUtils.animateCrossbowCharge(model.rightArm, model.leftArm, entity, rightHanded);
            }
            case CROSSBOW_HOLD -> {
                model.rightArm.zRot = 0.0F;
                model.leftArm.zRot = 0.0F;
                AnimationUtils.animateCrossbowHold(model.rightArm, model.leftArm, head, rightHanded);
            }
            default -> {
                // THROW_SPEAR: apply() replaces the whole stance with its own javelin wind-up.
            }
        }
    }

    public static void apply(LivingEntity entity, HumanoidModel<?> model, float partialTick) {
        if (!isAnimated(entity)) {
            RELEASE_STATES.remove(entity);
            return;
        }

        WeaponContext weapon = findRangedWeapon(entity);
        PoseKind pose = detectPose(entity, model, weapon);
        boolean rightHanded = weapon == null
                ? entity.getMainArm() == HumanoidArm.RIGHT
                : armForHand(entity, weapon.hand()) == HumanoidArm.RIGHT;
        float age = entity.tickCount + partialTick;

        switch (pose) {
            case BOW_AIM -> applyBowAim(model, rightHanded, age);
            case BOW_IDLE -> applyBowIdle(model, rightHanded, age);
            case CROSSBOW_CHARGE -> applyCrossbowCharge(model, rightHanded, age);
            case CROSSBOW_HOLD -> applyCrossbowHold(model, rightHanded, age);
            case CROSSBOW_IDLE -> applyCrossbowIdle(model, rightHanded, age);
            case SPEAR_AIM -> applySpearAim(model, rightHanded, age);
            case NONE -> {
            }
        }

        ReleaseState release = RELEASE_STATES.get(entity);
        if (release == null) {
            return;
        }

        boolean spear = release.kind() == RangedWeaponKind.SPEAR;
        float duration = spear ? SPEAR_RELEASE_DURATION_TICKS : RELEASE_DURATION_TICKS;
        float elapsed = age - release.startTick();
        if (elapsed >= duration || elapsed < 0.0F) {
            RELEASE_STATES.remove(entity);
            return;
        }

        float progress = Mth.clamp(elapsed / duration, 0.0F, 1.0F);
        float recoil;
        if (spear) {
            // Whip forward to full extension in the first ~20%, then ease back into the wind-up.
            float strike = 0.2F;
            if (progress < strike) {
                float t = progress / strike;
                recoil = 1.0F - (1.0F - t) * (1.0F - t);
            } else {
                float t = (progress - strike) / (1.0F - strike);
                recoil = 1.0F - t * t * (3.0F - 2.0F * t);
            }
        } else {
            recoil = Mth.sin(progress * (float) Math.PI) * (1.0F - progress);
        }
        applyRelease(model, release.rightArm(), release.kind(), recoil);
    }

    private static PoseKind detectPose(
            LivingEntity entity,
            HumanoidModel<?> model,
            WeaponContext weapon
    ) {
        if (model.rightArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW
                || model.leftArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW) {
            return PoseKind.BOW_AIM;
        }
        if (model.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_CHARGE
                || model.leftArmPose == HumanoidModel.ArmPose.CROSSBOW_CHARGE) {
            return PoseKind.CROSSBOW_CHARGE;
        }
        if (model.rightArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD
                || model.leftArmPose == HumanoidModel.ArmPose.CROSSBOW_HOLD) {
            return PoseKind.CROSSBOW_HOLD;
        }
        if (model.rightArmPose == HumanoidModel.ArmPose.THROW_SPEAR
                || model.leftArmPose == HumanoidModel.ArmPose.THROW_SPEAR) {
            return PoseKind.SPEAR_AIM;
        }
        if (weapon == null) {
            return PoseKind.NONE;
        }

        Item heldItem = weapon.stack().getItem();
        if (heldItem instanceof TridentItem && entity.isUsingItem()) {
            return PoseKind.SPEAR_AIM;
        }

        // Better Combat's own pose layer already supplies the normal body/arm stance when a weapon
        // declares one. Only use the procedural idle fallback for ranged weapons without that data.
        WeaponAttributes attributes = WeaponRegistry.getAttributes(weapon.stack());
        boolean hasBetterCombatPose = attributes != null
                && attributes.pose() != null
                && !attributes.pose().isBlank();
        if (hasBetterCombatPose) {
            return PoseKind.NONE;
        }

        if (heldItem instanceof BowItem) {
            return PoseKind.BOW_IDLE;
        }
        if (heldItem instanceof CrossbowItem) {
            return PoseKind.CROSSBOW_IDLE;
        }
        if (heldItem instanceof ProjectileWeaponItem) {
            return PoseKind.BOW_IDLE;
        }
        return PoseKind.NONE;
    }

    private static WeaponContext findRangedWeapon(LivingEntity entity) {
        if (entity.isUsingItem() && isRanged(entity.getUseItem())) {
            return new WeaponContext(entity.getUseItem(), entity.getUsedItemHand());
        }
        if (isRanged(entity.getMainHandItem())) {
            return new WeaponContext(entity.getMainHandItem(), InteractionHand.MAIN_HAND);
        }
        if (isRanged(entity.getOffhandItem())) {
            return new WeaponContext(entity.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        return null;
    }

    private static boolean isRanged(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof ProjectileWeaponItem || item instanceof TridentItem;
    }

    private static HumanoidArm armForHand(LivingEntity entity, InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            return entity.getMainArm();
        }
        return entity.getMainArm() == HumanoidArm.RIGHT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
    }

    private static void applyBowAim(HumanoidModel<?> model, boolean rightHanded, float age) {
        float side = rightHanded ? 1.0F : -1.0F;
        float breathing = Mth.sin(age * 0.11F) * 0.018F;

        model.body.yRot -= side * 0.16F;
        model.body.xRot -= 0.055F + breathing;
        model.head.yRot += side * 0.055F;
        model.head.xRot -= breathing * 0.5F;

        ModelPart weaponArm = rightHanded ? model.rightArm : model.leftArm;
        ModelPart drawArm = rightHanded ? model.leftArm : model.rightArm;
        weaponArm.zRot += side * 0.035F;
        drawArm.zRot -= side * 0.075F;
        drawArm.yRot -= side * 0.055F;

        applyBracedLegs(model, side, 0.12F);
    }

    private static void applyBowIdle(HumanoidModel<?> model, boolean rightHanded, float age) {
        float side = rightHanded ? 1.0F : -1.0F;
        float breathing = Mth.sin(age * 0.09F) * 0.025F;
        ModelPart weaponArm = rightHanded ? model.rightArm : model.leftArm;
        ModelPart supportArm = rightHanded ? model.leftArm : model.rightArm;

        model.body.yRot -= side * 0.075F;
        model.body.xRot -= 0.025F + breathing * 0.25F;
        weaponArm.xRot -= 0.30F + breathing;
        weaponArm.yRot -= side * 0.16F;
        weaponArm.zRot += side * 0.10F;
        supportArm.xRot -= 0.55F + breathing;
        supportArm.yRot += side * 0.28F;
        supportArm.zRot -= side * 0.12F;
    }

    private static void applyCrossbowCharge(HumanoidModel<?> model, boolean rightHanded, float age) {
        float side = rightHanded ? 1.0F : -1.0F;
        float effort = 0.035F + Mth.sin(age * 0.30F) * 0.018F;
        model.body.xRot += 0.09F + effort;
        model.body.yRot -= side * 0.10F;
        model.head.xRot -= 0.045F;
        applyBracedLegs(model, side, 0.09F);
    }

    private static void applyCrossbowHold(HumanoidModel<?> model, boolean rightHanded, float age) {
        float side = rightHanded ? 1.0F : -1.0F;
        float breathing = Mth.sin(age * 0.10F) * 0.015F;
        model.body.xRot -= 0.04F + breathing;
        model.body.yRot -= side * 0.12F;
        model.head.yRot += side * 0.04F;
        applyBracedLegs(model, side, 0.10F);
    }

    private static void applyCrossbowIdle(HumanoidModel<?> model, boolean rightHanded, float age) {
        float side = rightHanded ? 1.0F : -1.0F;
        float breathing = Mth.sin(age * 0.09F) * 0.02F;
        ModelPart weaponArm = rightHanded ? model.rightArm : model.leftArm;
        ModelPart supportArm = rightHanded ? model.leftArm : model.rightArm;

        model.body.yRot -= side * 0.055F;
        weaponArm.xRot -= 0.48F + breathing;
        weaponArm.yRot -= side * 0.18F;
        supportArm.xRot -= 0.62F + breathing;
        supportArm.yRot += side * 0.30F;
    }

    /**
     * Javelin wind-up. Vanilla's THROW_SPEAR pose pushes the arm 45 degrees behind vertical on top
     * of the zombie-arm pose, which leaves the trident hanging behind the head. Replace it with a
     * coiled overhead stance: throwing arm high and slightly back so the trident points at the
     * target, the other arm reaching forward to aim, and the torso turned onto the throwing side.
     */
    private static void applySpearAim(HumanoidModel<?> model, boolean rightHanded, float age) {
        float side = rightHanded ? 1.0F : -1.0F;
        float tension = Mth.sin(age * 0.13F) * 0.025F;
        ModelPart throwingArm = rightHanded ? model.rightArm : model.leftArm;
        ModelPart supportArm = rightHanded ? model.leftArm : model.rightArm;

        model.body.yRot = side * SPEAR_TORSO_TWIST;
        model.body.xRot = -0.06F;
        throwingArm.xRot = SPEAR_ARM_RAISE - tension;
        throwingArm.yRot = side * SPEAR_TORSO_TWIST;
        throwingArm.zRot = 0.0F;
        supportArm.xRot = -1.35F + model.head.xRot * 0.5F;
        supportArm.yRot = side * 0.35F;
        supportArm.zRot = 0.0F;
        applyBracedLegs(model, side, 0.18F);
    }

    /** Arm angle while coiled: just past vertical, so the trident sits above the head. */
    private static final float SPEAR_ARM_RAISE = -3.25F;
    private static final float SPEAR_TORSO_TWIST = 0.30F;

    private static void applyBracedLegs(HumanoidModel<?> model, float side, float amount) {
        model.rightLeg.xRot += amount;
        model.leftLeg.xRot -= amount * 0.65F;
        model.rightLeg.zRot += side * amount * 0.16F;
        model.leftLeg.zRot -= side * amount * 0.16F;
    }

    private static void applyRelease(
            HumanoidModel<?> model,
            boolean rightHanded,
            RangedWeaponKind kind,
            float recoil
    ) {
        float side = rightHanded ? 1.0F : -1.0F;
        ModelPart weaponArm = rightHanded ? model.rightArm : model.leftArm;
        ModelPart otherArm = rightHanded ? model.leftArm : model.rightArm;

        switch (kind) {
            case BOW -> {
                model.body.xRot += recoil * 0.12F;
                model.body.yRot += side * recoil * 0.08F;
                weaponArm.xRot += recoil * 0.15F;
                otherArm.xRot += recoil * 0.26F;
                otherArm.yRot += side * recoil * 0.18F;
            }
            case CROSSBOW -> {
                model.body.xRot += recoil * 0.16F;
                model.body.yRot += side * recoil * 0.06F;
                weaponArm.xRot += recoil * 0.18F;
                otherArm.xRot += recoil * 0.18F;
            }
            case SPEAR -> {
                // recoil is the throw curve here: a fast forward whip of the throwing arm through
                // the target line, the torso uncoiling, and the aiming arm pulling back down.
                // The arm stops well above shoulder height so the trident still in hand finishes
                // angled down at the target instead of flipping over.
                model.body.xRot += recoil * 0.28F;
                model.body.yRot -= side * recoil * (SPEAR_TORSO_TWIST * 2.0F);
                weaponArm.xRot += recoil * 0.95F;
                weaponArm.yRot -= side * recoil * (SPEAR_TORSO_TWIST * 1.6F);
                otherArm.xRot += recoil * 0.95F;
                otherArm.yRot -= side * recoil * 0.25F;
            }
            case GENERIC -> {
                model.body.xRot += recoil * 0.10F;
                weaponArm.xRot += recoil * 0.18F;
                otherArm.xRot += recoil * 0.12F;
            }
        }
    }

    private enum PoseKind {
        NONE,
        BOW_IDLE,
        BOW_AIM,
        CROSSBOW_IDLE,
        CROSSBOW_CHARGE,
        CROSSBOW_HOLD,
        SPEAR_AIM
    }

    private record WeaponContext(ItemStack stack, InteractionHand hand) {
    }

    private record ReleaseState(RangedWeaponKind kind, int startTick, boolean rightArm) {
    }
}