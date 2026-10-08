package com.blib.internal.common.registry.util;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.Internal
public class BLibRegistrationUtil {

    public static final List<Registry<?>> VANILLA_REGISTRATION_ORDER = List.of(
        // ⚠⚠ FLUID MUST COME BEFORE BLOCK. A LiquidBlock resolves its FlowingFluid in its CONSTRUCTOR - vanilla's
        // LiquidBlock builds a state cache from it right there - so a mod registering a fluid and its liquid block
        // through BLib had the block built while the fluid was still unbound:
        // NullPointerException: Trying to access unbound value: ResourceKey[minecraft:fluid / <mod>:<fluid>]
        // FLUID was absent from this list entirely, so it fell into the unordered pass that runs AFTER every entry
        // here - which meant no amount of init ordering in the mod could fix it. Vanilla itself loads Fluids before
        // Blocks for exactly this reason.
        BuiltInRegistries.FLUID,
        // Independent registries.
        BuiltInRegistries.BLOCK,
        BuiltInRegistries.DATA_COMPONENT_TYPE,
        BuiltInRegistries.DECORATED_POT_PATTERN,
        BuiltInRegistries.ENTITY_TYPE,
        BuiltInRegistries.GAME_EVENT,
        BuiltInRegistries.MENU,
        BuiltInRegistries.MOB_EFFECT,
        BuiltInRegistries.PARTICLE_TYPE,
        BuiltInRegistries.POINT_OF_INTEREST_TYPE,
        BuiltInRegistries.RECIPE_SERIALIZER,
        BuiltInRegistries.RECIPE_TYPE,
        BuiltInRegistries.SOUND_EVENT,
        BuiltInRegistries.VILLAGER_PROFESSION,
        // Depends on sound events.
        BuiltInRegistries.ARMOR_MATERIAL,
        // Depends on blocks.
        BuiltInRegistries.BLOCK_ENTITY_TYPE,
        // Potentially depends on blocks (block items), entities (spawn egg items) or armor materials (armor items).
        BuiltInRegistries.ITEM,
        // Depends on blocks and items.
        BuiltInRegistries.CREATIVE_MODE_TAB
    );

    private BLibRegistrationUtil() {
        throw new UnsupportedOperationException();
    }
}
