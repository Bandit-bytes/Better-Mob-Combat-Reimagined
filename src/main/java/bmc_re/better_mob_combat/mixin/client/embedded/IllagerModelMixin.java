package bmc_re.better_mob_combat.mixin.client.embedded;

import bmc_re.better_mob_combat.internal.mobanim.EmbeddedPlayerAnimator;
import bmc_re.better_mob_combat.internal.mobanim.FirstPersonTracker;
import bmc_re.better_mob_combat.internal.mobanim.HumanoidBodyPose;
import bmc_re.better_mob_combat.internal.mobanim.IllagerModelAccess;
import bmc_re.better_mob_combat.internal.mobanim.IllagerArmOwnership;
import bmc_re.better_mob_combat.internal.mobanim.OptionalEmfCompat;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.SetableSupplier;
import dev.kosmx.playerAnim.impl.IMutableModel;
import dev.kosmx.playerAnim.impl.IPlayerModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractIllager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Function;

/** Embedded Mob Player Animator support for pillagers, vindicators and other illagers. */
@Mixin(value = IllagerModel.class, priority = 2000)
public abstract class IllagerModelMixin<T extends AbstractIllager> extends HierarchicalModelMixin<T>
        implements IPlayerModel, IMutableModel, IllagerModelAccess, FirstPersonTracker {

    @Shadow @Final private ModelPart head;
    @Shadow @Final private ModelPart hat;
    @Shadow @Final private ModelPart leftArm;
    @Shadow @Final private ModelPart rightArm;
    @Shadow @Final private ModelPart rightLeg;
    @Shadow @Final private ModelPart leftLeg;
    @Shadow @Final private ModelPart arms;

    @Unique
    private ModelPart bmc$body;

    @Unique
    private HumanoidBodyPose bmc$initialBodyPose;

    @Unique
    private SetableSupplier<AnimationProcessor> bmc$animation = new SetableSupplier<>();

    @Unique
    private final SetableSupplier<AnimationProcessor> bmc$ownedSupplier = new SetableSupplier<>();

    @Unique
    private boolean bmc$firstPersonNext;

    protected IllagerModelMixin(Function<ResourceLocation, RenderType> renderType) {
        super(renderType);
    }

    @Shadow
    public abstract ModelPart getHead();

    @Shadow
    public abstract ModelPart getHat();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void bmc$initializeIllagerModel(ModelPart root, CallbackInfo ci) {
        this.bmc$body = root.getChild("body");
        EmbeddedPlayerAnimator.initializeIllagerBends(root, this);
        EmbeddedPlayerAnimator.initializeSupplier(this, this.bmc$ownedSupplier);
        this.bmc$initialBodyPose = new HumanoidBodyPose(
                this.head,
                this.bmc$body,
                this.leftArm,
                this.rightArm,
                this.leftLeg,
                this.rightLeg
        );

    }

    @Override
    protected void bmc$copyMutatedAttributes(EntityModel<T> otherModel) {
        EmbeddedPlayerAnimator.copySupplier((IMutableModel) otherModel, this.bmc$animation);
    }

    @Override
    protected boolean bmc$bendRenderToBuffer(
            PoseStack poseStack,
            VertexConsumer vertices,
            int packedLight,
            int packedOverlay,
            int color
    ) {
        // When EMF owns the model, it owns the render hierarchy even while its animations are paused.
        // This bend path
        // renders each vanilla ModelPart by hand, which under EMF draws them outside that hierarchy:
        // the legs end up at torso height / inverted, and parts get drawn twice (the visible stutter
        // during a swing). It only ever triggers while the animation processor is active, which is
        // exactly why the legs looked fine walking and broke on every swing.
        //
        // Measured pivots confirmed the pose itself is correct (vindicator legs sit at y=11.95,
        // matching the zombie's 12.0), so this is purely a rendering problem. Fall back to the
        // normal render and let EMF draw its own hierarchy.
        if (OptionalEmfCompat.isEmfModel((EntityModel<?>) (Object) this)) {
            return false;
        }

        return EmbeddedPlayerAnimator.renderIllagerWithBends(
                poseStack,
                vertices,
                packedLight,
                packedOverlay,
                color,
                this.bmc$animation,
                List.of(this.head),
                List.of(
                        this.bmc$body,
                        this.rightArm,
                        this.leftArm,
                        this.rightLeg,
                        this.leftLeg,
                        this.hat
                )
        );
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/monster/AbstractIllager;FFFFF)V",
            at = @At("HEAD")
    )
    private void bmc$restoreIllagerPivots(
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        EmbeddedPlayerAnimator.resetToBakedPose(this);
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/monster/AbstractIllager;FFFFF)V",
            at = @At("TAIL")
    )
    private void bmc$applyIllagerAnimation(
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        var animation = EmbeddedPlayerAnimator.getAnimation(entity);

        if (OptionalEmfCompat.isEmfModel((EntityModel<?>) (Object) this)) {
            // Keep the complete Better Combat upper-body animation and, critically, keep the
            // Player Animator supplier active. The held-item renderer reads rightItem/leftItem
            // transforms through that supplier, and many two-handed attacks also use the torso.
            //
            // Fresh Animations must remain in charge of the Vindicator's two-part leg hierarchy,
            // though. Snapshot the walking/running leg transforms produced by vanilla + EMF,
            // apply the complete Better Combat animation, then restore only the two leg anchors.
            // This preserves the authored torso, both arms, hand/item transforms, head motion and
            // two-handed grip without allowing player-shaped leg keyframes to pull CEM legs into
            // the torso.
            float[] leftLegState = bmc$captureTransform(this.leftLeg);
            float[] rightLegState = bmc$captureTransform(this.rightLeg);

            EmbeddedPlayerAnimator.applyToModel(this, animation);

            bmc$restoreTransform(this.leftLeg, leftLegState);
            bmc$restoreTransform(this.rightLeg, rightLegState);
            return;
        }

        EmbeddedPlayerAnimator.applyToModel(this, animation);
    }

    @Unique
    private static float[] bmc$captureTransform(ModelPart part) {
        return new float[] {
                part.x, part.y, part.z,
                part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale
        };
    }

    @Unique
    private static void bmc$restoreTransform(ModelPart part, float[] state) {
        part.x = state[0];
        part.y = state[1];
        part.z = state[2];
        part.xRot = state[3];
        part.yRot = state[4];
        part.zRot = state[5];
        part.xScale = state[6];
        part.yScale = state[7];
        part.zScale = state[8];
    }

    /**
     * Ported from the original 1.20.1 mod. Vanilla IllagerModel computes a local {@code
     * useCrossedArms} boolean (from {@code entity.isAggressive()}/spellcasting checks) and uses it
     * to swap between the crossed-arms "arms" part and the individual leftArm/rightArm parts -
     * hiding whatever the mob is holding whenever that boolean is true. Force it false while our
     * arm animation is active so two-handed idle grips and attacks both keep the individual arms
     * and held weapon visible.
     */
    @ModifyVariable(
            method = "setupAnim(Lnet/minecraft/world/entity/monster/AbstractIllager;FFFFF)V",
            at = @At(value = "STORE", ordinal = 0),
            ordinal = 0
    )
    private boolean bmc$modifyUseCrossedArms(
            boolean useCrossedArms,
            T illager,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        return IllagerArmOwnership.suppressesVanillaAttack(illager) ? false : useCrossedArms;
    }

    @WrapWithCondition(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/AnimationUtils;animateZombieArms(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;ZFF)V"
            )
    )
    private boolean bmc$onlyAnimateZombieArmsWhenIdle(
            ModelPart leftArm,
            ModelPart rightArm,
            boolean aggressive,
            float attackTime,
            float ageInTicks,
            T illager,
            float limbSwing,
            float limbSwingAmount,
            float entityAge,
            float netHeadYaw,
            float headPitch
    ) {
        // Suppress vanilla's arm swing whenever Better Combat owns an arm channel. This includes
        // the authored two-handed idle body pose; allowing this call through would immediately
        // replace that grip with IllagerModel's one-handed aggressive stance.
        return !IllagerArmOwnership.suppressesVanillaAttack(illager);
    }

    @WrapWithCondition(
            method = "setupAnim",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/AnimationUtils;swingWeaponDown(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/world/entity/Mob;FF)V"
            )
    )
    private boolean bmc$onlySwingWeaponWhenIdle(
            ModelPart rightArm,
            ModelPart leftArm,
            Mob mob,
            float attackTime,
            float ageInTicks
    ) {
        return !IllagerArmOwnership.suppressesVanillaAttack(mob);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void setEmoteSupplier(SetableSupplier supplier) {
        this.bmc$animation = supplier;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public SetableSupplier getEmoteSupplier() {
        return this.bmc$animation;
    }

    @Override
    public void playerAnimator_prepForFirstPersonRender() {
        this.bmc$setFirstPersonNext(true);
    }

    @Override
    public ModelPart bmc$getHead() {
        return this.getHead();
    }

    @Override
    public ModelPart bmc$getHat() {
        return this.getHat();
    }

    @Override
    public ModelPart bmc$getBody() {
        return this.bmc$body;
    }

    @Override
    public ModelPart bmc$getLeftArm() {
        return this.leftArm;
    }

    @Override
    public ModelPart bmc$getRightArm() {
        return this.rightArm;
    }

    @Override
    public ModelPart bmc$getLeftLeg() {
        return this.leftLeg;
    }

    @Override
    public ModelPart bmc$getRightLeg() {
        return this.rightLeg;
    }

    @Override
    public ModelPart bmc$getCrossedArms() {
        return this.arms;
    }

    @Override
    public boolean bmc$isFirstPersonNext() {
        return this.bmc$firstPersonNext;
    }

    @Override
    public void bmc$setFirstPersonNext(boolean firstPersonNext) {
        this.bmc$firstPersonNext = firstPersonNext;
    }

    @Override
    public HumanoidBodyPose bmc$getInitialBodyPose() {
        return this.bmc$initialBodyPose;
    }
}