package bmc_re.better_mob_combat.internal.mobanim;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;

/**
 * Resolves {@link ModelPart}'s private {@code children} map under its runtime name. Production Forge
 * 1.20.1 uses SRG names ({@code f_104213_}), so looking the field up by its Mojang name only works in
 * a development environment.
 */
@OnlyIn(Dist.CLIENT)
public final class ModelPartReflection {
    private static volatile Field childrenField;

    private ModelPartReflection() {
    }

    public static Field childrenField() throws NoSuchFieldException {
        Field field = childrenField;
        if (field != null) {
            return field;
        }
        try {
            field = ObfuscationReflectionHelper.findField(ModelPart.class, "f_104213_");
        } catch (ObfuscationReflectionHelper.UnableToFindFieldException exception) {
            NoSuchFieldException wrapped = new NoSuchFieldException("ModelPart#children");
            wrapped.initCause(exception);
            throw wrapped;
        }
        childrenField = field;
        return field;
    }
}
