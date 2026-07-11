package bmc_re.better_mob_combat.internal.mobanim;

import net.minecraft.client.model.geom.ModelPart;

/** Internal illager extension of the humanoid model contract. */
public interface IllagerModelAccess extends HumanoidModelAccess {
    ModelPart bmc$getCrossedArms();
}
