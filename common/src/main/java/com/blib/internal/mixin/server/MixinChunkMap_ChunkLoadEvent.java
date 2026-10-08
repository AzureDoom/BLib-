package com.blib.internal.mixin.server;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.blib.internal.common.diagnostics.BLibDiagnosticSwitches;
import com.blib.internal.common.event.BLibGlobalEvents;

@Mixin(ChunkMap.class)
public abstract class MixinChunkMap_ChunkLoadEvent {

    @Shadow
    @Final
    ServerLevel level;

    /**
     * ⚠ CONCURRENT ON PURPOSE. This is touched from {@code onFullChunkStatusChange}, which vanilla drives from the
     * server thread — but C2ME moves chunk work onto worker threads and is standard on servers, and a plain
     * {@code HashSet} corrupting or spinning here would present as a freeze on the chunk path with nothing in the log
     * to attribute it. The set only ever sees {@code add}/{@code remove}, so the concurrent version costs nothing.
     */
    @Unique
    private final Set<ChunkPos> blib$firedChunks = ConcurrentHashMap.newKeySet();

    /**
     * SAFETY CONTRACT — this callback runs INSIDE the chunk system's own bookkeeping. Vanilla calls
     * {@code onFullChunkStatusChange} from {@code DistanceManager.runAllUpdates}'s iteration over
     * {@code chunksToUpdateFutures}. The old body called the BLOCKING {@code level.getChunk(x, z)} right here; when the
     * chunk wasn't immediately ready (a nether-portal wall of promotions/demotions on an overloaded server), that call
     * drained main-thread tasks via {@code managedBlock -> pollTask ->
     * runDistanceManagerUpdates} REENTRANTLY — seven nested frames in the crash that found this — and the outer
     * iteration died with a {@link java.util.ConcurrentModificationException}: "Exception ticking world" on dimension
     * change. Dispatching listeners synchronously here is just as unsafe: listener code that touches chunks or tickets
     * mutates the same set mid-iteration.
     * <p>
     * So the handler now does only cheap, chunk-system-free work synchronously (status guard, dedupe, listener-presence
     * check) and DEFERS resolution + dispatch onto the server task queue, which drains after the current chunk-future
     * pass completes. The deferred task resolves the chunk with the NON-BLOCKING {@code getChunkNow} — a chunk gone
     * again by then simply skips, and listeners (which fire once per load) will see it on its next load instead.
     */
    @Inject(at = @At("TAIL"), method = "onFullChunkStatusChange")
    private void blib$onChunkLoad(ChunkPos pos, FullChunkStatus fullChunkStatus, CallbackInfo ci) {
        // Sep 28 - diagnostic kill switch (-Dblib.chunkEvents=false); see BLibDiagnosticSwitches. Default: on.
        if (!BLibDiagnosticSwitches.CHUNK_EVENTS_ENABLED) {
            return;
        }

        if (!fullChunkStatus.isOrAfter(FullChunkStatus.FULL)) {
            // Dropping below FULL is the unload edge: forget the chunk so the event fires again on its NEXT
            // load, and so this set stays bounded by the loaded-chunk count instead of growing for the whole
            // server lifetime (it used to retain every chunk ever loaded, which also meant "chunk load" only
            // ever fired once per chunk per session — reloads were silent).
            blib$firedChunks.remove(pos);
            return;
        }

        if (!blib$firedChunks.add(pos)) {
            return;
        }

        var listeners = BLibGlobalEvents.CHUNK_LOAD.listeners();

        if (listeners.isEmpty()) {
            return;
        }

        level.getServer().execute(() -> {
            LevelChunk levelChunk = level.getChunkSource().getChunkNow(pos.x, pos.z);

            if (levelChunk == null) {
                return;
            }

            for (var listener : listeners) {
                listener.invoke(level, levelChunk);
            }
        });
    }
}
