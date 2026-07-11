package bmc_re.better_mob_combat.mixin.client.embedded;

import bmc_re.better_mob_combat.internal.mobanim.EmbeddedPlayerAnimator;
import bmc_re.better_mob_combat.internal.mobanim.FirstPersonTracker;
import bmc_re.better_mob_combat.internal.mobanim.HumanoidBodyPose;
import bmc_re.better_mob_combat.internal.mobanim.IllagerModelAccess;
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
                this.head.storePose(),
                this.bmc$body.storePose(),
                this.leftArm.storePose(),
                this.rightArm.storePose(),
                this.leftLeg.storePose(),
                this.rightLeg.storePose()
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
        // Vindicators normally hide their separate arms whenever the vanilla arm pose returns to
        // CROSSED. A Better Combat swing can continue into recovery after that flag changes, which
        // made the animated arms disappear and the crossed-arms mesh snap back through the axe.
        if (EmbeddedPlayerAnimator.isAttackAnimating(entity)) {
            this.arms.visible = false;
            this.leftArm.visible = true;
            this.rightArm.visible = true;
        }
        EmbeddedPlayerAnimator.applyToModel(this, EmbeddedPlayerAnimator.getAnimation(entity));
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
        return !EmbeddedPlayerAnimator.isAnimating(illager);
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
        return !EmbeddedPlayerAnimator.isAnimating(mob);
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
