package com.blib.internal.client.viewer.rei;

import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import com.blib.api.client.viewer.v1.BLibViewers;
import com.blib.api.client.viewer.v1.ViewerCategory;
import com.blib.api.client.viewer.v1.ViewerPage;
import com.blib.api.client.viewer.v1.ViewerSlot;
import com.blib.internal.client.viewer.ViewerLayout;

/**
 * BLib's REI plugin: draws every page registered with {@link BLibViewers}. NeoForge loads it through the annotated
 * subclass in BLib's NeoForge module, Fabric through BLib's {@code rei_client} entrypoint.
 */
public class BLibReiPlugin implements REIClientPlugin {

    @Override
    public void registerCategories(CategoryRegistry registry) {
        for (var category : BLibViewers.categories()) {
            var id = CategoryIdentifier.<Display>of(category.id());
            registry.add(new Category(id, category));
            for (var workstation : BLibViewers.safeWorkstations(category)) {
                registry.addWorkstations(id, EntryStacks.of(workstation));
            }
        }
    }

    /** Items mods have asked to keep out of the viewers (BLibViewers.hide). */
    @Override
    public void registerEntries(me.shedaniel.rei.api.client.registry.entry.EntryRegistry registry) {
        var hidden = BLibViewers.hiddenItems();
        if (!hidden.isEmpty()) {
            registry.removeEntryIf(
                (EntryStack<?> stack) -> stack.getValue() instanceof net.minecraft.world.item.ItemStack item && hidden.contains(
                    item.getItem()
                )
            );
        }
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        for (var page : BLibViewers.pages()) {
            registry.add(new Display(page));
        }
        for (var info : BLibViewers.info()) {
            var entries = EntryIngredient.of(info.items().stream().map(EntryStacks::of).toList());
            registry.add(
                me.shedaniel.rei.plugin.common.displays.DefaultInformationDisplay
                    .createFromEntries(entries, info.items().get(0).getHoverName())
                    .lines(info.lines())
            );
        }
    }

    /** One bucket in the platform's own fluid unit: droplets on Fabric, millibuckets on NeoForge. */
    static long bucketAmount() {
        return com.blib.api.BLibAPI.getModLoaderType() == com.blib.api.common.mod.v1.model.loader.ModLoaderType.FABRIC ? 81_000L : 1_000L;
    }

    static EntryIngredient entries(ViewerSlot slot) {
        if (slot.fluid() != null) {
            var amount = Math.max(1L, slot.millibuckets() * bucketAmount() / 1000L);
            EntryStack<?> stack = EntryStacks.of(slot.fluid(), amount);
            for (var line : slot.tooltip()) {
                stack = stack.tooltip(line);
            }
            return EntryIngredient.of(stack);
        }
        var stacks = new ArrayList<EntryStack<?>>();
        for (var item : slot.items()) {
            EntryStack<?> stack = EntryStacks.of(item);
            for (var line : slot.tooltip()) {
                stack = stack.tooltip(line);
            }
            stacks.add(stack);
        }
        return EntryIngredient.of(stacks);
    }

    public static final class Display extends BasicDisplay {

        private final ViewerPage page;

        Display(ViewerPage page) {
            super(
                page.inputs().stream().map(BLibReiPlugin::entries).toList(),
                page.outputs().stream().map(BLibReiPlugin::entries).toList()
            );
            this.page = page;
        }

        @Override
        public CategoryIdentifier<?> getCategoryIdentifier() {
            return CategoryIdentifier.of(page.category());
        }
    }

    private static final class Category implements DisplayCategory<Display> {

        private final CategoryIdentifier<Display> id;

        private final ViewerCategory category;

        Category(CategoryIdentifier<Display> id, ViewerCategory category) {
            this.id = id;
            this.category = category;
        }

        @Override
        public CategoryIdentifier<? extends Display> getCategoryIdentifier() {
            return id;
        }

        @Override
        public Component getTitle() {
            return category.title();
        }

        @Override
        public Renderer getIcon() {
            return EntryStacks.of(BLibViewers.safeIcon(category));
        }

        @Override
        public List<Widget> setupDisplay(Display display, Rectangle bounds) {
            var widgets = new ArrayList<Widget>();
            widgets.add(Widgets.createRecipeBase(bounds));
            var left = bounds.getX() + 4;
            var top = bounds.getY() + 4;

            var y = top;
            for (var note : display.page.notes()) {
                if (y > top + 10) {
                    break;
                }
                widgets.add(Widgets.createLabel(new Point(left, y), note).leftAligned().noShadow().color(0x404040, 0xBBBBBB));
                y += 10;
            }

            var inputs = display.getInputEntries();
            for (int i = 0; i < inputs.size() && i < ViewerLayout.MAX_INPUTS; i++) {
                widgets.add(
                    Widgets.createSlot(new Point(left + ViewerLayout.inputX(i), top + ViewerLayout.inputY(i)))
                        .entries(inputs.get(i))
                        .markInput()
                );
            }
            widgets.add(Widgets.createArrow(new Point(left + ViewerLayout.ARROW_X, top + ViewerLayout.arrowY())));
            var outputs = display.getOutputEntries();
            for (int i = 0; i < outputs.size() && i < ViewerLayout.MAX_OUTPUTS; i++) {
                widgets.add(
                    Widgets.createSlot(new Point(left + ViewerLayout.outputX(i), top + ViewerLayout.outputY(i)))
                        .entries(outputs.get(i))
                        .markOutput()
                );
            }
            return widgets;
        }

        @Override
        public int getDisplayHeight() {
            return ViewerLayout.HEIGHT + 8;
        }

        @Override
        public int getDisplayWidth(Display display) {
            return ViewerLayout.WIDTH + 8;
        }
    }
}
