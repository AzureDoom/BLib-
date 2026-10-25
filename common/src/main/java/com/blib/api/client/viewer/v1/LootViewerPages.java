package com.blib.api.client.viewer.v1;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mob and block drop pages built from a mod's own loot tables.
 * <p>
 * Recipe viewers cannot see loot tables - they are server data the client never receives. The client does have every
 * mod's jar, so the tables are read straight from it (through the mod's own class, so it works on both loaders) and
 * worked out the way the game rolls them: pools and their rolls, entry weights, random chances, set counts, nested
 * tables, tag entries, and the alternatives a block's table chooses between.
 * </p>
 * <h2>Blocks: Silk Touch and shears</h2> [stated] Oct 24 - "make sure if something is silktouch only like the vats it
 * also has a tab for that". Each block table is worked out three times - mined with an ordinary tool, with Silk Touch,
 * and with shears - by reading the tool conditions in the table itself. The page then says plainly what happens:
 * "Requires Silk Touch", "Silk Touch or shears", or both outcomes when they differ (an ore: Silk Touch keeps the ore,
 * an ordinary pickaxe gives raw ore). A block that simply drops itself gets no page - that is not worth a tab entry,
 * and there are hundreds of them.
 * <h2>What is approximated</h2> Looting and Fortune are left out (the unenchanted chance is shown), and conditions a
 * viewer cannot judge - a block state, the killer's weapon - count as met. Mob pages assume a player kill.
 */
public final class LootViewerPages {

    public static final ResourceLocation MOB_DROPS = ResourceLocation.fromNamespaceAndPath("blib", "mob_drops");

    public static final ResourceLocation BLOCK_DROPS = ResourceLocation.fromNamespaceAndPath("blib", "block_drops");

    private static final int MAX_NESTING = 4;

    private LootViewerPages() {}

    /** The two shared tabs - one Mob Drops and one Block Drops tab for every mod that registers. */
    static void registerCategories() {
        BLibViewers.registerCategory(
            ViewerCategory.of(MOB_DROPS, Component.literal("Mob Drops"), () -> new ItemStack(Items.ROTTEN_FLESH))
        );
        BLibViewers.registerCategory(
            ViewerCategory.of(BLOCK_DROPS, Component.literal("Block Drops"), () -> new ItemStack(Items.IRON_PICKAXE))
        );
    }

    // ---- pages ----

    static List<ViewerPage> mobDrops(String namespace, Class<?> anchor) {
        var pages = new ArrayList<ViewerPage>();
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!namespace.equals(id.getNamespace())) {
                continue;
            }
            var egg = SpawnEggItem.byId(type);
            if (egg == null) {
                continue;
            }
            Map<Item, Drop> drops;
            try {
                var table = read(anchor, type.getDefaultLootTable().location());
                if (table == null) {
                    continue;
                }
                drops = evaluate(anchor, table, Tool.NONE, 0);
            } catch (RuntimeException exception) {
                // One malformed table (a data pack's, another mod's) costs only its own page, never the whole tab.
                com.blib.mod.BLib.LOGGER.warn("BLib viewers: could not read the loot table of {}", id, exception);
                continue;
            }
            if (drops.isEmpty()) {
                continue;
            }
            pages.add(
                new ViewerPage(
                    MOB_DROPS,
                    List.of(ViewerSlot.of(new ItemStack(egg))),
                    slots(drops, "per kill"),
                    List.of(type.getDescription()),
                    0
                )
            );
        }
        return pages;
    }

    static List<ViewerPage> blockDrops(String namespace, Class<?> anchor) {
        var pages = new ArrayList<ViewerPage>();
        var hidden = BLibViewers.hiddenItems();
        for (var block : BuiltInRegistries.BLOCK) {
            var id = BuiltInRegistries.BLOCK.getKey(block);
            if (!namespace.equals(id.getNamespace())) {
                continue;
            }
            var item = block.asItem();
            if (item == Items.AIR || hidden.contains(item)) {
                continue;
            }
            Map<Item, Drop> plain;
            Map<Item, Drop> silk;
            Map<Item, Drop> shears;
            try {
                var table = read(anchor, block.getLootTable().location());
                if (table == null) {
                    continue;
                }
                plain = evaluate(anchor, table, Tool.NONE, 0);
                silk = evaluate(anchor, table, Tool.SILK_TOUCH, 0);
                shears = evaluate(anchor, table, Tool.SHEARS, 0);
            } catch (RuntimeException exception) {
                com.blib.mod.BLib.LOGGER.warn("BLib viewers: could not read the loot table of {}", id, exception);
                continue;
            }
            var input = List.of(ViewerSlot.of(new ItemStack(item)));

            if (plain.isEmpty() && (!silk.isEmpty() || !shears.isEmpty())) {
                var how = !silk.isEmpty() && !shears.isEmpty() && sameItems(silk, shears)
                    ? "Silk Touch or shears only - otherwise drops nothing"
                    : !silk.isEmpty() ? "Requires Silk Touch - otherwise drops nothing" : "Requires shears - otherwise drops nothing";
                var drops = !silk.isEmpty() ? silk : shears;
                pages.add(new ViewerPage(BLOCK_DROPS, input, slots(drops, "per block"), notes(block.getName(), how), 0));
                if (!silk.isEmpty() && !shears.isEmpty() && !sameItems(silk, shears)) {
                    pages.add(new ViewerPage(BLOCK_DROPS, input, slots(shears, "per block"), notes(block.getName(), "With shears"), 0));
                }
                continue;
            }

            if (!plain.isEmpty() && !dropsOnlyItself(plain, item)) {
                pages.add(new ViewerPage(BLOCK_DROPS, input, slots(plain, "per block"), notes(block.getName(), "Mined normally"), 0));
            }
            if (!silk.isEmpty() && !sameItems(silk, plain)) {
                pages.add(new ViewerPage(BLOCK_DROPS, input, slots(silk, "per block"), notes(block.getName(), "With Silk Touch"), 0));
            }
            if (!shears.isEmpty() && !sameItems(shears, plain) && !sameItems(shears, silk)) {
                pages.add(new ViewerPage(BLOCK_DROPS, input, slots(shears, "per block"), notes(block.getName(), "With shears"), 0));
            }
        }
        return pages;
    }

    private static List<Component> notes(Component name, String how) {
        return List.of(name, Component.literal(how).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static boolean dropsOnlyItself(Map<Item, Drop> drops, Item self) {
        if (drops.size() != 1) {
            return false;
        }
        var drop = drops.get(self);
        return drop != null && drop.chance >= 0.999F && drop.min == 1 && drop.max == 1;
    }

    private static boolean sameItems(Map<Item, Drop> a, Map<Item, Drop> b) {
        return a.keySet().equals(b.keySet());
    }

    private static List<ViewerSlot> slots(Map<Item, Drop> drops, String per) {
        var slots = new ArrayList<ViewerSlot>();
        for (var entry : drops.entrySet()) {
            var drop = entry.getValue();
            var stack = new ItemStack(entry.getKey(), Math.max(1, drop.max));
            var count = drop.min == drop.max ? String.valueOf(drop.max) : drop.min + "-" + drop.max;
            var percent = drop.chance * 100.0F;
            var chance = percent >= 99.5F ? "Always" : percent >= 1.0F ? Math.round(percent) + "% " + per : "<1% " + per;
            slots.add(
                ViewerSlot.of(stack)
                    .withTooltip(Component.literal(chance + " (x" + count + ")").withStyle(ChatFormatting.GOLD))
            );
        }
        return slots;
    }

    // ---- reading ----

    /**
     * A loot table as JSON. In single player the game's OWN loaded table is used - so data packs, and other mods that
     * change loot tables as they load (Fabric's loot events), show exactly as they will play. On a server the client
     * has no loot tables at all, so the mod's own file from its jar is used instead.
     */
    private static JsonObject read(Class<?> anchor, ResourceLocation table) {
        var live = readFromIntegratedServer(table);
        return live != null ? live : readFromJar(anchor, table);
    }

    private static JsonObject readFromIntegratedServer(ResourceLocation table) {
        try {
            var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (server == null) {
                return null;
            }
            var key = net.minecraft.resources.ResourceKey.create(Registries.LOOT_TABLE, table);
            var loot = server.reloadableRegistries().getLootTable(key);
            if (loot == net.minecraft.world.level.storage.loot.LootTable.EMPTY) {
                return null;
            }
            var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, server.registryAccess());
            var json = net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.encodeStart(ops, loot).result().orElse(null);
            return json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static JsonObject readFromJar(Class<?> anchor, ResourceLocation table) {
        var path = "/data/" + table.getNamespace() + "/loot_table/" + table.getPath() + ".json";
        try (var stream = anchor.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (Exception exception) {
            return null;
        }
    }

    // ---- the maths ----

    private enum Tool {
        NONE,
        SILK_TOUCH,
        SHEARS
    }

    /** One item's drop: the chance of at least one, and the count range when it drops. */
    private static final class Drop {

        float chance;

        int min = Integer.MAX_VALUE;

        int max;
    }

    private static Map<Item, Drop> evaluate(Class<?> anchor, JsonObject table, Tool tool, int depth) {
        var drops = new LinkedHashMap<Item, Drop>();
        if (depth > MAX_NESTING || !table.has("pools")) {
            return drops;
        }
        for (var poolElement : table.getAsJsonArray("pools")) {
            if (!poolElement.isJsonObject()) {
                continue;
            }
            var pool = poolElement.getAsJsonObject();
            if (!passes(pool.getAsJsonArray("conditions"), tool)) {
                continue;
            }
            var poolChance = chance(pool.getAsJsonArray("conditions"));
            var rolls = average(pool.get("rolls"));
            var entries = pool.getAsJsonArray("entries");
            if (entries == null || rolls <= 0.0F || poolChance <= 0.0F) {
                continue;
            }

            var live = new ArrayList<JsonObject>();
            var totalWeight = 0.0F;
            for (var element : entries) {
                if (element.isJsonObject() && passes(element.getAsJsonObject().getAsJsonArray("conditions"), tool)) {
                    live.add(element.getAsJsonObject());
                    totalWeight += weight(element.getAsJsonObject());
                }
            }
            if (totalWeight <= 0.0F) {
                continue;
            }

            var perRoll = new LinkedHashMap<Item, Drop>();
            for (var entry : live) {
                var share = weight(entry) / totalWeight * chance(entry.getAsJsonArray("conditions"));
                collect(anchor, entry, share, tool, perRoll, depth);
            }
            for (var found : perRoll.entrySet()) {
                var once = 1.0F - (float) Math.pow(1.0F - Math.min(1.0F, found.getValue().chance), rolls);
                merge(drops, found.getKey(), poolChance * once, found.getValue().min, found.getValue().max);
            }
        }
        return drops;
    }

    private static void collect(Class<?> anchor, JsonObject entry, float share, Tool tool, Map<Item, Drop> into, int depth) {
        var type = entry.has("type") ? entry.get("type").getAsString() : "";
        switch (type) {
            case "minecraft:item" -> {
                var id = ResourceLocation.tryParse(entry.get("name").getAsString());
                if (id != null) {
                    var item = BuiltInRegistries.ITEM.get(id);
                    if (item != Items.AIR) {
                        var counts = counts(entry.getAsJsonArray("functions"));
                        merge(into, item, share, counts[0], counts[1]);
                    }
                }
            }
            case "minecraft:tag" -> {
                var id = ResourceLocation.tryParse(entry.get("name").getAsString());
                if (id != null) {
                    var counts = counts(entry.getAsJsonArray("functions"));
                    var tag = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id));
                    if (tag.isPresent()) {
                        var size = Math.max(1, tag.get().size());
                        for (var holder : tag.get()) {
                            merge(into, holder.value(), share / size, counts[0], counts[1]);
                        }
                    }
                }
            }
            case "minecraft:loot_table" -> {
                var value = entry.get("value");
                if (value != null && value.isJsonPrimitive()) {
                    var id = ResourceLocation.tryParse(value.getAsString());
                    var nested = id == null ? null : read(anchor, id);
                    if (nested != null) {
                        evaluate(anchor, nested, tool, depth + 1).forEach(
                            (item, drop) -> merge(into, item, drop.chance * share, drop.min, drop.max)
                        );
                    }
                }
            }
            case "minecraft:alternatives" -> {
                // The first child whose conditions pass is the one taken.
                var children = entry.getAsJsonArray("children");
                if (children != null) {
                    for (var child : children) {
                        if (child.isJsonObject() && passes(child.getAsJsonObject().getAsJsonArray("conditions"), tool)) {
                            collect(
                                anchor,
                                child.getAsJsonObject(),
                                share * chance(child.getAsJsonObject().getAsJsonArray("conditions")),
                                tool,
                                into,
                                depth
                            );
                            break;
                        }
                    }
                }
            }
            case "minecraft:group", "minecraft:sequence" -> {
                var children = entry.getAsJsonArray("children");
                if (children != null) {
                    for (var child : children) {
                        if (child.isJsonObject() && passes(child.getAsJsonObject().getAsJsonArray("conditions"), tool)) {
                            collect(anchor, child.getAsJsonObject(), share, tool, into, depth);
                        }
                    }
                }
            }
            default -> {}
        }
    }

    private static void merge(Map<Item, Drop> into, Item item, float chance, int min, int max) {
        if (max <= 0 || chance <= 0.0F) {
            return;
        }
        var drop = into.computeIfAbsent(item, ignored -> new Drop());
        drop.chance = 1.0F - (1.0F - drop.chance) * (1.0F - Math.min(1.0F, chance));
        drop.min = Math.min(drop.min, Math.max(1, min));
        drop.max = Math.max(drop.max, max);
    }

    /** The count range set by set_count (min, max); 1 when there is none. */
    private static int[] counts(JsonArray functions) {
        var range = new int[] { 1, 1 };
        if (functions == null) {
            return range;
        }
        for (var function : functions) {
            if (!function.isJsonObject()) {
                continue;
            }
            var object = function.getAsJsonObject();
            var name = object.has("function") ? object.get("function").getAsString() : "";
            if (name.equals("minecraft:set_count") && object.has("count")) {
                var count = object.get("count");
                if (count.isJsonPrimitive()) {
                    range[0] = range[1] = Math.round(count.getAsFloat());
                } else if (count.isJsonObject() && count.getAsJsonObject().has("min")) {
                    range[0] = Math.round(average(count.getAsJsonObject().get("min")));
                    range[1] = Math.round(average(count.getAsJsonObject().get("max")));
                } else if (count.isJsonObject() && count.getAsJsonObject().has("value")) {
                    range[0] = range[1] = Math.round(average(count.getAsJsonObject().get("value")));
                }
            }
        }
        return range;
    }

    private static float weight(JsonObject entry) {
        return entry.has("weight") ? entry.get("weight").getAsFloat() : 1.0F;
    }

    private static float average(JsonElement value) {
        if (value == null) {
            return 1.0F;
        }
        if (value.isJsonPrimitive()) {
            return value.getAsFloat();
        }
        var object = value.getAsJsonObject();
        if (object.has("value")) {
            return average(object.get("value"));
        }
        if (object.has("min") && object.has("max")) {
            return (average(object.get("min")) + average(object.get("max"))) / 2.0F;
        }
        return 1.0F;
    }

    /** The random part of a condition list: random_chance, or the unenchanted chance of the looting form. */
    private static float chance(JsonArray conditions) {
        if (conditions == null) {
            return 1.0F;
        }
        var chance = 1.0F;
        for (var element : conditions) {
            if (!element.isJsonObject()) {
                continue;
            }
            var object = element.getAsJsonObject();
            var type = object.has("condition") ? object.get("condition").getAsString() : "";
            if (type.equals("minecraft:random_chance") && object.has("chance")) {
                chance *= average(object.get("chance"));
            } else if (type.equals("minecraft:random_chance_with_enchanted_bonus") && object.has("unenchanted_chance")) {
                chance *= object.get("unenchanted_chance").getAsFloat();
            }
        }
        return chance;
    }

    /** Whether a condition list is met with this tool. Anything a viewer cannot judge counts as met. */
    private static boolean passes(JsonArray conditions, Tool tool) {
        if (conditions == null) {
            return true;
        }
        for (var element : conditions) {
            if (element.isJsonObject() && !passes(element.getAsJsonObject(), tool)) {
                return false;
            }
        }
        return true;
    }

    private static boolean passes(JsonObject condition, Tool tool) {
        var type = condition.has("condition") ? condition.get("condition").getAsString() : "";
        return switch (type) {
            // Only a tool test can be judged here; "NOT <something a viewer cannot see>" (a yautja's tier, say) is
            // shown as
            // possible, the same as the test itself would be - never hidden.
            case "minecraft:inverted" -> !judgesTool(condition.getAsJsonObject("term")) || !passes(condition.getAsJsonObject("term"), tool);
            case "minecraft:any_of", "minecraft:alternative" -> {
                var terms = condition.getAsJsonArray("terms");
                if (terms == null) {
                    yield true;
                }
                for (var term : terms) {
                    if (term.isJsonObject() && passes(term.getAsJsonObject(), tool)) {
                        yield true;
                    }
                }
                yield false;
            }
            case "minecraft:all_of" -> passes(condition.getAsJsonArray("terms"), tool);
            case "minecraft:match_tool" -> matchesTool(condition.getAsJsonObject("predicate"), tool);
            default -> true;
        };
    }

    /** Whether a condition (or anything inside it) is a tool test this viewer can actually judge. */
    private static boolean judgesTool(JsonObject condition) {
        return condition != null && condition.toString().contains("minecraft:match_tool");
    }

    /** A match_tool predicate: Silk Touch and shears are judged; any other tool test counts as met. */
    private static boolean matchesTool(JsonObject predicate, Tool tool) {
        if (predicate == null) {
            return true;
        }
        var text = predicate.toString();
        if (text.contains("minecraft:silk_touch")) {
            return tool == Tool.SILK_TOUCH;
        }
        if (text.contains("minecraft:shears") || text.contains("#minecraft:shears") || text.contains("c:tools/shear")) {
            return tool == Tool.SHEARS;
        }
        return true;
    }
}
