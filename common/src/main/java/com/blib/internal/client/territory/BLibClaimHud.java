package com.blib.internal.client.territory;

import org.jetbrains.annotations.ApiStatus;

import com.blib.internal.client.faction.ClientFactionCache;
import com.blib.internal.client.territory.compat.XaeroWorldMapCompat;

@ApiStatus.Internal
public final class BLibClaimHud {

    /**
     * ⚠ Aug 28 — THE 10-TICK ACTION-BAR SPAM IS GONE. His ruling: edge-triggered notices instead — one "Entering X
     * territory" / "Leaving X territory" with the faction name coloured by standing, plus the awareness tiers — all
     * decided SERVER-side in ClaimAwarenessTracker (the server knows relationships and who can actually see you) and
     * displayed by BLibClientListener.handleClaimHudEvent. This class now only hosts the two client-side switches the
     * settings sync writes, and keeps the contested-blink refresh.
     */
    private static volatile boolean claimMapOverlayEnabled = true;

    private static volatile boolean claimHudMessagesEnabled = true;

    private static boolean lastBlinkPhase = XaeroWorldMapCompat.contestedBlinkPhase();

    public static boolean isClaimMapOverlayEnabled() {
        return claimMapOverlayEnabled;
    }

    public static void setClaimMapOverlayEnabled(boolean enabled) {
        if (claimMapOverlayEnabled != enabled) {
            claimMapOverlayEnabled = enabled;
            XaeroWorldMapCompat.invalidateAll();
        }
    }

    public static boolean isClaimHudMessagesEnabled() {
        return claimHudMessagesEnabled;
    }

    public static void setClaimHudMessagesEnabled(boolean enabled) {
        claimHudMessagesEnabled = enabled;
    }

    public static void tick() {
        refreshContestedBlink();
    }

    private static void refreshContestedBlink() {
        var blinkPhase = XaeroWorldMapCompat.contestedBlinkPhase();
        if (blinkPhase == lastBlinkPhase) {
            return;
        }
        lastBlinkPhase = blinkPhase;

        if (ClientTerritoryCache.INSTANCE.hasContestedClaims() && XaeroWorldMapCompat.isLoaded()) {
            XaeroWorldMapCompat.invalidateAll();
        }
    }

    private static String factionName(net.minecraft.resources.ResourceLocation factionId) {
        var metadata = ClientFactionCache.INSTANCE.get(factionId);

        return metadata == null ? factionId.toString() : metadata.name();
    }

    private BLibClaimHud() {
        throw new UnsupportedOperationException();
    }
}
