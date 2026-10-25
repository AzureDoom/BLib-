package com.blib.api.client.viewer.v1;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One slot on a {@link ViewerPage}: the items it cycles through (or one fluid), and extra tooltip lines such as a drop
 * chance. Fluid amounts are in millibuckets; each viewer converts to its platform's own unit.
 */
public record ViewerSlot(
    List<ItemStack> items,
    @Nullable Fluid fluid,
    long millibuckets,
    List<Component> tooltip
) {

    public static ViewerSlot of(ItemStack stack) {
        return new ViewerSlot(List.of(stack), null, 0L, List.of());
    }

    public static ViewerSlot of(List<ItemStack> stacks) {
        return new ViewerSlot(List.copyOf(stacks), null, 0L, List.of());
    }

    public static ViewerSlot fluid(Fluid fluid, long millibuckets) {
        return new ViewerSlot(List.of(), fluid, millibuckets, List.of());
    }

    public ViewerSlot withTooltip(Component... lines) {
        return new ViewerSlot(items, fluid, millibuckets, List.of(lines));
    }

    public boolean isEmpty() {
        return fluid == null && (items.isEmpty() || items.stream().allMatch(ItemStack::isEmpty));
    }
}
