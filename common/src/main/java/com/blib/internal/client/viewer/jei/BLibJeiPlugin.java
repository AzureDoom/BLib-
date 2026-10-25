package com.blib.internal.client.viewer.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.blib.api.client.viewer.v1.BLibViewers;
import com.blib.api.client.viewer.v1.ViewerCategory;
import com.blib.api.client.viewer.v1.ViewerPage;
import com.blib.api.client.viewer.v1.ViewerSlot;
import com.blib.internal.client.viewer.ViewerLayout;

/**
 * BLib's JEI plugin: draws every page registered with {@link BLibViewers}, for any mod. On NeoForge JEI finds it by its
 * annotation; on Fabric through BLib's {@code jei_mod_plugin} entrypoint.
 */
@JeiPlugin
public class BLibJeiPlugin implements IModPlugin {

    private static final Map<ResourceLocation, RecipeType<ViewerPage>> TYPES = new HashMap<>();

    private static RecipeType<ViewerPage> type(ResourceLocation id) {
        return TYPES.computeIfAbsent(id, key -> RecipeType.create(key.getNamespace(), key.getPath(), ViewerPage.class));
    }

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("blib", "viewers");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var gui = registration.getJeiHelpers().getGuiHelper();
        var bucket = registration.getJeiHelpers().getPlatformFluidHelper().bucketVolume();
        for (var category : BLibViewers.categories()) {
            registration.addRecipeCategories(new Category(gui, bucket, category));
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var byCategory = new HashMap<ResourceLocation, java.util.ArrayList<ViewerPage>>();
        for (var page : BLibViewers.pages()) {
            byCategory.computeIfAbsent(page.category(), ignored -> new java.util.ArrayList<>()).add(page);
        }
        byCategory.forEach((id, pages) -> registration.addRecipes(type(id), pages));

        for (var info : BLibViewers.info()) {
            registration.addIngredientInfo(info.items(), VanillaTypes.ITEM_STACK, info.lines().toArray(Component[]::new));
        }
    }

    /** Items mods have asked to keep out of the viewers (BLibViewers.hide). */
    @Override
    public void onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime runtime) {
        try {
            var hidden = BLibViewers.hiddenItems().stream().map(net.minecraft.world.item.ItemStack::new).toList();
            if (!hidden.isEmpty()) {
                runtime.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, hidden);
            }
        } catch (RuntimeException exception) {
            com.blib.mod.BLib.LOGGER.warn("BLib viewers: could not hide items from JEI", exception);
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        for (var category : BLibViewers.categories()) {
            for (var workstation : BLibViewers.safeWorkstations(category)) {
                registration.addRecipeCatalyst(workstation, type(category.id()));
            }
        }
    }

    private static final class Category implements IRecipeCategory<ViewerPage> {

        private final IGuiHelper gui;

        private final long bucket;

        private final ViewerCategory category;

        Category(IGuiHelper gui, long bucket, ViewerCategory category) {
            this.gui = gui;
            this.bucket = bucket;
            this.category = category;
        }

        @Override
        public @NotNull RecipeType<ViewerPage> getRecipeType() {
            return type(category.id());
        }

        @Override
        public @NotNull Component getTitle() {
            return category.title();
        }

        @Override
        public IDrawable getIcon() {
            return gui.createDrawableIngredient(VanillaTypes.ITEM_STACK, BLibViewers.safeIcon(category));
        }

        @Override
        public int getWidth() {
            return ViewerLayout.WIDTH;
        }

        @Override
        public int getHeight() {
            return ViewerLayout.HEIGHT;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, ViewerPage page, @NotNull IFocusGroup focuses) {
            var inputs = page.inputs();
            for (int i = 0; i < inputs.size() && i < ViewerLayout.MAX_INPUTS; i++) {
                fill(builder.addSlot(RecipeIngredientRole.INPUT, ViewerLayout.inputX(i), ViewerLayout.inputY(i)), inputs.get(i));
            }
            var outputs = page.outputs();
            for (int i = 0; i < outputs.size() && i < ViewerLayout.MAX_OUTPUTS; i++) {
                fill(builder.addSlot(RecipeIngredientRole.OUTPUT, ViewerLayout.outputX(i), ViewerLayout.outputY(i)), outputs.get(i));
            }
        }

        private void fill(IRecipeSlotBuilder slot, ViewerSlot content) {
            slot.setStandardSlotBackground();
            if (content.fluid() != null) {
                var amount = Math.max(1L, content.millibuckets() * bucket / 1000L);
                slot.setFluidRenderer(Math.max(amount, bucket), false, 16, 16).addFluidStack(content.fluid(), amount);
            } else {
                slot.addItemStacks(content.items());
            }
            if (!content.tooltip().isEmpty()) {
                List<Component> lines = content.tooltip();
                slot.addRichTooltipCallback((view, tooltip) -> lines.forEach(tooltip::add));
            }
        }

        @Override
        public void createRecipeExtras(IRecipeExtrasBuilder builder, @NotNull ViewerPage page, @NotNull IFocusGroup focuses) {
            builder.addAnimatedRecipeArrow(Math.max(20, page.timeTicks())).setPosition(ViewerLayout.ARROW_X, ViewerLayout.arrowY());
        }

        @Override
        public void draw(ViewerPage page, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
            var font = Minecraft.getInstance().font;
            var y = 0;
            for (var note : page.notes()) {
                if (y > 10) {
                    break;
                }
                var lines = font.split(note, ViewerLayout.WIDTH);
                if (!lines.isEmpty()) {
                    graphics.drawString(font, lines.get(0), 0, y, 0x404040, false);
                }
                y += 10;
            }
        }
    }
}
