package bmc_re.better_mob_combat.internal.mobanim;

import net.minecraft.client.model.geom.PartPose;

/**
 * Stores the baked pivots for a humanoid model. Player Animator keyframes can move pivots and
 * scale parts, so every setup pass must begin from the model's own baked pose rather than hard-coded
 * player coordinates. This is especially important for skeletons, zombies and armor models.
 */
public final class HumanoidBodyPose {
    private final PartPose headPose;
    private final PartPose bodyPose;
    private final PartPose leftArmPose;
    private final PartPose rightArmPose;
    private final PartPose leftLegPose;
    private final PartPose rightLegPose;

    public HumanoidBodyPose(
            PartPose headPose,
            PartPose bodyPose,
            PartPose leftArmPose,
            PartPose rightArmPose,
            PartPose leftLegPose,
            PartPose rightLegPose
    ) {
        this.headPose = headPose;
        this.bodyPose = bodyPose;
        this.leftArmPose = leftArmPose;
        this.rightArmPose = rightArmPose;
        this.leftLegPose = leftLegPose;
        this.rightLegPose = rightLegPose;
    }

    public void apply(HumanoidModelAccess model) {
        model.bmc$getHead().loadPose(this.headPose);
        model.bmc$getBody().loadPose(this.bodyPose);
        model.bmc$getLeftArm().loadPose(this.leftArmPose);
        model.bmc$getRightArm().loadPose(this.rightArmPose);
        model.bmc$getLeftLeg().loadPose(this.leftLegPose);
        model.bmc$getRightLeg().loadPose(this.rightLegPose);
    }
}
