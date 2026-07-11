package bmc_re.better_mob_combat.mixin.client;

import bmc_re.better_mob_combat.internal.mobanim.EmbeddedPlayerAnimator;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.monster.Vindicator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The vanilla vindicator renderer only draws its axe while {@link Vindicator#isAggressive()} is
 * true. Better Combat's animation can outlive that vanilla flag by several recovery frames, making
 * the axe disappear or snap in and out halfway through a swing. Keep the item layer enabled for the
 * exact lifetime of our attack layer.
 */
@Mixin(targets = "net.minecraft.client.renderer.entity.VindicatorRenderer$1")
public abstract class VindicatorItemInHandLayerMixin
        extends ItemInHandLayer<Vindicator, IllagerModel<Vindicator>> {

    protected VindicatorItemInHandLayerMixin(
            RenderLayerParent<Vindicator, IllagerModel<Vindicator>> renderer,
            ItemInHandRenderer itemInHandRenderer
    ) {
        super(renderer, itemInHandRenderer);
    }

    @Redirect(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/monster/Vindicator;FFFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/monster/Vindicator;isAggressive()Z"
            )
    )
    private boolean bmc$renderAxeForCompleteAttack(Vindicator vindicator) {
        return vindicator.isAggressive() || EmbeddedPlayerAnimator.isAttackAnimating(vindicator);
    }
}
