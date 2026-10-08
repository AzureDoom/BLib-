package com.blib.internal.client.service;

import com.just.core.functional.tuple.Tuple2;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.blib.api.client.input.v1.model.KeyInteractType;
import com.blib.api.client.mod.v1.BLibClientMod;
import com.blib.api.client.registry.v1.AzArmorRendererRegistry;
import com.blib.api.client.registry.v1.AzItemRendererRegistry;
import com.blib.api.client.render.v1.armor.AzArmorRenderer;
import com.blib.api.client.render.v1.item.AzItemRenderer;

@ApiStatus.Internal
public interface BLibClientRegistryService {

    void registerArmorRenderer(
        BLibClientMod mod,
        Supplier<AzArmorRenderer> armorRendererSupplier,
        List<Supplier<? extends Item>> itemSuppliers
    );

    default void registerArmorRendererImmediately(
        BLibClientMod mod,
        Supplier<AzArmorRenderer> armorRendererSupplier,
        List<Supplier<? extends Item>> itemSuppliers
    ) {
        itemSuppliers.forEach(itemSupplier -> AzArmorRendererRegistry.register(armorRendererSupplier, itemSupplier.get()));
    }

    <T extends BlockEntity> void registerBlockEntityRenderer(
        BLibClientMod mod,
        Supplier<BlockEntityType<T>> blockEntityTypeSupplier,
        BlockEntityRendererProvider<T> renderProvider
    );

    void registerBlockRenderLayer(BLibClientMod mod, Supplier<? extends Block> blockSupplier, RenderType renderType);

    <E extends Entity> void registerEntityRenderer(
        BLibClientMod mod,
        Supplier<EntityType<E>> entityTypeSupplier,
        EntityRendererProvider<E> entityRendererFactory
    );

    void registerItemColor(BLibClientMod mod, ItemColor itemColor, List<Supplier<? extends Item>> itemSuppliers);

    void registerItemRenderer(
        BLibClientMod mod,
        Supplier<? extends Item> itemSupplier,
        Function<String, Supplier<AzItemRenderer>> rendererFactory
    );

    default void registerItemRendererImmediately(BLibClientMod mod, Item item, Function<String, Supplier<AzItemRenderer>> rendererFactory) {
        var path = BuiltInRegistries.ITEM.getKey(item).getPath();
        var itemRendererSupplier = rendererFactory.apply(path);
        AzItemRendererRegistry.register(itemRendererSupplier, item);
    }

    Supplier<Tuple2<KeyMapping, Consumer<KeyInteractType>>> registerKeyMapping(
        BLibClientMod mod,
        ResourceLocation resourceLocation,
        String category,
        int key,
        Consumer<KeyInteractType> keyInteractTypeConsumer
    );

    <T extends AbstractContainerMenu, U extends Screen & MenuAccess<T>> void registerMenuScreen(
        BLibClientMod mod,
        Supplier<? extends MenuType<T>> menuTypeSupplier,
        MenuScreens.ScreenConstructor<T, U> screenConstructor
    );

    <T extends ParticleOptions> void registerParticleProviderFactory(
        BLibClientMod mod,
        Supplier<? extends ParticleType<T>> particleTypeSupplier,
        ParticleEngine.SpriteParticleRegistration<T> spriteParticleRegistration
    );

    /**
     * The armor model another mod wants drawn for this stack, if the loader offers a hook for that, or null to use the
     * vanilla layer textures. NeoForge answers through {@code IClientItemExtensions.getHumanoidArmorModel}, which is
     * how GeckoLib and every other NeoForge armor mod supply a custom model; Fabric has no equivalent hook and returns
     * null (GeckoLib on Fabric is reached directly, see {@code AzForeignArmor}).
     */
    default @Nullable HumanoidModel<?> getForeignArmorModel(
        LivingEntity livingEntity,
        ItemStack itemStack,
        EquipmentSlot equipmentSlot,
        HumanoidModel<?> original
    ) {
        return null;
    }

    /**
     * The texture the loader resolves for one armor layer, honouring any per-item override the mod registered. Defaults
     * to the material layer's own texture.
     */
    default ResourceLocation getForeignArmorTexture(
        LivingEntity livingEntity,
        ItemStack itemStack,
        EquipmentSlot equipmentSlot,
        ArmorMaterial.Layer layer,
        boolean innerModel
    ) {
        return layer.texture(innerModel);
    }

}
