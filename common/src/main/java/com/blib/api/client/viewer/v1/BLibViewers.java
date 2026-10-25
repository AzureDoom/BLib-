package com.blib.api.client.viewer.v1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Recipe-viewer pages for any mod, without the mod depending on JEI, REI or EMI.
 * <p>
 * [stated] Oct 24 - the AVP mods' recipe-viewer work: drops for every mob and block, infections and more, in JEI, REI
 * and EMI. A mod registers {@link ViewerCategory categories} and lazy page suppliers here during CLIENT setup; BLib's
 * own JEI, REI and EMI plugins draw every registered page the same way. So a mod with no viewer dependency at all -
 * avp_alien, avp_predator - still gets full JEI, REI and EMI support, and all three viewers always show the same thing.
 * </p>
 * <p>
 * Suppliers run when the viewer (re)loads its recipes - after the player has joined, so the level, its registries and
 * the synced tags are all available. They must be quick and must never throw; a failing supplier is logged and skipped.
 * </p>
 */
public final class BLibViewers {

    private static final Map<net.minecraft.resources.ResourceLocation, ViewerCategory> CATEGORIES = new LinkedHashMap<>();

    private static final List<Supplier<List<ViewerPage>>> PAGES = new ArrayList<>();

    private static final Map<net.minecraft.resources.ResourceLocation, ViewerInfo> INFO = new LinkedHashMap<>();

    private static final List<Supplier<List<net.minecraft.world.item.ItemStack>>> HIDDEN = new ArrayList<>();

    private BLibViewers() {}

    /** Adds a tab. Registering the same id twice keeps the first. */
    public static synchronized void registerCategory(ViewerCategory category) {
        CATEGORIES.putIfAbsent(category.id(), category);
    }

    /** Adds pages, built when the viewers load. */
    public static synchronized void registerPages(Supplier<List<ViewerPage>> pages) {
        PAGES.add(pages);
    }

    /**
     * Mob drops and block drops (Silk Touch and shears included) for every entity and block in {@code namespace}, read
     * from the loot tables in that mod's own jar - see {@link LootViewerPages}. {@code anchor} is any class from that
     * mod, used to find its jar.
     */
    public static void registerLootDrops(String namespace, Class<?> anchor) {
        LootViewerPages.registerCategories();
        registerPages(() -> LootViewerPages.mobDrops(namespace, anchor));
        registerPages(() -> LootViewerPages.blockDrops(namespace, anchor));
    }

    /** The shared "World Interactions" tab: blocks and items that change by something done to them in the world. */
    public static final net.minecraft.resources.ResourceLocation WORLD_INTERACTIONS =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("blib", "world_interactions");

    /** Registers the shared World Interactions tab (safe to call from several mods) and returns its id. */
    public static net.minecraft.resources.ResourceLocation worldInteractions() {
        registerCategory(
            ViewerCategory.of(
                WORLD_INTERACTIONS,
                net.minecraft.network.chat.Component.literal("World Interactions"),
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL)
            )
        );
        return WORLD_INTERACTIONS;
    }

    /**
     * Hides items from JEI's, REI's and EMI's item lists - for items a player must never have (outside commands), so
     * the viewers do not show what the creative menu deliberately leaves out. Read when the viewers load.
     */
    public static synchronized void hide(Supplier<List<net.minecraft.world.item.ItemStack>> items) {
        HIDDEN.add(items);
    }

    /** Every hidden item, looked up now; a lookup that fails hides nothing and is logged. */
    public static java.util.Set<net.minecraft.world.item.Item> hiddenItems() {
        List<Supplier<List<net.minecraft.world.item.ItemStack>>> suppliers;
        synchronized (BLibViewers.class) {
            suppliers = List.copyOf(HIDDEN);
        }
        var items = new java.util.HashSet<net.minecraft.world.item.Item>();
        for (var supplier : suppliers) {
            try {
                var stacks = supplier.get();
                if (stacks != null) {
                    for (var stack : stacks) {
                        if (stack != null && !stack.isEmpty()) {
                            items.add(stack.getItem());
                        }
                    }
                }
            } catch (RuntimeException exception) {
                com.blib.mod.BLib.LOGGER.warn("BLib viewers: a hidden-item list failed and was skipped", exception);
            }
        }
        return items;
    }

    /** Adds an information page (see {@link ViewerInfo}). Registering the same id twice keeps the first. */
    public static synchronized void registerInfo(ViewerInfo info) {
        INFO.putIfAbsent(info.id(), info);
    }

    /**
     * Every information page, with its items resolved now - pages whose items fail to resolve, or resolve to nothing,
     * are left out (and logged), never thrown.
     */
    public static List<ResolvedInfo> info() {
        List<ViewerInfo> all;
        synchronized (BLibViewers.class) {
            all = List.copyOf(INFO.values());
        }
        var resolved = new ArrayList<ResolvedInfo>();
        for (var info : all) {
            try {
                var items = info.items().get();
                if (items == null) {
                    continue;
                }
                var stacks = items.stream().filter(stack -> stack != null && !stack.isEmpty()).toList();
                if (!stacks.isEmpty() && !info.lines().isEmpty()) {
                    resolved.add(new ResolvedInfo(info.id(), stacks, info.lines()));
                }
            } catch (RuntimeException exception) {
                com.blib.mod.BLib.LOGGER.warn("BLib viewers: info page {} failed and was skipped", info.id(), exception);
            }
        }
        return resolved;
    }

    /** An information page with its items looked up. */
    public record ResolvedInfo(
        net.minecraft.resources.ResourceLocation id,
        List<net.minecraft.world.item.ItemStack> items,
        List<net.minecraft.network.chat.Component> lines
    ) {}

    /** A tab's icon, or an empty stack if looking it up fails - a viewer must never crash on a bad icon. */
    public static net.minecraft.world.item.ItemStack safeIcon(ViewerCategory category) {
        try {
            var icon = category.icon().get();
            return icon == null ? net.minecraft.world.item.ItemStack.EMPTY : icon;
        } catch (RuntimeException exception) {
            com.blib.mod.BLib.LOGGER.warn("BLib viewers: icon for {} failed", category.id(), exception);
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    /** A tab's workstations, or none if looking them up fails. */
    public static List<net.minecraft.world.item.ItemStack> safeWorkstations(ViewerCategory category) {
        try {
            var stations = category.workstations().get();
            return stations == null ? List.of() : stations.stream().filter(stack -> stack != null && !stack.isEmpty()).toList();
        } catch (RuntimeException exception) {
            com.blib.mod.BLib.LOGGER.warn("BLib viewers: workstations for {} failed", category.id(), exception);
            return List.of();
        }
    }

    public static synchronized List<ViewerCategory> categories() {
        return List.copyOf(CATEGORIES.values());
    }

    /** Every registered page, built now. */
    public static List<ViewerPage> pages() {
        List<Supplier<List<ViewerPage>>> suppliers;
        synchronized (BLibViewers.class) {
            suppliers = List.copyOf(PAGES);
        }
        var all = new ArrayList<ViewerPage>();
        for (var supplier : suppliers) {
            try {
                var pages = supplier.get();
                if (pages != null) {
                    for (var page : pages) {
                        if (CATEGORIES.containsKey(page.category())) {
                            all.add(page);
                        }
                    }
                }
            } catch (RuntimeException exception) {
                com.blib.mod.BLib.LOGGER.warn("BLib viewers: a page supplier failed and was skipped", exception);
            }
        }
        return all;
    }
}
