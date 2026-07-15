package bmc_re.better_mob_combat.internal.mobanim;

import net.minecraft.client.model.geom.ModelPart;

public interface HumanoidModelAccess {
    ModelPart bmc$getHead();

    ModelPart bmc$getHat();

    ModelPart bmc$getBody();

    ModelPart bmc$getLeftArm();

    ModelPart bmc$getRightArm();

    ModelPart bmc$getLeftLeg();

    ModelPart bmc$getRightLeg();

    HumanoidBodyPose bmc$getInitialBodyPose();
}
