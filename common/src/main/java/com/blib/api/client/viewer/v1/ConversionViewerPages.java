package com.blib.api.client.viewer.v1;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * World Interactions pages from a block-conversion table in the AVP format - avp_human's nuke conversions and
 * avp_predator's plasma conversions:
 *
 * <pre>
 * { "conversions": [ { "from": "minecraft:stone" | "#tag" | [ ... ],
 *                      "to": "minecraft:cobblestone" | [ { "block": "...", "weight": 3 }, ... ] } ] }
 * </pre>
 *
 * Read from the mod's own jar, one page per rule: the blocks it matches (a tag expanded through this client's synced
 * tags) on the left, what they become on the right with each result's share. Blocks from mods that are not installed
 * are skipped, as the game skips them.
 */
public final class ConversionViewerPages {

    private ConversionViewerPages() {}

    /**
     * @param anchor   any class from the mod that ships the table
     * @param resource the table's path inside the jar, e.g.
     *                 {@code /data/avp_human/avp_human/nuke_conversions/default.json}
     * @param title    the first line on every page
     * @param note     the second line, e.g. how the conversion happens
     * @param cause    an item standing for what causes it (shown beside the blocks), or empty
     */
    public static List<ViewerPage> pages(Class<?> anchor, String resource, Component title, Component note, ItemStack cause) {
        var pages = new ArrayList<ViewerPage>();
        JsonObject table;
        try (var stream = anchor.getResourceAsStream(resource)) {
            if (stream == null) {
                return pages;
            }
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!json.isJsonObject() || !json.getAsJsonObject().has("conversions")) {
                return pages;
            }
            table = json.getAsJsonObject();
        } catch (Exception exception) {
            return pages;
        }

        for (var element : table.getAsJsonArray("conversions")) {
            if (!element.isJsonObject()) {
                continue;
            }
            var rule = element.getAsJsonObject();
            var from = blocks(rule.get("from"));
            var to = results(rule.get("to"));
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }
            var inputs = new ArrayList<ViewerSlot>();
            inputs.add(ViewerSlot.of(from));
            if (!cause.isEmpty()) {
                inputs.add(ViewerSlot.of(cause));
            }
            pages.add(new ViewerPage(BLibViewers.WORLD_INTERACTIONS, inputs, to, List.of(title, note), 0));
        }
        return pages;
    }

    private static List<ItemStack> blocks(JsonElement from) {
        var stacks = new ArrayList<ItemStack>();
        if (from == null) {
            return stacks;
        }
        if (from.isJsonArray()) {
            for (var each : from.getAsJsonArray()) {
                stacks.addAll(blocks(each));
            }
            return stacks;
        }
        var text = from.getAsString();
        if (text.startsWith("#")) {
            var id = ResourceLocation.tryParse(text.substring(1));
            if (id != null) {
                BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, id)).ifPresent(tag -> tag.forEach(holder -> {
                    var item = holder.value().asItem();
                    if (item != Items.AIR) {
                        stacks.add(new ItemStack(item));
                    }
                }));
            }
        } else {
            var id = ResourceLocation.tryParse(text);
            if (id != null) {
                BuiltInRegistries.BLOCK.getOptional(id)
                    .map(block -> block.asItem())
                    .filter(item -> item != Items.AIR)
                    .ifPresent(item -> stacks.add(new ItemStack(item)));
            }
        }
        return stacks;
    }

    private static List<ViewerSlot> results(JsonElement to) {
        var slots = new ArrayList<ViewerSlot>();
        if (to == null) {
            return slots;
        }
        if (to.isJsonPrimitive()) {
            var stacks = blocks(to);
            if (!stacks.isEmpty()) {
                slots.add(ViewerSlot.of(stacks.get(0)));
            }
            return slots;
        }
        var total = 0;
        for (var each : to.getAsJsonArray()) {
            total += weight(each);
        }
        for (var each : to.getAsJsonArray()) {
            if (!each.isJsonObject() || !each.getAsJsonObject().has("block")) {
                continue;
            }
            var stacks = blocks(each.getAsJsonObject().get("block"));
            if (stacks.isEmpty()) {
                continue;
            }
            var share = total <= 0 ? 100 : Math.round(100.0F * weight(each) / total);
            slots.add(ViewerSlot.of(stacks.get(0)).withTooltip(Component.literal(share + "% of the time").withStyle(ChatFormatting.GOLD)));
        }
        return slots;
    }

    private static int weight(JsonElement entry) {
        return entry.isJsonObject() && entry.getAsJsonObject().has("weight") ? entry.getAsJsonObject().get("weight").getAsInt() : 1;
    }
}
