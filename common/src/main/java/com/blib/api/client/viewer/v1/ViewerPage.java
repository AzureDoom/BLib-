package com.blib.api.client.viewer.v1;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One page on a {@link ViewerCategory}: inputs on the left, outputs on the right, up to two lines of text on top, and
 * an optional time for the arrow (0 = a plain arrow).
 */
public record ViewerPage(
    ResourceLocation category,
    List<ViewerSlot> inputs,
    List<ViewerSlot> outputs,
    List<Component> notes,
    int timeTicks
) {}
