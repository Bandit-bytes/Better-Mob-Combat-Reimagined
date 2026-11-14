package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.internal.mobanim.GeckoLibHumanoidBridge;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * Turns items held by GeckoLib humanoids to Better Combat's grip, the item channel Player Animator
 * applies in vanilla's held-item layer. Iron's Spells places the item in the hand and then calls this
 * method to draw it.
 */
@Pseudo
@Mixin(targets = "software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer", remap = false)
public abstract class GeckoLibItemLayerMixin {
    @Inject(
            method = "renderStackForBone(Lcom/mojang/blaze3d/vertex/PoseStack;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lnet/minecraft/world/item/ItemStack;Lsoftware/bernie/geckolib/animatable/GeoAnimatable;Lnet/minecraft/client/renderer/MultiBufferSource;FII)V",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private void bmc$applyHeldItemChannel(
            PoseStack poseStack,
            GeoBone bone,
            ItemStack stack,
            @Coerce Object animatable,
            MultiBufferSource bufferSource,
            float partialTick,
            int packedLight,
            int packedOverlay,
            CallbackInfo ci
    ) {
        if (animatable instanceof LivingEntity entity) {
            GeckoLibHumanoidBridge.applyHeldItem(poseStack, bone.getName(), entity, partialTick);
        }
    }
}
