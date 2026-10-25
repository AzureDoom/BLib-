package com.blib.neoforge.client.viewer;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.blib.api.client.viewer.v1.BLibViewers;
import com.blib.api.client.viewer.v1.ViewerPage;
import com.blib.api.client.viewer.v1.ViewerSlot;
import com.blib.internal.client.viewer.ViewerLayout;

/**
 * BLib's EMI plugin: draws every page registered with {@link BLibViewers}, the same layout as the JEI and REI ones.
 * Fluid amounts are given in this platform's own unit (1L per millibucket).
 */
@dev.emi.emi.api.EmiEntrypoint
public final class BLibEmiPlugin implements EmiPlugin {

    private static final long UNITS_PER_MILLIBUCKET = 1L;

    @Override
    public void register(EmiRegistry registry) {
        // Items mods have asked to keep out of the viewers (BLibViewers.hide).
        var hidden = BLibViewers.hiddenItems();
        if (!hidden.isEmpty()) {
            registry.removeEmiStacks(stack -> !stack.getItemStack().isEmpty() && hidden.contains(stack.getItemStack().getItem()));
        }

        Map<ResourceLocation, EmiRecipeCategory> categories = new HashMap<>();
        for (var category : BLibViewers.categories()) {
            var icon = EmiStack.of(BLibViewers.safeIcon(category));
            var title = category.title();
            // EMI names a category from a lang key it builds from the id; ours carry their own title instead, so no
            // lang
            // file is needed and a tab never shows a raw key.
            var emiCategory = new EmiRecipeCategory(category.id(), icon, icon) {

                @Override
                public Component getName() {
                    return title;
                }
            };
            categories.put(category.id(), emiCategory);
            registry.addCategory(emiCategory);
            for (var workstation : BLibViewers.safeWorkstations(category)) {
                registry.addWorkstation(emiCategory, EmiStack.of(workstation));
            }
        }

        var index = 0;
        for (var page : BLibViewers.pages()) {
            var category = categories.get(page.category());
            if (category != null) {
                var id = ResourceLocation.fromNamespaceAndPath(
                    page.category().getNamespace(),
                    "/" + page.category().getPath() + "/" + index++
                );
                registry.addRecipe(new Page(category, id, page));
            }
        }

        for (var info : BLibViewers.info()) {
            List<EmiIngredient> stacks = info.items().stream().map(stack -> (EmiIngredient) EmiStack.of(stack)).toList();
            var id = ResourceLocation.fromNamespaceAndPath(info.id().getNamespace(), "/info/" + info.id().getPath());
            registry.addRecipe(new dev.emi.emi.api.recipe.EmiInfoRecipe(stacks, info.lines(), id));
        }
    }

    private static EmiIngredient ingredient(ViewerSlot slot) {
        if (slot.fluid() != null) {
            return EmiStack.of(slot.fluid(), Math.max(1L, slot.millibuckets() * UNITS_PER_MILLIBUCKET));
        }
        return EmiIngredient.of(slot.items().stream().map(EmiStack::of).toList());
    }

    private static final class Page extends BasicEmiRecipe {

        private final ViewerPage page;

        Page(EmiRecipeCategory category, ResourceLocation id, ViewerPage page) {
            super(category, id, ViewerLayout.WIDTH, ViewerLayout.HEIGHT);
            this.page = page;
            for (var input : page.inputs()) {
                inputs.add(ingredient(input));
            }
            for (var output : page.outputs()) {
                var ingredient = ingredient(output);
                for (var stack : ingredient.getEmiStacks()) {
                    outputs.add(stack);
                }
            }
        }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            var font = Minecraft.getInstance().font;
            var y = 0;
            for (var note : page.notes()) {
                if (y > 10) {
                    break;
                }
                var lines = font.split(note, ViewerLayout.WIDTH);
                if (!lines.isEmpty()) {
                    widgets.addText(lines.get(0), 0, y, 0x404040, false);
                }
                y += 10;
            }

            var pageInputs = page.inputs();
            for (int i = 0; i < pageInputs.size() && i < ViewerLayout.MAX_INPUTS; i++) {
                widgets.addSlot(ingredient(pageInputs.get(i)), ViewerLayout.inputX(i), ViewerLayout.inputY(i));
            }

            if (page.timeTicks() > 0) {
                widgets.addFillingArrow(ViewerLayout.ARROW_X, ViewerLayout.arrowY(), page.timeTicks() * 50);
            } else {
                widgets.addTexture(dev.emi.emi.api.render.EmiTexture.EMPTY_ARROW, ViewerLayout.ARROW_X, ViewerLayout.arrowY());
            }

            var pageOutputs = page.outputs();
            for (int i = 0; i < pageOutputs.size() && i < ViewerLayout.MAX_OUTPUTS; i++) {
                var output = pageOutputs.get(i);
                var slot = widgets.addSlot(ingredient(output), ViewerLayout.outputX(i), ViewerLayout.outputY(i)).recipeContext(this);
                for (Component line : output.tooltip()) {
                    slot.appendTooltip(line);
                }
            }
        }
    }
}
