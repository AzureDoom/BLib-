package com.blib.mod.common.animation_sync;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehavior;
import com.blib.api.client.animation.v1.command.play_behavior.AzPlayBehaviors;
import com.blib.mod.BLib;

/**
 * Server-side entry point for playing an item animation on every client that can see the holder.
 * <h2>⚠ THE GAP THIS FILLS</h2> AzureLib's own example drives an item's idle loop from {@code Item.inventoryTick}
 * server-side via {@code AzCommand.create(...).sendForItem(entity, stack)}. BLib 0.3.10 carries no equivalent — its
 * {@code AzCommand} dispatches only LOCALLY, against an animator cached on the ItemStack. Because that cache is keyed
 * on the stack and the renderer receives a different stack object each frame, a locally dispatched loop is discarded
 * before it can play: measured as a new animator instance every single frame, with nothing ever playing.
 * <h2>⚠ Self-contained on purpose</h2> This class, the payload beside it and the client handler are the whole feature.
 * Two registration lines in {@code BLibPacketDirections} and the client handler registry are the only edits outside
 * this package, so the whole thing lifts out cleanly if it misbehaves.
 */
public final class BLibItemAnimationSync {

    private BLibItemAnimationSync() {
        throw new UnsupportedOperationException();
    }

    /**
     * Plays an animation on the item in the given hand, on every client tracking the holder.
     * <p>
     * ⚠ Sent to TRACKING players AND the holder. Tracking alone omits the holder in single-player and in first-person,
     * which is the one view that matters most for a held item.
     */
    public static void play(
        Entity holder,
        InteractionHand hand,
        String trackName,
        String animationName,
        boolean loop
    ) {
        play(holder, hand, trackName, animationName, loop ? AzPlayBehaviors.LOOP : AzPlayBehaviors.PLAY_ONCE);
    }

    /**
     * Plays a clip with an explicit behaviour on every tracking client and the holder — the way to sync a HELD clip (a
     * wrist door that stays open until something closes it), which is neither a loop nor a one-shot. ⚠ The behaviour
     * crosses the wire by registry name; one registered only on one side resolves to PLAY_ONCE on the other.
     */
    public static void play(
        Entity holder,
        InteractionHand hand,
        String trackName,
        String animationName,
        AzPlayBehavior behavior
    ) {
        if (holder.level().isClientSide) {
            return;
        }

        var payload = new S2CItemAnimationPayload(
            holder.getId(),
            hand == InteractionHand.OFF_HAND,
            trackName,
            animationName,
            behavior.name()
        );

        BLib.MOD.networking().sendToAllClientsTrackingEntity(holder, payload);

        if (holder instanceof ServerPlayer serverPlayer) {
            BLib.MOD.networking().sendToClient(serverPlayer, payload);
        }
    }
}
