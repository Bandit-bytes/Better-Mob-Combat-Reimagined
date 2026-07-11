package bmc_re.better_mob_combat.internal.mobanim;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;

/**
 * Stores the baked pivots for a humanoid model. Player Animator keyframes can move pivots and
 * scale parts, so every setup pass must begin from the model's own baked pose rather than hard-coded
 * player coordinates. This is especially important for skeletons, zombies and armor models.
 *
 * <p>{@link PartPose} only captures position and rotation. It does not capture scale. Some shared
 * Better Combat attack animations include a scale keyframe on the arm (for visual effect on the
 * player model), and without also resetting scale here, that shrink would persist indefinitely
 * after the animation ends instead of only lasting for the duration of the keyframe - most visibly
 * as a mob's held weapon appearing to shrink away and never return after a swing.</p>
 */
public final class HumanoidBodyPose {
    private final PartPose headPose;
    private final PartPose bodyPose;
    private final PartPose leftArmPose;
    private final PartPose rightArmPose;
    private final PartPose leftLegPose;
    private final PartPose rightLegPose;

    private final Scale headScale;
    private final Scale bodyScale;
    private final Scale leftArmScale;
    private final Scale rightArmScale;
    private final Scale leftLegScale;
    private final Scale rightLegScale;

    public HumanoidBodyPose(
            ModelPart head,
            ModelPart body,
            ModelPart leftArm,
            ModelPart rightArm,
            ModelPart leftLeg,
            ModelPart rightLeg
    ) {
        this.headPose = head.storePose();
        this.bodyPose = body.storePose();
        this.leftArmPose = leftArm.storePose();
        this.rightArmPose = rightArm.storePose();
        this.leftLegPose = leftLeg.storePose();
        this.rightLegPose = rightLeg.storePose();

        this.headScale = Scale.of(head);
        this.bodyScale = Scale.of(body);
        this.leftArmScale = Scale.of(leftArm);
        this.rightArmScale = Scale.of(rightArm);
        this.leftLegScale = Scale.of(leftLeg);
        this.rightLegScale = Scale.of(rightLeg);
    }

    public void apply(HumanoidModelAccess model) {
        model.bmc$getHead().loadPose(this.headPose);
        model.bmc$getBody().loadPose(this.bodyPose);
        model.bmc$getLeftArm().loadPose(this.leftArmPose);
        model.bmc$getRightArm().loadPose(this.rightArmPose);
        model.bmc$getLeftLeg().loadPose(this.leftLegPose);
        model.bmc$getRightLeg().loadPose(this.rightLegPose);

        this.headScale.applyTo(model.bmc$getHead());
        this.bodyScale.applyTo(model.bmc$getBody());
        this.leftArmScale.applyTo(model.bmc$getLeftArm());
        this.rightArmScale.applyTo(model.bmc$getRightArm());
        this.leftLegScale.applyTo(model.bmc$getLeftLeg());
        this.rightLegScale.applyTo(model.bmc$getRightLeg());
    }

    private record Scale(float x, float y, float z) {
        static Scale of(ModelPart part) {
            return new Scale(part.xScale, part.yScale, part.zScale);
        }

        void applyTo(ModelPart part) {
            part.xScale = this.x;
            part.yScale = this.y;
            part.zScale = this.z;
        }
    }
}