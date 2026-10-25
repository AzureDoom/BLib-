package com.blib.api.client.viewer.v1;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Supplier;

/**
 * An information page: the items it is about and a few lines of text, shown as JEI's, REI's and EMI's own native
 * information pages. For items no recipe, drop or trade explains - where they come from, or what they are for.
 * <p>
 * The items are a SUPPLIER for the same reason as {@link ViewerCategory}: registered during client setup, read only
 * when a viewer loads. {@code id} must be unique (EMI needs it).
 * </p>
 */
public record ViewerInfo(
    ResourceLocation id,
    Supplier<List<ItemStack>> items,
    List<Component> lines
) {}
