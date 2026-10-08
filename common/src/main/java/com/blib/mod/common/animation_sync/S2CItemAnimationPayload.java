package com.blib.mod.common.animation_sync;

import com.just.codec.stream.RecordStreamCodec;
import com.just.codec.stream.StreamCodec;
import com.just.codec.stream.impl.StreamCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import com.blib.mod.BLib;

/**
 * Tells a client to play an animation on an item held by an entity.
 * <h2>⚠ This whole package is an ADDITION, kept in one folder on purpose</h2> BLib 0.3.10 has no networked animation
 * dispatch at all — {@code AzCommand} exposes only {@code dispatchForEntity}, {@code dispatchForBlockEntity} and
 * {@code dispatchForItem}, all of which run LOCALLY against an animator cached on the ItemStack. AzureLib's own
 * gun-with-arm example drives its idle loop from {@code Item.inventoryTick} SERVER-SIDE via
 * {@code AzCommand.create(...).sendForItem(entity, stack)} — an API this fork does not carry.
 * <p>
 * Everything needed to close that gap lives in {@code com.blib.mod.common.animation_sync} plus two registration lines,
 * so it can be deleted wholesale if it ever causes trouble.
 * <h2>⚠ Why the hand and not a slot index</h2> The client has to find the SAME ItemStack object the renderer will use.
 * Held items are reachable by hand on both sides; a slot index would resolve to a different object in the offhand,
 * which is exactly the kind of mismatch that leaves an animation playing on a stack nobody draws.
 */
public record S2CItemAnimationPayload(
    int entityId,
    boolean offhand,
    String trackName,
    String animationName,
    String behavior
) implements CustomPacketPayload {

    /**
     * ⚠ {@code behavior} is the registry NAME of an {@code AzPlayBehavior} — "loop", "play_once", "hold_on_last_frame"
     * — not a boolean. A held clip (a wrist door that stays open until something closes it) is neither a loop nor a
     * one-shot, and the old boolean could only express those two, which is why every synced one-shot used to STOP at
     * its last frame and get replayed over by the idle a tick later.
     */

    public static final ResourceLocation PAYLOAD_ID = BLib.MOD.resources().createLocation("item_animation");

    public static final Type<S2CItemAnimationPayload> TYPE = new Type<>(PAYLOAD_ID);

    public static final StreamCodec<S2CItemAnimationPayload> CODEC = RecordStreamCodec.of(
        StreamCodecs.VAR_INT,
        S2CItemAnimationPayload::entityId,
        StreamCodecs.BOOLEAN,
        S2CItemAnimationPayload::offhand,
        StreamCodecs.STRING_UTF8,
        S2CItemAnimationPayload::trackName,
        StreamCodecs.STRING_UTF8,
        S2CItemAnimationPayload::animationName,
        StreamCodecs.STRING_UTF8,
        S2CItemAnimationPayload::behavior,
        S2CItemAnimationPayload::new
    );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
