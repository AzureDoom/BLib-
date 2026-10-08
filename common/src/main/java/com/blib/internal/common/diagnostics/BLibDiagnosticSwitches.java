package com.blib.internal.common.diagnostics;

import org.jetbrains.annotations.ApiStatus;

/**
 * Launch-time switches for isolating a problem by turning a BLib subsystem OFF. Each is a JVM system property, read
 * once at class load, so it costs nothing per call and cannot change mid-session.
 * <p>
 * ⚠⚠ TROUBLESHOOTING ONLY. Every switch here disables real behaviour and may lose data for that session — use a
 * throwaway test world. With no property set, every switch is ON and BLib behaves exactly as it always has.
 * <p>
 * Added Sep 28 for the Sable/Aeronautics report (leaked LevelChunks + micro-lag while exploring, reproduced with BLib
 * alone). ⭐ Kept deliberately as a permanent tool: the next "is it BLib's chunk handling?" question is one launch.
 */
@ApiStatus.Internal
public final class BLibDiagnosticSwitches {

    /**
     * {@code -Dblib.chunkEvents=false} stops BLib's chunk hooks from doing ANY work: the chunk load, chunk unload and
     * chunk save events never fire, for BLib's own listeners and every other mod's. That removes, for the session:
     * <ul>
     * <li>the per-load dedupe set and the deferred load task queued onto the server for every chunk reaching FULL;</li>
     * <li>territory claim lookups and the claim sync packet sent on chunk load;</li>
     * <li>the chunk data store's load, save-on-unload and region-file cache, and its save on every chunk save;</li>
     * <li>the entity-reference check on chunk load.</li>
     * </ul>
     * ⚠ With it off, chunk-scoped BLib data (territory claims and anything else stored per chunk) is neither loaded on
     * chunk load nor saved on chunk unload. Level save still writes whatever was touched. Test worlds only.
     * <p>
     * Level SAVE events are untouched — they are not per-chunk and are not part of what is being isolated.
     */
    public static final String CHUNK_EVENTS_PROPERTY = "blib.chunkEvents";

    public static final boolean CHUNK_EVENTS_ENABLED = !"false".equalsIgnoreCase(System.getProperty(CHUNK_EVENTS_PROPERTY));

    /**
     * {@code -Dblib.itemAnimatorExpiry=false} turns OFF the Oct 5 item-animator leak fix (client side). With it on, an
     * identity item's animator (every avp_human gun, the predator gauntlet, ...) is dropped once that stack has not
     * been drawn for {@link #ITEM_ANIMATOR_EXPIRY_MILLIS}; the next draw rebuilds it. With it off, animators are kept
     * for the whole session - the old behaviour, which never freed one.
     * <p>
     * ⚠ A JVM flag, not a game rule, because this runs on the CLIENT: game rules live on the server and a dedicated
     * server never sends them to clients.
     */
    public static final String ITEM_ANIMATOR_EXPIRY_PROPERTY = "blib.itemAnimatorExpiry";

    public static final boolean ITEM_ANIMATOR_EXPIRY_ENABLED = !"false".equalsIgnoreCase(
        System.getProperty(ITEM_ANIMATOR_EXPIRY_PROPERTY)
    );

    /** How long an item animator may go undrawn before it is dropped. */
    public static final long ITEM_ANIMATOR_EXPIRY_MILLIS = 30_000L;

    private BLibDiagnosticSwitches() {
        throw new UnsupportedOperationException();
    }
}
