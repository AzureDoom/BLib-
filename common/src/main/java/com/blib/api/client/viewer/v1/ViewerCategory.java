package com.blib.api.client.viewer.v1;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Supplier;

/**
 * A recipe-viewer tab: its id, title, icon and the workstations shown with it. See {@link BLibViewers}.
 * <p>
 * The icon and workstations are SUPPLIERS, read only when a viewer loads: a mod registers its tabs during client setup,
 * which can run before its items exist (NeoForge registers them later), so nothing here may touch an item early.
 * </p>
 */
public record ViewerCategory(
    ResourceLocation id,
    Component title,
    Supplier<ItemStack> icon,
    Supplier<List<ItemStack>> workstations
) {

    /** A tab with no workstations. */
    public static ViewerCategory of(ResourceLocation id, Component title, Supplier<ItemStack> icon) {
        return new ViewerCategory(id, title, icon, List::of);
    }
}
