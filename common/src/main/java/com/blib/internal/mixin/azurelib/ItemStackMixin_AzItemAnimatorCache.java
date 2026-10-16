package com.blib.internal.mixin.azurelib;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.UUID;

import com.blib.api.client.animation.v1.animator.AzAnimator;
import com.blib.api.client.animation.v1.animator.AzItemAnimator;
import com.blib.internal.client.animation.AzAnimatorAccessor;
import com.blib.internal.client.animation.cache.AzIdentifiableItemStackAnimatorCache;
import com.blib.internal.common.util.AzureLibUtil;
import com.blib.mod.common.registry.init.BLibDataComponents;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin_AzItemAnimatorCache implements AzAnimatorAccessor<UUID, ItemStack> {

    @Override
    public void setAnimator(@Nullable AzAnimator<UUID, ItemStack> animator) {
        var itemStack = AzureLibUtil.<ItemStack>self(this);
        // Stored for the view currently being rendered on this thread (DEFAULT outside rendering).
        AzIdentifiableItemStackAnimatorCache.getInstance()
            .add(itemStack, com.blib.api.client.animation.v1.animator.AzItemAnimatorView.current(), (AzItemAnimator) animator);
    }

    @Override
    public @Nullable AzAnimator<UUID, ItemStack> getAnimatorOrNull() {
        var self = AzureLibUtil.<ItemStack>self(this);
        var uuid = self.getComponents().get(BLibDataComponents.AZ_ID.get());
        return AzIdentifiableItemStackAnimatorCache.getInstance()
            .getOrNull(uuid, com.blib.api.client.animation.v1.animator.AzItemAnimatorView.current());
    }
}
