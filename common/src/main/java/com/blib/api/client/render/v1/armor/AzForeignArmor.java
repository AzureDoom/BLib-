package com.blib.api.client.render.v1.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.blib.api.BLibAPI;
import com.blib.api.common.mod.v1.BLibMod;
import com.blib.internal.client.service.BLibInternalClientServices;

/**
 * Armor from other mods on an Az entity: anything that has no {@link AzArmorRenderer} but does supply its own humanoid
 * model - GeckoLib armor first and foremost, and on NeoForge any mod using the loader's armor-model hook.
 * <p>
 * ⚠⚠ WHY THIS EXISTS. {@code AzArmorLayer} had two paths: an Az renderer if one was registered, else the material's raw
 * {@code _layer_1/_layer_2} textures on the vanilla humanoid parts. GeckoLib armor draws its own geo model and often
 * ships only one of those two textures (Fracture Point's plate and shoulder pads have no layer 2, its backpack no layer
 * 1), so on a marine it rendered as vanilla's magenta-and-black missing texture. This is the third path.
 * <p>
 * HOW A GECKOLIB PIECE IS DRIVEN - copied from GeckoLib's own {@code InternalUtil.tryRenderGeoArmorPiece}, the code
 * path vanilla's {@code HumanoidArmorLayer} goes through on both loaders, plus the per-part restriction its
 * {@code ItemArmorGeoLayer} uses when GeckoLib entities wear armor:
 * <ol>
 * <li>{@code GeoRenderProvider.of(item).getGeoArmorRenderer(entity, stack, slot, baseModel)} - the renderer, which IS a
 * {@code HumanoidModel}.</li>
 * <li>{@code prepForRender(entity, stack, slot, baseModel, bufferSource, partialTick, limbSwing, ...)} then
 * {@code baseModel.copyPropertiesTo(renderer)} - without the prep the renderer has no entity and logs an error instead
 * of drawing.</li>
 * <li>{@code applyBoneVisibilityByPart(slot, part, baseModel)} - only the geo bones belonging to the one vanilla part
 * being drawn, since Az draws armor bone by bone.</li>
 * <li>{@code renderToBuffer(pose, null, light, overlay, color)} - it binds its own texture and buffers, so the consumer
 * passed is ignored, exactly as BLib's own Az path passes null.</li>
 * </ol>
 * THE GUARD IS THE INNER CLASS. Every GeckoLib type lives in {@link GeckoLib}, loaded only once {@code geckolib} is
 * confirmed present, so nothing here resolves it on an install without GeckoLib. GeckoLib is a {@code compileOnly}
 * dependency of the common module for that reason alone.
 */
public final class AzForeignArmor {

    private static final BLibMod GECKOLIB = BLibAPI.createMod("geckolib");

    /**
     * {@return the model another mod wants drawn for this stack, or null when there is none} Loader hook first (that is
     * how GeckoLib registers on NeoForge, and how every other NeoForge armor mod does), then GeckoLib directly (which
     * is the only route on Fabric).
     */
    public static @Nullable HumanoidModel<?> getModel(
        LivingEntity livingEntity,
        ItemStack itemStack,
        EquipmentSlot equipmentSlot,
        HumanoidModel<?> baseModel
    ) {
        var model = BLibInternalClientServices.CLIENT_REGISTRY.getForeignArmorModel(livingEntity, itemStack, equipmentSlot, baseModel);

        if (model == null && GECKOLIB.isLoaded()) {
            model = GeckoLib.getModel(livingEntity, itemStack, equipmentSlot, baseModel);
        }

        return model == baseModel ? null : model;
    }

    /**
     * Draws one vanilla part's worth of a foreign model, posed like {@code baseModel} (whose parts the caller has
     * already positioned from the Az bone).
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void render(
        HumanoidModel<?> foreignModel,
        HumanoidModel<?> baseModel,
        ModelPart part,
        LivingEntity livingEntity,
        ItemStack itemStack,
        EquipmentSlot equipmentSlot,
        PoseStack poseStack,
        MultiBufferSource bufferSource,
        VertexSupplier vertexSupplier,
        int packedLight,
        int packedOverlay,
        int color,
        float partialTick
    ) {
        ((HumanoidModel) baseModel).copyPropertiesTo((HumanoidModel) foreignModel);

        if (GECKOLIB.isLoaded() && GeckoLib.isGeoArmor(foreignModel)) {
            GeckoLib.render(
                foreignModel,
                baseModel,
                part,
                livingEntity,
                itemStack,
                equipmentSlot,
                poseStack,
                bufferSource,
                packedLight,
                color,
                partialTick
            );

            return;
        }

        // A plain custom HumanoidModel: show only the part being drawn and render it through the mod's textures.
        restrictToPart(foreignModel, baseModel, part);
        foreignModel.renderToBuffer(poseStack, vertexSupplier.get(), packedLight, packedOverlay, color);
    }

    /** One vertex consumer per foreign texture layer; resolved lazily because a GeckoLib model never asks. */
    @FunctionalInterface
    public interface VertexSupplier {

        com.mojang.blaze3d.vertex.VertexConsumer get();
    }

    private static void restrictToPart(HumanoidModel<?> model, HumanoidModel<?> baseModel, ModelPart part) {
        model.setAllVisible(false);

        if (part == baseModel.head) {
            model.head.visible = true;
            model.hat.visible = true;
            model.hat.copyFrom(model.head);
        } else if (part == baseModel.body) {
            model.body.visible = true;
        } else if (part == baseModel.rightArm) {
            model.rightArm.visible = true;
        } else if (part == baseModel.leftArm) {
            model.leftArm.visible = true;
        } else if (part == baseModel.rightLeg) {
            model.rightLeg.visible = true;
        } else if (part == baseModel.leftLeg) {
            model.leftLeg.visible = true;
        }
    }

    /** Everything that names a GeckoLib type. Not loaded until {@link #GECKOLIB} says the mod is present. */
    private static final class GeckoLib {

        private static @Nullable HumanoidModel<?> getModel(
            LivingEntity livingEntity,
            ItemStack itemStack,
            EquipmentSlot equipmentSlot,
            HumanoidModel<?> baseModel
        ) {
            return software.bernie.geckolib.animatable.client.GeoRenderProvider.of(itemStack)
                .getGeoArmorRenderer(livingEntity, itemStack, equipmentSlot, (HumanoidModel<LivingEntity>) baseModel);
        }

        private static boolean isGeoArmor(HumanoidModel<?> model) {
            return model instanceof software.bernie.geckolib.renderer.GeoArmorRenderer<?>;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private static void render(
            HumanoidModel<?> foreignModel,
            HumanoidModel<?> baseModel,
            ModelPart part,
            LivingEntity livingEntity,
            ItemStack itemStack,
            EquipmentSlot equipmentSlot,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int color,
            float partialTick
        ) {
            var renderer = (software.bernie.geckolib.renderer.GeoArmorRenderer<?>) foreignModel;

            // The same limb and head figures vanilla's LivingEntityRenderer hands its layers.
            var limbSwing = livingEntity.walkAnimation.position(partialTick);
            var limbSwingAmount = Math.min(1.0F, livingEntity.walkAnimation.speed(partialTick));
            var bodyYaw = Mth.rotLerp(partialTick, livingEntity.yBodyRotO, livingEntity.yBodyRot);
            var headYaw = Mth.rotLerp(partialTick, livingEntity.yHeadRotO, livingEntity.yHeadRot);
            var netHeadYaw = Mth.wrapDegrees(headYaw - bodyYaw);
            var headPitch = Mth.lerp(partialTick, livingEntity.xRotO, livingEntity.getXRot());

            renderer.prepForRender(
                livingEntity,
                itemStack,
                equipmentSlot,
                baseModel,
                bufferSource,
                partialTick,
                limbSwing,
                limbSwingAmount,
                netHeadYaw,
                headPitch
            );
            ((HumanoidModel) baseModel).copyPropertiesTo((HumanoidModel) renderer);
            renderer.applyBoneVisibilityByPart(equipmentSlot, part, baseModel);
            renderer.renderToBuffer(poseStack, null, packedLight, OverlayTexture.NO_OVERLAY, color);
        }

        private GeckoLib() {
            throw new UnsupportedOperationException();
        }
    }

    private AzForeignArmor() {
        throw new UnsupportedOperationException();
    }
}
