package com.blib.internal.client.profiling.profile;

import com.blib.internal.client.animation.track.AzAbstractAnimationTrack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Maps hook subjects to stable aggregation keys without allocating: an entity aggregates under its type, an item stack
 * under its item, a controller under its name. Labels are resolved only when a report is written.
 */
final class SubjectKeys {

    static final Object UNKNOWN = new Object();

    private SubjectKeys() {}

    static Object key(Object subject) {
        return switch (subject) {
            case null -> UNKNOWN;
            case Entity entity -> entity.getType();
            case BlockEntity blockEntity -> blockEntity.getType();
            case ItemStack stack -> stack.getItem();
            case AzAbstractAnimationTrack controller ->
                controller.name();
            default -> subject.getClass();
        };
    }

    static String label(Object key) {
        if (key == UNKNOWN) {
            return "unknown";
        }

        if (key instanceof EntityType<?> type) {
            return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(type));
        }

        if (key instanceof BlockEntityType<?> type) {
            return String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type));
        }

        if (key instanceof Item item) {
            return String.valueOf(BuiltInRegistries.ITEM.getKey(item));
        }

        if (key instanceof String controllerName) {
            return "controller:" + controllerName;
        }

        if (key instanceof Class<?> type) {
            return type.getName();
        }

        return key.toString();
    }
}
