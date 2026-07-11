package bmc_re.better_mob_combat.mixin.client.embedded;

import bmc_re.better_mob_combat.internal.mobanim.EmbeddedPlayerAnimator;
import bmc_re.better_mob_combat.internal.mobanim.FirstPersonTracker;
import bmc_re.better_mob_combat.internal.mobanim.HumanoidBodyPose;
import bmc_re.better_mob_combat.internal.mobanim.HumanoidModelAccess;
import dev.kosmx.playerAnim.core.impl.AnimationProcessor;
import dev.kosmx.playerAnim.core.util.SetableSupplier;
import dev.kosmx.playerAnim.impl.IMutableModel;
import dev.kosmx.playerAnim.impl.IPlayerModel;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/**
 * Embedded/adapted from Mob Player Animator's 1.21.1 HumanoidModel mixin.
 *
 * <p>This is the key armor fix: animation is supplied during the model's own setup pass. Player
 * Animator's existing HumanoidModel mixin then copies the same live processor into the inner and
 * outer armor models and uses it for bend-aware rendering.</p>
 */
@Mixin(value = HumanoidModel.class, priority = 2000)
public abstract class HumanoidModelMixin<T extends LivingEntity> extends AgeableListModel<T>
        implements IPlayerModel, IMutableModel, HumanoidModelAccess, FirstPersonTracker {

    @Shadow @Final public ModelPart leftLeg;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart hat;

    @Unique
    private HumanoidBodyPose bmc$initialBodyPose;

    @Unique
    private final SetableSupplier<AnimationProcessor> bmc$emoteSupplier = new SetableSupplier<>();

    @Unique
    private boolean bmc$firstPersonNext;

    @Shadow
    public abstract ModelPart getHead();

    @Inject(
            method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;Ljava/util/function/Function;)V",
            at = @At("RETURN")
    )
    private void bmc$initializeMobModel(
            ModelPart root,
            Function<?, ?> renderType,
            CallbackInfo ci
    ) {
        // PlayerModel owns its own supplier through Player Animator. All other humanoid models need
        // a dedicated supplier so copyPropertiesTo can propagate it into armor models.
        if (!((Object) this instanceof PlayerModel<?>)) {
            EmbeddedPlayerAnimator.initializeSupplier((IMutableModel) (Object) this, this.bmc$emoteSupplier);
        }

        this.bmc$initialBodyPose = new HumanoidBodyPose(
                this.head.storePose(),
                this.body.storePose(),
                this.leftArm.storePose(),
                this.rightArm.storePose(),
                this.leftLeg.storePose(),
                this.rightLeg.storePose()
        );
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("HEAD")
    )
    private void bmc$restoreBakedPivots(
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        if (!((Object) this instanceof PlayerModel<?>)) {
            EmbeddedPlayerAnimator.resetToBakedPose(this);
        }
    }

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("TAIL")
    )
    private void bmc$applyMobAnimation(
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        if (!((Object) this instanceof PlayerModel<?>)) {
            EmbeddedPlayerAnimator.applyToModel(this, EmbeddedPlayerAnimator.getAnimation(entity));
        }
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
        return this.hat;
    }

    @Override
    public ModelPart bmc$getBody() {
        return this.body;
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
