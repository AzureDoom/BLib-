package com.blib.api.client.render.v1.armor;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import com.blib.api.client.animation.v1.animator.AzItemAnimator;
import com.blib.api.client.model.v1.AzBakedModel;
import com.blib.api.client.render.v1.AzRendererConfig;
import com.blib.api.client.render.v1.armor.pipeline.AzArmorRendererPipeline;
import com.blib.internal.client.render.AzProvider;
import com.blib.mod.common.registry.init.BLibDataComponents;

public class AzArmorRenderer {

    private Entity entity;

    private final AzProvider<UUID, ItemStack> provider;

    private final AzArmorRendererPipeline rendererPipeline;

    @Nullable
    private AzItemAnimator reusedAzItemAnimator;

    public AzArmorRenderer(AzArmorRendererConfig config) {
        this.provider = new AzProvider<>(
            config::createAnimator,
            config::modelLocation,
            animator -> {
                // ⚠⚠⚠ Aug 28 — THE BRANCHES WERE INVERTED, and the inversion was the armour-corruption bug that
                // survived a full night of predator-side hunting. As written before: a stack WITH an AZ_ID returned
                // UUID.randomUUID() ON EVERY CALL — a fresh context key per lookup — so its per-instance context
                // could never be found again, provideBakedModel's miss path permanently handed back the SHARED
                // baked model, and the per-slot visibility dance then mutated the SHARED bones. Every az armour
                // renderer in every mod that touched that fallback inherited someone else's all-hidden bone state:
                // armour drawing sixty times a second and putting nothing on screen, all mods breaking together,
                // healed only by a full restart (caches die), untouched by F3+T (not resource listeners).
                // Correct form: a stack that HAS an id keeps that id — stable context identity — and only an
                // id-less stack mints a random one at animator creation.
                var azId = animator.get(BLibDataComponents.AZ_ID.get());

                return azId != null ? azId : UUID.randomUUID();
            }
        );
        this.rendererPipeline = createPipeline(config);
    }

    protected AzArmorRendererPipeline createPipeline(AzRendererConfig config) {
        return new AzArmorRendererPipeline(config, this);
    }

    public void prepForRender(
        @Nullable Entity entity,
        ItemStack stack,
        @Nullable EquipmentSlot slot,
        @Nullable HumanoidModel<?> baseModel
    ) {
        if (entity == null || slot == null || baseModel == null) {
            return;
        }

        this.entity = entity;

        rendererPipeline.context().prepare(entity, stack, slot, baseModel);

        var model = provider.provideBakedModel(entity, stack);
        prepareAnimator(stack, model);
    }

    private void prepareAnimator(ItemStack stack, AzBakedModel model) {
        // Point the renderer's current animator reference to the cached entity animator before rendering.
        reusedAzItemAnimator = (AzItemAnimator) provider.provideAnimator(entity, stack);
    }

    public @Nullable AzItemAnimator animator() {
        return reusedAzItemAnimator;
    }

    public AzProvider<UUID, ItemStack> provider() {
        return provider;
    }

    public AzArmorRendererPipeline rendererPipeline() {
        return rendererPipeline;
    }
}
