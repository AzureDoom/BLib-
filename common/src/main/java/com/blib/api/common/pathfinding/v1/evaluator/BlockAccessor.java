package com.blib.api.common.pathfinding.v1.evaluator;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Fast block state access with chunk caching and block property caching. Optimized for spatial locality during
 * pathfinding searches.
 * <p>
 * Chunk data is cached per-chunk to avoid repeated lookups. Block properties (solid, liquid, passable) are cached by
 * block state ID to avoid virtual dispatch overhead (Baritone-style PrecomputedData).
 * </p>
 */
public final class BlockAccessor {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * What a synchronous search sees in a chunk that is not loaded: a solid wall. Nothing can stand in it or walk
     * through it, so the search simply routes around - or reports no path - instead of loading the chunk.
     */
    private static final BlockState UNLOADED = Blocks.BARRIER.defaultBlockState();

    // --- Chunk cache (spatial locality, direct section access) ---
    private final Long2ObjectOpenHashMap<ChunkAccess> chunkMap = new Long2ObjectOpenHashMap<>();

    /** Chunks found NOT loaded during this search, so the lookup is made once per chunk rather than once per block. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet unloadedChunks = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    private LevelChunkSection @Nullable [] cachedSections;

    private int cachedChunkX = Integer.MIN_VALUE;

    private int cachedChunkZ = Integer.MIN_VALUE;

    private int cachedMinSectionY;

    // --- Block property cache (avoid virtual dispatch) ---
    private boolean @Nullable [] solidCache;

    private boolean @Nullable [] climbableCache;

    private boolean @Nullable [] liquidCache;

    private boolean @Nullable [] passableCache;

    private boolean @Nullable [] propertyComputed;

    private @Nullable LevelReader level;

    /**
     * Prepares the accessor for synchronous block reads. The level is used for on-demand chunk loading.
     */
    public void prepare(LevelReader level) {
        this.level = level;
        resetChunkCache();
    }

    /**
     * Prepares the accessor for async (off-thread) block reads. Chunks must be pre-loaded via
     * {@link #preloadChunk(int, int, ChunkAccess)} before the search starts. Reads that miss the cache return AIR.
     */
    public void prepareAsync() {
        this.level = null;
        resetChunkCache();
    }

    /**
     * Pre-loads a chunk for async access. Call from the main thread before dispatching a background search.
     */
    public void preloadChunk(int chunkX, int chunkZ, ChunkAccess chunk) {
        chunkMap.put(packChunkKey(chunkX, chunkZ), chunk);
    }

    /**
     * Releases the level reference after a search completes.
     */
    public void cleanup() {
        this.level = null;
    }

    /**
     * Drops every chunk this accessor is holding: the snapshot map and the cached section array.
     * <p>
     * ⚠⚠ Added Sep 28. {@link #cleanup()} only drops the level, so each pathing mob kept the chunks of its LAST search
     * alive until it searched again — chunks the server had long since unloaded. ⚠ NOT folded into cleanup() on
     * purpose: an async search's cleanup runs on a background thread, and releasing there was only made safe by
     * {@code SearchTicket}, which guarantees no newer search is using this accessor. The pathfinder decides when to
     * call this; nothing else should.
     */
    public void releaseChunks() {
        chunkMap.clear();
        unloadedChunks.clear();
        cachedSections = null;
        cachedChunkX = Integer.MIN_VALUE;
        cachedChunkZ = Integer.MIN_VALUE;
    }

    /**
     * Returns the block state at the given position. Uses cached chunk sections for fast spatial access. Returns AIR
     * for out-of-bounds or missing chunks (in async mode).
     */
    public BlockState getBlockState(int x, int y, int z) {
        var cx = x >> 4;
        var cz = z >> 4;

        if (cx != cachedChunkX || cz != cachedChunkZ) {
            var key = packChunkKey(cx, cz);
            var chunk = chunkMap.get(key);

            if (chunk == null) {
                if (level != null) {
                    // ⚠ Oct 23 - never load or generate a chunk mid-search (see loadedChunkOrNull).
                    if (unloadedChunks.contains(key)) {
                        return UNLOADED;
                    }
                    chunk = loadedChunkOrNull(level, cx, cz);
                    if (chunk == null) {
                        unloadedChunks.add(key);
                        return UNLOADED;
                    }
                    chunkMap.put(key, chunk);
                } else {
                    return AIR;
                }
            }

            cachedSections = chunk.getSections();
            cachedChunkX = cx;
            cachedChunkZ = cz;
            cachedMinSectionY = chunk.getMinSection();
        }

        var sectionIndex = (y >> 4) - cachedMinSectionY;

        if (sectionIndex < 0 || sectionIndex >= cachedSections.length) {
            return AIR;
        }

        var section = cachedSections[sectionIndex];

        if (section == null || section.hasOnlyAir()) {
            return AIR;
        }

        return section.getBlockState(x & 15, y & 15, z & 15);
    }

    /**
     * The chunk at the given chunk coordinates if it is ALREADY loaded, else null. Never loads, never generates.
     * <p>
     * 🚨 Oct 23 - [stated] "walk ... every single costly possible path". A path search is the one piece of mob AI that
     * reads blocks a long way from where the mob stands, so it is the piece most likely to wander off the edge of the
     * loaded world. Every read here used the plain {@code getChunk(x, z)}, which LOADS a missing chunk and GENERATES
     * one that does not exist yet, synchronously, on the server thread - and a chunk loaded that way is dropped again
     * on the next tick, so the next search loads it all over again. With C2ME's chunk system (which reroutes that work
     * through its own scheduler) and a heavy worldgen pack, each one is a long wait, and a search touching several is a
     * multi-second freeze. Unloaded now means unpathable, which is also simply true: a mob cannot walk into a chunk the
     * server is not running.
     * </p>
     * <p>
     * {@code getChunkNow} is the server's own non-blocking lookup; anything that is not a full Level (a region view, a
     * test reader) goes through the non-loading {@code getChunk(..., false)} overload instead.
     * </p>
     */
    public static @Nullable ChunkAccess loadedChunkOrNull(LevelReader level, int chunkX, int chunkZ) {
        if (level instanceof net.minecraft.world.level.Level fullLevel) {
            return fullLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
        }
        return level.getChunk(chunkX, chunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
    }

    public boolean isSolid(BlockState state) {
        var id = Block.BLOCK_STATE_REGISTRY.getId(state);
        ensurePropertyCached(state, id);
        return solidCache[id];
    }

    /**
     * {@return whether a body can stand in and move vertically through this block} Ladders, vines, scaffolding, weeping
     * and twisting vines — vanilla's {@code minecraft:climbable} tag.
     * <p>
     * ⚠⚠ A CLIMBABLE IS NEITHER A WALL NOR A HOLE, AND THIS CLASS SAID BOTH. {@link #isSolid} is vanilla's
     * {@code isSolid()}, which is "has any collision shape", so a LADDER column read as a wall the planner could never
     * route through; a VINE has no collision, so a vine shaft read as open-but-unsupported — a hole. Vanilla's own node
     * evaluator treats both as walkable; this is that knowledge for the BLib planner.
     */
    public boolean isClimbable(BlockState state) {
        var id = Block.BLOCK_STATE_REGISTRY.getId(state);
        ensurePropertyCached(state, id);
        return climbableCache[id];
    }

    public boolean isLiquid(BlockState state) {
        var id = Block.BLOCK_STATE_REGISTRY.getId(state);
        ensurePropertyCached(state, id);
        return liquidCache[id];
    }

    public boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    public boolean isPassable(BlockState state) {
        var id = Block.BLOCK_STATE_REGISTRY.getId(state);
        ensurePropertyCached(state, id);
        return passableCache[id];
    }

    public VoxelShape getCollisionShape(BlockState state, int x, int y, int z) {
        return state.getCollisionShape(blockGetter(), new BlockPos(x, y, z), CollisionContext.empty());
    }

    public boolean isCollisionShapeFullBlock(BlockState state, int x, int y, int z) {
        return Block.isShapeFullBlock(getCollisionShape(state, x, y, z));
    }

    private BlockGetter blockGetter() {
        return level != null ? level : EmptyBlockGetter.INSTANCE;
    }

    private void resetChunkCache() {
        cachedSections = null;
        cachedChunkX = Integer.MIN_VALUE;
        cachedChunkZ = Integer.MIN_VALUE;
        chunkMap.clear();
        unloadedChunks.clear();

        // Initialize property cache once (block state properties never change at runtime).
        if (solidCache == null) {
            var stateCount = Block.BLOCK_STATE_REGISTRY.size();
            solidCache = new boolean[stateCount];
            climbableCache = new boolean[stateCount];
            liquidCache = new boolean[stateCount];
            passableCache = new boolean[stateCount];
            propertyComputed = new boolean[stateCount];
        }
    }

    private void ensurePropertyCached(BlockState state, int id) {
        if (!propertyComputed[id]) {
            var solid = state.isSolid();
            var liquid = state.liquid();
            var climbable = state.is(BlockTags.CLIMBABLE);
            solidCache[id] = solid;
            liquidCache[id] = liquid;
            climbableCache[id] = climbable;
            // ⚠ A ladder is "solid" (thin collision) but a body passes into it: passable, not blocked.
            passableCache[id] = (!solid || climbable) && !liquid;
            propertyComputed[id] = true;
        }
    }

    private static long packChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) << 32 | ((long) chunkZ & 0xFFFFFFFFL);
    }
}
