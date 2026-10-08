package com.blib.internal.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.blib.api.client.render.v1.dismemberment.ModelPartResolverRegistry;
import com.blib.api.common.dismemberment.v1.Dismemberable;

/**
 * Skips rendering a held item on a parent body that already has its corresponding arm hidden by dismemberment. Without
 * this, {@link ItemInHandLayer} keeps drawing whatever the entity holds even after the arm subtree has been hidden —
 * which presents as a bow / sword / crossbow floating in mid-air where the arm used to be.
 * <p>
 * Detection goes through {@link ModelPartResolverRegistry} so {@code right_arm}/{@code left_arm} resolve consistently
 * across {@code HumanoidModel}, {@code IllagerModel}, and any other registered humanoid-shaped model.
 */
@Mixin(ItemInHandLayer.class)
public abstract class MixinItemInHandLayer_Dismemberment<T extends LivingEntity, M extends EntityModel<T> & ArmedModel> {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void blib$skipForDetachedArm(
        LivingEntity livingEntity,
        ItemStack itemStack,
        ItemDisplayContext displayContext,
        HumanoidArm arm,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int packedLight,
        CallbackInfo ci
    ) {
        // ⚠⚠ ONLY WHEN THIS MOD HID THE ARM. An invisible arm part is not proof of dismemberment: other mods hide the
        // vanilla arms to draw their own — Mekanism's MekaSuit chestplate sets both arms invisible for the whole
        // render — and this cancel then made every held item vanish in third person ("gun models become invisible
        // ... only the mekasuit chestplate"). A body with nothing detached is not ours to touch.
        if (
            !(livingEntity instanceof Dismemberable dismemberable)
                || dismemberable.getDismembermentManager() == null
                || !dismemberable.getDismembermentManager().hasAnyDetached()
        ) {
            return;
        }

        @SuppressWarnings("unchecked")
        var self = (RenderLayer<T, M>) (Object) this;
        var parentModel = self.getParentModel();

        if (parentModel == null) {
            return;
        }

        var partName = arm == HumanoidArm.RIGHT ? "right_arm" : "left_arm";
        var armPart = ModelPartResolverRegistry.resolve(parentModel, partName);

        if (armPart != null && !armPart.visible) {
            ci.cancel();
        }
    }
}
